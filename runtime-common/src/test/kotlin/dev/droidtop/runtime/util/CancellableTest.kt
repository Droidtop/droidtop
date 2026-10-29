package dev.droidtop.runtime.util

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#766: a cancelled block is never read as a failed one. */
class CancellableTest {
    @Test
    fun `a failure is kept and a value is returned`() {
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
        assertTrue(runCatchingCancellable { error("no answer") }.isFailure)
    }

    @Test(expected = CancellationException::class)
    fun `a cancellation is thrown on`() {
        runCatchingCancellable { throw CancellationException("stopped") }
    }
}
