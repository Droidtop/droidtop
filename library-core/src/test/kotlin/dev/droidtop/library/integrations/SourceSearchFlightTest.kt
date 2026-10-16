package dev.droidtop.library.integrations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** One `search` per source in flight, the newest asked next (docs/plugin-api.md "Fetchers answer in the one search"). */
class SourceSearchFlightTest {
    private fun flight() = SourceSearchFlight(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Test
    fun `a second search to a busy source waits for the first to answer`() = runBlocking {
        val flight = flight()
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()
        val first = async { flight.run("f95") { sent += "a"; release.await(); "A" } }
        delay(50)
        val second = async { flight.run("f95") { sent += "b"; "B" } }
        delay(50)
        assertEquals("nothing more is sent while one runs", listOf("a"), sent.toList())
        release.complete(Unit)
        assertEquals("A", first.await())
        assertEquals("B", second.await())
        assertEquals(listOf("a", "b"), sent.toList())
    }

    @Test
    fun `a search left while it waits is never sent, and the newest is`() = runBlocking {
        val flight = flight()
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()
        launch { flight.run("nyaa") { sent += "q1"; release.await() } }
        delay(50)
        val typedPast = launch { flight.run("nyaa") { sent += "q2" } }
        delay(50)
        typedPast.cancelAndJoin()
        val newest = async { flight.run("nyaa") { sent += "q3" } }
        delay(50)
        release.complete(Unit)
        withTimeout(2_000) { newest.await() }
        assertEquals(listOf("q1", "q3"), sent.toList())
    }

    @Test
    fun `a search left mid-call keeps its source busy until the plugin answers`() = runBlocking {
        val flight = flight()
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val left = launch { flight.run("romgi") { sent += "old"; release.await(); finished.complete(Unit) } }
        delay(50)
        left.cancelAndJoin()
        val next = async { flight.run("romgi") { sent += "new" } }
        delay(50)
        assertEquals("the plugin's call was not interrupted, so nothing else is sent", listOf("old"), sent.toList())
        release.complete(Unit)
        withTimeout(2_000) { next.await() }
        assertTrue("the left call still ran to its end", finished.isCompleted)
        assertEquals(listOf("old", "new"), sent.toList())
    }

    @Test
    fun `different sources are asked at the same time`() = runBlocking {
        val flight = flight()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val slow = async { flight.run("repacks") { release.await(); 1 } }
        val other = async { flight.run("abandonware") { started.complete(Unit); 2 } }
        withTimeout(2_000) { started.await() }
        assertFalse(slow.isCompleted)
        release.complete(Unit)
        assertEquals(1, slow.await())
        assertEquals(2, other.await())
    }

    @Test
    fun `a failing call frees its source`() = runBlocking {
        val flight = flight()
        val failed = runCatching { flight.run("bios") { error("site down") } }
        assertTrue(failed.isFailure)
        assertEquals("ok", withTimeout(2_000) { flight.run("bios") { "ok" } })
    }
}
