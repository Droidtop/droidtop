package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Quick Menu's section order, the person's to set (Droidtop/tracker#610). */
class QuickMenuOrderTest {
    private val default = QuickMenuOrder.DEFAULT.map { it.first }

    @Test
    fun `picking a section for a place swaps it with the one there`() {
        val moved = QuickMenuOrder.place(default, 0, ControlPanel.DISPLAY)
        assertEquals(ControlPanel.DISPLAY, moved[0])
        assertEquals(default[0], moved[default.indexOf(ControlPanel.DISPLAY)])
        assertEquals(default.size, moved.toSet().size)
    }

    @Test
    fun `a stored order missing a section gets it back at its own place, and nothing twice`() {
        val stored = listOf(ControlPanel.DISPLAY, ControlPanel.APPS, ControlPanel.APPS)
        val completed = QuickMenuOrder.complete(stored)
        assertEquals(default.toSet(), completed.toSet())
        assertEquals(default.size, completed.size)
        assertEquals(ControlPanel.DISPLAY, completed.first())
    }
}
