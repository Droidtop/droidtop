package dev.droidtop.runtime.windows

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import dev.droidtop.library.OwnGameScreens
import dev.droidtop.library.PadMouse
import dev.droidtop.mapping.ControllerLayout
import dev.droidtop.library.PadMousePrefs
import dev.droidtop.library.diagnostics.RunRecords
import dev.droidtop.runtime.windows.data.TouchGestureConfig
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.TouchMouse
import com.winlator.renderer.ASurfaceRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.widget.XServerView
import com.winlator.widget.XServerViewGL
import com.winlator.winhandler.WinHandler
import com.winlator.winhandler.WinHandler.PreferredInputApi
import com.winlator.xserver.Keyboard
import com.winlator.xserver.Window
import com.winlator.xserver.WindowManager as XWindowManager
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import java.io.File
import java.util.concurrent.Executors
import timber.log.Timber
import dev.droidtop.runtime.windows.PrefManager as GameNativePrefManager

/**
 * Where a Windows game is actually seen and played.
 *
 * droidtop composes its own shell through `:host-bridge`, but a Wine
 * guest does not draw Wayland surfaces -- it draws into an X server that
 * gamenative already knows how to present, through
 * [XServerView]/[XServerViewGL] and the native renderers
 * (`libvulkan_renderer.so`, `libvortekrenderer.so`, `libwinlator.so`)
 * that this module has always packaged and never constructed. This
 * Activity is the piece that was missing: it makes that view, hands it
 * to the [XServer] the guest connects to, and starts a [WineXSession]
 * against it.
 *
 * It is an Activity, and not a surface inside the shell, for a concrete
 * reason: handheld launches are placed on the launch-target display with
 * `ActivityOptions.setLaunchDisplayId`, exactly like every other launch
 * droidtop makes (`LaunchDisplay`). A view hosted inside the shell's own
 * window could only ever appear where the shell already is.
 *
 * Desktop mode is out of scope here on purpose. There, a Windows program
 * should appear as a window among others inside the container's sway
 * compositor, which is a different presentation problem with a different
 * answer; nothing in this Activity assumes it, and nothing in it should
 * be stretched to cover it.
 *
 * Input follows gamenative's own routing and adds nothing: physical
 * controllers go to [WinHandler], which is the bridge that publishes
 * XInput state into the guest; keys that no controller claimed go to the
 * X keyboard; touch goes to [TouchpadView], which drives the X pointer.
 */
class WineGameActivity : Activity() {

    private var xServer: XServer? = null
    private var gamePointer: OwnGameScreens.Pointer? = null
    private var rendererView: XServerRendererView? = null
    private var session: WineXSession? = null
    private var touchpadView: TouchpadView? = null
    private var touchMouse: TouchMouse? = null
    private var keyboard: Keyboard? = null
    private var softKeyboard: WineKeyboard? = null
    private var winHandler: WinHandler? = null
    private var blackScreen: WineBlackScreen? = null

    /** The run's setup, said in its record and in a report: Wine, Direct3D, the graphics driver and the emulator. */
    private var setupFacts: List<Pair<String, String>> = emptyList()
    private var failed = false
    private var startedAtMs = 0L

    /** Whether the guest mapped an application window of its own (not the virtual desktop). */
    @Volatile
    private var guestShowedWindow = false

    /** The library game this screen runs, when it is one (its cloud saves are synced when the screen ends). */
    private var entryId: String? = null

    /** What a prefix tool is called, when this screen runs one ([WineEngine.launch]'s `tool`); null for a game. */
    private var tool: String? = null

    /** What this run started, for its record in logs/wine-last.log. */
    private var launchTarget: String? = null

    /** Where this run's game and prefix are, for finding the logs the game wrote itself (WineGameLogs). */
    private var gameWorkingDir: File? = null
    private var prefixRoot: File? = null

    /** The kernel drivers in the prefix when the run started (Droidtop/tracker#607), to tell which ones it installed. */
    @Volatile private var driversAtStart: List<dev.droidtop.library.KernelDriver>? = null

    /** When the run started, on the wall clock (startedAtMs is the monotonic one): to tell a log this run wrote. */
    private var startedAtWallMs = 0L

