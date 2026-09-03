package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.pocketworkstation.pckeyboard.AndroidCharKeyResolver

import org.pocketworkstation.pckeyboard.LatinKeyboardView
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard
import org.pocketworkstation.pckeyboard.SecondScreenKeyboardListener
import java.lang.ref.WeakReference

/**
 * What the second screen is for, per mode.
 *
 * Two real roles, with different defaults per mode because the modes want
 * different things (docs/SPEC.md section 4): Desktop mode's lower screen
 * is an input surface by design, while in Handheld mode the shell itself
 * moves to the addon and the remaining screen is the ambient widgets
 * panel. Both are switchable, because a user with a physical keyboard
 * wants the companion in Desktop mode, and a user browsing a large library
 * one-handed wants the trackpad in Handheld mode.
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

    fun setRole(context: Context, mode: SecondaryDisplayContent.Mode, role: Role) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PREFIX + mode.name, role.name)
            .apply()
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
 * `INJECT_EVENTS`, a signature permission, which is why the Handheld
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
 * - **Handheld / Standard**: there is no pointer to move, so the trackpad
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
    private var keyboardView: LatinKeyboardView? = null
    private var keyboardListener: SecondScreenKeyboardListener? = null
    private var functionLayer = false

    /**
     * Modifier state as the far side will see it.
     *
     * `InputConnection.sendKeyEvent` does not have the framework's own
     * meta tracking behind it, so a Shift press followed by a letter is
     * only reliably a capital when the letter's event carries
     * META_SHIFT_ON as well. Tracked here rather than inside the listener
     * because it is a property of THIS delivery route -- the container
     * route needs nothing of the sort, since the compositor tracks
     * modifiers from the key stream itself.
     */
    private var metaState = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(android.graphics.Color.BLACK)

        val listener = buildKeyboardListener()
        keyboardListener = listener
        val view = runCatching { SecondScreenKeyboard.createView(context, listener) }.getOrNull()
        keyboardView = view
        if (view != null) {
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        status.setTextColor(android.graphics.Color.parseColor("#FF6E7B8B"))
        status.textSize = 13f
        status.gravity = Gravity.CENTER
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(trackpad, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Tells the IME to stop putting a keyboard over the primary
        // display; see LatinIME.onEvaluateInputViewShown.
        SecondScreenKeyboard.setAttached(true)
        // Built on attach rather than cached, so a desktop session that
        // connected after this view was created is picked up, and one that
        // went away leaves no sink pointing at a dead bridge.
        trackpad.engine = TrackpadGestureEngine(trackpadOutput())
        status.text = statusText()
    }

    override fun onDetachedFromWindow() {
        SecondScreenKeyboard.setAttached(false)
        keyboardListener?.releaseEverything()
        metaState = 0
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

    private fun buildKeyboardListener(): SecondScreenKeyboardListener =
        if (mode == SecondaryDisplayContent.Mode.DESKTOP) {
            // Routed through DesktopInputRouter rather than at the seat
            // directly: it owns the Android-keycode-to-evdev step and the
            // held-key bookkeeping, and a second path beside it is exactly
            // the duplication :input-seat exists to prevent.
            val router = DesktopInputRouter()
            SecondScreenKeyboardListener(
                send = { keyCode, down ->
                    router.seat = (DesktopSessionService.state.value as? DesktopSessionState.Connected)
                        ?.let { InputSeats.of(it.hostBridge) }
                    val now = SystemClock.uptimeMillis()
                    val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                    router.onKeyEvent(KeyEvent(now, now, action, keyCode, 0))
                },
                resolver = AndroidCharKeyResolver(),
                // No text channel: a compositor takes keys, not strings.
                commit = null,
                onLayoutToggle = ::toggleLayout,
            )
        } else {
            SecondScreenKeyboardListener(
                send = { keyCode, down ->
                    metaState = updatedMetaState(metaState, keyCode, down)
                    val connection = SecondScreenKeyboard.androidTarget
                    if (connection == null) {
                        status.text = statusText()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                        connection.sendKeyEvent(
                            KeyEvent(now, now, action, keyCode, 0, metaState),
                        )
                    }
                },
                resolver = AndroidCharKeyResolver(),
                commit = { text -> SecondScreenKeyboard.androidTarget?.commitText(text, 1) },
                onLayoutToggle = ::toggleLayout,
            )
        }

    private fun toggleLayout() {
        val view = keyboardView ?: return
        functionLayer = !functionLayer
        SecondScreenKeyboard.applyLayout(view, context, functionLayer)
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
                "Trackpad and keyboard — primary container"
            } else {
                "No desktop session — start one to use this surface"
            }

        !SecondScreenKeyboard.imeRunning ->
            "Swipe to navigate. Typing needs droidtop's keyboard picked in Android's keyboard switcher."

        !SecondScreenKeyboard.androidTargetAvailable() ->
            "Swipe to navigate. Typing goes to a text field once one is focused on the other screen."

        else -> "Swipe to navigate, tap to select, two fingers to go back."
    }

    private companion object {
        /**
         * Which meta bit a modifier keycode contributes. Not a mapping
         * table so much as the pairing Android itself defines between
         * `KEYCODE_*_LEFT/RIGHT` and `META_*_ON`.
         */
        fun updatedMetaState(current: Int, keyCode: Int, down: Boolean): Int {
            val bit = when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
                else -> return current
            }
            return if (down) current or bit else current and bit.inv()
        }
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
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { context -> SecondScreenInputView(context, mode) as View },
    )
}
