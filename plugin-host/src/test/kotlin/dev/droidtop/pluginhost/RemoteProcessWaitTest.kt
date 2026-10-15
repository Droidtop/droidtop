package dev.droidtop.pluginhost

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Droidtop/tracker#412: a Shizuku remote process says "still running" with an IllegalArgumentException. */
class RemoteProcessWaitTest {
    /** A process that exits with [code] after [pollsBeforeExit] polls, saying "still running" the way [running] does. */
    private class FakeProcess(private val code: Int, private val pollsBeforeExit: Int, private val running: () -> RuntimeException) : Process() {
        var polls = 0
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = code
        override fun exitValue(): Int {
            if (polls++ < pollsBeforeExit) throw running()
            return code
        }
        override fun destroy() = Unit
    }

    private var clock = 0L
    private val now = { clock }
    private val sleep = { ms: Long -> clock += ms }

    @Test
    fun `a remote process still running is waited for, not an error`() {
        // What Binder delivers for Shizuku's server-side IllegalThreadStateException.
        val remote = FakeProcess(0, pollsBeforeExit = 3) { IllegalArgumentException("process hasn't exited") }
        assertEquals(0, RemoteProcessWait.exitWithin(remote, 5_000, now, sleep))
        assertEquals(4, remote.polls)
    }

    @Test
    fun `a local process still running is waited for too, and its exit value is kept`() {
        val local = FakeProcess(7, pollsBeforeExit = 2) { IllegalThreadStateException("process hasn't exited") }
        assertEquals(7, RemoteProcessWait.exitWithin(local, 5_000, now, sleep))
    }

    @Test
    fun `a process that does not end in time is null, after waiting the whole time`() {
        val stuck = FakeProcess(0, pollsBeforeExit = Int.MAX_VALUE) { IllegalArgumentException("process hasn't exited") }
        assertNull(RemoteProcessWait.exitWithin(stuck, 1_000, now, sleep))
        assertEquals(1_000L, clock)
    }

    @Test(expected = IllegalStateException::class)
    fun `any other failure is not taken for still running`() {
        RemoteProcessWait.exitWithin(FakeProcess(0, 1) { IllegalStateException("binder died") }, 1_000, now, sleep)
    }
}
