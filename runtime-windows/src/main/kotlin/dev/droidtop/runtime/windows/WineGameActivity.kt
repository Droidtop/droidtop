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
import com.winlator.PrefManager as WinlatorPrefManager

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
    private var rendererView: XServerRendererView? = null
    private var session: WineXSession? = null
    private var touchpadView: TouchpadView? = null
    private var touchMouse: TouchMouse? = null
    private var keyboard: Keyboard? = null
    private var softKeyboard: WineKeyboard? = null
    private var winHandler: WinHandler? = null
    private var failed = false
    private var startedAtMs = 0L

    /** Whether the guest mapped an application window of its own (not the virtual desktop). */
    @Volatile
    private var guestShowedWindow = false

    /** The library game this screen runs, when it is one (its cloud saves are synced when the screen ends). */
    private var entryId: String? = null

    /** What the Quick Menu's Game section reaches this screen through (Resume is a relaunch, Stop is [stop]). */
    private val ownScreen = object : OwnGameScreens.Screen {
        override val entryId: String? get() = this@WineGameActivity.entryId
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Both preference stores are read from deep inside the Winlator
        // view and renderer code, and both inits are no-ops once done.
        GameNativePrefManager.init(this)
        WinlatorPrefManager.init(this)
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
        touchpad.setShowKeyboardCallback { soft.toggle() }
        touchpad.setKeyboardSwipeCallback { soft.show() }
        setContentView(root)

        val session = WineXSession(this, prefix, target, workingDir, xServer, arguments)
        this.session = session
        startedAtMs = SystemClock.elapsedRealtime()
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
    private fun onGuestTerminated(status: Int) {
        val ranMs = SystemClock.elapsedRealtime() - startedAtMs
        val report = WinePresentation.exitReport(status, ranMs, guestShowedWindow, session?.output().orEmpty())
        Timber.i("Wine exited %d after %d ms", status, ranMs)
        runOnUiThread {
            // The person left the game (Back): stopping the session killed
            // Wine (code 137), which is the quit itself, not a failure to report.
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (report != null) showFailure(report.detail, title = report.title) else finish()
        }
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
    private fun showFailure(message: String, cause: Throwable? = null, title: String = "This Windows game did not start.") {
        if (failed) return
        failed = true
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
                setBackgroundColor(Color.BLACK)
                setTextColor(Color.WHITE)
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
        if (failed) {
            if (event.action == KeyEvent.ACTION_UP && WinePresentation.dismissesFailure(event.keyCode)) leaveFailure()
            return true
        }
        // Back with the soft keyboard up closes the keyboard, not the game.
        softKeyboard?.takeIf { it.shown && event.keyCode == KeyEvent.KEYCODE_BACK }?.let {
            if (event.action == KeyEvent.ACTION_UP) it.hide()
            return true
        }
        if (winHandler?.onKeyEvent(event) == true) return true
        if (keyboard?.onKeyEvent(event) == true) return true
        // Only a back press nothing else wanted leaves the game, and it does not end it: the shell
        // comes forward with its Quick Menu on the Game section (Resume, Restart, Stop). The order
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
        if (winHandler?.onGenericMotionEvent(event) == true) return true
        if (touchMouse?.onExternalMouseEvent(event) == true) return true
        return super.onGenericMotionEvent(event)
    }

    // ---- lifecycle ---------------------------------------------------

    override fun onResume() {
        super.onResume()
        goFullscreen()
        rendererView?.onResume()
        session?.onResume()
    }

    override fun onPause() {
        session?.onPause()
        rendererView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // The game is over for good (not a rotation): its cloud saves go up.
        OwnGameScreens.unregister(ownScreen)
        if (isFinishing) entryId?.let { dev.droidtop.library.stores.StoreSaves.afterExit(applicationContext, it) }
        session?.stop()
        session = null
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
        private const val EXTRA_CONTAINER_ID = "dev.droidtop.wine.CONTAINER_ID"
        private const val EXTRA_TARGET = "dev.droidtop.wine.TARGET"
        private const val EXTRA_WORKING_DIR = "dev.droidtop.wine.WORKING_DIR"
        private const val EXTRA_ARGUMENTS = "dev.droidtop.wine.ARGUMENTS"
        private const val EXTRA_ENTRY_ID = "dev.droidtop.wine.ENTRY_ID"
        private const val FAILURE_PADDING_PX = 48

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
        ): Intent =
            Intent(context, WineGameActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_CONTAINER_ID, prefix.id)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_WORKING_DIR, workingDir.absolutePath)
                putStringArrayListExtra(EXTRA_ARGUMENTS, ArrayList(arguments))
                entryId?.let { putExtra(EXTRA_ENTRY_ID, it) }
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

    /** Wine ending this soon after it started means the game did not really run. */
    const val EXITED_AT_ONCE_MS = 10_000L

    /** At most this many of Wine's last output lines are shown. */
    const val EXIT_LINES = 12

    /**
     * What to say when Wine ends with [status] after [ranMs], given the tail
     * of what it printed ([output]); null for a program that ran and quit with
     * code 0, which needs no report. A code-0 exit within [EXITED_AT_ONCE_MS]
     * is reported too when the guest never mapped a window of its own
     * ([showedWindow]): a game that cannot create its device often quits
     * cleanly (rig, a Unity game with no Direct3D 11 device), while a tool such
     * as Wine configuration may well be closed again within seconds.
     */
    fun exitReport(status: Int, ranMs: Long, showedWindow: Boolean, output: String): ExitReport? {
        val atOnce = ranMs < EXITED_AT_ONCE_MS
        if (status == 0 && (!atOnce || showedWindow)) return null
        val title = if (atOnce) "This Windows game closed straight after it started." else "This Windows game stopped with an error."
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
