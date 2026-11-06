package dev.droidtop.hostbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which bar lists a window and where it can be moved (Droidtop/tracker#908, step D4; docs/SPEC.md 2b). */
class WindowOutputsTest {
    private data class Window(val id: Int, val output: String)

    private val two = listOf("HEADLESS-1", "HEADLESS-2")
    private val twoNames = listOf("Bottom screen", "Top screen")

    @Test
    fun `with one output every window is on the primary's bar, the same list as before`() {
        val windows = listOf(Window(1, "HEADLESS-1"), Window(2, ""), Window(3, "HEADLESS-9"))
        assertSame(windows, WindowOutputs.onBar(windows, { it.output }, listOf("HEADLESS-1"), 0))
        assertSame(windows, WindowOutputs.onBar(windows, { it.output }, emptyList(), 0))
        assertEquals(emptyList<Window>(), WindowOutputs.onBar(windows, { it.output }, listOf("HEADLESS-1"), 1))
    }

    @Test
    fun `each bar lists the windows on its own output`() {
        val windows = listOf(Window(1, "HEADLESS-1"), Window(2, "HEADLESS-2"), Window(3, "HEADLESS-1"))
        assertEquals(listOf(1, 3), WindowOutputs.onBar(windows, { it.output }, two, 0).map { it.id })
        assertEquals(listOf(2), WindowOutputs.onBar(windows, { it.output }, two, 1).map { it.id })
        assertEquals(emptyList<Window>(), WindowOutputs.onBar(windows, { it.output }, two, 2))
    }

    @Test
    fun `a window on no known output is on the primary's bar`() {
        // Not named yet, an output that went, and a primary the session has not named ("").
        assertEquals(0, WindowOutputs.barFor("", two))
        assertEquals(0, WindowOutputs.barFor("HEADLESS-3", two))
        assertEquals(0, WindowOutputs.barFor("HEADLESS-1", listOf("", "HEADLESS-2")))
        assertEquals(1, WindowOutputs.barFor("HEADLESS-2", listOf("", "HEADLESS-2")))
    }

    @Test
    fun `nothing offers a move with one output`() {
        assertEquals(emptyList<MoveTarget>(), WindowOutputs.moveTargets("HEADLESS-1", listOf("HEADLESS-1"), listOf("Built-in screen")))
        assertEquals(emptyList<MoveTarget>(), WindowOutputs.moveTargets("", emptyList(), emptyList()))
        assertNull(WindowOutputs.screenOf("HEADLESS-1", listOf("HEADLESS-1"), listOf("Built-in screen")))
    }

    @Test
    fun `with two outputs a window moves to the other one, by its screen's name`() {
        assertEquals(listOf(MoveTarget("HEADLESS-2", "Top screen")), WindowOutputs.moveTargets("HEADLESS-1", two, twoNames))
        assertEquals(listOf(MoveTarget("HEADLESS-1", "Bottom screen")), WindowOutputs.moveTargets("HEADLESS-2", two, twoNames))
        // A window the primary's bar lists for want of a known output moves from there.
        assertEquals(listOf(MoveTarget("HEADLESS-2", "Top screen")), WindowOutputs.moveTargets("", two, twoNames))
        assertEquals("Top screen", WindowOutputs.screenOf("HEADLESS-2", two, twoNames))
        assertEquals("Bottom screen", WindowOutputs.screenOf("", two, twoNames))
    }

    @Test
    fun `with more outputs there is one row per other screen, in the outputs' order`() {
        val three = two + "HEADLESS-3"
        val names = twoNames + "DP Screen"
        assertEquals(
            listOf(MoveTarget("HEADLESS-1", "Bottom screen"), MoveTarget("HEADLESS-3", "DP Screen")),
            WindowOutputs.moveTargets("HEADLESS-2", three, names),
        )
    }

    @Test
    fun `an output without a name yet is not offered, since sway is told the name`() {
        assertTrue(WindowOutputs.moveTargets("HEADLESS-2", listOf("", "HEADLESS-2"), twoNames).isEmpty())
    }
}
