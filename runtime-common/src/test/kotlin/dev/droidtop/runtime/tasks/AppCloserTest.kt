package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCloserTest {
    private class FakeOps(
        private val privileges: TaskPrivileges,
        private val result: ForceStopResult = ForceStopResult.NoProvider,
    ) : PrivilegedOps {
        val forceStops = mutableListOf<String>()

        override fun available() = privileges

        override fun forceStop(packageName: String): ForceStopResult {
            forceStops += packageName
            return result
        }

        override fun exec(argv: List<String>): ShellOutput? = null
    }

    private val killed = mutableListOf<String>()
    private val closed = mutableListOf<String>()

    private fun closer(ops: PrivilegedOps, killWorks: Boolean = true) =
        AppCloser(ops, killBackground = { killed += it; killWorks }, onClosed = { closed += it })

    @Test
    fun `a force-stop provider that stops the package makes it Closed, and nothing else is tried`() {
        val ops = FakeOps(TaskPrivileges(forceStop = true, shell = true), ForceStopResult.Stopped)

        val outcome = closer(ops).close("com.android.calendar")

        assertEquals(CloseOutcome.Closed, outcome)
        assertEquals(listOf("com.android.calendar"), ops.forceStops)
        assertTrue(killed.isEmpty())
        assertEquals(listOf("com.android.calendar"), closed)
    }

    @Test
    fun `without a provider the background kill is asked, and the result is never Closed`() {
        val ops = FakeOps(TaskPrivileges.NONE)

        val outcome = closer(ops).close("com.android.calendar")

        assertTrue(outcome is CloseOutcome.Requested)
        assertTrue(outcome.text.contains(TaskPolicy.ENABLE_HINT))
        assertTrue(ops.forceStops.isEmpty())
        assertEquals(listOf("com.android.calendar"), killed)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a provider that fails carries its own words, and the kill is still tried`() {
        val ops = FakeOps(TaskPrivileges(forceStop = true, shell = false), ForceStopResult.Failed("Shizuku is not running"))

        val outcome = closer(ops).close("com.android.calendar")

        assertTrue(outcome is CloseOutcome.Failed)
        assertTrue(outcome.text.contains("Shizuku is not running"))
        assertEquals(listOf("com.android.calendar"), killed)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `when Android refuses even the ask it says so rather than claiming anything`() {
        val outcome = closer(FakeOps(TaskPrivileges.NONE), killWorks = false).close("com.android.calendar")

        assertTrue(outcome is CloseOutcome.Failed)
        assertTrue(outcome.text.contains(TaskPolicy.ENABLE_HINT))
    }

    @Test
    fun `close all closes each package in order`() {
        val ops = FakeOps(TaskPrivileges(forceStop = true, shell = false), ForceStopResult.Stopped)

        val summary = closer(ops).closeAll(listOf("a.one", "b.two", "c.three"))

        assertEquals(ClearAllSummary(closed = 3, requested = 0, failed = 0), summary)
        assertEquals(listOf("a.one", "b.two", "c.three"), ops.forceStops)
    }
}
