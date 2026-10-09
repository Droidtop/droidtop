package dev.droidtop.app

import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.compose.ui.graphics.toArgb
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.keyboard.AccessibilityKeyboard
import dev.droidtop.runtime.keyboard.AddonKeyboard
import dev.droidtop.runtime.keyboard.AddonKeyboardRules
import dev.droidtop.runtime.keyboard.DisplayImePolicy
import dev.droidtop.runtime.keyboard.OverlayOwner
import dev.droidtop.runtime.keyboard.TypingRoute
import dev.droidtop.runtime.tasks.Fidelity
import dev.droidtop.runtime.tasks.LaunchLedger
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.shell.gamepad.ChromeColors
import dev.droidtop.shell.gamepad.KeyboardTargets
import dev.droidtop.shell.gamepad.INLINE_KEYBOARD_HEIGHT_PERCENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pocketworkstation.pckeyboard.ImeConnectionSink
import org.pocketworkstation.pckeyboard.KeyMeta
import org.pocketworkstation.pckeyboard.KeyboardPanel
import org.pocketworkstation.pckeyboard.KeyboardSink
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard
import java.util.concurrent.Executors

/**
 * Run by the elevated helper, never inside droidtop's own process: `env CLASSPATH=<droidtop's APK> app_process
 * /system/bin dev.droidtop.app.ImePolicyTool get|set <display> [<policy>]` (docs/SPEC.md 4c, "Typing on the add-on
 * display"). Android has no shell command for a display's keyboard policy (`WindowManagerShellCommand` has none,
 * Android 10 to 15); the call is `IWindowManager.setDisplayImePolicy` (Android 12+) or `setShouldShowIme`
 * (Android 10 and 11), which need INTERNAL_SYSTEM_WINDOW, a permission the shell user holds
 * (frameworks/base packages/Shell/AndroidManifest.xml). A process started by app_process gets no hidden-API
 * restriction, so the binder interface is reached by reflection. Prints `policy=<n>` and exits 0, or prints the
 * error and exits 1. Kept by name in proguard-rules.pro.
 */
object ImePolicyTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            val wm = windowManager()
            val display = args.getOrNull(1)?.toIntOrNull() ?: error("usage: get|set <display> [<policy>]")
            when (args.getOrNull(0)) {
                "get" -> Unit
                "set" -> set(wm, display, args.getOrNull(2)?.toIntOrNull() ?: error("no policy"))
                else -> error("usage: get|set <display> [<policy>]")
            }
            println("policy=${get(wm, display)}")
            0
        } catch (t: Throwable) {
            System.err.println("error: ${(t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t}")
            1
        }
        System.exit(code)
    }

    private fun windowManager(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "window") as IBinder
        return Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun get(wm: Any, display: Int): Int =
        try {
            wm.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType).invoke(wm, display) as Int
        } catch (e: NoSuchMethodException) {
            val shows = wm.javaClass.getMethod("shouldShowIme", Int::class.javaPrimitiveType).invoke(wm, display) as Boolean
            if (shows) DisplayImePolicy.LOCAL.code else DisplayImePolicy.FALLBACK_DISPLAY.code
        }

    private fun set(wm: Any, display: Int, policy: Int) {
        try {
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(wm, display, policy)
        } catch (e: NoSuchMethodException) {
            wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(wm, display, policy == DisplayImePolicy.LOCAL.code)
        }
    }
}

/**
 * Typing on the add-on display, in `:app` (docs/SPEC.md 4c, "Typing on the add-on display", Droidtop/tracker#314):
 * with elevated access, Android's own keyboard is made to show on each second display ([sync]); without it, or for
 * apps the policy does not help, droidtop's own keyboard is drawn on the editor's screen ([ShowOverEditor]) or on the
 * companion, typing through droidtop's input method or the elevated `input` command.
 */
object AddonKeyboardHost {
    private const val TAG = "droidtop.keyboard"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()

    /** What the last completed pass saw; a pass with the same inputs runs no command. */
    @Volatile
    private var lastPass: Triple<Boolean, Boolean, Set<Int>>? = null

