package dev.droidtop.library.integrations

import dev.droidtop.library.integrations.UnifiedSearch.pacedAtMost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Droidtop/tracker#915: four sources answering together sent the result list many new states in a burst, each one
 * rebuilt on the main thread, and fast typing in Get games gave an ANR on a device short of memory.
 */
class PacedResultsTest {
    @Test
    fun `a burst of states is applied a few times, the first at once and the last always`() = runTest {
        // 40 states 10 ms apart (four sources: starting, ready, answered, and the device rows), 400 ms in all.
        val burst = flow { repeat(40) { emit(it); delay(10) } }
        val applied = burst.pacedAtMost(150).toList()
        assertEquals(0, applied.first())
        assertEquals(39, applied.last())
        assertTrue("applied ${applied.size} times: $applied", applied.size <= 5)
    }

    @Test
    fun `states further apart than the pace all go through`() = runTest {
        val slow = flow { repeat(4) { emit(it); delay(500) } }
        assertEquals(listOf(0, 1, 2, 3), slow.pacedAtMost(150).toList())
    }
}
