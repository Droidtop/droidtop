package dev.droidtop.library.integrations

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking, merging and streaming of the one search (docs/SPEC.md 12a
 * "One search"), against fake sources so no plugin connection is needed.
 */
class UnifiedSearchTest {

    private fun result(id: String, title: String = id) = AcquireContentResult(
        id = id, title = title, subtitle = null, sizeLabel = null, platform = null,
        artUrl = null, options = emptyList(), raw = "{}",
    )

    private fun source(
        id: String,
        delayMs: Long = 0,
        throws: Boolean = false,
        results: List<AcquireContentResult> = emptyList(),
    ): GameSourceProvider = object : GameSourceProvider {
        override val id = id
        override val label = id.uppercase()
        override fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: java.io.File?) =
            dev.droidtop.library.settings.CatalogScreen("fake_detail", result.title, groups = { emptyList() })
        override suspend fun search(context: android.content.Context, query: String, platform: String?): Result<List<AcquireContentResult>> {
            delay(delayMs)
            if (throws) error("$id failed")
            return Result.success(results)
        }
    }

    private fun local(kind: SearchRowKind, title: String) = LocalSearchRow(title, kind, title) {}

    private fun run(
        query: String,
        local: List<LocalSearchRow> = emptyList(),
        sources: List<GameSourceProvider> = emptyList(),
        carry: List<SourceHit> = emptyList(),
        timeoutMs: Long = 2_000,
        keep: (AcquireContentResult) -> Boolean = { true },
    ): Flow<UnifiedState> = UnifiedSearch.stream(
        query = query,
        local = { local },
        carry = carry,
        keep = keep,
        debounceMs = 0,
        timeoutMs = timeoutMs,
        loadSources = { sources to emptyList() },
        fetch = { it.search(android.content.ContextWrapper(null), query, null) },
    )

    @Test
    fun `match quality ranks before kind and before source`() {
        assertEquals(SearchRank.EXACT, SearchRank.score("Sonic", "sonic"))
        assertEquals(SearchRank.PREFIX, SearchRank.score("Sonic Mania", "sonic"))
        assertEquals(SearchRank.WORD, SearchRank.score("Super Sonic Racing", "sonic"))
        assertEquals(SearchRank.WORD, SearchRank.score("Mega Man X", "man x"))
        assertEquals(SearchRank.CONTAINS, SearchRank.score("Ultrasonic", "sonic"))
        assertEquals(SearchRank.OTHER, SearchRank.score("Tetris", "sonic"))
    }

    @Test
    fun `a source exact match outranks a device substring match, and ties go to the device`() {
        val s = source("s")
        val hits = listOf(
            SourceHit(s, result("1", "Sonic")),
            SourceHit(s, result("2", "Sonic Mania")),
        )
        val rows = UnifiedSearch.rank(
            "sonic",
            listOf(local(SearchRowKind.GAME, "Ultrasonic"), local(SearchRowKind.GAME, "Sonic"), local(SearchRowKind.APP, "Sonic")),
            listOf(SourceOutcome(s, hits.map { it.result })),
            emptyList(),
            listOf(s),
        )
        // The library's Sonic and the source's Sonic are one game: one row, the device's in front (docs/SPEC.md 12a).
        assertEquals(
            listOf("APP:Sonic", "GAME:Sonic", "SOURCE:Sonic Mania", "GAME:Ultrasonic"),
            rows.map { "${it.kind}:${it.title}" },
        )
        assertEquals(listOf("GAME:Sonic", "SOURCE:Sonic"), rows[1].choices.map { "${it.kind}:${it.title}" })
    }

    @Test
    fun `a source keeps its own order for rows that tie and is capped`() {
        val s = source("s")
        val many = (1..25).map { result("r$it", "Sonic $it") }
        val rows = UnifiedSearch.rank("sonic", emptyList(), listOf(SourceOutcome(s, many)), emptyList(), listOf(s))
        assertEquals(UnifiedSearch.MAX_ROWS_PER_SOURCE, rows.size)
        assertEquals((1..UnifiedSearch.MAX_ROWS_PER_SOURCE).map { "r$it" }, rows.map { it.hit!!.result.id })
    }

    @Test
    fun `two sources tie in the order they were asked, as the places of one row`() {
        val a = source("a")
        val b = source("b")
        val rows = UnifiedSearch.rank(
            "x", emptyList(),
            listOf(SourceOutcome(b, listOf(result("b1", "X"))), SourceOutcome(a, listOf(result("a1", "X")))),
            emptyList(), listOf(a, b),
        )
        assertEquals(listOf("a1", "b1"), rows.single().choices.map { it.hit!!.result.id })
    }

    @Test
    fun `the device's rows are emitted before any source answers, then each source joins as it answers`() = runBlocking {
        val fast = source("fast", results = listOf(result("f", "Sonic Fast")))
        val slow = source("slow", delayMs = 150, results = listOf(result("s", "Sonic Slow")))
        val states = run("sonic", local = listOf(local(SearchRowKind.GAME, "Sonic")), sources = listOf(fast, slow)).toList()
        assertEquals(listOf("Sonic"), states.first().rows.map { it.title })
        assertFalse(states.first().settled)
        val withFastOnly = states.first { s -> s.sourceHits.any { it.source.id == "fast" } && s.sourceHits.none { it.source.id == "slow" } }
        assertFalse(withFastOnly.settled)
        assertTrue(withFastOnly.sources.first { it.source.id == "slow" }.pending)
        val last = states.last()
        assertTrue(last.settled)
        assertEquals(listOf("Sonic", "Sonic Fast", "Sonic Slow"), last.rows.map { it.title })
    }

    @Test
    fun `a failing or hung source is reported and never stops the others`() = runBlocking {
        val ok = source("ok", results = listOf(result("o", "Sonic OK")))
        val broken = source("broken", throws = true)
        val hung = source("hung", delayMs = 5_000)
        val last = run("sonic", sources = listOf(ok, broken, hung), timeoutMs = 60).toList().last()
        assertTrue(last.settled)
        assertEquals(listOf("Sonic OK"), last.rows.map { it.title })
        assertEquals("broken failed", last.sources.first { it.source.id == "broken" }.failure)
        assertTrue(last.sources.first { it.source.id == "hung" }.failure != null)
    }

    @Test
    fun `a blank query is an empty settled state and asks nothing`() = runBlocking {
        var asked = false
        val state = UnifiedSearch.stream(
            query = "  ", local = { asked = true; emptyList() }, debounceMs = 0,
            loadSources = { asked = true; emptyList<GameSourceProvider>() to emptyList() },
            fetch = { asked = true; Result.success(emptyList()) },
        ).first()
        assertTrue(state.settled)
        assertTrue(state.rows.isEmpty())
        assertFalse(asked)
    }

    @Test
    fun `a surface can drop the results it has no use for`() = runBlocking {
        val s = source("s", results = listOf(result("pc1", "Sonic PC").copy(platform = "Windows"), result("psp1", "Sonic PSP").copy(platform = "PSP")))
        val last = run("sonic", sources = listOf(s), keep = { it.platform != "PSP" }).toList().last()
        assertEquals(listOf("pc1"), last.rows.map { it.hit!!.result.id })
    }

    @Test
    fun `no source installed settles with the device's rows alone`() = runBlocking {
        val last = run("sonic", local = listOf(local(SearchRowKind.APP, "Sonic Browser"))).toList().last()
        assertTrue(last.settled)
        assertEquals(listOf("Sonic Browser"), last.rows.map { it.title })
    }

    @Test
    fun `the previous query's source rows stay until their source answers`() = runBlocking {
        val s = source("s", delayMs = 100, results = listOf(result("new", "Sonic Mania")))
        val old = SourceHit(s, result("old", "Sonic"))
        val unrelated = SourceHit(s, result("gone", "Tetris"))
        val states = run("sonic m", sources = listOf(s), carry = listOf(old, unrelated)).toList()
        // "Sonic" does not contain "sonic m", so it is not carried; neither is the unrelated one.
        assertTrue(states.first().sourceHits.isEmpty())
        val carried = run("sonic", sources = listOf(s), carry = listOf(old, unrelated)).toList()
        assertEquals(listOf("old"), carried.first().sourceHits.map { it.result.id })
        assertEquals(listOf("new"), carried.last().sourceHits.map { it.result.id })
    }

    @Test
    fun `cancelling the collector stops a slow source`() = runBlocking {
        var finished = false
        val slow = object : GameSourceProvider by source("slow") {
            override suspend fun search(context: android.content.Context, query: String, platform: String?): Result<List<AcquireContentResult>> {
                delay(10_000)
                finished = true
                return Result.success(emptyList())
            }
        }
        try {
            withTimeout(200) { run("sonic", sources = listOf(slow)).toList() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        }
        delay(50)
        assertFalse(finished)
    }

    @Test
    fun `the same game from the library and a source is one row that offers both`() {
        // Owner, 2026-10-16: "Clicking on the game entry should give the game's page, or options if it's available
        // from multiple sources".
        val rows = UnifiedSearch.rank(
            "love",
            listOf(LocalSearchRow("lib", SearchRowKind.GAME, "Love and Sex: Second Base", "Game") {}),
            listOf(SourceOutcome(source("f95"), listOf(result("1", "Love & Sex: Second Base")))),
            emptyList(),
            listOf(source("f95")),
        )
        assertEquals(1, rows.size)
        assertEquals("lib", rows.single().key)
        assertEquals(2, rows.single().choices.size)
        assertEquals("Game · F95", rows.single().detail)
    }

    @Test
    fun `a game found only on a source is new, one the library has too is not`() {
        // Owner, 2026-10-23: "Get new games should be presented under the search results" (Droidtop/tracker#612).
        val rows = UnifiedSearch.rank(
            "love",
            listOf(LocalSearchRow("lib", SearchRowKind.GAME, "Love and Sex: Second Base", "Game") {}),
            listOf(SourceOutcome(source("f95"), listOf(result("1", "Love & Sex: Second Base"), result("2", "Lovely Day")))),
            emptyList(),
            listOf(source("f95")),
        )
        assertFalse(rows.single { it.key == "lib" }.isNew)
        assertTrue(rows.single { it.title == "Lovely Day" }.isNew)
    }

    @Test
    fun `different games and apps are never merged`() {
        val rows = UnifiedSearch.rank(
            "zelda",
            listOf(
                LocalSearchRow("app", SearchRowKind.APP, "Zelda", "App") {},
                LocalSearchRow("game", SearchRowKind.GAME, "Zelda", "Game") {},
            ),
            listOf(SourceOutcome(source("s"), listOf(result("2", "Zelda II")))),
            emptyList(),
            listOf(source("s")),
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.others.isEmpty() })
    }

    @Test
    fun `a source the person turned off is not asked, and is listed as off`() = runBlocking {
        val on = source("on", results = listOf(result("o", "Sonic On")))
        val off = source("off", results = listOf(result("x", "Sonic Off")))
        val last = UnifiedSearch.stream(
            query = "sonic", local = { emptyList() }, debounceMs = 0, timeoutMs = 2_000,
            loadSources = { listOf(on, off) to emptyList() },
            fetch = { it.search(android.content.ContextWrapper(null), "sonic", null) },
            isOff = { it.id == "off" },
        ).toList().last()
        assertEquals(listOf("Sonic On"), last.rows.map { it.title })
        assertEquals(listOf("on"), last.sources.map { it.source.id })
        assertEquals(listOf("off"), last.off.map { it.id })
    }

    @Test
    fun `with no query, what the sources feature takes turns and one game from two sources is one card`() = runBlocking {
        val a = source("a")
        val b = source("b")
        val features = mapOf(
            "a" to listOf(result("a1", "Alpha"), result("a2", "Shared Game")),
            "b" to listOf(result("b1", "Beta"), result("b2", "Shared Game")),
        )
        val last = UnifiedSearch.stream(
            query = "", local = { listOf(local(SearchRowKind.GAME, "Not asked")) }, debounceMs = 0, timeoutMs = 2_000,
            loadSources = { listOf(a, b) to emptyList() },
            fetch = { error("a search is not asked with no query") },
            featured = { Result.success(features.getValue(it.id)) },
        ).toList().last()
        assertTrue(last.featured)
        assertTrue(last.settled)
        assertEquals(listOf("Alpha", "Beta", "Shared Game"), last.rows.map { it.title })
        assertEquals(listOf("a2", "b2"), last.rows[2].choices.map { it.hit!!.result.id })
    }

    @Test
    fun `with no query and no featured asked, the search is empty`() = runBlocking {
        val last = run("", sources = listOf(source("s", results = listOf(result("r", "R"))))).toList().last()
        assertTrue(last.rows.isEmpty())
        assertFalse(last.featured)
    }

    @Test
    fun `the filter keeps the sources turned off, so a new source is on`() {
        assertEquals(setOf("a"), SourceSelection.toggled(emptySet(), "a", on = false))
        assertEquals(emptySet<String>(), SourceSelection.toggled(setOf("a"), "a", on = true))
        assertEquals(setOf("a"), SourceSelection.toggled(setOf("a"), "b", on = true))
    }
}