    /** Main process only: installs the keeper, its display listener and the keyboard over other apps. */
    fun install(app: Application) {
        val main = Build.VERSION.SDK_INT < 28 || Application.getProcessName() == app.packageName
        if (!main) return
        AddonKeyboard.resync = { context -> resync(context, force = true) }
        app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = resync(app, force = false)

                override fun onDisplayRemoved(displayId: Int) = resync(app, force = false)

                override fun onDisplayChanged(displayId: Int) = Unit
            },
            Handler(Looper.getMainLooper()),
        )
        ShowOverEditor.install(app)
        KeyboardTargets.internalSurface = InternalScreenKeyboard(app)
        resync(app, force = false)
    }

    /**
     * Re-runs the policy keeper off the main thread. Called at start, when a display comes or goes, when droidtop
     * comes to the front (elevated access may have been granted meanwhile) and when the setting changes ([force]).
     */
    fun resync(context: Context, force: Boolean) {
        val app = context.applicationContext
        scope.launch { syncLock.withLock { sync(app, force) } }
    }

    private fun sync(context: Context, force: Boolean) {
        AddonKeyboard.load(context)
        val shell = TaskManager.shell
        val elevated = runCatching { shell.capabilities().shellCommand }.getOrDefault(false)
        val enabled = AddonKeyboard.androidKeyboardOnSecondScreen(context) && AddonKeyboard.controlsKeyboard
        val second = DisplayOutputRepository(context).currentOutputsSnapshot()
            .filter { it.kind == DisplayOutputKind.SECOND_SCREEN }
            .map { it.androidDisplayId }
            .toSet()
        val pass = Triple(enabled, elevated, second)
        if (!force && pass == lastPass) return
        val applied = AddonKeyboard.applied(context).toMutableMap()
        val plan = AddonKeyboardRules.plan(enabled, elevated, second, applied)
        val apk = context.applicationInfo.sourceDir
        val local = mutableSetOf<Int>()
        for (display in plan.setLocal) {
            val before = applied[display] ?: policy(apk, "get", display, null) ?: continue
            val now = policy(apk, "set", display, DisplayImePolicy.LOCAL)
            Log.i(TAG, "display $display keyboard policy $before -> $now")
            if (now == DisplayImePolicy.LOCAL) {
                local += display
                if (display !in applied) applied[display] = before
            }
        }
        for ((display, original) in plan.restore) {
            val now = policy(apk, "set", display, original)
            Log.i(TAG, "display $display keyboard policy given back: $now")
            applied.remove(display)
        }
        AddonKeyboard.setApplied(context, applied)
        // Without elevated access nothing was asked; Android keeps a policy across restarts (display_settings.xml),
        // so a display droidtop set local earlier still is.
        if (!elevated) local += applied.keys.filter { it in second }
        AddonKeyboard.publishLocal(local)
        lastPass = pass
    }

    /** One run of [ImePolicyTool] through the elevated helper; the policy it read back, or null. */
    private fun policy(apk: String, verb: String, display: Int, policy: DisplayImePolicy?): DisplayImePolicy? {
        if (!APK_PATH.matches(apk)) return null
        val argv = buildList {
            addAll(listOf("env", "CLASSPATH=$apk", "app_process", "/system/bin", ImePolicyTool::class.java.name, verb, display.toString()))
            policy?.let { add(it.code.toString()) }
        }
        val out = runCatching { TaskManager.shell.exec(argv) }.getOrNull() ?: return null
        if (out.exit != 0) Log.w(TAG, "keyboard policy $verb on display $display: ${out.stderr.ifBlank { out.stdout }}")
        return AddonKeyboardRules.parsePolicyLine(out.stdout)
    }

    private val APK_PATH = Regex("^/[A-Za-z0-9._/=+~-]+\\.apk\$")
}

