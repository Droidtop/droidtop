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

    @Test
    fun `a library game in front folds its display's bar until Show, and Hide holds on its own display`() {
        assertFalse(TaskbarPolicy.collapsed(hidden = false, folds = false, shownForThisApp = false))
        assertTrue(TaskbarPolicy.collapsed(hidden = false, folds = true, shownForThisApp = false))
        assertFalse(TaskbarPolicy.collapsed(hidden = false, folds = true, shownForThisApp = true))
        assertTrue(TaskbarPolicy.collapsed(hidden = true, folds = false, shownForThisApp = false))
        assertTrue(TaskbarPolicy.collapsed(hidden = true, folds = true, shownForThisApp = true))
    }

    @Test
    fun `any app folds the bar on a non-default display, a game folds it anywhere, nothing folds it on the home screen`() {
        assertTrue(TaskbarPolicy.folds(gameHere = false, appInFront = true, defaultDisplay = false))
        assertFalse(TaskbarPolicy.folds(gameHere = false, appInFront = true, defaultDisplay = true))
        assertTrue(TaskbarPolicy.folds(gameHere = true, appInFront = true, defaultDisplay = true))
        assertFalse(TaskbarPolicy.folds(gameHere = false, appInFront = false, defaultDisplay = false))
    }

    // Droidtop/tracker#702: with Android's task list read exactly, the open list is the running apps.
    @Test
    fun `the task list keeps the bar's order, adds the other running apps and drops what stopped`() {
        assertEquals(
            listOf("b", "a", "c"),
            TaskbarPolicy.openFromTasks(open = listOf("x", "b", "a"), running = listOf("a", "c", "b"), front = "a"),
        )
    }

    @Test
    fun `past the cap the oldest go, never the app in front`() {
        val running = (1..10).map { "app$it" }
        val open = TaskbarPolicy.openFromTasks(open = listOf("app1"), running = running, front = "app1", max = 3)
        assertEquals(3, open.size)
        assertTrue("app1" in open)
    }
}
