package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Test

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