/**
 * Types into the focused app on [displayId] through the elevated helper's `input -d <display>` (Shizuku or Sui),
 * the fallback when droidtop's input method is not the selected one. Commands run one after another on their own
 * thread, in the order they were typed. A key that types a printable character goes as text (so Shift is honoured);
 * every other key goes as a key event. Android sends a display's keys to that display's focused window, which is the
 * focused one as long as that display holds the system's focus (`config_perDisplayFocusEnabled` is off on handhelds).
 */
class ElevatedInputSink(private val displayId: () -> Int?) : KeyboardSink {
    private var metaState = 0

    override fun key(androidKeyCode: Int, down: Boolean) {
        metaState = KeyMeta.updated(metaState, androidKeyCode, down)
        if (!down || KeyMeta.updated(0, androidKeyCode, true) != 0) return
        val display = displayId() ?: return
        val unicode = runCatching { VIRTUAL.get(androidKeyCode, metaState and KeyEvent.META_SHIFT_ON) }.getOrDefault(0)
        val char = unicode.takeIf { it != 0 && unicode and KeyCharacterMap.COMBINING_ACCENT == 0 }?.toChar()
        val argv = char?.let { AddonKeyboardRules.inputTextArgv(display, it.toString()) }
            ?: AddonKeyboardRules.inputKeyArgv(display, androidKeyCode)
        run(argv)
    }

    override fun text(chars: CharSequence) {
        val display = displayId() ?: return
        AddonKeyboardRules.inputTextArgv(display, chars)?.let(::run)
    }

    private fun run(argv: List<String>) {
        RUNNER.execute { runCatching { TaskManager.shell.exec(argv) } }
    }

    private companion object {
        val RUNNER = Executors.newSingleThreadExecutor { r -> Thread(r, "droidtop-elevated-input").apply { isDaemon = true } }
        val VIRTUAL: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }
    }
}

/**
 * droidtop's keyboard typing into another app's focused field, by the best route there is right now
 * ([AddonKeyboardRules.route]): droidtop's input method's own connection, else droidtop's accessibility service's
 * focused field ([AccessibilityTyping]), else the elevated `input` command on [displayId]. [elevated] is read off the
 * main thread by the caller. [onNoRoute] runs when a key has nowhere to go, [onRouted] when one went somewhere.
 */
class RoutedKeyboardSink(
    private val displayId: () -> Int?,
    private val elevated: () -> Boolean,
    private val onNoRoute: () -> Unit = {},
    private val onRouted: () -> Unit = {},
) : KeyboardSink {
    private val ime = ImeConnectionSink()
    private val accessibility = AccessibilityTyping.FieldSink()
    private val shell = ElevatedInputSink(displayId)

    private fun target(): KeyboardSink? =
        when (AddonKeyboardRules.route(SecondScreenKeyboard.androidTargetAvailable(), AccessibilityKeyboard.hasEditor(), elevated())) {
            TypingRoute.DROIDTOP_IME -> ime
            TypingRoute.ACCESSIBILITY -> accessibility
            TypingRoute.ELEVATED_INPUT -> shell
            TypingRoute.NONE -> null
        }

    override fun key(androidKeyCode: Int, down: Boolean) {
        val sink = target() ?: return onNoRoute()
        sink.key(androidKeyCode, down)
        onRouted()
    }

    override fun text(chars: CharSequence) {
        val sink = target() ?: return onNoRoute()
        sink.text(chars)
        onRouted()
    }
}

/**
 * The keyboard droidtop pops over another app on a screen where Android draws none (docs/SPEC.md 4c): when an
 * editor asks droidtop's input method for its keyboard and the editor's app is on a second display Android has not
 * been set to show a keyboard on, the same keyboard view is drawn at the bottom of that display in an overlay
 * window, typing through the input method's connection. The overlay is not focusable, so the app keeps its focus
 * and its input session. Needs "Display over other apps"; without it nothing is drawn and the settings row says so.
 * The editor's display comes from the system's task list where the elevated helper reads it, else from the screen
 * droidtop launched the app on: `EditorInfo` names the package, not the display ([appDisplay]). When droidtop's
 * accessibility service is on and this overlay may not be drawn, the service draws the same keyboard instead
 * ([AddonKeyboardRules.overlayOwner]).
 */
