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
    fun onPointerMove(source: InputSource, dx: Float, dy: Float) {
        TODO("Forward as a relative pointer-motion event via HostBridge")
    }

    fun onPointerAbsolute(source: InputSource, x: Float, y: Float) {
        TODO("Forward as an absolute pointer-motion event via HostBridge")
    }

    fun onKey(source: InputSource, keyCode: Int, down: Boolean) {
        TODO("Forward as a virtual-keyboard event via HostBridge")
    }
}
