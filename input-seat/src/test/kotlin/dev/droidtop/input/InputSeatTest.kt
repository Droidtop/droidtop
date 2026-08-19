package dev.droidtop.input

import dev.droidtop.hostbridge.HostBridgeInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Records every call instead of touching real Wayland input injection —
 * this is exactly why [HostBridgeInput] was pulled out of the concrete,
 * JNI-backed `HostBridge` class (which would throw `UnsatisfiedLinkError`
 * in a plain JVM test).
 */
private class FakeHostBridge : HostBridgeInput {
    val pointerMotions = mutableListOf<Pair<Double, Double>>()
    val pointerAbsolutes = mutableListOf<List<Any>>()
    val pointerButtons = mutableListOf<Pair<Int, Boolean>>()
    val pointerAxes = mutableListOf<Pair<Double, Double>>()
    val keys = mutableListOf<Pair<Int, Boolean>>()

    override fun injectPointerMotion(dx: Double, dy: Double) {
        pointerMotions += dx to dy
    }

    override fun injectPointerMotionAbsolute(x: Double, y: Double, extentWidth: Int, extentHeight: Int) {
        pointerAbsolutes += listOf(x, y, extentWidth, extentHeight)
    }

    override fun injectPointerButton(linuxButtonCode: Int, pressed: Boolean) {
        pointerButtons += linuxButtonCode to pressed
    }

    override fun injectPointerAxis(horizontal: Double, vertical: Double) {
        pointerAxes += horizontal to vertical
    }

    override fun injectKey(evdevKeyCode: Int, pressed: Boolean) {
        keys += evdevKeyCode to pressed
    }
}

class InputSeatTest {
    @Test
    fun `onPointerMove forwards relative delta as-is`() {
        val bridge = FakeHostBridge()
        val seat = InputSeat(bridge)

        seat.onPointerMove(InputSource.SECOND_SCREEN_TRACKPAD, dx = 3.5f, dy = -2f)

        assertEquals(listOf(3.5 to -2.0), bridge.pointerMotions)
    }

    @Test
    fun `onPointerAbsolute forwards position and extent together`() {
        val bridge = FakeHostBridge()
        val seat = InputSeat(bridge)

        seat.onPointerAbsolute(InputSource.TOUCH, x = 100f, y = 200f, extentWidth = 1920, extentHeight = 1080)

        assertEquals(listOf(listOf<Any>(100.0, 200.0, 1920, 1080)), bridge.pointerAbsolutes)
    }

    @Test
    fun `onPointerButton and onPointerScroll and onKey all pass through unchanged`() {
        val bridge = FakeHostBridge()
        val seat = InputSeat(bridge)

        seat.onPointerButton(InputSource.GAMEPAD, linuxButtonCode = 0x110, pressed = true)
        seat.onPointerScroll(InputSource.TOUCH, horizontal = 0f, vertical = 1.5f)
        seat.onKey(InputSource.SECOND_SCREEN_KEYBOARD, keyCode = 30, down = true)

        assertEquals(listOf(0x110 to true), bridge.pointerButtons)
        assertEquals(listOf(0.0 to 1.5), bridge.pointerAxes)
        assertEquals(listOf(30 to true), bridge.keys)
    }
}
