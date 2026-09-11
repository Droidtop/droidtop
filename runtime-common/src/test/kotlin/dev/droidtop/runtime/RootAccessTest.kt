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
        assertFalse(ProcessRunner.run(emptyList()).launched)
    }

    @Test
    fun `a process that runs and fails still reports as launched`() = runBlocking {
        // `false` is POSIX and present wherever these tests run.
        val result = ProcessRunner.run(listOf("false"))

        assertTrue(result.launched)
        assertFalse(result.succeeded)
    }

    @Test
    fun `no su is ABSENT, a refusing su is DENIED, success is AVAILABLE`() {
        assertEquals(
            RootAccess.ABSENT,
            RootProcess.accessOf(RootProcessResult(ProcessRunner.NOT_LAUNCHED, "", "could not start su")),
        )
        assertEquals(RootAccess.DENIED, RootProcess.accessOf(RootProcessResult(1, "", "permission denied")))
        assertEquals(RootAccess.AVAILABLE, RootProcess.accessOf(RootProcessResult(0, "uid=0(root)", "")))
        assertFalse(RootAccess.ABSENT.available)
        assertFalse(RootAccess.DENIED.available)
        assertTrue(RootAccess.AVAILABLE.available)
    }
}
