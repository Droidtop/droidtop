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

    @Test
    fun `guarded answers the fallback for an exception and throws a cancellation and an error on`() {
        assertEquals(listOf(1), guarded("t", "listing", emptyList()) { listOf(1) })
        assertEquals(emptyList<Int>(), guarded("t", "listing", emptyList<Int>()) { error("no answer") })
        try {
            guarded("t", "listing", 0) { throw CancellationException("stopped") }
            org.junit.Assert.fail("a cancellation is thrown on")
        } catch (expected: CancellationException) {
        }
        try {
            guarded("t", "listing", 0) { throw StackOverflowError() }
            org.junit.Assert.fail("an error is thrown on")
        } catch (expected: StackOverflowError) {
        }
    }
}
