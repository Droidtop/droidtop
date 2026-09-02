package dev.droidtop.input

import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Turns the Android events a view receives into [InputSeat] calls. This is
 * the *only* thing that drives the seat from the desktop shell — there is
 * no side channel that reaches [dev.droidtop.hostbridge.HostBridgeInput]
 * directly, which is the point: the compositor sees one pointer and one
 * keyboard no matter how many physical devices the user has plugged in, and
 * that invariant only holds if everything funnels through here.
 *
 * ## Inert without a session
 * [seat] is null whenever `DesktopSessionService` is not `Connected`. Every
 * entry point returns false in that state and nothing is buffered. Queueing
 * would be actively wrong: a click the user made against a "session not
 * started" placeholder should not be replayed into a desktop that appears
 * three minutes later, possibly onto a different window.
 *
 * ## Threading
 * All entry points are called on the UI thread (view input dispatch, and
 * the [Choreographer] callback the analog-stick pointer runs on).
 * `WaylandClient`'s injection methods document themselves as safe to call
 * from a thread other than its dispatch thread, so no handoff is needed.
 */
class DesktopInputRouter {

    /** Set from the session state. Null means no container to inject into. */
    var seat: InputSeat? = null
        set(value) {
            if (field !== value) releaseHeldInput()
            field = value
        }

    /**
     * View-to-output geometry, refreshed on every surface size change. Null
     * until the surface has been laid out at least once.
     */
    var transform: PointerTransform? = null

    /**
     * Whether the right analog stick drives the pointer. See
     * [onJoystickMotion] for why this covers the right stick and the stick
     * clicks only, and nothing that Compose's focus navigation uses.
     */
    var gamepadPointerEnabled: Boolean = true

    private var touchButtonDown = false
    private var touchScrolling = false
    private var lastTouchCentroidX = 0f
    private var lastTouchCentroidY = 0f

    private var lastMouseButtonState = 0
    private val heldKeys = mutableSetOf<Int>()
    private val heldButtons = mutableSetOf<Int>()

    private var stickX = 0f
    private var stickY = 0f
    private var stickCallbackScheduled = false

    /**
     * Installs this router on [view] and gives it focus so hardware key
     * events are dispatched to it at all. `focusableInTouchMode` is
     * required, not decorative: without it a touchscreen interaction clears
     * focus and the physical keyboard stops reaching the container.
     */
    fun attachTo(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnTouchListener { v, event ->
            v.requestFocus()
            onMotionEvent(event)
        }
        view.setOnGenericMotionListener { _, event -> onMotionEvent(event) }
        view.setOnKeyListener { _, _, event -> onKeyEvent(event) }
        view.requestFocus()
    }

    /**
     * Releases everything the container currently thinks is held down.
     * Called when the session goes away or the surface loses input, because
     * a key or button whose release event never arrives stays stuck down
     * inside the container forever — the classic symptom being a desktop
     * that behaves as though Ctrl is permanently pressed.
     */
    fun releaseHeldInput() {
        val current = seat
        if (current != null) {
            heldButtons.forEach { current.onPointerButton(InputSource.TOUCH, it, pressed = false) }
            heldKeys.forEach { current.onKey(InputSource.LAPDOCK_PERIPHERAL, it, down = false) }
        }
        heldButtons.clear()
        heldKeys.clear()
        touchButtonDown = false
        touchScrolling = false
        lastMouseButtonState = 0
        stickX = 0f
        stickY = 0f
    }

