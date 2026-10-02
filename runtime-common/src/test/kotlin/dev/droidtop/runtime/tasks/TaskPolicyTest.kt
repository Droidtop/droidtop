package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPolicyTest {
    private fun app(pkg: String, display: Int = 0) = RunningApp(pkg, pkg, display, taskId = null, visible = false)

    @Test
    fun `capabilities name the operations exposed by a privileged shell`() {
        val caps = TaskPrivileges(forceStop = true, shell = true, grantPermission = true)

        assertTrue(caps.listTasks)
        assertTrue(caps.forceStop)
        assertTrue(caps.shellCommand)
        assertTrue(caps.grantPermission)
    }

    @Test
    fun `clear all never closes droidtop, enginehost, the system, a home app or what the user protected`() {
        val protected = TaskPolicy.protectedPackages("dev.droidtop.app", setOf("com.example.launcher"), setOf("org.example.keep"))
        val apps = listOf(
            app("com.android.calendar"),
            app("dev.droidtop.app"),
            app("dev.enginehost"),
            app("com.android.systemui"),
            app("com.example.launcher"),
            app("org.example.keep"),
            app("org.example.browser"),
        )
        assertEquals(
            listOf("com.android.calendar", "org.example.browser"),
            TaskPolicy.clearAllTargets(apps, protected).map { it.packageName },
        )
    }

    @Test
    fun `clear all lists a package once even when it has tasks on both screens`() {
        val apps = listOf(app("com.android.calendar", 0), app("com.android.calendar", 2), app("org.example.browser", 0))
        assertEquals(2, TaskPolicy.clearAllTargets(apps, emptySet()).size)
    }

    @Test
    fun `a confirm is asked only above the threshold`() {
        assertFalse(TaskPolicy.needsClearAllConfirm(0))
        assertFalse(TaskPolicy.needsClearAllConfirm(TaskPolicy.CONFIRM_CLEAR_ALL_ABOVE))
        assertTrue(TaskPolicy.needsClearAllConfirm(TaskPolicy.CONFIRM_CLEAR_ALL_ABOVE + 1))
    }

    @Test
    fun `without a provider the only close path is the unconfirmable background kill`() {
        assertEquals(listOf(CloseStep.KILL_BACKGROUND), TaskPolicy.closeSteps(TaskPrivileges.NONE))
        // A shell provider alone cannot force-stop: only priv.packages can.
        assertEquals(listOf(CloseStep.KILL_BACKGROUND), TaskPolicy.closeSteps(TaskPrivileges(forceStop = false, shell = true)))
    }

    @Test
    fun `with a force-stop provider it is tried first`() {
        assertEquals(
            listOf(CloseStep.FORCE_STOP, CloseStep.KILL_BACKGROUND),
            TaskPolicy.closeSteps(TaskPrivileges(forceStop = true, shell = false)),
        )
    }

    @Test
    fun `the list hides droidtop, home apps and the system but not enginehost`() {
        val hidden = TaskPolicy.hiddenFromList("dev.droidtop.app", setOf("com.example.launcher"))
        assertTrue("dev.droidtop.app" in hidden)
        assertTrue("com.example.launcher" in hidden)
        assertTrue("com.android.systemui" in hidden)
        assertFalse(TaskPolicy.ENGINEHOST_PACKAGE in hidden)
    }

    @Test
    fun `the other screen is the built-in one for an app elsewhere, and null with one screen`() {
        assertEquals(0, TaskPolicy.otherDisplay(current = 2, all = listOf(0, 2)))
        assertEquals(2, TaskPolicy.otherDisplay(current = 0, all = listOf(0, 2)))
        assertNull(TaskPolicy.otherDisplay(current = 0, all = listOf(0)))
    }

    @Test
    fun `clear all summarises what was confirmed and what was only asked`() {
        assertEquals("Nothing to close.", ClearAllSummary.of(emptyList()).message)
        assertEquals("Closed 2 apps.", ClearAllSummary.of(listOf(CloseOutcome.Closed, CloseOutcome.Closed)).message)
        assertEquals("Closed 1 app.", ClearAllSummary.of(listOf(CloseOutcome.Closed)).message)
        val asked = ClearAllSummary.of(listOf(CloseOutcome.Requested("x")))
        assertTrue(asked.message.startsWith("Asked Android to close 1 app."))
        assertTrue(asked.message.contains(TaskPolicy.ENABLE_HINT))
        val mixed = ClearAllSummary.of(listOf(CloseOutcome.Closed, CloseOutcome.Requested("x"), CloseOutcome.Failed("y")))
        assertEquals(ClearAllSummary(1, 1, 1), mixed)
        assertTrue(mixed.message.startsWith("Closed 1, asked Android to close 1, could not close 1."))
    }
}
