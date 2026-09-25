package dev.droidtop.library

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The F95zone update source (docs/SPEC.md 7g, "Where an update comes from"), without a network. */
class F95UpdatesTest {

    @Test
    fun `a thread is read from the link a browser shows, the short form and the bare number`() {
        assertEquals(12345L, F95Thread.parse("https://f95zone.to/threads/star-harbor-v0-9-5-somedev.12345/"))
        assertEquals(12345L, F95Thread.parse("https://f95zone.to/threads/star-harbor-v0.9.5.12345/post-6789"))
        assertEquals(12345L, F95Thread.parse("f95zone.to/threads/12345"))
        assertEquals(12345L, F95Thread.parse("  12345 "))
        assertNull(F95Thread.parse("https://example.com/threads/12345"))
        assertNull(F95Thread.parse("star harbor"))
        assertNull(F95Thread.parse("0"))
    }

    private class FakeApi(
        var stamps: Map<Long, Long>,
        var versions: Map<Long, String?>,
        val refuse: Set<Long> = emptySet(),
        var fail: Boolean = false,
    ) : F95CheckerApi {
        val fastCalls = mutableListOf<List<Long>>()
        val fullCalls = mutableListOf<Long>()

        override fun fastCheck(threads: List<Long>): Map<Long, Long>? {
            fastCalls += threads
            if (fail) throw IOException("down")
            if (threads.any { it in refuse }) return null
            return threads.mapNotNull { t -> stamps[t]?.let { t to it } }.toMap()
        }

        override fun fullCheck(thread: Long, lastChanged: Long): F95CheckerApi.FullAnswer {
            fullCalls += thread
            return if (thread in versions) F95CheckerApi.FullAnswer.Found(versions[thread]) else F95CheckerApi.FullAnswer.Gone
        }
    }

    private class FakeLinks(links: Map<String, Long>) : GameLinksStore {
        val threadOf = links.toMutableMap()
        val checks = mutableMapOf<Long, F95ThreadCheck>()
        override suspend fun getAll(ids: Collection<String>): Map<String, GameLinks> =
            ids.mapNotNull { id -> threadOf[id]?.let { id to GameLinks(f95Thread = it, check = checks[it]) } }.toMap()
        override suspend fun setGameName(ids: Collection<String>, name: String) {}
        override suspend fun setF95Thread(ids: Collection<String>, thread: Long?) {
            ids.forEach { if (thread == null) threadOf.remove(it) else threadOf[it] = thread }
        }
        override suspend fun moveTo(fromId: String, toId: String) {}
        override suspend fun linkedThreads(): Map<Long, F95ThreadCheck?> = threadOf.values.toSet().associateWith { checks[it] }
        override suspend fun idsLinkedTo(thread: Long): List<String> = threadOf.filterValues { it == thread }.keys.toList()
        override suspend fun saveCheck(check: F95ThreadCheck) {
            checks[check.thread] = check
        }
    }

    private var clock = 1_000_000L
    private val pauses = mutableListOf<Long>()

    private fun check(store: GameLinksStore, api: F95CheckerApi) =
        F95UpdateCheck(store, api, now = { clock }, pause = { pauses += it })

    @Test
    fun `a first round asks about every linked thread and records what it said`() = runBlocking {
        val store = FakeLinks(mapOf("/g/a" to 1L, "/g/a2" to 1L, "/g/b" to 2L))
        val api = FakeApi(stamps = mapOf(1L to 100L, 2L to 200L), versions = mapOf(1L to "0.9.6", 2L to "1.0"))

        val outcome = check(store, api).round()!!

        assertEquals(2, outcome.asked)
        assertEquals(setOf("/g/a", "/g/a2", "/g/b"), outcome.changedIds)
        assertEquals("0.9.6", store.getAll(listOf("/g/a"))["/g/a"]?.latestKnown)
        assertEquals(listOf(listOf(1L, 2L)), api.fastCalls)
        // One fast check and two full checks, each after a pause.
        assertEquals(listOf(F95UpdateCheck.REQUEST_SPACING_MS, F95UpdateCheck.REQUEST_SPACING_MS), pauses)
    }

    @Test
    fun `a thread asked about recently is not asked again, and an unchanged one gets no full check`() = runBlocking {
        val store = FakeLinks(mapOf("/g/a" to 1L))
        val api = FakeApi(stamps = mapOf(1L to 100L), versions = mapOf(1L to "0.9.6"))
        val check = check(store, api)
        check.round()

        clock += 60_000L
        assertEquals(0, check.round()!!.asked)

        clock += F95UpdateCheck.CHECK_INTERVAL_MS
        val again = check.round()!!
        assertEquals(1, again.asked)
        assertTrue(again.changedIds.isEmpty())
        assertEquals(listOf(1L), api.fullCalls)
    }

    @Test
    fun `a thread that changed gets a full check and its new version`() = runBlocking {
        val store = FakeLinks(mapOf("/g/a" to 1L))
        val api = FakeApi(stamps = mapOf(1L to 100L), versions = mapOf(1L to "0.9.6"))
        val check = check(store, api)
        check.round()

        api.stamps = mapOf(1L to 150L)
        api.versions = mapOf(1L to "0.9.7")
        clock += F95UpdateCheck.CHECK_INTERVAL_MS
        val outcome = check.round()!!

        assertEquals(setOf("/g/a"), outcome.changedIds)
        assertEquals("0.9.7", store.getAll(listOf("/g/a"))["/g/a"]?.latestKnown)
    }

    @Test
    fun `a refused batch is asked one thread at a time, and the refused thread is gone`() = runBlocking {
        val store = FakeLinks(mapOf("/g/a" to 1L, "/g/b" to 2L))
        val api = FakeApi(stamps = mapOf(1L to 100L), versions = mapOf(1L to "0.9.6"), refuse = setOf(2L))

        check(store, api).round()

        assertEquals(listOf(listOf(1L, 2L), listOf(1L), listOf(2L)), api.fastCalls)
        assertEquals("0.9.6", store.checks[1L]?.version)
        assertTrue(store.checks[2L]!!.gone)
        assertNull(store.getAll(listOf("/g/b"))["/g/b"]?.latestKnown)
    }

    @Test
    fun `a person's check is one thread, and not twice a minute`() = runBlocking {
        val store = FakeLinks(mapOf("/g/a" to 1L, "/g/b" to 2L))
        val api = FakeApi(stamps = mapOf(1L to 100L, 2L to 200L), versions = mapOf(1L to "0.9.6", 2L to "1.0"))
        val check = check(store, api)

        assertEquals(1, check.round(only = 2L)!!.asked)
        assertEquals(listOf(listOf(2L)), api.fastCalls)
        clock += 30_000L
        assertEquals(0, check.round(only = 2L)!!.asked)
    }

    @Test
    fun `a failure changes nothing and says why`() = runBlocking {
        val store = FakeLinks(mapOf("/g/a" to 1L))
        val api = FakeApi(stamps = emptyMap(), versions = emptyMap(), fail = true)

        val outcome = check(store, api).round()!!

        assertEquals("down", outcome.error)
        assertTrue(store.checks.isEmpty())
    }
}