    // ---- Motion ----

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (seat == null) return false
        return when {
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) -> onRelativePointer(event)
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.isFromSource(InputDevice.SOURCE_STYLUS) -> onAbsolutePointer(event)
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) -> onJoystickMotion(event)
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) -> onTouch(event)
            else -> false
        }
    }

    /**
     * Direct touch: the finger *is* the cursor, so position is absolute and
     * a single finger holds the left button down for its whole gesture,
     * which is what makes dragging a window title bar work.
     *
     * Two fingers scroll instead. The left button is released first, so a
     * second finger landing mid-drag cannot leave a button stuck down.
     * There is deliberately no long-press-to-right-click here: it is a real
     * gap, but guessing at a hold duration with no device to tune it on
     * would be worse than leaving right click to a mouse or to the
     * second-screen trackpad.
     */
    private fun onTouch(event: MotionEvent): Boolean {
        val seat = seat ?: return false
        val transform = transform ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = transform.map(event.x, event.y) ?: return false
                seat.onPointerAbsolute(InputSource.TOUCH, point.x, point.y, point.extentWidth, point.extentHeight)
                pressButton(seat, InputSource.TOUCH, EvdevKeys.BTN_LEFT)
                touchButtonDown = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (touchButtonDown) {
                    releaseButton(seat, InputSource.TOUCH, EvdevKeys.BTN_LEFT)
                    touchButtonDown = false
                }
                touchScrolling = true
                val centroid = centroidOf(event)
                lastTouchCentroidX = centroid.first
                lastTouchCentroidY = centroid.second
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchScrolling && event.pointerCount >= 2) {
                    val centroid = centroidOf(event)
                    val delta = transform.mapDelta(
                        centroid.first - lastTouchCentroidX,
                        centroid.second - lastTouchCentroidY,
                    ) ?: return true
                    lastTouchCentroidX = centroid.first
                    lastTouchCentroidY = centroid.second
                    // Content follows the fingers: dragging up (a negative
                    // dy in view space) reveals content further down the
                    // document, which is a positive Wayland axis value.
                    seat.onPointerScroll(InputSource.TOUCH, -delta.first, -delta.second)
                } else if (!touchScrolling) {
                    val point = transform.map(event.x, event.y) ?: return true
                    seat.onPointerAbsolute(InputSource.TOUCH, point.x, point.y, point.extentWidth, point.extentHeight)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) touchScrolling = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchButtonDown) {
                    val point = transform.map(event.x, event.y)
                    if (point != null) {
                        seat.onPointerAbsolute(
                            InputSource.TOUCH,
                            point.x,
                            point.y,
                            point.extentWidth,
                            point.extentHeight,
                        )
                    }
                    releaseButton(seat, InputSource.TOUCH, EvdevKeys.BTN_LEFT)
                    touchButtonDown = false
                }
                touchScrolling = false
            }

            else -> return false
        }
        return true
    }

    /**
     * A physical mouse, a stylus, or a trackpad Android is running in
     * pointer-gesture mode: Android places a cursor itself and reports its
     * position in view coordinates, so injecting absolutely keeps the
     * container's cursor under Android's rather than letting the two drift
     * apart.
     */
    private fun onAbsolutePointer(event: MotionEvent): Boolean {
        val seat = seat ?: return false

        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            emitScroll(seat, event)
            return true
        }

        transform?.map(event.x, event.y)?.let { point ->
            seat.onPointerAbsolute(
                InputSource.LAPDOCK_PERIPHERAL,
                point.x,
                point.y,
                point.extentWidth,
                point.extentHeight,
            )
        }
        syncMouseButtons(seat, event.buttonState)
        return true
    }

    /** A captured trackpad or a relative-mode mouse: deltas, no position. */
    private fun onRelativePointer(event: MotionEvent): Boolean {
        val seat = seat ?: return false

        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            emitScroll(seat, event)
            return true
        }

        val delta = transform?.mapDelta(
            event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
            event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y),
        )
        if (delta != null && (delta.first != 0f || delta.second != 0f)) {
            seat.onPointerMove(InputSource.LAPDOCK_PERIPHERAL, delta.first, delta.second)
        }
        syncMouseButtons(seat, event.buttonState)
        return true
    }

    private fun emitScroll(seat: InputSeat, event: MotionEvent) {
        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (horizontal == 0f && vertical == 0f) return
        // Android reports one wheel detent as 1.0 and counts positive
        // upward; the Wayland axis is in the units libinput emits for a
        // wheel, where one detent is 15, and counts positive downward.
        seat.onPointerScroll(
            InputSource.LAPDOCK_PERIPHERAL,
            horizontal * WHEEL_DETENT,
            -vertical * WHEEL_DETENT,
        )
    }

    /**
     * Diffs the whole button bitmask rather than reading `actionButton`
     * from ACTION_BUTTON_PRESS/RELEASE. Chording (holding left while
     * clicking right) and any button released outside the view both come
     * out correct this way, and a button can never be left held, because
     * the mask itself states what is down.
     */
    private fun syncMouseButtons(seat: InputSeat, buttonState: Int) {
        if (buttonState == lastMouseButtonState) return
        val now = EvdevKeys.evdevButtons(buttonState).toSet()
        val before = EvdevKeys.evdevButtons(lastMouseButtonState).toSet()
        (before - now).forEach { releaseButton(seat, InputSource.LAPDOCK_PERIPHERAL, it) }
        (now - before).forEach { pressButton(seat, InputSource.LAPDOCK_PERIPHERAL, it) }
        lastMouseButtonState = buttonState
    }

    /**
     * The right analog stick moves the pointer; the two stick clicks are
     * left and right button.
     *
     * The decision this encodes: the D-pad, the face buttons, the shoulders
     * and the left stick are all left alone, because every one of them is
     * load-bearing for navigating the shell that surrounds this surface —
     * the taskbar, the start menu and the settings screens are Compose
     * focus navigation, driven by D-pad and A. Taking those over would make
     * the desktop unusable in exactly the situation a gamepad pointer is
     * for: no mouse attached and nothing else to navigate with. The right
     * stick and the two stick clicks are the controls Compose focus
     * navigation does not use at all, so they are free.
     */
    private fun onJoystickMotion(event: MotionEvent): Boolean {
        if (!gamepadPointerEnabled) return false
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false
        stickX = deadzone(event.getAxisValue(MotionEvent.AXIS_Z))
        stickY = deadzone(event.getAxisValue(MotionEvent.AXIS_RZ))
        scheduleStickTick()
        return stickX != 0f || stickY != 0f
    }

    /**
     * A stick sends events only when it moves, but a held stick has to keep
     * moving the cursor, so deflection is resampled once per frame for as
     * long as it lasts. The callback stops rescheduling itself the moment
     * the stick returns to centre, so a resting gamepad costs nothing.
     */
    private fun scheduleStickTick() {
        if (stickCallbackScheduled) return
        if (stickX == 0f && stickY == 0f) return
        stickCallbackScheduled = true
        Choreographer.getInstance().postFrameCallback(stickTick)
    }

    private val stickTick = Choreographer.FrameCallback {
        stickCallbackScheduled = false
        val seat = seat
        if (seat != null && (stickX != 0f || stickY != 0f)) {
            // Squared response: fine positioning near centre, full speed at
            // full deflection. That is the standard fix for a stick that is
            // otherwise either too twitchy to hit a menu item or too slow
            // to cross the screen.
            val dx = stickX * abs(stickX) * STICK_PIXELS_PER_FRAME
            val dy = stickY * abs(stickY) * STICK_PIXELS_PER_FRAME
            seat.onPointerMove(InputSource.GAMEPAD, dx, dy)
        }
        scheduleStickTick()
    }

    // ---- Keys ----

    fun onKeyEvent(event: KeyEvent): Boolean {
        val seat = seat ?: return false

        // Gamepad buttons arrive as key events too. Only the two stick
        // clicks are claimed; everything else is left to the shell so
        // D-pad/A navigation keeps working (see onJoystickMotion).
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            if (!gamepadPointerEnabled) return false
            val button = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_THUMBR -> EvdevKeys.BTN_LEFT
                KeyEvent.KEYCODE_BUTTON_THUMBL -> EvdevKeys.BTN_RIGHT
                else -> return false
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) pressButton(seat, InputSource.GAMEPAD, button)
                KeyEvent.ACTION_UP -> releaseButton(seat, InputSource.GAMEPAD, button)
                else -> return false
            }
            return true
        }

        val evdev = EvdevKeys.evdevKeyCode(event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Auto-repeat is the compositor's job: it owns the seat's
                // repeat rate and delay. Forwarding Android's repeats as
                // fresh presses would stack a second repeat on top of it.
                if (event.repeatCount == 0) {
                    heldKeys += evdev
                    seat.onKey(InputSource.LAPDOCK_PERIPHERAL, evdev, down = true)
                }
            }

            KeyEvent.ACTION_UP -> {
                heldKeys -= evdev
                seat.onKey(InputSource.LAPDOCK_PERIPHERAL, evdev, down = false)
            }

            else -> return false
        }
        return true
    }

    private fun pressButton(seat: InputSeat, source: InputSource, button: Int) {
        heldButtons += button
        seat.onPointerButton(source, button, pressed = true)
    }

    private fun releaseButton(seat: InputSeat, source: InputSource, button: Int) {
        heldButtons -= button
        seat.onPointerButton(source, button, pressed = false)
    }

    private fun centroidOf(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (i in 0 until event.pointerCount) {
            x += event.getX(i)
            y += event.getY(i)
        }
        return x / event.pointerCount to y / event.pointerCount
    }

    private fun deadzone(value: Float): Float =
        if (abs(value) < STICK_DEADZONE) 0f else value

    private companion object {
        const val WHEEL_DETENT = 15.0f
        const val STICK_DEADZONE = 0.15f
        const val STICK_PIXELS_PER_FRAME = 18f
    }
}
