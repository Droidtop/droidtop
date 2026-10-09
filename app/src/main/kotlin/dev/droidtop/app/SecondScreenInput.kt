package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.input.FocusNavTrackpadSink
import dev.droidtop.input.InputSeats
import dev.droidtop.input.NavKey
import dev.droidtop.input.SeatTrackpadSink
import dev.droidtop.input.TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH
import dev.droidtop.input.TrackpadGestureEngine
import dev.droidtop.input.TrackpadOutput
import dev.droidtop.input.TrackpadView
import dev.droidtop.runtime.keyboard.AccessibilityKeyboard
import dev.droidtop.shell.gamepad.ChromeColors
import dev.droidtop.shell.gamepad.KeyboardTargets
import org.pocketworkstation.pckeyboard.KeyboardPanel
import org.pocketworkstation.pckeyboard.KeyboardSink
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/**
 * The foreground droidtop Activity, for the one thing the second screen
 * needs from it: somewhere to deliver synthetic navigation keys.
 *
 * `Activity.dispatchKeyEvent` is an ordinary public call into droidtop's
 * OWN window; it needs no permission and reaches the same Compose focus
 * machinery a real D-pad reaches. Reaching another app's window would need
 * `INJECT_EVENTS`, a signature permission, which is why the Gaming
 * trackpad navigates droidtop and nothing else -- see `FocusNavTrackpadSink`.
 *
 * Weak, because holding an Activity from a process-wide object is the
 * classic leak, and a stale reference would deliver keys to a destroyed
 * window.
 */
object ForegroundShell {
    private var ref: WeakReference<Activity>? = null

    fun set(activity: Activity?) {
        ref = activity?.let { WeakReference(it) }
    }

    fun current(): Activity? = ref?.get()

    /**
     * Wires the touch-only second-screen activities to the shell (docs/SPEC.md 4c): when one of
     * them becomes the top activity, the shell's task is brought to the front if the shell is
     * resumed on another screen, and any key that still reaches the surface is delivered here.
     * Moving a task needs REORDER_TASKS (a normal permission); the caller is the top activity, so
     * the background-start rules allow it.
     */
    fun installPadReturn(context: Context) {
        appContext = context.applicationContext
        dev.droidtop.display.TouchOnlySurfaceFocus.returnPadToShell = returnPad@{ fromDisplayId ->
            val shell = current()?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (shell != null && displayIdOf(shell) != fromDisplayId) {
                val activityManager = shell.getSystemService(android.app.ActivityManager::class.java)
                    ?: return@returnPad false
                activityManager.moveTaskToFront(shell.taskId, 0)
                return@returnPad true
            }
            returnFocusToApp(fromDisplayId)
        }
        dev.droidtop.display.TouchOnlySurfaceFocus.forwardKeyToShell = { event ->
            current()?.takeIf { !it.isFinishing && !it.isDestroyed }?.dispatchKeyEvent(event) ?: false
        }
    }

