package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCloserTest {
    private class FakeOps(
        private val privileges: TaskPrivileges,
        private val result: ForceStopResult = ForceStopResult.NoProvider,
        private val reason: String? = null,
    ) : PrivilegedShell {
        val forceStops = mutableListOf<String>()
        var connects = 0

        override fun available() = privileges

        override fun connect(timeoutMs: Long) {
            connects++
        }

        override fun unavailableReason() = reason

        override fun forceStop(packageName: String): ForceStopResult {
            forceStops += packageName
            return result
        }

        override fun exec(argv: List<String>): ShellOutput? = null
    }

    private val killed = mutableListOf<String>()
    private val closed = mutableListOf<String>()

    private var endGameAnswer: Boolean? = null
    private var endGameAsks = 0

    private val order = mutableListOf<String>()

    private fun closer(ops: PrivilegedShell, killWorks: Boolean = true) =
        AppCloser(
            ops,
            killBackground = { order += "kill"; killed += it; killWorks },
            endEnginehostGame = { endGameAsks++; endGameAnswer },
            sendToBackground = { order += "home" },
            onClosed = { closed += it },
        )

    @Test
    fun `an engine game Enginehost ended without a helper is Closed`() {
        endGameAnswer = true

        val outcome = closer(FakeOps(TaskPrivileges.NONE)).close(TaskPolicy.ENGINEHOST_PACKAGE)

        assertEquals(CloseOutcome.Closed, outcome)
        assertTrue(killed.isEmpty())
        assertEquals(listOf(TaskPolicy.ENGINEHOST_PACKAGE), closed)
    }

    @Test
    fun `when Enginehost could not be asked the close is only asked of Android`() {
        endGameAnswer = null

        val outcome = closer(FakeOps(TaskPrivileges.NONE)).close(TaskPolicy.ENGINEHOST_PACKAGE)

        assertTrue(outcome is CloseOutcome.Requested)
        assertEquals(listOf(TaskPolicy.ENGINEHOST_PACKAGE), killed)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `Enginehost saying no game is running ends droidtop's stale session and asks nothing else`() {
        endGameAnswer = false
        // A helper that would open a full-access sheet is never reached for a game that is not running.
        val ops = FakeOps(TaskPrivileges(forceStop = true, shell = true), ForceStopResult.Stopped)

        val outcome = closer(ops).close(TaskPolicy.ENGINEHOST_PACKAGE)

        assertEquals(CloseOutcome.NotRunning("Enginehost has no game running"), outcome)
        assertTrue(outcome.sessionOver)
        assertEquals("Enginehost has no game running", outcome.text)
        assertTrue(ops.forceStops.isEmpty())
        assertTrue(killed.isEmpty())
        assertEquals(listOf(TaskPolicy.ENGINEHOST_PACKAGE), closed)
    }

    @Test
    fun `a request to Android is not the end of a session`() {
        assertEquals(false, CloseOutcome.Requested("Not confirmed").sessionOver)
        assertEquals(false, CloseOutcome.Failed("Helper failed").sessionOver)
        assertEquals(true, CloseOutcome.Closed.sessionOver)
    }

    @Test
    fun `the app is put in the background just before the background kill and never when a helper stops it`() {
        closer(FakeOps(TaskPrivileges.NONE)).close("com.retroarch.aarch64")

        assertEquals(listOf("home", "kill"), order)

        order.clear()
        closer(FakeOps(TaskPrivileges(forceStop = true, shell = true), ForceStopResult.Stopped)).close("com.retroarch.aarch64")

        assertTrue(order.isEmpty())
    }

    @Test
    fun `Clear all does not count an app that had nothing running`() {
        val summary = ClearAllSummary.of(listOf(CloseOutcome.Closed, CloseOutcome.NotRunning(), CloseOutcome.Requested("Not confirmed")))

        assertEquals(ClearAllSummary(closed = 1, requested = 1, failed = 0), summary)
    }

    @Test
    fun `Enginehost is asked before a helper and other apps are never asked`() {
        endGameAnswer = true
        val ops = FakeOps(TaskPrivileges(forceStop = true, shell = true), ForceStopResult.Stopped)

        assertEquals(CloseOutcome.Closed, closer(ops).close(TaskPolicy.ENGINEHOST_PACKAGE))
        assertEquals(1, endGameAsks)
        assertTrue(ops.forceStops.isEmpty())
        closer(FakeOps(TaskPrivileges.NONE)).close("com.retroarch.aarch64")

        assertEquals(1, endGameAsks)
    }

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
        assertEquals(TaskPolicy.NOT_CONFIRMED, outcome.text)
        assertTrue(ops.forceStops.isEmpty())
        assertEquals(listOf("com.android.calendar"), killed)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a close that could only ask Android says why no helper ended it`() {
        val ops = FakeOps(TaskPrivileges.NONE, reason = "droidtop is not allowed in Shizuku yet")

        val outcome = closer(ops).close("com.retroarch.aarch64")

        assertTrue(outcome is CloseOutcome.Requested)
        assertEquals("Not confirmed. droidtop is not allowed in Shizuku yet", outcome.text)
        assertEquals(1, ops.connects)
    }

    @Test
    fun `a provider that fails carries its own words, and the kill is still tried`() {
        val ops = FakeOps(TaskPrivileges(forceStop = true, shell = false), ForceStopResult.Failed("Shizuku is not running"))

        val outcome = closer(ops).close("com.android.calendar")

        assertTrue(outcome is CloseOutcome.Failed)
        assertTrue(outcome.text.contains("Shizuku is not running"))
        assertTrue(outcome.text.contains("Android was asked to close it too"))
        assertEquals(listOf("com.android.calendar"), killed)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `when Android refuses even the ask it says so rather than claiming anything`() {
        val outcome = closer(FakeOps(TaskPrivileges.NONE), killWorks = false).close("com.android.calendar")

        assertTrue(outcome is CloseOutcome.Failed)
        assertEquals("Android refused", outcome.text)
    }

    @Test
    fun `close all closes each package in order`() {
        val ops = FakeOps(TaskPrivileges(forceStop = true, shell = false), ForceStopResult.Stopped)

        val summary = closer(ops).closeAll(listOf("a.one", "b.two", "c.three"))

        assertEquals(ClearAllSummary(closed = 3, requested = 0, failed = 0), summary)
        assertEquals(listOf("a.one", "b.two", "c.three"), ops.forceStops)
    }
}
