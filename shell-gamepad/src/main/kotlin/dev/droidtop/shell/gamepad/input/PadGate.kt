package dev.droidtop.shell.gamepad.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Where the scheduling [PadGateCore] needs comes from: a Handler on the
 * device, a list of pending tasks in the unit tests.
 */
internal interface PadScheduler {
    /** The clock key events are timed on (uptime milliseconds on a device). */
    fun now(): Long

    /** Runs [task] after [delayMs]; the returned function cancels it. */
    fun after(delayMs: Long, task: () -> Unit): () -> Unit
}

/** A key event the gate makes up: the stick held as a D-pad key, a trigger as its button, a Select tap or hold. */
internal data class PadSyntheticKey(
    val keyCode: Int,
    val down: Boolean,
    val repeatCount: Int,
    val downTime: Long,
    val eventTime: Long,
    val deviceId: Int,
)

/**
 * The front of the input pipeline (docs/SPEC.md 6e): what every window of
 * the shell does with a raw press before any screen sees it. Pure, so the
 * unit tests drive it with numbers and a fake clock.
 *
 * - **Bounce.** A fresh press of a key less than [DEBOUNCE_MS] after that
 *   key's own release is a worn or noisy switch, not a person: the press,
 *   its repeats and its release are dropped.
 * - **The stick and the hat are a D-pad.** Crossing [ENTER_THRESHOLD]
 *   presses the direction, falling back under [EXIT_THRESHOLD] releases it
 *   (a hysteresis band, so a stick resting near the edge cannot chatter);
 *   inside that is the deadzone. A held stick is a HELD key: one DOWN,
 *   DOWNs with a rising repeat count at the platform's own key-repeat
 *   timing, one UP -- exactly what a D-pad button sends, so every screen's
 *   cadence and its "a held press stops at the end" rule treat both alike.
 *   The hat wins over the stick when both move. A device that also sends a
 *   real key for a direction is not translated for that direction again
 *   (per device AND per key: the Retroid Pocket 5 sends Down and Right as
 *   keys but Up and Left only on the hat, Droidtop/tracker#1/#43).
 * - **Analog triggers are buttons.** Some pads report L2/R2 only as an
 *   axis; past the same threshold they press `BUTTON_L2`/`BUTTON_R2`, unless
 *   the device sends the key itself.
 * - **Holding Select is R2.** The shell's fallback route to the Quick Menu
 *   for a pad whose triggers send nothing at all. Select is held back until
 *   it is either released (a tap: one Select press is delivered) or held
 *   for the long-press timeout (one R2 press is delivered, and the release
 *   is dropped). No screen ever sees half of a hold.
 */
