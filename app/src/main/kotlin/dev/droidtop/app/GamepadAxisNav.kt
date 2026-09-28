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
 *
 * Per-KEY dedupe (owner's own safeguard request, tracker#43 triage):
 * a gamepad that reports its D-pad through BOTH a real `KeyEvent` stream
 * AND the hat axis would otherwise get this translated on top of the
 * real one, doubling every press. [noteRealDpadKeyEvent] is called from
 * `MainActivity.dispatchKeyEvent` for every real (non-synthetic) DPAD key
 * seen; once a (device, keyCode) pair has sent ONE, that exact key's own
 * hat/stick motion is never translated again for the rest of the process
 * -- a real key beats a translated one permanently, per key, rather than
 * a per-event race that could still double an occasional press.
 *
 * Real bug this fixes (owner, console, Droidtop/tracker#1/#43,
 * 2026-09-28): "Up still isn't processed" / "Left and up ... are just
 * inert." This dedupe used to be keyed on the DEVICE alone: the first
 * real DOWN or RIGHT `KeyEvent` seen from the pad permanently disabled
 * hat/stick translation for that ENTIRE device, UP and LEFT included --
 * and the Retroid Pocket 5's own D-pad, per the owner's report, only
 * ever sends DOWN/RIGHT as real `KeyEvent`s; UP/LEFT arrive solely as hat
 * axis motion. One real DOWN press was enough to permanently blind this
 * class to the hat's own UP/LEFT motion for the rest of the process --
 * exactly "inert, on press or release," and exactly the two directions
 * the device does not send as real keys. Keying the dedupe per exact
 * keyCode instead means a real DOWN only ever suppresses translated
 * DOWN; UP keeps translating from the hat for as long as the device
 * never sends a real UP itself.
 */
/**
 * The pure direction/edge/dedupe decision [GamepadAxisNav.updateAxis]
 * delegates to, pulled out exactly like [dev.droidtop.shell.gamepad.gridPadTarget]
 * so [GamepadAxisNavTest] can exercise the hysteresis and the per-KEY
 * dedupe directly, with no real `MotionEvent`/`InputDevice` needed (those
 * throw `Stub!` outside an instrumented/Robolectric runtime -- see that
 * test's own doc comment). [suppressed] answers per exact keyCode, never
 * per axis or per device: this is the whole of the fix for
 * Droidtop/tracker#1/#43 (own doc comment above) -- a real DOWN must
 * suppress only a translated DOWN, never a translated UP the device only
 * ever sends through the hat.
 */
internal fun axisTargetKey(
    value: Float,
    currentKey: Int?,
    negativeKey: Int,
    positiveKey: Int,
    suppressed: (keyCode: Int) -> Boolean,
): Int? {
    // Hysteresis: a direction ENTERS at the larger threshold and only
    // EXITS once the axis has actually returned near center, so a stick
    // sitting right at the edge of the threshold cannot chatter
    // press/release every other sample.
    val rawKey = when {
        value <= -AXIS_ENTER_THRESHOLD -> negativeKey
        value >= AXIS_ENTER_THRESHOLD -> positiveKey
        currentKey != null && kotlin.math.abs(value) > AXIS_EXIT_THRESHOLD -> currentKey
        else -> null
    }
    return rawKey?.takeUnless(suppressed)
}

internal const val AXIS_ENTER_THRESHOLD = 0.5f
internal const val AXIS_EXIT_THRESHOLD = 0.3f

internal class GamepadAxisNav(
    private val handler: Handler,
    private val dispatch: (down: Boolean, keyCode: Int) -> Unit,
) {
    private var verticalKey: Int? = null
    private var horizontalKey: Int? = null
    /** (deviceId, keyCode) pairs seen as a real KeyEvent -- see this class's own "Per-KEY dedupe" doc comment. */
    private val realDpadKeys = HashSet<Pair<Int, Int>>()

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

    /**
     * Call from `MainActivity.dispatchKeyEvent` for every real DPAD
     * `KeyEvent` (a positive `deviceId`; this class's own synthetic
     * events always carry deviceId 0, see [gamepadAxisNavFor]'s own
     * `KeyEvent` construction, so they never register here by accident).
     */
    fun noteRealDpadKeyEvent(deviceId: Int, keyCode: Int) {
        if (deviceId > 0) realDpadKeys.add(deviceId to keyCode)
    }

    /** Call from `Activity.dispatchGenericMotionEvent`; never consumes the event. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val device = event.device ?: return false
        val sources = device.sources
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
            deviceId = device.id,
            value = if (hatY != 0f) hatY else stickY,
            negativeKey = KeyEvent.KEYCODE_DPAD_UP,
            positiveKey = KeyEvent.KEYCODE_DPAD_DOWN,
            vertical = true,
        )
        updateAxis(
            deviceId = device.id,
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

    private fun updateAxis(deviceId: Int, value: Float, negativeKey: Int, positiveKey: Int, vertical: Boolean) {
        val currentKey = if (vertical) verticalKey else horizontalKey
        val newKey = axisTargetKey(value, currentKey, negativeKey, positiveKey) { keyCode ->
            (deviceId to keyCode) in realDpadKeys
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

    /**
     * One physical press worth of synthetic input: navigation now acts
     * only on the DOWN half (Droidtop/tracker#1's `handleGamepadKeyDown`,
     * own doc comment) and treats UP as a plain, un-acted-on release, so
     * sending a full down/up pair per repeat tick is the correct
     * equivalent of a real held key's repeated DOWNs -- each pair moves
     * the selection exactly once, on its own DOWN.
     */
    private fun press(keyCode: Int) {
        dispatch(true, keyCode)
        dispatch(false, keyCode)
    }

    private companion object {
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
 * `ForegroundShell.send`. deviceId is left at its default (0) deliberately
 * -- see [GamepadAxisNav.noteRealDpadKeyEvent]'s own doc comment, which
 * relies on synthetic events never colliding with a real device's id.
 */
internal fun gamepadAxisNavFor(activity: android.app.Activity, handler: Handler): GamepadAxisNav =
    GamepadAxisNav(handler) { down, keyCode ->
        val now = SystemClock.uptimeMillis()
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        activity.dispatchKeyEvent(KeyEvent(now, now, action, keyCode, 0))
    }
