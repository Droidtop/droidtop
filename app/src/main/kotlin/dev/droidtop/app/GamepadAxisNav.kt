package dev.droidtop.app

import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Translates a gamepad's ANALOG D-pad -- the hat switch (`AXIS_HAT_X`/
 * `AXIS_HAT_Y`) or the left stick (`AXIS_X`/`AXIS_Y`) reported through
 * `MotionEvent`s rather than real `KeyEvent`s -- into the same synthetic
 * `KEYCODE_DPAD_*` down/up pair a physical D-pad BUTTON sends. Real bug
 * this fixes (owner, on the console, Retroid Pocket 5, 2026-09-28,
 * Droidtop/tracker#1): "joystick Up AND D-pad Up both fail to scroll the
 * game list." droidtop's whole shell reads D-pad navigation exclusively
 * through Compose's `Modifier.onKeyEvent` (see `GamepadKeyMap`), which
 * only ever sees real `KeyEvent`s -- a control whose report comes through
 * the joystick axes instead never reached that code at all, no matter how
 * many fixes landed downstream of it.
 *
 * `Activity.dispatchKeyEvent` is the exact route [ForegroundShell] already
 * uses for the second screen's own synthetic navigation keys: an ordinary
 * call into this window that reaches the identical Compose focus/
 * `onKeyEvent` machinery a real D-pad reaches, no `INJECT_EVENTS`
 * permission needed (see its own doc comment).
 *
 * Edge-triggered, plus a [Handler]-driven hold-repeat that keeps firing on
 * its own timer rather than only when a fresh `MotionEvent` sample happens
 * to arrive -- a stick held rock-steady at full deflection can stop
 * producing new samples entirely, which would silently stop a
 * motion-driven repeat that only reacted to incoming events.
 */
internal class GamepadAxisNav(
    private val handler: Handler,
    private val dispatch: (down: Boolean, keyCode: Int) -> Unit,
) {
    private var verticalKey: Int? = null
    private var horizontalKey: Int? = null

    private val verticalRepeat = object : Runnable {
        override fun run() {
            val key = verticalKey ?: return
            press(key)
            handler.postDelayed(this, REPEAT_MS)
        }
    }
    private val horizontalRepeat = object : Runnable {
        override fun run() {
            val key = horizontalKey ?: return
            press(key)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    /** Call from `Activity.dispatchGenericMotionEvent`; never consumes the event. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val sources = event.device?.sources ?: 0
        val isPad = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isPad) return false
        // The hat switch (a real D-pad reported as an axis) wins over the
        // left stick when a device reports both non-zero at once --
        // that never happens in practice (a person uses one or the
        // other), but the hat is the more literal "D-pad" of the two.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        updateAxis(
            value = if (hatY != 0f) hatY else stickY,
            negativeKey = KeyEvent.KEYCODE_DPAD_UP,
            positiveKey = KeyEvent.KEYCODE_DPAD_DOWN,
            vertical = true,
        )
        updateAxis(
            value = if (hatX != 0f) hatX else stickX,
            negativeKey = KeyEvent.KEYCODE_DPAD_LEFT,
            positiveKey = KeyEvent.KEYCODE_DPAD_RIGHT,
            vertical = false,
        )
        return false
    }

    /** Stops any repeat in flight -- call from `onPause`, before a possibly-different window takes over. */
    fun cancel() {
        handler.removeCallbacks(verticalRepeat)
        handler.removeCallbacks(horizontalRepeat)
        verticalKey = null
        horizontalKey = null
    }

    private fun updateAxis(value: Float, negativeKey: Int, positiveKey: Int, vertical: Boolean) {
        val currentKey = if (vertical) verticalKey else horizontalKey
        // Hysteresis: a direction ENTERS at the larger threshold and only
        // EXITS once the axis has actually returned near center, so a
        // stick sitting right at the edge of the threshold cannot chatter
        // press/release every other sample.
        val newKey = when {
            value <= -ENTER_THRESHOLD -> negativeKey
            value >= ENTER_THRESHOLD -> positiveKey
            currentKey != null && kotlin.math.abs(value) > EXIT_THRESHOLD -> currentKey
            else -> null
        }
        if (newKey == currentKey) return
        if (vertical) verticalKey = newKey else horizontalKey = newKey
        val repeatRunnable = if (vertical) verticalRepeat else horizontalRepeat
        handler.removeCallbacks(repeatRunnable)
        if (newKey != null) {
            press(newKey)
            handler.postDelayed(repeatRunnable, INITIAL_REPEAT_DELAY_MS)
        }
    }

    private fun press(keyCode: Int) {
        dispatch(true, keyCode)
        dispatch(false, keyCode)
    }

    private companion object {
        const val ENTER_THRESHOLD = 0.5f
        const val EXIT_THRESHOLD = 0.3f
        // Matches the shell's own long-press timing elsewhere
        // (GamepadShell's Select-hold uses the system long-press
        // timeout); a plain, conventional key-repeat feel otherwise --
        // real Android key repeat defaults are in the same range.
        const val INITIAL_REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 120L
    }
}

/**
 * A ready-to-dispatch [GamepadAxisNav] bound to [activity]'s own
 * `dispatchKeyEvent`, the same "this window, ordinary dispatch" route
 * [ForegroundShell] documents. `SystemClock.uptimeMillis()` is shared
 * between the down and up event of one press, same as
 * `ForegroundShell.send`.
 */
internal fun gamepadAxisNavFor(activity: android.app.Activity, handler: Handler): GamepadAxisNav =
    GamepadAxisNav(handler) { down, keyCode ->
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        activity.dispatchKeyEvent(KeyEvent(now, now, action, keyCode, 0))
    }