    /**
     * No shell in front on another screen, but the user may be in an app there (an app launched onto the add-on
     * display covers the shell, which then is not [current]). When the system's own task list (the elevated helper)
     * shows an app visible on another screen, it is brought back to the front through the task manager's one switch
     * ([dev.droidtop.library.tasks.TaskActions.bringTo], its launcher intent on its own screen), so its screen holds
     * the system's focus again and its keys and keyboard reach it (Droidtop/tracker#314). Without that list droidtop
     * cannot tell an app still showing from one the user left, so it moves nothing; a tap on the app does the same.
     */
    private fun returnFocusToApp(fromDisplayId: Int?): Boolean {
        val context = appContext ?: return false
        // The list is read fresh (it is only polled while a task list is on screen), off the main thread; the
        // switch itself is an activity start, back on the main thread. Until it lands, keys stay here.
        refocusScope.launch {
            if (!dev.droidtop.runtime.tasks.TaskManager.privileges().listTasks) return@launch
            dev.droidtop.runtime.tasks.TaskManager.refresh(context)
            val snapshot = dev.droidtop.runtime.tasks.TaskManager.snapshot.value ?: return@launch
            val app = dev.droidtop.runtime.keyboard.FocusReturn.appToRefocus(snapshot, fromDisplayId, context.packageName)
                ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                dev.droidtop.library.tasks.TaskActions.bringTo(context, app.packageName, app.displayId)
            }
        }
        return false
    }

    private var appContext: Context? = null

    private val refocusScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun displayIdOf(activity: Activity): Int? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.displayId
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay?.displayId
        }

    fun send(navKey: NavKey) {
        val activity = current() ?: return
        val keyCode = when (navKey) {
            NavKey.UP -> KeyEvent.KEYCODE_DPAD_UP
            NavKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            NavKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            NavKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            // GamepadKeyMap already reads DirectionCenter as the A action
            // and Back as BACK, so these are the shell's own vocabulary
            // rather than a second mapping invented here.
            NavKey.CONFIRM -> KeyEvent.KEYCODE_DPAD_CENTER
            NavKey.BACK -> KeyEvent.KEYCODE_BACK
        }
        val now = SystemClock.uptimeMillis()
        // Both halves: the shell reads a few actions on key-down and most
        // on key-up, and a down with no up would leave Compose believing
        // the key is still held.
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}

/**
 * The second screen as an input surface: droidtop's own keyboard above a
 * trackpad (docs/SPEC.md sections 4, 6 and 6c).
 *
 * A plain `LinearLayout` rather than Compose, because both children are
 * real Views that already exist and must not be reimplemented:
 * `LatinKeyboardView` is the forked Hacker's Keyboard's own key grid with
 * its own layouts and themes, and `TrackpadView` is `:input-seat`'s.
 *
 * Where the input goes depends on the mode, and the two answers are not
 * the same thing dressed differently:
 *
 * - **Desktop**: the pointer and the keys go into the primary container
 *   through the one `InputSeat`, exactly as the main desktop surface's own
 *   touch and keyboard do. A real trackpad and a real keyboard.
 * - **Gaming / Standard**: there is no pointer to move, so the trackpad
 *   drives the shell's focus navigation, and the keyboard types into
 *   whatever Android editor has focus through droidtop's own IME. Both
 *   limits are the platform's, and both are stated on the surface rather
 *   than failing quietly.
 */
