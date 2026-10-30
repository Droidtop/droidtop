package dev.droidtop.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** The companion pointer through the helper app's shell (Droidtop/tracker#811). */
class ShellPointerCommandsTest {
    private fun argv(op: PointerOp) = ShellPointerCommands.argv(op, displayId = 2, maxX = 1919f, maxY = 1079f)

    @Test
    fun `a tap is input tap on the target screen in whole pixels`() {
        assertEquals(listOf("input", "-d", "2", "tap", "100", "200"), argv(PointerOp.Tap(100.6f, 200.2f)))
    }

    @Test
    fun `a long press is a swipe that stays in place`() {
        assertEquals(listOf("input", "-d", "2", "swipe", "5", "6", "5", "6", "650"), argv(PointerOp.ContextClick(5f, 6f)))
    }

    @Test
    fun `a scroll is a short swipe, kept on the screen`() {
        assertEquals(listOf("input", "-d", "2", "swipe", "10", "1000", "10", "1079", "150"), argv(PointerOp.Scroll(10f, 1000f, 0f, 400f)))
    }

    @Test
    fun `a drag is one stroke of motion events`() {
        assertEquals(listOf("input", "-d", "2", "motionevent", "DOWN", "0", "0"), argv(PointerOp.DragStart(-4f, -9f)))
        assertEquals(listOf("input", "-d", "2", "motionevent", "MOVE", "300", "400"), argv(PointerOp.DragMove(300f, 400f)))
        assertEquals(listOf("input", "-d", "2", "motionevent", "UP", "1919", "1079"), argv(PointerOp.DragEnd(5000f, 5000f)))
    }
}
