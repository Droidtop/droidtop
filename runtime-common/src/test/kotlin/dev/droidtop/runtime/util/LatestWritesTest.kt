package dev.droidtop.runtime.util

import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The companion's write queue for live controls (Droidtop/tracker#786 S2a). */
class LatestWritesTest {
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private fun queue() = LatestWrites(CoroutineScope(SupervisorJob() + worker))

    @Test
    fun `a drag across the track ends at where the finger stopped, with fewer writes than steps`() = runBlocking {
        val writes = queue()
        val written = Collections.synchronizedList(mutableListOf<Int>())
        // The first write is still running while the finger moves on: every later step but the last is replaced.
        val started = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        writes.submit("volume") { started.complete(Unit); held.await(); written += 0 }
        started.await()
        for (step in 1 until 200) writes.submit("volume") { written += step }
        held.complete(Unit)
        writes.idle()
        assertEquals(listOf(0, 199), written.toList())
    }

    @Test
    fun `different controls all land`() = runBlocking {
        val writes = queue()
        val written = Collections.synchronizedMap(mutableMapOf<String, Int>())
        listOf("volume", "brightness", "media").forEachIndexed { index, key -> writes.submit(key) { written[key] = index } }
        writes.idle()
        assertEquals(mapOf("volume" to 0, "brightness" to 1, "media" to 2), written.toMap())
    }

    @Test
    fun `a write that fails does not stop the next`() = runBlocking {
        val writes = queue()
        var ran = false
        writes.submit("a") { error("no access") }
        writes.submit("b") { ran = true }
        writes.idle()
        assertTrue(ran)
        // And the queue still takes writes afterwards.
        var again = false
        writes.submit("a") { again = true }
        writes.idle()
        assertTrue(again)
    }
}