internal class PadGateCore(
    private val scheduler: PadScheduler,
    private val longPressMs: Long,
    private val repeatTimeoutMs: Long,
    private val repeatDelayMs: Long,
    private val emit: (PadSyntheticKey) -> Unit,
) {
    private val lastUp = HashMap<Int, Long>()
    private val bounced = HashSet<Int>()
    private val realKeys = HashSet<Long>()

    private class Axis(val negativeKey: Int, val positiveKey: Int) {
        var key: Int? = null
        var downTime = 0L
        var repeats = 0
        var deviceId = 0
        var cancelRepeat: (() -> Unit)? = null
    }

    private val vertical = Axis(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private val horizontal = Axis(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    private val leftTrigger = Axis(0, KeyEvent.KEYCODE_BUTTON_L2)
    private val rightTrigger = Axis(0, KeyEvent.KEYCODE_BUTTON_R2)

    private var selectDown = false
    private var selectHoldFired = false
    private var cancelSelectHold: (() -> Unit)? = null

    /**
     * A key event from the device. Returns true to deliver it unchanged,
     * false to drop it ([emit] may have delivered something in its place).
     */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, deviceId: Int, fallback: Boolean, now: Long): Boolean {
        if (down && repeatCount == 0) {
            val released = lastUp[keyCode]
            if (released != null && now - released < DEBOUNCE_MS) {
                bounced += keyCode
                return false
            }
            bounced -= keyCode
        }
        if (keyCode in bounced) {
            if (!down) bounced -= keyCode
            return false
        }
        if (!down) lastUp[keyCode] = now
        if (deviceId > 0 && !fallback && keyCode in TRANSLATED_KEYS) realKeys += pair(deviceId, keyCode)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) return select(down, repeatCount, now)
        return true
    }

    /**
     * One joystick sample from [deviceId]. The caller consumes the motion
     * event either way: the platform would otherwise make its own D-pad keys
     * out of the same motion, at its own thresholds.
     */
    fun onAxes(
        deviceId: Int,
        hatX: Float,
        hatY: Float,
        stickX: Float,
        stickY: Float,
        leftTriggerValue: Float,
        rightTriggerValue: Float,
        now: Long,
    ) {
        update(vertical, deviceId, if (hatY != 0f) hatY else stickY, now, repeats = true)
        update(horizontal, deviceId, if (hatX != 0f) hatX else stickX, now, repeats = true)
        update(leftTrigger, deviceId, leftTriggerValue, now, repeats = false)
        update(rightTrigger, deviceId, rightTriggerValue, now, repeats = false)
    }

    /** Lets go of everything held: the window is going away or losing input. */
    fun cancel() {
        for (axis in listOf(vertical, horizontal, leftTrigger, rightTrigger)) {
            axis.cancelRepeat?.invoke()
            axis.cancelRepeat = null
            axis.key = null
        }
        cancelSelectHold?.invoke()
        cancelSelectHold = null
        selectDown = false
    }

    private fun update(axis: Axis, deviceId: Int, value: Float, now: Long, repeats: Boolean) {
        val target = axisTargetKey(value, axis.key, axis.negativeKey, axis.positiveKey) { key ->
            key == 0 || pair(deviceId, key) in realKeys
        }
        if (target == axis.key) return
        axis.key?.let { held ->
            axis.cancelRepeat?.invoke()
            axis.cancelRepeat = null
            emit(PadSyntheticKey(held, down = false, repeatCount = 0, downTime = axis.downTime, eventTime = now, deviceId = axis.deviceId))
        }
        axis.key = target
        if (target == null) return
        axis.downTime = now
        axis.repeats = 0
        axis.deviceId = deviceId
        emit(PadSyntheticKey(target, down = true, repeatCount = 0, downTime = now, eventTime = now, deviceId = deviceId))
        if (repeats) scheduleRepeat(axis, repeatTimeoutMs)
    }

    private fun scheduleRepeat(axis: Axis, delayMs: Long) {
        axis.cancelRepeat = scheduler.after(delayMs) {
            val key = axis.key ?: return@after
            axis.repeats++
            emit(
                PadSyntheticKey(
                    key,
                    down = true,
                    repeatCount = axis.repeats,
                    downTime = axis.downTime,
                    eventTime = scheduler.now(),
                    deviceId = axis.deviceId,
                ),
            )
            scheduleRepeat(axis, repeatDelayMs)
        }
    }

    private fun select(down: Boolean, repeatCount: Int, now: Long): Boolean {
        if (down && repeatCount == 0) {
            selectDown = true
            selectHoldFired = false
            cancelSelectHold?.invoke()
            cancelSelectHold = scheduler.after(longPressMs) {
                cancelSelectHold = null
                selectHoldFired = true
                press(KeyEvent.KEYCODE_BUTTON_R2, scheduler.now())
            }
            return false
        }
        // A release (or a repeat) of a press that began before this window
        // was listening is not ours to reinterpret.
        if (!selectDown) return true
        if (down) return false
        selectDown = false
        cancelSelectHold?.invoke()
        cancelSelectHold = null
        if (!selectHoldFired) press(KeyEvent.KEYCODE_BUTTON_SELECT, now)
        return false
    }

    private fun press(keyCode: Int, at: Long) {
        emit(PadSyntheticKey(keyCode, down = true, repeatCount = 0, downTime = at, eventTime = at, deviceId = 0))
        emit(PadSyntheticKey(keyCode, down = false, repeatCount = 0, downTime = at, eventTime = at, deviceId = 0))
    }

    private fun pair(deviceId: Int, keyCode: Int): Long = (deviceId.toLong() shl 32) or (keyCode.toLong() and 0xffffffffL)

    companion object {
        /** A press this soon after the same key's release is the switch bouncing, not a person. */
        const val DEBOUNCE_MS = 25L

        /** Stick, hat and trigger travel that presses (enter) and releases (exit) a direction. */
        const val ENTER_THRESHOLD = 0.5f
        const val EXIT_THRESHOLD = 0.3f

        private val TRANSLATED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
        )
    }
}

