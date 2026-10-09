package dev.droidtop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The absence of root is a STATE, never an exception: on a device with no
 * `su`, `ProcessBuilder.start()` throws, and that used to crash droidtop
 * at launch through ContainerRuntimeFactory.select.
 */
class RootAccessTest {

    @Test
    fun `a binary that cannot be started is a result, not a throw`() = runBlocking {
        val result = ProcessRunner.run(listOf("/definitely/not/a/binary/on/any/machine"))

        assertFalse(result.launched)
        assertFalse(result.succeeded)
        assertEquals(ProcessRunner.NOT_LAUNCHED, result.exitCode)
        assertTrue("stderr should name the command", result.stderr.contains("/definitely/not/a/binary"))
    }

    @Test
    fun `an empty command is a result too`() = runBlocking {
        // ProcessBuilder.start() indexes before it validates, so this one
        // throws ArrayIndexOutOfBoundsException rather than anything
        // documented -- it is answered ahead of start(), not caught.
        val result = ProcessRunner.run(emptyList())

        assertFalse(result.launched)
        assertEquals("no command to run", result.stderr)
    }

    @Test
    fun `a process that runs and fails still reports as launched`() = runBlocking {
        // `false` is POSIX and present wherever these tests run.
        val result = ProcessRunner.run(listOf("false"))

        assertTrue(result.launched)
        assertFalse(result.succeeded)
    }

    @Test
    fun `no helper is ABSENT, a helper not running as root is DENIED, uid 0 is AVAILABLE`() {
        assertEquals(
            RootAccess.ABSENT,
            RootProcess.accessOf(RootProcessResult(ProcessRunner.NOT_LAUNCHED, "", "no elevated helper")),
        )
        assertEquals(RootAccess.DENIED, RootProcess.accessOf(RootProcessResult(0, "2000\n", "")))
        assertEquals(RootAccess.DENIED, RootProcess.accessOf(RootProcessResult(1, "", "permission denied")))
        assertEquals(RootAccess.AVAILABLE, RootProcess.accessOf(RootProcessResult(0, "0\n", "")))
        assertFalse(RootAccess.ABSENT.available)
        assertFalse(RootAccess.DENIED.available)
        assertTrue(RootAccess.AVAILABLE.available)
    }

    @Test
    fun `root commands go to the helper as an argv, and no helper is a result`() = runBlocking {
        val asked = mutableListOf<List<String>>()
        val helper = object : dev.droidtop.runtime.tasks.PrivilegedShell {
            override fun forceStop(packageName: String): dev.droidtop.runtime.tasks.ForceStopResult =
                dev.droidtop.runtime.tasks.ForceStopResult.NoProvider
            override fun exec(argv: List<String>): dev.droidtop.runtime.tasks.ShellOutput? = null
            override fun spawn(argv: List<String>): Process {
                asked += argv
                return ProcessBuilder("echo", "0").start()
            }
        }
        val result = RootProcess.run(helper, listOf("sh", "-c", "printf %s \"\$1\"", "sh", "it's"), null)
        assertEquals(listOf(listOf("sh", "-c", "printf %s \"\$1\"", "sh", "it's")), asked)
        assertTrue(result.succeeded)

        val none = RootProcess.run(dev.droidtop.runtime.tasks.NoPrivilegedOps, listOf("id", "-u"), null)
        assertFalse(none.launched)
    }
}