class SecondScreenInputView(
    context: Context,
    private val mode: SecondaryDisplayContent.Mode,
) : LinearLayout(context) {

    private val trackpad = TrackpadView(context)
    private val status = TextView(context)
    private val imePicker = Button(context)

    // The header (slice C12): where the keys go, Pin for that screen, and Tabs, which brings the companion's bar back
    // over Input; then the chord keys and the controllers with their batteries.
    private val target = TextView(context)
    private val pin = Button(context)
    private val tabs = Button(context)
    private val controllers = TextView(context)

    /** The screens Input can type to, read off the main thread when the view attaches or regains focus. */
    @Volatile
    private var screens: List<InputTarget.Screen> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(ChromeColors.DarkBackground.toArgb())

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        target.setTextColor(ChromeColors.DarkOnSurfaceVariant.toArgb())
        target.textSize = 14f
        header.addView(target, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        pin.setOnClickListener { togglePin() }
        header.addView(pin, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        tabs.text = "Tabs"
        tabs.contentDescription = "Show tabs"
        tabs.setOnClickListener { CompanionInputHandle.showTabs.value = !CompanionInputHandle.showTabs.value }
        header.addView(tabs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // The chord keys, played into the same destination as the keyboard ([CompanionChords]).
        val chordSink = requestAwareSink(keyboardSink())
        val player = org.pocketworkstation.pckeyboard.MacroPlayer({ code, down -> chordSink.key(code, down) }, { chordSink.text(it) })
        val chords = LinearLayout(context).apply { orientation = HORIZONTAL }
        CompanionChords.ALL.forEach { macro ->
            chords.addView(
                Button(context).apply {
                    text = macro.name
                    isAllCaps = false
                    setOnClickListener { player.play(macro) }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        addView(
            android.widget.HorizontalScrollView(context).apply { addView(chords) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        // droidtop's one keyboard view (KeyboardPanel); only the destination is this surface's own. It suppresses
        // the input method's view while it is on screen, so the user never gets two keyboards.
        addView(
            KeyboardPanel(context, requestAwareSink(keyboardSink()), suppressImeView = true),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        status.setTextColor(ChromeColors.DarkOnSurfaceVariant.toArgb())
        status.textSize = 13f
        status.gravity = Gravity.CENTER
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        imePicker.text = "Use droidtop keyboard"
        imePicker.gravity = Gravity.CENTER
        imePicker.setOnClickListener {
            (context.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showInputMethodPicker()
        }
        addView(imePicker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        controllers.setTextColor(ChromeColors.DarkOnSurfaceVariant.toArgb())
        controllers.textSize = 13f
        controllers.gravity = Gravity.CENTER
        addView(controllers, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(trackpad, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** Re-reads the screens and the controllers off the main thread, then names the target. */
    private fun refreshFacts() {
        val app = context.applicationContext
        Thread {
            val outputs = runCatching { dev.droidtop.runtime.DisplayOutputRepository(app).currentOutputsSnapshot() }.getOrDefault(emptyList())
            val names = runCatching { dev.droidtop.runtime.ScreenNaming.names(app, outputs) }.getOrDefault(emptyMap())
            screens = outputs.map { InputTarget.Screen(it.androidDisplayId, it.uniqueId, names[it.androidDisplayId] ?: it.name) }
            val pads = runCatching { Controllers.read(app) }.getOrDefault(emptyList())
            post {
                controllers.text = Controllers.line(pads)?.let { "Controllers: $it" }.orEmpty()
                controllers.visibility = if (pads.isEmpty()) View.GONE else View.VISIBLE
                syncTarget()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun targetId(): Int? = InputTarget.displayId(
        own = display?.displayId,
        shell = ForegroundShell.current()?.window?.decorView?.display?.displayId,
        screens = screens,
        pinned = dev.droidtop.library.settings.CompanionPrefs.settings.value.inputPin,
    )

    private fun syncTarget() {
        val desktop = mode == SecondaryDisplayContent.Mode.DESKTOP
        val pinned = dev.droidtop.library.settings.CompanionPrefs.settings.value.inputPin
        val id = targetId()
        val screen = screens.firstOrNull { it.id == id }
        target.text = InputTarget.label(desktop, screen, pinned != null && screen?.uniqueId == pinned)
        pin.visibility = if (desktop || screen?.uniqueId == null) View.GONE else View.VISIBLE
        pin.text = if (pinned != null) "Unpin" else "Pin"
        pin.contentDescription = if (pinned != null) "Stop typing only to ${screen?.name}" else "Always type to ${screen?.name}"
    }

    /** Pins the screen Input types to now, or lets it follow the shell again. */
    private fun togglePin() {
        val app = context.applicationContext
        val pinned = dev.droidtop.library.settings.CompanionPrefs.settings.value.inputPin
        val next = if (pinned != null) null else screens.firstOrNull { it.id == targetId() }?.uniqueId
        Thread {
            dev.droidtop.library.settings.CompanionPrefs.setInputPin(app, next)
            post { syncTarget() }
        }.apply { isDaemon = true }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Built on attach rather than cached, so a desktop session that
        // connected after this view was created is picked up, and one that
        // went away leaves no sink pointing at a dead bridge.
        val settings = dev.droidtop.library.settings.CompanionPrefs.settings.value.trackpad
        trackpad.engine = TrackpadGestureEngine(trackpadOutput(), trackpadConfig(settings))
        status.text = statusText()
        refreshFacts()
        syncImePicker()
        Thread {
            elevated = runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
        }.apply { isDaemon = true }.start()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncImePicker()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        syncImePicker()
        status.text = statusText()
        if (hasWindowFocus) refreshFacts()
    }

    private fun syncImePicker() {
        imePicker.visibility = if (
            mode != SecondaryDisplayContent.Mode.DESKTOP && !SecondScreenKeyboard.imeRunning &&
                !AccessibilityKeyboard.connected && KeyboardTargets.companion.value == null
        ) View.VISIBLE else View.GONE
    }

    override fun onDetachedFromWindow() {
        trackpad.engine = null
        super.onDetachedFromWindow()
    }

    private fun trackpadOutput(): TrackpadOutput {
        val session = DesktopSessionService.state.value as? DesktopSessionState.Connected
        if (mode == SecondaryDisplayContent.Mode.DESKTOP && session != null) {
            val settings = dev.droidtop.library.settings.CompanionPrefs.settings.value.trackpad
            return SeatTrackpadSink(
                seat = InputSeats.of(session.hostBridge),
                // Gain from the DESTINATION output's width, so the same
                // hand movement crosses whatever the container renders at
                // -- not from this panel's own size.
                gainPxPerMm = session.primaryOutput.widthPx / TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH,
                userSpeed = trackpadSpeed(settings),
                naturalScroll = settings.naturalScroll,
            )
        }
        return FocusNavTrackpadSink(emit = ForegroundShell::send)
    }

    private fun keyboardSink(): KeyboardSink =
        if (mode == SecondaryDisplayContent.Mode.DESKTOP) {
            // Routed through DesktopInputRouter rather than at the seat
            // directly: it owns the Android-keycode-to-evdev step and the
            // held-key bookkeeping, and a second path beside it is exactly
            // the duplication :input-seat exists to prevent.
            val router = DesktopInputRouter()
            object : KeyboardSink {
                // No text channel: a compositor takes keys, not strings.
                override val takesText: Boolean = false

                override fun key(androidKeyCode: Int, down: Boolean) {
                    router.seat = (DesktopSessionService.state.value as? DesktopSessionState.Connected)
                        ?.let { InputSeats.of(it.hostBridge) }
                    val now = SystemClock.uptimeMillis()
                    val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                    router.onKeyEvent(KeyEvent(now, now, action, androidKeyCode, 0))
                }
            }
        } else {
            RoutedKeyboardSink(
                displayId = { targetId() },
                elevated = { elevated },
                onNoRoute = { status.text = statusText() },
            )
        }

    /**
     * The controller's keys: into the field that asked for the companion's keyboard ([KeyboardTargets.companion],
     * SPEC 4c) while one does, else this mode's own destination.
     */
    private fun requestAwareSink(own: KeyboardSink): KeyboardSink = object : KeyboardSink {
        override val takesText: Boolean get() = own.takesText

        override fun key(androidKeyCode: Int, down: Boolean) =
            (KeyboardTargets.companion.value?.sink ?: own).key(androidKeyCode, down)

        override fun text(chars: CharSequence) = (KeyboardTargets.companion.value?.sink ?: own).text(chars)
    }

    /** Whether the elevated helper can type (`input -d`), read off the main thread when the view attaches. */
    @Volatile
    private var elevated = false

    /**
     * The honest one-line description of what this surface can currently
     * do. Every state below is real and none of them is droidtop's to fix
     * silently: an IME that is not the selected one is never bound, and a
     * container that is not connected has no pointer.
     */
    private fun statusText(): String = when {
        mode == SecondaryDisplayContent.Mode.DESKTOP ->
            if (DesktopSessionService.state.value is DesktopSessionState.Connected) {
                "Trackpad and keyboard"
            } else {
                "No desktop session"
            }

        KeyboardTargets.companion.value != null ->
            "Typing"

        !SecondScreenKeyboard.imeRunning && !AccessibilityKeyboard.connected && !elevated ->
            "Keyboard off"

        !SecondScreenKeyboard.androidTargetAvailable() && !AccessibilityKeyboard.hasEditor() && !elevated ->
            "No text field"

        else -> "Touchpad"
    }
}

/**
 * The input surface as a composable, for `:display`'s registry -- which
 * hands a mode a `@Composable`, not a View. The View is the real thing;
 * this is only the adapter.
 */
@Composable
fun SecondScreenInputSurface(mode: SecondaryDisplayContent.Mode) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> SecondScreenInputView(context, mode) as View },
    )
}