/**
 * The direction an axis value presses, given the one it presses now
 * ([currentKey]): enter past [PadGateCore.ENTER_THRESHOLD], stay until it
 * falls under [PadGateCore.EXIT_THRESHOLD]. [suppressed] answers per exact
 * key, never per device (see [PadGateCore]).
 */
internal fun axisTargetKey(
    value: Float,
    currentKey: Int?,
    negativeKey: Int,
    positiveKey: Int,
    suppressed: (keyCode: Int) -> Boolean,
): Int? {
    val rawKey = when {
        value <= -PadGateCore.ENTER_THRESHOLD -> negativeKey
        value >= PadGateCore.ENTER_THRESHOLD -> positiveKey
        currentKey != null && kotlin.math.abs(value) > PadGateCore.EXIT_THRESHOLD -> currentKey
        else -> null
    }
    return rawKey?.takeUnless(suppressed)
}

/**
 * [PadGateCore] on a real window: every key and joystick event of that
 * window passes through [dispatchKey] and [dispatchMotion] before the
 * window's own handling ([deliver]), and what the gate makes up is
 * delivered the same way. [enabled] lets a window that sometimes hands the
 * pad to something else (Desktop mode's container) step out of the way.
 */
class PadGate(
    private val deliver: (KeyEvent) -> Boolean,
    private val enabled: () -> Boolean = { true },
) {
    private val handler = Handler(Looper.getMainLooper())
    private val core = PadGateCore(
        scheduler = object : PadScheduler {
            override fun now(): Long = SystemClock.uptimeMillis()

            override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
                val runnable = Runnable { task() }
                handler.postDelayed(runnable, delayMs)
                return { handler.removeCallbacks(runnable) }
            }
        },
        longPressMs = ViewConfiguration.getLongPressTimeout().toLong(),
        repeatTimeoutMs = ViewConfiguration.getKeyRepeatTimeout().toLong(),
        repeatDelayMs = ViewConfiguration.getKeyRepeatDelay().toLong(),
        emit = { key ->
            PadModality.padDriving()
            val event = key.toKeyEvent()
            val handled = deliver(event)
            log(event, if (handled) "made-by-gate handled" else "made-by-gate unhandled")
        },
    )

    /** A key event for this window; returns whether it was handled (or dropped). */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (!enabled()) return deliver(event)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return deliver(event)
        val pass = core.onKey(
            keyCode = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            deviceId = event.deviceId,
            fallback = (event.flags and KeyEvent.FLAG_FALLBACK) != 0,
            now = event.eventTime,
        )
        if (!pass) {
            log(event, "dropped")
            return true
        }
        if (event.keyCode != KeyEvent.KEYCODE_BACK && GamepadKeyMap.physicalAction(event.keyCode) != null) {
            PadModality.padDriving()
        }
        val handled = deliver(event)
        log(event, if (handled) "handled" else "unhandled")
        return handled
    }

    /**
     * A generic motion event; returns true when the gate took it (a pad's
     * joystick motion), false to let the window have it.
     */
    fun dispatchMotion(event: MotionEvent): Boolean {
        if (!enabled()) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        if ((event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        val device = event.device ?: return false
        val sources = device.sources
        val isPad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isPad) return false
        // A trigger is translated only on a pad that has no key for it: a
        // pad that sends BUTTON_R2 as well as the axis would otherwise press
        // R2 twice, and R2 toggles the Quick Menu (open, then shut).
        val triggerKeys = triggerKeysByDevice.getOrPut(device.id) {
            device.hasKeys(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)
        }
        core.onAxes(
            deviceId = device.id,
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            stickX = event.getAxisValue(MotionEvent.AXIS_X),
            stickY = event.getAxisValue(MotionEvent.AXIS_Y),
            leftTriggerValue = if (triggerKeys[0]) 0f else
                maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)),
            rightTriggerValue = if (triggerKeys[1]) 0f else
                maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)),
            now = SystemClock.uptimeMillis(),
        )
        return true
    }

    /**
     * A touch on this window. A finger on the screen hands the selection
     * back to touch, so the ring stops being drawn ([PadModality]); a
     * mouse is a pointer that moves focus by hovering, and leaves it on.
     */
    fun noteTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (event.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        ) {
            PadModality.touched()
        }
    }

    private val triggerKeysByDevice = HashMap<Int, BooleanArray>()

    /** Lets go of everything held and stops every timer. */
    fun cancel() = core.cancel()

    private fun PadSyntheticKey.toKeyEvent(): KeyEvent = KeyEvent(
        downTime,
        eventTime,
        if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
        keyCode,
        repeatCount,
        0,
        deviceId,
        0,
        0,
        InputDevice.SOURCE_GAMEPAD,
    )

    /**
     * Every D-pad edge, where it came from and what became of it: a rig or
     * console run is read back from logcat ("droidtop.input"), since nobody
     * can watch the handheld live.
     */
    private fun log(event: KeyEvent, outcome: String) {
        val name = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            else -> return
        }
        val edge = if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"
        android.util.Log.d(
            "droidtop.input",
            "$name edge=$edge device=${event.deviceId} repeat=${event.repeatCount} $outcome",
        )
    }

    /** A dialog window's own callback with this gate in front of it. */
    private class GatedCallback(val wrapped: Window.Callback, val gate: PadGate) : Window.Callback by wrapped {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = gate.dispatchKey(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
            gate.dispatchMotion(event) || wrapped.dispatchGenericMotionEvent(event)
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            gate.noteTouch(event)
            return wrapped.dispatchTouchEvent(event)
        }
    }

    init {
        PadModality.assumeOnce { ControllerPrefs.attachedControllers().isNotEmpty() }
    }

    companion object {
        /**
         * Puts a gate in front of [window]'s own callback, once; the
         * returned function takes it out again.
         */
        fun attach(window: Window): () -> Unit {
            val existing = window.callback
            if (existing == null || existing is GatedCallback) return {}
            val gate = PadGate(deliver = { event -> existing.dispatchKeyEvent(event) })
            val gated = GatedCallback(existing, gate)
            window.callback = gated
            return {
                gate.cancel()
                if (window.callback === gated) window.callback = existing
            }
        }
    }
}