    /** What the Quick Menu's Game section reaches this screen through (Resume is a relaunch, Stop is [stop]). */
    private val ownScreen = object : OwnGameScreens.Screen {
        override val entryId: String? get() = this@WineGameActivity.entryId
        override val padMouse: Boolean? get() = this@WineGameActivity.padMouse?.settings?.on
        override val pointer: OwnGameScreens.Pointer? get() = this@WineGameActivity.gamePointer
        override fun setPadMouse(on: Boolean) {
            runOnUiThread { this@WineGameActivity.padMouse?.let { it.set(it.settings.copy(on = on)) } }
        }
        override val controls: String?
            get() = if (this@WineGameActivity.layoutDriver == null) null else controlsChoice?.line ?: WineControls.GAMEPAD_LINE
        override fun nextControls(): String? {
            if (this@WineGameActivity.layoutDriver == null) return null
            val next = WineControls.next(controlsChoice)
            useControls(next)
            val id = this@WineGameActivity.entryId
            if (id != null) startupExecutor.execute { WineControlsPrefs.setLayout(applicationContext, id, next) }
            return next?.line ?: WineControls.GAMEPAD_LINE
        }
        override val canReport: Boolean get() = true
        override fun reportProblem(done: (String) -> Unit) {
            report(heading = "Wine report: from the Quick Menu", screen = null, frame = null, done = done)
        }
        override fun stop() {
            // The guest's teardown is queued now (off the main thread), so a relaunch that follows
            // (Restart) waits for it; the screen then closes.
            session?.stop()
            session = null
            runOnUiThread { if (!isFinishing) finish() }
        }
    }

    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "droidtop-wine-start")
    }

    /** droidtop's token set for the failure screen; null until read, and then the screen keeps black and white. */
    @Volatile private var chrome: dev.droidtop.library.theme.RoleTokens.Set? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Both preference files are read from deep inside the Winlator
        // view and renderer code. Opening them does no disk work; the
        // runtime's start loaded them off the main thread (PrefManager.load).
        GameNativePrefManager.init(this)
        // The failure screen's colours, droidtop's own for its Light or dark (docs/SPEC.md 7k, Droidtop/tracker#561):
        // read once here, off the main thread, since they come from a preference.
        startupExecutor.execute { chrome = runCatching { dev.droidtop.library.theme.RoleTokens.current(applicationContext) }.getOrNull() }
        // Not optional, and not cosmetic: WinHandler.start() asks the
        // controller manager to scan for devices, and the manager holds
        // the InputManager it scans with only after this call. Without it
        // the first thing every Wine launch did was throw inside
        // start(), which surfaced as a failure screen over a game that
        // had in fact begun to boot.
        ControllerManager.getInstance().init(applicationContext)

        // The screen stays on by GameWakeLock (the setting "While a game runs"), not by a window flag
        // that ignored it.
        goFullscreen()

        val target = intent.getStringExtra(EXTRA_TARGET)
        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID)
        val workingDir = intent.getStringExtra(EXTRA_WORKING_DIR)?.let(::File)
        val arguments = intent.getStringArrayListExtra(EXTRA_ARGUMENTS).orEmpty()
        entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        tool = intent.getStringExtra(EXTRA_TOOL)
        OwnGameScreens.register(ownScreen)
        val prefix = containerId?.let { id ->
            runCatching { ContainerManager(this).getContainerById(id) }.getOrNull()
        }
        if (target.isNullOrBlank() || prefix == null || workingDir == null) {
            showFailure("the Windows launch was missing its prefix or its target")
            return
        }
        // Before anything reads the prefix: the game's own Wine choices, if it
        // has any, answer for its graphics driver, Direct3D and emulator.
        prefix.setLaunchOverrides(launchOverrides(intent))
        setupFacts = listOf(
            "Wine" to prefix.wineVersion.orEmpty(),
            "Direct3D" to prefix.dxWrapper.orEmpty(),
            "Graphics driver" to listOf(prefix.graphicsDriver, prefix.graphicsDriverVersion).filterNot { it.isNullOrBlank() }.joinToString(" "),
            "Emulator" to prefix.emulator.orEmpty(),
        ).filter { it.second.isNotBlank() }

        val xServer = XServer(ScreenInfo(prefix.screenSize), false)
        this.xServer = xServer
        xServer.windowManager.addOnWindowModificationListener(object : XWindowManager.OnWindowModificationListener {
            override fun onMapWindow(window: Window) {
                if (window.isApplicationWindow && !window.className.contains("explorer.exe")) guestShowedWindow = true
            }
        })

        // virgl is OpenGL passthrough and has to be presented by the GL
        // view, which shares its EGL context with the VirGL component;
        // everything else is the Vulkan/SurfaceFlinger view, picked by
        // the prefix's own display-renderer field.
        val useGl = prefix.graphicsDriver == "virgl" || prefix.displayRenderer.equals("gl", ignoreCase = true)
        val view: XServerRendererView = if (useGl) {
            XServerViewGL(this, xServer)
        } else {
            XServerView(this, xServer, prefix.displayRenderer)
        }
        rendererView = view
        val renderer = view.renderer
        xServer.renderer = renderer

        // How frames reach the screen, from the prefix's own fields --
        // the presentation mode is the difference between a game that
        // tears and one that stalls, and a prefix that asked for one has
        // to get it.
        if (!useGl && renderer is VulkanRenderer) {
            renderer.setVkPresentMode(WinePresentation.vkPresentMode(prefix.rendererPresentMode))
        }
        if (renderer is ASurfaceRenderer) {
            renderer.setSfCompatMode(prefix.sfCompatMode)
        }
        // A pointer the person can see, unless this prefix is set up to
        // be played by touching the screen directly.
        renderer.setCursorVisible(!prefix.isTouchscreenMode)

        // The virtual desktop `explorer` opens is the window a game lives
        // in; the shell window itself must not be presented, and the
        // game's own window is what should fill the screen.
        renderer.setUnviewableWMClasses("explorer.exe")
        // The WM class the renderer should blow up to fill the screen is
        // the executable's own file name. Both separators are stripped
        // because a .desktop shortcut stores a Windows path, and
        // File(...).name would keep the whole of one on Android.
        WinePresentation.fullscreenWMClass(target)?.let { renderer.forceFullscreenWMClass = it }
        launchTarget = target
        gameWorkingDir = workingDir
        val wineDir = File(prefix.rootDir, ".wine")
        prefixRoot = wineDir
        startupExecutor.execute { driversAtStart = dev.droidtop.library.KernelDrivers.scan(wineDir) }

        winHandler = WinHandler(xServer, view).also { handler ->
            xServer.winHandler = handler
            // Which Windows input API the guest's games are expected to
            // read the pad through, and how a DirectInput device is
            // mapped onto it: both are the prefix's own settings, and a
            // value outside the enum (an older prefix, a hand-edited
            // file) becomes BOTH rather than an index crash.
            val inputType = prefix.inputType.takeIf { it in PreferredInputApi.values().indices }
                ?: PreferredInputApi.BOTH.ordinal
            handler.setPreferredInputApi(PreferredInputApi.values()[inputType])
            handler.setDInputMapperType(prefix.dinputMapperType)
        }
        touchMouse = TouchMouse(xServer)
        keyboard = Keyboard(xServer)
        // Controller mouse mode (docs/SPEC.md 5b, Droidtop/tracker#565): off until the game's remembered choice is read.
        // The game's one pointer: mouse mode and the companion's trackpad both drive it (Droidtop/tracker#565).
        val pointer = XServerPointer(xServer) { window?.decorView?.display?.displayId }
        gamePointer = pointer
        val mouseMode = PadMouseDriver(pointer, PadMouse.Settings()) { settings -> padMouseChanged(settings) }
        padMouse = mouseMode
        entryId?.let { id ->
            // A game whose genre is played with a pointer starts with it on (Droidtop/tracker#618 C2); the library's
            // launch carries the genre.
            val genre = dev.droidtop.library.LaunchDisplay.runningGame?.takeIf { it.gameId == id }?.genre
            startupExecutor.execute {
                val initial = PadMouse.initial(PadMousePrefs.stored(applicationContext, id), PadMouse.genreNeedsMouse(genre))
                val hint = PadMousePrefs.firstHint(applicationContext, id)
                runOnUiThread {
                    mouseMode.settings = initial
                    showPadMouse(initial.on, announce = false)
                    if (hint) showPadMouseHint(initial.on)
                }
            }
        }

        // The pad's layouts (Droidtop/tracker#618): read and parsed off the main thread; the one this game was left
        // with is played.
        startupExecutor.execute {
            val parsed = WineControls.entries.mapNotNull { choice ->
                runCatching {
                    choice to resources.openRawResource(choice.raw).bufferedReader().use { ControllerLayout.parse(it.readText()) }
                }.getOrNull()
            }.toMap()
            val chosen = entryId?.let { WineControlsPrefs.layout(applicationContext, it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                controlLayouts = parsed
                layoutDriver = LayoutDriver(xServer, pointer)
                useControls(chosen)
            }
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(view as View, matchParent())
        // Constructed after the renderer is on the server: its own
        // constructor asks the renderer whether it is fullscreen to build
        // the touch-to-screen transform.
        val touchpad = TouchpadView(this, xServer, GameNativePrefManager.getBoolean("capture_pointer_on_external_mouse", true))
        touchpadView = touchpad
        touchpad.setMoveCursorToTouchpoint(GameNativePrefManager.getBoolean("move_cursor_to_touchpoint", false))
        // Touch behaviour, again from the prefix: a prefix with mouse
        // input disabled must not move the pointer at all, and one in
        // touchscreen mode treats a touch as a click where it landed,
        // under its own gesture configuration.
        if (prefix.isDisableMouseInput) {
            touchpad.setTouchscreenMouseDisabled(true)
        } else if (prefix.isTouchscreenMode) {
            touchpad.setTouchscreenMode(true)
            touchpad.setGestureConfig(TouchGestureConfig.fromJson(prefix.gestureConfig))
        }
        root.addView(touchpad, matchParent())
        // The soft keyboard's landing place: what it types becomes X key
        // presses (WineKeyboard). Three fingers swiping up open it (windows
        // games often use the controller themselves, so no pad button does);
        // a touch gesture or radial slot bound to "show keyboard" toggles it.
        // Back closes it (dispatchKeyEvent).
        val soft = WineKeyboard(this) { event -> keyboard?.onKeyEvent(event) == true }
        softKeyboard = soft
        root.addView(soft)
        root.addView(padMouseBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
        touchpad.setShowKeyboardCallback { soft.toggle() }
        touchpad.setKeyboardSwipeCallback { soft.show() }
        setContentView(root)
        // A black screen is noticed and can be reported (docs/SPEC.md 7e2, Droidtop/tracker#601).
        blackScreen = WineBlackScreen(
            activity = this,
            parent = root,
            surface = { rendererView as? android.view.SurfaceView },
            windowShown = { guestShowedWindow },
            report = ::reportBlackScreen,
        )

        val session = WineXSession(this, prefix, target, workingDir, xServer, arguments)
        this.session = session
        startedAtMs = SystemClock.elapsedRealtime()
        startedAtWallMs = System.currentTimeMillis()
        startupExecutor.execute {
            runCatching { session.start(::onGuestTerminated) }
                .onFailure { failure ->
                    runOnUiThread {
                        showFailure(failure.message ?: "the Wine environment failed to start", failure)
                    }
                }
        }
    }

    /**
     * Wine has ended. A game that ran and quit with code 0 just closes this
     * screen; any other end is reported here, with Wine's exit code and the
     * last lines it printed. This screen is droidtop's own, so the launch
     * watchdog leaves it alone (LaunchDisplay): its "closed straight after it
     * started" named droidtop and gave emulator advice for a Windows game
     * (Droidtop/tracker#302).
     */
    private fun onGuestTerminated(status: Int, output: String) {
        val ranMs = SystemClock.elapsedRealtime() - startedAtMs
        val report = WinePresentation.exitReport(status, ranMs, guestShowedWindow, output, tool = tool != null)
        Timber.i("Wine exited %d after %d ms", status, ranMs)
        // Every run leaves a trace (console, 2026-10-17: three failed launches of one game and none could be read
        // afterwards): one logcat line (the output itself went to logcat as it was printed), and the run's record
        // in droidtop's logs folder with the logs the game wrote itself, which Share diagnostics packs.
        android.util.Log.i(WinePresentation.LOG_TAG, "Wine exited $status after $ranMs ms, window shown: $guestShowedWindow")
        writeRunRecord(status, ranMs, output)
        // Killed (137) while the person was still playing: Android's limit on child processes may have done it, and the
        // log says so when the privileged shell can read it (Droidtop/tracker#816). Read on its own thread.
        if (status == dev.droidtop.runtime.tasks.PhantomKills.KILLED_STATUS && !isFinishing) {
            val since = startedAtWallMs
            Thread({
                val killed = dev.droidtop.runtime.tasks.PhantomKills.since(dev.droidtop.runtime.tasks.TaskManager.shell, android.os.Process.myUid(), since)
                    ?.isNotEmpty()
                val why = dev.droidtop.runtime.tasks.PhantomKills.explain(status, killed, android.os.Build.VERSION.SDK_INT, CHILD_PROCESSES_ROW)
                showEnd(if (why != null) WinePresentation.ExitReport("Android ended the game", why) else report)
            }, "droidtop-wine-ended").start()
            return
        }
        showEnd(report)
    }

    private fun showEnd(report: WinePresentation.ExitReport?) {
        runOnUiThread {
            // The person left the game (Back): stopping the session killed
            // Wine (code 137), which is the quit itself, not a failure to report.
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (report != null) showFailure(report.detail, title = report.title) else finish()
        }
    }

    /**
     * Writes the run's record off the main thread: how it ended, the last lines Wine printed and the game's own
     * logs ([WineGameLogs]), as `logs/wine-last.log` and the game's short history ([RunRecords.save]).
     */
    private fun writeRunRecord(status: Int, ranMs: Long, output: String) {
        val context = applicationContext
        val target = launchTarget ?: tool
        val key = entryId ?: target?.let(WinePresentation::fullscreenWMClass) ?: "unknown"
        val dirs = WineGameLogs.gameDirs(launchTarget, gameWorkingDir)
        val prefixDir = prefixRoot
        val shownWindow = guestShowedWindow
        val startedWall = startedAtWallMs
        Thread({
            runCatching {
                val attachments = WineGameLogs.find(dirs, prefixDir, launchTarget).mapNotNull { RunRecords.attach(it.label, it.file, startedWall) }
                val now = System.currentTimeMillis()
                val record = RunRecords.render(
                    heading = "Wine run",
                    facts = listOf(
                        "Started" to (target ?: "unknown"),
                        "Exit code" to status.toString(),
                        "Ran" to "$ranMs ms",
                        "Showed a window" to if (shownWindow) "yes" else "no",
                    ) + setupFacts + driverFacts(context, prefixDir, remember = true),
                    outputLabel = "Wine printed",
                    output = output,
                    attachments = attachments,
                    nowMs = now,
                )
                RunRecords.save(dev.droidtop.library.ScanLog.logsDir(context), WinePresentation.RUN_RECORD_PREFIX, key, record, now)
            }.onFailure { android.util.Log.w(WinePresentation.LOG_TAG, "could not write the run's record", it) }
        }, "droidtop-wine-record").start()
    }

    /**
     * The kernel drivers in the prefix now, one record line each (docs/SPEC.md 5b, "Kernel drivers";
     * Droidtop/tracker#607), read only. With [remember], at the end of a run, the game's Drivers list learns the ones
     * this run installed (new since [driversAtStart]) and everything its prefix holds. Disk: off the main thread.
     */
    private fun driverFacts(context: android.content.Context, prefixDir: File?, remember: Boolean): List<Pair<String, String>> {
        val dir = prefixDir ?: return emptyList()
        val now = dev.droidtop.library.KernelDrivers.scan(dir)
        val id = entryId
        if (remember && id != null) {
            driversAtStart?.map { it.key }?.toSet()?.let { before ->
                dev.droidtop.library.KernelDriverPrefs.addSeen(context, id, now.filter { it.key !in before })
            }
            dev.droidtop.library.KernelDriverPrefs.setPresent(context, id, now)
        }
        val approved = id?.let { dev.droidtop.library.KernelDriverPrefs.approved(context, it) }.orEmpty()
        val allow = id?.let { dev.droidtop.library.KernelDriverPrefs.allowUnlisted(context, it) } ?: false
        return now.map { "Kernel driver" to dev.droidtop.library.KernelDrivers.recordLine(it, approved = allow || it.key in approved) }
    }

    /** Report a problem from the black-screen notice: [report] with the dark frame that was shown. */
    private fun reportBlackScreen(frame: android.graphics.Bitmap?, done: (String) -> Unit) =
        report(
            heading = "Wine report: black screen",
            screen = "flat dark for ${dev.droidtop.library.diagnostics.BlackScreenWatch.HOLD_MS / 1000} s",
            frame = frame,
            done = done,
        )

    /**
     * Report a problem (docs/SPEC.md 7e2, "A black screen is noticed"; from the Quick Menu too, owner 2026-10-28,
     * Droidtop/tracker#601: "Yes"): the run's record as it stands, made while the game still runs (Wine's output so far,
     * the run's setup and the game's own logs), [frame] beside it as a PNG when there is one, then Share diagnostics so
     * the person chooses where it goes. Nothing is sent by droidtop, and no tracker link is offered (owner: "no. The
     * tracker is private"). Off the main thread; [done] gets the line to say.
     */
    private fun report(heading: String, screen: String?, frame: android.graphics.Bitmap?, done: (String) -> Unit) {
        val context = applicationContext
        val target = launchTarget ?: tool
        val key = entryId ?: target?.let(WinePresentation::fullscreenWMClass) ?: "unknown"
        val dirs = WineGameLogs.gameDirs(launchTarget, gameWorkingDir)
        val prefixDir = prefixRoot
        val startedWall = startedAtWallMs
        val ranMs = SystemClock.elapsedRealtime() - startedAtMs
        val output = session?.outputSoFar().orEmpty()
        val shown = guestShowedWindow
        Thread({
            val said = runCatching {
                val attachments = WineGameLogs.find(dirs, prefixDir, launchTarget).mapNotNull { RunRecords.attach(it.label, it.file, startedWall) }
                val now = System.currentTimeMillis()
                val record = RunRecords.render(
                    heading = heading,
                    facts = listOfNotNull(
                        "Started" to (target ?: "unknown"),
                        "Running for" to "$ranMs ms",
                        "Showed a window" to if (shown) "yes" else "no",
                        screen?.let { "Screen" to it },
                    ) + setupFacts + driverFacts(context, prefixDir, remember = false),
                    outputLabel = "Wine printed so far",
                    output = output,
                    attachments = attachments,
                    nowMs = now,
                )
                val logs = dev.droidtop.library.ScanLog.logsDir(context)
                val saved = RunRecords.save(logs, WinePresentation.REPORT_RECORD_PREFIX, key, record, now)
                frame?.let { shot ->
                    File(logs, saved.nameWithoutExtension + ".png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
                // A frame whose record was pruned goes with it.
                logs.listFiles().orEmpty()
                    .filter { it.name.startsWith(WinePresentation.REPORT_RECORD_PREFIX + "-run-") && it.extension == "png" }
                    .filter { !File(logs, it.nameWithoutExtension + ".log").isFile }
                    .forEach { it.delete() }
                dev.droidtop.library.diagnostics.DiagnosticsArchive.share(context)
            }.getOrElse {
                android.util.Log.w(WinePresentation.LOG_TAG, "could not make the report", it)
                "Couldn't save the report: ${it.message}"
            }
            done(said)
        }, "droidtop-wine-report").start()
    }

    /**
     * Shows why the game is not running, and stays up until the person
     * dismisses it.
     *
     * Deliberately its own view rather than a toast: a toast goes away by
     * itself, which is the one thing a failure report must not do. A launch that
     * fails after the shell has already handed off is only visible here,
     * so it is shown here, and finishing immediately would take it away
     * before it could be read.
     */
    private fun showFailure(
        message: String,
        cause: Throwable? = null,
        title: String = if (tool != null) "This Windows program did not start." else "This Windows game did not start.",
    ) {
        if (failed) return
        failed = true
        blackScreen?.pause()
        if (cause == null) {
            Timber.e("Wine launch failed: %s", message)
        } else {
            Timber.e(cause, "Wine launch failed: %s", message)
        }
        session?.stop()
        session = null
        // Nothing is running any more: the Quick Menu must not offer Resume or Quit for this launch
        // (Droidtop/tracker#171, rig 2026-10-01 r18).
        if (dev.droidtop.library.LaunchDisplay.runningPackageName == packageName) dev.droidtop.library.LaunchDisplay.clearRunning()
        setContentView(
            TextView(this).apply {
                // A tap dismisses it too, for a person holding the console without a pad in reach.
                setOnClickListener { leaveFailure() }
                text = title + "\n" + "\n" +
                    message + "\n" + "\n" +
                    "Check that Windows games is set up in Settings, then try again." + "\n" + "\n" +
                    "Press B to return to droidtop."
                gravity = Gravity.CENTER
                // droidtop's own screen, not the game's frame: it follows Light or dark (Droidtop/tracker#561).
                val tokens = chrome
                setBackgroundColor(tokens?.color("ground") ?: Color.BLACK)
                setTextColor(tokens?.color("onSurface") ?: Color.WHITE)
                setPadding(FAILURE_PADDING_PX, FAILURE_PADDING_PX, FAILURE_PADDING_PX, FAILURE_PADDING_PX)
            },
        )
    }

    /**
     * Leaves the failure screen for droidtop's shell. This screen is its own task (taskAffinity
     * `:wine`), so finishing it alone shows whatever task Android has beneath it, which on the rig was
     * a browser left open earlier (Droidtop/tracker#171, r09-after-back.png); the shell is brought to
     * the front explicitly, the same way the launch watchdog's "Return to droidtop" does.
     */
    private fun leaveFailure() {
        if (isFinishing) return
        dev.droidtop.library.LaunchWatchdog.returnToShell(this)
        finish()
    }

    // ---- input -------------------------------------------------------
    // Order matches gamenative's own: a controller claims the event
    // first, because that is the only consumer that can turn it into
    // XInput state; anything left is a keyboard key for the X server.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A key in droidtop's own game screen wakes a dimmed companion, as input on the shell does (docs/SPEC.md).
        dev.droidtop.library.settings.CompanionPrefs.inputSeen(android.os.SystemClock.uptimeMillis())
        if (failed) {
            if (event.action == KeyEvent.ACTION_UP && WinePresentation.dismissesFailure(event.keyCode)) leaveFailure()
            return true
        }
        // The black-screen notice takes the pad while it is up.
        if (blackScreen?.onKey(event) == true) return true
        // Back with the soft keyboard up closes the keyboard, not the game.
        softKeyboard?.takeIf { it.shown && event.keyCode == KeyEvent.KEYCODE_BACK }?.let {
            if (event.action == KeyEvent.ACTION_UP) it.hide()
            return true
        }
        // A keyboard-and-mouse layout, when on, plays the pad buttons it binds; mouse mode then sees none of them.
        if (layoutDriver?.onKey(event) == true) return true
        // Mouse mode takes its clicks and scroll hold first, and always watches for its on/off combination.
        if (padMouse?.onKey(event) == true) return true
        if (winHandler?.onKeyEvent(event) == true) return true
        if (keyboard?.onKeyEvent(event) == true) return true
        // Only a back press nothing else wanted leaves the game, and it does not end it: the shell
        // comes forward with the running game's sheet (Resume, Restart, Quit, Kill). The order
        // matters: several pads report their B button as KEYCODE_BACK.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) openQuickMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** A back gesture or button that did not arrive as a key event behaves as the key does. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (failed) finish() else openQuickMenu()
    }

    private fun openQuickMenu() {
        startActivity(OwnGameScreens.shellIntent(this))
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (layoutDriver?.onMotion(event) == true) return true
        if (padMouse?.onMotion(event) == true) return true
        if (winHandler?.onGenericMotionEvent(event) == true) return true
        if (touchMouse?.onExternalMouseEvent(event) == true) return true
        return super.onGenericMotionEvent(event)
    }

    // ---- controller mouse mode ----------------------------------------

    private var padMouse: PadMouseDriver? = null

    /** The layouts' driver, once they are read; Gamepad unless the game was left with a layout. */
    private var layoutDriver: LayoutDriver? = null
    private var controlLayouts: Map<WineControls, ControllerLayout> = emptyMap()
    private var controlsChoice: WineControls? = null

    /** Plays [choice]'s layout (null: Gamepad). Main thread. */
    private fun useControls(choice: WineControls?) {
        val layout = choice?.let { controlLayouts[it] }
        controlsChoice = if (layout != null) choice else null
        layoutDriver?.use(layout)
    }

    /** The visible sign that the pad is a mouse: a small badge in the top corner while it is on. */
    private val padMouseBadge by lazy {
        android.widget.TextView(this).apply {
            text = "Mouse mode"
            setTextColor(Color.WHITE)
            textSize = 14f
            // The screen's own ground (black), at 70% over the game, so the white text reads over any frame.
            setBackgroundColor(Color.BLACK)
            background.alpha = (0.7f * 255).toInt()
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            visibility = View.GONE
        }
    }

    private fun showPadMouse(on: Boolean, announce: Boolean) {
        padMouseBadge.removeCallbacks(endPadMouseHint)
        padMouseBadge.text = "Mouse mode"
        padMouseBadge.visibility = if (on) View.VISIBLE else View.GONE
        if (announce) padMouseBadge.announceForAccessibility(if (on) "Mouse mode on" else "Mouse mode off")
    }

    private val endPadMouseHint = Runnable { showPadMouse(padMouse?.settings?.on == true, announce = false) }

    /**
     * A game's first start says how mouse mode is reached (Droidtop/tracker#618 C2), on or off, so a game that wants
     * a pointer never looks dead to someone who has not heard of the combination. Gone after [PAD_MOUSE_HINT_MS].
     */
    private fun showPadMouseHint(on: Boolean) {
        padMouseBadge.text = if (on) "Mouse mode: hold both stick clicks to turn it off" else "Hold both stick clicks for mouse mode"
        padMouseBadge.visibility = View.VISIBLE
        padMouseBadge.postDelayed(endPadMouseHint, PAD_MOUSE_HINT_MS)
    }

    /** Turned on or off (the combination, the Quick Menu): shown at once, and kept for this game. */
    private fun padMouseChanged(settings: PadMouse.Settings) {
        showPadMouse(settings.on, announce = true)
        val id = entryId ?: return
        startupExecutor.execute { PadMousePrefs.set(applicationContext, id, settings) }
    }

    // ---- lifecycle ---------------------------------------------------

    override fun onResume() {
        super.onResume()
        goFullscreen()
        rendererView?.onResume()
        session?.onResume()
        blackScreen?.resume()
    }

    override fun onPause() {
        blackScreen?.pause()
        session?.onPause()
        rendererView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // The game is over for good (not a rotation): its cloud saves go up.
        OwnGameScreens.unregister(ownScreen)
        layoutDriver?.close()
        if (isFinishing) entryId?.let { dev.droidtop.library.stores.StoreSaves.afterExit(applicationContext, it) }
        session?.stop()
        session = null
        blackScreen?.stop()
        blackScreen = null
        startupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun goFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    companion object {
        /** Where the person lifts Android's limit on child processes (docs/SPEC.md "Child processes and Android's limit"). */
        private const val CHILD_PROCESSES_ROW = "Settings > Windows games > Child processes"

        private const val EXTRA_CONTAINER_ID = "dev.droidtop.wine.CONTAINER_ID"
        private const val EXTRA_TARGET = "dev.droidtop.wine.TARGET"
        private const val EXTRA_WORKING_DIR = "dev.droidtop.wine.WORKING_DIR"
        private const val EXTRA_ARGUMENTS = "dev.droidtop.wine.ARGUMENTS"
        private const val EXTRA_ENTRY_ID = "dev.droidtop.wine.ENTRY_ID"
        private const val EXTRA_TOOL = "dev.droidtop.wine.TOOL"
        private const val FAILURE_PADDING_PX = 48
        private const val PAD_MOUSE_HINT_MS = 8_000L

        /**
         * The intent a launch dispatches. Deliberately carries ids and
         * paths rather than objects: the [Container] is re-resolved here
         * from the same [ContainerManager] that owns it, so there is one
         * source of prefix state and no copy to fall out of date.
         */
        /**
         * Brings the running game screen forward without touching it: the Quick Menu's Resume
         * relaunches the entry, and for a Windows game that already runs this is that relaunch.
         */
        fun bringToFront(context: Context): Intent =
            Intent(context, WineGameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        fun intent(
            context: Context,
            prefix: Container,
            target: String,
            workingDir: File,
            arguments: List<String> = emptyList(),
            entryId: String? = null,
            tool: String? = null,
        ): Intent =
            Intent(context, WineGameActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_CONTAINER_ID, prefix.id)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_WORKING_DIR, workingDir.absolutePath)
                putStringArrayListExtra(EXTRA_ARGUMENTS, ArrayList(arguments))
                entryId?.let { putExtra(EXTRA_ENTRY_ID, it) }
                tool?.let { putExtra(EXTRA_TOOL, it) }
                // A game's own Wine choices over a shared prefix are this
                // launch's, not the prefix's (docs/SPEC.md 5a), so they
                // travel with the launch rather than being saved into it.
                if (prefix.launchOverrides.isNotEmpty()) {
                    putExtra(EXTRA_LAUNCH_OVERRIDES, org.json.JSONObject(prefix.launchOverrides).toString())
                }
            }

        private const val EXTRA_LAUNCH_OVERRIDES = "dev.droidtop.wine.LAUNCH_OVERRIDES"

        /** The overrides [intent] carried, read back. */
        internal fun launchOverrides(intent: Intent): Map<String, String> {
            val json = intent.getStringExtra(EXTRA_LAUNCH_OVERRIDES)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                ?: return emptyMap()
            return json.keys().asSequence().associateWith { json.getString(it) }
        }
    }
}

/**
 * The presentation decisions that are pure functions of the prefix and
 * the target, so they are tested without a surface.
 */
object WinePresentation {

    /**
     * The keys that leave the failure screen: Back, and the pad's B, because most pads report B as
     * KEYCODE_BUTTON_B rather than KEYCODE_BACK, and on the rig six B presses did nothing while the
     * screen said "Press Back" (Droidtop/tracker#171, r03..r08). Escape for a keyboard. Not A: the
     * release of the A press that started the launch can arrive here when the screen fails at once.
     */
    fun dismissesFailure(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BACK ||
        keyCode == KeyEvent.KEYCODE_BUTTON_B ||
        keyCode == KeyEvent.KEYCODE_ESCAPE

    /**
     * The Vulkan present mode for a prefix's named one. The numbers are
     * `VkPresentModeKHR`'s own (immediate 0, mailbox 1, fifo 2, relaxed
     * 3); anything unnamed is fifo, which is the mode that always
     * exists.
     */
    fun vkPresentMode(name: String?): Int = when (name?.lowercase()) {
        "immediate" -> 0
        "mailbox" -> 1
        "relaxed" -> 3
        else -> 2
    }

    /**
     * The WM class the renderer should blow up to fill the screen: the
     * target executable's own file name. Both separators are stripped
     * because a `.desktop` shortcut stores a Windows path and an
     * installed game stores a Unix one.
     */
    fun fullscreenWMClass(target: String): String? =
        target.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }

    /** How Wine's end is put to the person: a headline and the detail under it. */
    data class ExitReport(val title: String, val detail: String)

    const val LOG_TAG = "droidtop.wine"

    /**
     * The newest Wine run's record, in droidtop's logs folder (ScanLog.logsDir), so Share diagnostics carries it;
     * the last few runs of each game stay beside it as `wine-run-<game>-<time>.log` ([RunRecords.save]).
     */
    const val RUN_RECORD_PREFIX = "wine"

    /** A Report a problem made while a game runs: its record (`wine-report-last.log`, `wine-report-run-...`) and frame. */
    const val REPORT_RECORD_PREFIX = "wine-report"

    /** Wine ending this soon after it started means the game did not really run. */
    const val EXITED_AT_ONCE_MS = 10_000L

    /** At most this many of Wine's last output lines are shown. */
    const val EXIT_LINES = 12

    /**
     * What to say when Wine ends with [status] after [ranMs], given the tail
     * of what it printed ([output]); null for a game that showed a window and
     * quit with code 0, which needs no report. A game that never mapped a
     * window of its own ([showedWindow]) did not run, whatever its code and
     * however long Wine lasted: a game that cannot create its device often
     * quits cleanly (rig, a Unity game with no Direct3D 11 device), and one
     * that quit cleanly after ten seconds showed nothing at all (console,
     * 2026-10-17). A prefix [tool] is never reported for code 0: Wine
     * configuration may well be closed again within seconds.
     */
    fun exitReport(status: Int, ranMs: Long, showedWindow: Boolean, output: String, tool: Boolean = false): ExitReport? {
        // A prefix tool (Wine configuration, the registry editor, an installer) is closed when its work is done,
        // however soon; only a failing exit is worth saying.
        if (tool && status == 0) return null
        val atOnce = ranMs < EXITED_AT_ONCE_MS
        if (status == 0 && (tool || showedWindow)) return null
        val what = if (tool) "program" else "game"
        val title = when {
            atOnce -> "This Windows $what closed straight after it started."
            status == 0 -> "This Windows $what closed without showing a window."
            else -> "This Windows $what stopped with an error."
        }
        val seconds = (ranMs / 1000).coerceAtLeast(0)
        val code = "Wine exited with code $status after $seconds s."
        val lines = output.lines().map(String::trimEnd).filter(String::isNotBlank).takeLast(EXIT_LINES)
        val detail = if (lines.isEmpty()) {
            "$code It printed nothing."
        } else {
            code + "\n" + "\n" + "Last lines Wine printed:" + "\n" + lines.joinToString("\n").takeLast(EXIT_DETAIL_CHARS)
        }
        return ExitReport(title, detail)
    }

    private const val EXIT_DETAIL_CHARS = 1200
}
