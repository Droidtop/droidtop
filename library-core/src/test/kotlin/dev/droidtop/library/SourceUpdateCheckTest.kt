package dev.droidtop.library

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generic update round over source links (docs/SPEC.md 7g, "Where an update comes from"), with fake sources. */
class SourceUpdateCheckTest {

    private class FakeSources(
        val keys: List<String>,
        var answers: Map<String, UpdateSources.Answer>,
        val failing: Set<String> = emptySet(),
    ) : UpdateSources {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override fun sources() = keys.map { UpdateSources.Source(it, it, null) }
        override suspend fun check(source: String, externalIds: List<String>): Map<String, UpdateSources.Answer> {
            calls += source to externalIds
            if (source in failing) throw IOException("$source is down")
            return externalIds.mapNotNull { id -> answers["$source/$id"]?.let { id to it } }.toMap()
        }
        override suspend fun resolve(source: String, text: String): UpdateSources.Found? = null
        override suspend fun match(source: String, title: String, versions: List<String>) = emptyList<UpdateSources.Found>()
    }

    private class FakeLinks(links: Map<String, SourceKey>) : GameLinksStore {
        val linkOf = links.toMutableMap()
        val answers = mutableMapOf<SourceKey, SourceAnswer>()
        override suspend fun getAll(ids: Collection<String>): Map<String, GameLinks> =
            ids.mapNotNull { id ->
                linkOf[id]?.let { id to GameLinks(sources = listOf(SourceLink(it.source, it.externalId, answers[it]))) }
            }.toMap()
        override suspend fun setGameName(ids: Collection<String>, name: String?) {}
        override suspend fun setSourceLink(ids: Collection<String>, source: String, externalId: String?) {
            ids.forEach { if (externalId == null) linkOf.remove(it) else linkOf[it] = SourceKey(source, externalId) }
        }
        override suspend fun moveTo(fromId: String, toId: String) {}
        override suspend fun linkedSources(): Map<SourceKey, SourceAnswer?> = linkOf.values.toSet().associateWith { answers[it] }
        override suspend fun idsLinkedTo(key: SourceKey): List<String> = linkOf.filterValues { it == key }.keys.toList()
        override suspend fun saveAnswer(key: SourceKey, answer: SourceAnswer) {
            answers[key] = answer
        }
    }

    private var clock = 1_000_000L

    private fun check(store: GameLinksStore, sources: UpdateSources) = SourceUpdateCheck(store, sources, now = { clock })

    @Test
    fun `a first round asks each source once about its own links and records the answers`() = runBlocking {
        val store = FakeLinks(mapOf("a" to SourceKey("forum", "1"), "b" to SourceKey("forum", "2"), "c" to SourceKey("feed", "x")))
        val sources = FakeSources(
            listOf("forum", "feed"),
            mapOf("forum/1" to UpdateSources.Answer.Version("v0.9.6", "https://example.org/1"), "forum/2" to UpdateSources.Answer.Gone, "feed/x" to UpdateSources.Answer.Version("2.0", null)),
        )
        val outcome = check(store, sources).round()!!
        assertEquals(3, outcome.asked)
        assertEquals(setOf("a", "b", "c"), outcome.changedIds)
        assertEquals(2, sources.calls.size)
        assertEquals("v0.9.6", store.answers[SourceKey("forum", "1")]?.version)
        assertEquals("https://example.org/1", store.answers[SourceKey("forum", "1")]?.url)
        assertTrue(store.answers[SourceKey("forum", "2")]!!.gone)
        assertEquals("v0.9.6", store.getAll(listOf("a"))["a"]?.latestKnown)
        assertNull(store.getAll(listOf("b"))["b"]?.latestKnown)
    }

    @Test
    fun `a record is not asked again before it is due, and an unchanged answer changes no entry`() = runBlocking {
        val store = FakeLinks(mapOf("a" to SourceKey("forum", "1")))
        val sources = FakeSources(listOf("forum"), mapOf("forum/1" to UpdateSources.Answer.Version("1.0", null)))
        val round = check(store, sources)
        round.round()
        clock += 60 * 60_000L
        assertEquals(0, round.round()!!.asked)
        clock += SourceUpdateCheck.CHECK_INTERVAL_MS
        val again = round.round()!!
        assertEquals(1, again.asked)
        assertEquals(emptySet<String>(), again.changedIds)
    }

    @Test
    fun `check now asks about one record, but not twice within a minute`() = runBlocking {
        val store = FakeLinks(mapOf("a" to SourceKey("forum", "1"), "b" to SourceKey("forum", "2")))
        val sources = FakeSources(listOf("forum"), mapOf("forum/1" to UpdateSources.Answer.Version("1.0", null)))
        val round = check(store, sources)
        assertEquals(1, round.round(only = SourceKey("forum", "1"))!!.asked)
        assertEquals(listOf("forum" to listOf("1")), sources.calls)
        clock += 30_000L
        assertEquals(0, round.round(only = SourceKey("forum", "1"))!!.asked)
    }

    @Test
    fun `a failing source stops only its own part, and a source that is not installed is not asked`() = runBlocking {
        val store = FakeLinks(mapOf("a" to SourceKey("forum", "1"), "b" to SourceKey("feed", "x"), "c" to SourceKey("gone-plugin", "7")))
        val sources = FakeSources(listOf("forum", "feed"), mapOf("feed/x" to UpdateSources.Answer.Version("2.0", null)), failing = setOf("forum"))
        val outcome = check(store, sources).round()!!
        assertEquals("forum is down", outcome.error)
        assertEquals(setOf("b"), outcome.changedIds)
        assertNull(store.answers[SourceKey("forum", "1")])
        assertTrue(sources.calls.none { it.first == "gone-plugin" })
        assertEquals("The source for this link is not installed or not running", check(store, sources).round(only = SourceKey("gone-plugin", "7"))!!.error)
    }
}