object ShowOverEditor : SecondScreenKeyboard.ShowRequests {
    private const val HIDE_DELAY_MS = 400L
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val overlay = PlacedKeyboard()
    private var dismissedFor: String? = null
    private val hide = Runnable { overlay.hide() }

    fun install(context: Context) {
        app = context.applicationContext
        SecondScreenKeyboard.showRequests = this
    }

    override fun requested(editorPackage: String?) {
        main.post {
            main.removeCallbacks(hide)
            if (editorPackage != null && editorPackage == dismissedFor) return@post
            val display = if (editorPackage == app.packageName) null else appDisplay(editorPackage)
            val owner = AddonKeyboardRules.overlayOwner(
                display,
                AddonKeyboard.localDisplays.value,
                droidtopImeSelected = true,
                canDrawOverlays = Settings.canDrawOverlays(app),
                accessibilityOn = AccessibilityKeyboard.connected,
            )
            if (owner == OverlayOwner.INPUT_METHOD) show(display!!, editorPackage) else overlay.hide()
        }
    }

    /** A tap on the focused field asks for the keyboard again: a Hide holds only until the next ask (tracker#369). */
    override fun reasked() {
        main.post {
            val editor = dismissedFor ?: return@post
            dismissedFor = null
            requested(editor)
        }
    }

    override fun ended() {
        main.post {
            dismissedFor = null
            main.removeCallbacks(hide)
            main.postDelayed(hide, HIDE_DELAY_MS)
        }
    }

    private fun show(displayId: Int, editorPackage: String?) {
        overlay.show(
            displayId,
            windowContext = {
                app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)?.let { display ->
                    val displayContext = app.createDisplayContext(display)
                    if (Build.VERSION.SDK_INT >= 30) {
                        displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    } else {
                        displayContext
                    }
                }
            },
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            sink = ImeConnectionSink(),
            what = "keyboard over $editorPackage",
            onHide = { dismissedFor = editorPackage },
        )
    }
}

/**
 * The display [packageName]'s visible window is on: from the system's task list where the elevated helper reads it,
 * else from the screen droidtop launched the app on. Null when droidtop cannot place the app.
 */
internal fun appDisplay(packageName: String?): Int? {
    if (packageName == null) return null
    val snapshot = TaskManager.snapshot.value
    if (snapshot != null && snapshot.fidelity == Fidelity.EXACT) {
        snapshot.apps.firstOrNull { it.packageName == packageName && it.visible }?.let { return it.displayId }
    }
    return LaunchLedger.entries().firstOrNull { it.packageName == packageName }?.displayId
}

/**
 * droidtop's keyboard drawn over another app at the bottom of one display, in a window that never takes focus (the
 * app keeps its focus and its input session), with a Hide key (docs/SPEC.md 4c, "Typing on the add-on display").
 * The one overlay both owners use: the input method's ([ShowOverEditor], `TYPE_APPLICATION_OVERLAY`) and the
 * accessibility service's ([AccessibilityTyping], `TYPE_ACCESSIBILITY_OVERLAY`), each through [PlacedKeyboard],
 * and the internal-screen keyboard ([InternalScreenKeyboard]). Main thread only.
 */
internal class OverlayKeyboard {
    private var window: View? = null
    private var windowManager: WindowManager? = null

    /** The display the keyboard is drawn on, or null. */
    var shownOn: Int? = null
        private set