/**
 * Puts the pipeline's front ([PadGate]) in front of the Compose `Dialog`
 * this is called from. A dialog is its own window: without this, its keys
 * and its stick motion never pass the gate, and the stick works there only
 * through the platform's own joystick-to-D-pad conversion, at different
 * thresholds and speed. Outside a dialog it does nothing (the activity
 * owns its own gate).
 */
@Composable
fun GatePadInThisDialog() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val detach = window?.let { PadGate.attach(it) } ?: {}
        onDispose { detach() }
    }
}

/**
 * Whether the shell draws its selection (docs/SPEC.md 6e, Droidtop/tracker
 * #159). The selection is ONE thing whatever moves it -- a tap moves it as
 * much as the D-pad does -- but it is DRAWN only while a pad or keyboard is
 * driving: a person using a finger sees what they touch, and a ring left on
 * some other row reads as "the default" or "the chosen one" (first tester,
 * 2026-09-29: an onboarding option circled while another was ticked; a
 * theme outlined that was not the one picked). The first pad or keyboard
 * press shows it again, where the last tap left it.
 *
 * Starts as "a pad is attached" (a console's own controls count), so a
 * screen that opens with a selection shows it on a console and not on a
 * phone. A snapshot state: everything that draws the ring re-reads it.
 */
object PadModality {
    private val drivingState = mutableStateOf(false)
    private var assumed = false

    /** Whether the selection ring is drawn right now. */
    val showsFocus: Boolean get() = drivingState.value

    internal fun padDriving() = set(true)

    internal fun touched() = set(false)

    internal fun assumeOnce(padAttached: () -> Boolean) {
        if (assumed) return
        assumed = true
        set(padAttached())
    }

    private fun set(value: Boolean) {
        if (drivingState.value != value) drivingState.value = value
    }
}
