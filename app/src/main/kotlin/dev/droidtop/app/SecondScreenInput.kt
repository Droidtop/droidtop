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
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.shell.gamepad.ChromeColors
import org.pocketworkstation.pckeyboard.ImeConnectionSink
import org.pocketworkstation.pckeyboard.KeyboardPanel
import org.pocketworkstation.pckeyboard.KeyboardSink
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/**
 * What the second screen is for, per mode.
 *
 * Two real roles, with different defaults per mode because the modes want
 * different things (docs/SPEC.md section 4): Desktop mode's lower screen
 * is an input surface by design, while in Gaming mode the shell itself
 * moves to the addon and the remaining screen is the ambient widgets
 * panel. Both are switchable, because a user with a physical keyboard
 * wants the companion in Desktop mode, and a user browsing a large library
 * one-handed wants the trackpad in Gaming mode.
 */
object SecondScreenInputPrefs {

    enum class Role { COMPANION, INPUT }

    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PREFIX = "pref_second_screen_role_"

    fun role(context: Context, mode: SecondaryDisplayContent.Mode): Role {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + mode.name, null)
        return stored?.let { runCatching { Role.valueOf(it) }.getOrNull() } ?: defaultFor(mode)
    }

    fun defaultFor(mode: SecondaryDisplayContent.Mode): Role = when (mode) {
        SecondaryDisplayContent.Mode.DESKTOP -> Role.INPUT
        else -> Role.COMPANION
    }
}

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

    init {
        orientation = VERTICAL
        setBackgroundColor(ChromeColors.DarkBackground.toArgb())

        // droidtop's one keyboard view (KeyboardPanel); only the destination is this surface's own. It suppresses
        // the input method's view while it is on screen, so the user never gets two keyboards.
        addView(
            KeyboardPanel(context, keyboardSink(), suppressImeView = true),
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

        addView(trackpad, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Built on attach rather than cached, so a desktop session that
        // connected after this view was created is picked up, and one that
        // went away leaves no sink pointing at a dead bridge.
        trackpad.engine = TrackpadGestureEngine(trackpadOutput())
        status.text = statusText()
        syncImePicker()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncImePicker()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        syncImePicker()
        status.text = statusText()
    }

    private fun syncImePicker() {
        imePicker.visibility = if (
            mode != SecondaryDisplayContent.Mode.DESKTOP && !SecondScreenKeyboard.imeRunning
        ) View.VISIBLE else View.GONE
    }

    override fun onDetachedFromWindow() {
        trackpad.engine = null
        super.onDetachedFromWindow()
    }

    private fun trackpadOutput(): TrackpadOutput {
        val session = DesktopSessionService.state.value as? DesktopSessionState.Connected
        if (mode == SecondaryDisplayContent.Mode.DESKTOP && session != null) {
            return SeatTrackpadSink(
                seat = InputSeats.of(session.hostBridge),
                // Gain from the DESTINATION output's width, so the same
                // hand movement crosses whatever the container renders at
                // -- not from this panel's own size.
                gainPxPerMm = session.primaryOutput.widthPx / TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH,
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
            ImeConnectionSink(onNoTarget = { status.text = statusText() })
        }

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

        !SecondScreenKeyboard.imeRunning ->
            "Keyboard off"

        !SecondScreenKeyboard.androidTargetAvailable() ->
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