    /**
     * Draws the keyboard with [context] (one for [displayId], able to add a [type] window there), typing into
     * [sink]. [onHide] runs when the user presses Hide. Returns whether the window was added.
     */
    fun show(
        context: Context,
        displayId: Int,
        type: Int,
        sink: KeyboardSink,
        suppressImeView: Boolean,
        what: String,
        onHide: () -> Unit,
    ): Boolean {
        if (shownOn == displayId) return true
        hide()
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ChromeColors.DarkBackground.toArgb())
            addView(
                Button(context).apply {
                    text = "Hide"
                    setOnClickListener {
                        hide()
                        onHide()
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                },
            )
            addView(
                KeyboardPanel(context, sink, INLINE_KEYBOARD_HEIGHT_PERCENT, suppressImeView),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        return runCatching { wm.addView(root, params) }
            .onSuccess {
                window = root
                windowManager = wm
                shownOn = displayId
                Log.i("droidtop.keyboard", "$what on display $displayId")
            }
            .onFailure { Log.w("droidtop.keyboard", "$what on display $displayId refused", it) }
            .isSuccess
    }

    fun hide() {
        shownOn = null
        val view = window ?: return
        runCatching { windowManager?.removeView(view) }
        window = null
        windowManager = null
    }
}

/**
 * droidtop's keyboard for another app's field on [shownOn], wherever "Keyboard displays on" puts it
 * ([KeyboardTargets.open]): the companion's input controller, the internal screen, an overlay on the field's own
 * screen ([OverlayKeyboard], built from [show]'s window context only then), or nowhere when droidtop does not control
 * the keyboard. Used by both overlay owners. Main thread only.
 */
internal class PlacedKeyboard {
    private val overlay = OverlayKeyboard()
    private var elsewhere: KeyboardTargets.Request? = null

    var shownOn: Int? = null
        private set

    fun show(
        displayId: Int,
        windowContext: () -> Context?,
        type: Int,
        sink: KeyboardSink,
        what: String,
        onHide: () -> Unit,
    ) {
        if (shownOn == displayId) return
        hide()
        val hidden = {
            elsewhere = null
            shownOn = null
            onHide()
        }
        when (val opened = KeyboardTargets.open(displayId, sink, hidden)) {
            is KeyboardTargets.Opened.Elsewhere -> {
                elsewhere = opened.request
                shownOn = displayId
                Log.i("droidtop.keyboard", "$what on display $displayId, keyboard on ${opened.request.target.label}")
            }
            KeyboardTargets.Opened.Nowhere -> Unit
            KeyboardTargets.Opened.Here -> {
                val context = windowContext() ?: return
                if (overlay.show(context, displayId, type, sink, suppressImeView = true, what = what, onHide = hidden)) shownOn = displayId
            }
        }
    }

    fun hide() {
        elsewhere?.let { KeyboardTargets.close(it) }
        elsewhere = null
        overlay.hide()
        shownOn = null
    }
}

/**
 * "Keyboard displays on: Internal screen": a plain keyboard at the bottom of the built-in display, whatever shows
 * there, typing into the request's sink. Drawn as droidtop's accessibility overlay when that service is on, else as
 * an app overlay with "Display over other apps"; with neither, droidtop cannot draw there and the keyboard stays on
 * the field's screen.
 */
internal class InternalScreenKeyboard(private val app: Context) : KeyboardTargets.InternalSurface {
    private val overlay = OverlayKeyboard()
    private var current: KeyboardTargets.Request? = null

    override fun available(): Boolean = AccessibilityKeyboard.connected || Settings.canDrawOverlays(app)

    override fun show(request: KeyboardTargets.Request): Boolean {
        hide(current ?: request)
        val display = Display.DEFAULT_DISPLAY
        val (context, type) = AccessibilityTyping.overlayContext(display)?.let { it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
            ?: run {
                if (!Settings.canDrawOverlays(app)) return false
                val screen = app.getSystemService(DisplayManager::class.java)?.getDisplay(display) ?: return false
                val displayContext = app.createDisplayContext(screen)
                val window = if (Build.VERSION.SDK_INT >= 30) {
                    displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                } else {
                    displayContext
                }
                window to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
        val shown = overlay.show(context, display, type, request.sink, suppressImeView = true, what = "keyboard for display ${request.fieldDisplay}") {
            current = null
            request.onHide()
        }
        if (shown) current = request
        return shown
    }

    override fun hide(request: KeyboardTargets.Request) {
        if (current !== request) return
        current = null
        overlay.hide()
    }
}
