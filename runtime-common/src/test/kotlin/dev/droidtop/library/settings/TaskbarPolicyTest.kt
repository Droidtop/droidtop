package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskbarPolicyTest {

    @Test
    fun `a phone screen has no taskbar and a tablet screen has one`() {
        assertFalse(TaskbarPolicy.shownOnDisplay(smallestWidthDp = 411, isDefaultDisplay = true))
        assertFalse(TaskbarPolicy.shownOnDisplay(smallestWidthDp = 599, isDefaultDisplay = true))
        assertTrue(TaskbarPolicy.shownOnDisplay(smallestWidthDp = 600, isDefaultDisplay = true))
        assertTrue(TaskbarPolicy.shownOnDisplay(smallestWidthDp = 800, isDefaultDisplay = true))
    }

    @Test
    fun `an external display has a taskbar whatever its size`() {
        assertTrue(TaskbarPolicy.shownOnDisplay(smallestWidthDp = 360, isDefaultDisplay = false))
    }

    @Test
    fun `an app already open keeps its place`() {
        assertEquals(listOf("a", "b", "c"), TaskbarPolicy.withOpened(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `a new app goes last and the oldest drops past the limit`() {
        assertEquals(listOf("a", "b"), TaskbarPolicy.withOpened(listOf("a"), "b"))
        assertEquals(listOf("b", "c"), TaskbarPolicy.withOpened(listOf("a", "b"), "c", max = 2))
    }

    @Test
    fun `removing an app leaves the rest in order`() {
        assertEquals(listOf("a", "c"), TaskbarPolicy.without(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `the taskbar is Standard's alone, never over a game started from Gaming or Desktop`() {
        // The console on 2026-10-30: Standard was switched on, Gaming was in use, a game was in front, and the bar showed.
        assertFalse(TaskbarPolicy.shown(enabled = true, modeInUse = Mode.GAMING.id, appInFront = true, droidtopIsHome = true))
        assertFalse(TaskbarPolicy.shown(enabled = true, modeInUse = Mode.DESKTOP.id, appInFront = true, droidtopIsHome = true))
        assertTrue(TaskbarPolicy.shown(enabled = true, modeInUse = Mode.LAUNCHER.id, appInFront = true, droidtopIsHome = true))
        assertFalse(TaskbarPolicy.shown(enabled = false, modeInUse = Mode.LAUNCHER.id, appInFront = true, droidtopIsHome = true))
        assertFalse(TaskbarPolicy.shown(enabled = true, modeInUse = Mode.LAUNCHER.id, appInFront = false, droidtopIsHome = true))
    }
}
