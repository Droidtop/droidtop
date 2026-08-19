package dev.droidtop.input

/** Where a normalized input event came from, kept for UX/telemetry, not for routing. */
enum class InputSource { TOUCH, GAMEPAD, SECOND_SCREEN_TRACKPAD, SECOND_SCREEN_KEYBOARD, LAPDOCK_PERIPHERAL }

/**
 * One logical Wayland seat, fed from every physical input path this device
 * might have: touchscreen, gamepad-as-pointer, the second-screen trackpad
 * (see below), or a lapdock's physical keyboard/trackpad. All of it gets
 * normalized here and handed to a single [dev.droidtop.hostbridge.HostBridge] for
 * injection into the primary container's compositor — the compositor only
 * ever sees one pointer and one keyboard, regardless of source.
 *
 * The second-screen trackpad surface itself borrows its interaction model
 * from Moonlight Android's AbsoluteTouchContext/RelativeTouchContext split:
 * touch on the primary screen maps to absolute cursor position, touch on
 * the second-screen trackpad maps to relative deltas like a real trackpad.
 */
class InputSeat(
    private val bridge: dev.droidtop.hostbridge.HostBridge,
) {
    /** [dx]/[dy]: trackpad-style relative deltas — the second-screen trackpad's primary use case. */
    fun onPointerMove(source: InputSource, dx: Float, dy: Float) {
        bridge.injectPointerMotion(dx.toDouble(), dy.toDouble())
    }

    /**
     * [x]/[y]: absolute position within a logical area [extentWidth] x
     * [extentHeight] — the primary screen's direct-touch use case. Caller
     * (the view/window handling the touch event) is the natural place to
     * know its own bounds, so it's passed in here rather than assumed.
     */
    fun onPointerAbsolute(source: InputSource, x: Float, y: Float, extentWidth: Int, extentHeight: Int) {
        bridge.injectPointerMotionAbsolute(x.toDouble(), y.toDouble(), extentWidth, extentHeight)
    }

    /** [linuxButtonCode]: BTN_LEFT/BTN_RIGHT/BTN_MIDDLE (0x110/0x111/0x112) from linux/input-event-codes.h. */
    fun onPointerButton(source: InputSource, linuxButtonCode: Int, pressed: Boolean) {
        bridge.injectPointerButton(linuxButtonCode, pressed)
    }

    fun onPointerScroll(source: InputSource, horizontal: Float, vertical: Float) {
        bridge.injectPointerAxis(horizontal.toDouble(), vertical.toDouble())
    }

    /** [keyCode]: Linux evdev keycode (KEY_* from linux/input-event-codes.h), NOT an Android KeyEvent code. */
    fun onKey(source: InputSource, keyCode: Int, down: Boolean) {
        bridge.injectKey(keyCode, down)
    }
}
