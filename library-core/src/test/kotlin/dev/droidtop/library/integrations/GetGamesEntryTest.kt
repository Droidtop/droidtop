package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where "Get games" is offered and what a search says about its sources (docs/SPEC.md 12a "Get games
 * everywhere"). Pure rules, no Android: the screens themselves are exercised on a device.
 */
class GetGamesEntryTest {

    private fun result(id: String) = AcquireContentResult(id, id, null, null, null, null, emptyList(), "{}")

    private fun source(label: String): GameSourceProvider = object : GameSourceProvider {
        override val id = "fake_$label"
        override val label = label
        override fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: java.io.File?) =
            dev.droidtop.library.settings.CatalogScreen("fake_detail", result.title, groups = { emptyList() })
        override suspend fun search(context: android.content.Context, query: String, platform: String?) = Result.success(emptyList<AcquireContentResult>())
    }

    @Test
    fun `every context has an answer, so no menu is left without the entry`() {
        GetGamesContext.entries.forEach { context ->
            // Either a real system or a question; never a blank id that would open an empty list.
            val system = GetGamesEntry.systemFor(context, "snes")
            assertTrue("$context", system == null || system == "snes")
        }
    }

    @Test
    fun `contexts about one system open that system's sources`() {
        assertEquals("snes", GetGamesEntry.systemFor(GetGamesContext.SYSTEM, "snes"))
        assertEquals("snes", GetGamesEntry.systemFor(GetGamesContext.GAME_PAGE, "snes"))
        assertEquals("snes", GetGamesEntry.systemFor(GetGamesContext.EMPTY_STATE, "snes"))
        assertEquals("snes", GetGamesEntry.systemFor(GetGamesContext.SEARCH, " snes "))
    }

    @Test
    fun `contexts that span systems ask which system, even when handed one`() {
        listOf(
            GetGamesContext.LIBRARY, GetGamesContext.COLLECTION, GetGamesContext.PC,
            GetGamesContext.APPS,
        ).forEach { assertNull("$it", GetGamesEntry.systemFor(it, "snes")) }
    }

    @Test
    fun `the PC list and a missing or blank id are never a download destination`() {
        assertNull(GetGamesEntry.systemFor(GetGamesContext.SYSTEM, GetGamesEntry.PC_SYSTEM_ID))
        assertNull(GetGamesEntry.systemFor(GetGamesContext.SYSTEM, null))
        assertNull(GetGamesEntry.systemFor(GetGamesContext.GAME_PAGE, "  "))
    }

    @Test
    fun `no source anywhere is its own state, not no match`() {
        assertEquals(GetMoreState.NO_SOURCE, GetMoreState.of(emptyList(), emptyList()))
    }

    @Test
    fun `an installed source that cannot answer is not ready, not absent`() {
        val waiting = listOf(UnavailableSource("Source", "is waiting for your approval"))
        assertEquals(GetMoreState.NOT_READY, GetMoreState.of(emptyList(), waiting))
    }

    @Test
    fun `sources that answered with nothing are no match`() {
        val outcomes = listOf(SourceOutcome(source("A"), emptyList()), SourceOutcome(source("B"), emptyList()))
        assertEquals(GetMoreState.NO_MATCH, GetMoreState.of(outcomes, emptyList()))
    }

    @Test
    fun `a failed source is reported even when another found nothing`() {
        val outcomes = listOf(SourceOutcome(source("A"), emptyList()), SourceOutcome(source("B"), emptyList(), failure = "did not answer in time"))
        assertEquals(GetMoreState.FAILED, GetMoreState.of(outcomes, emptyList()))
    }

    @Test
    fun `a hit outranks a failure elsewhere`() {
        val outcomes = listOf(
            SourceOutcome(source("A"), listOf(result("a1"))),
            SourceOutcome(source("B"), emptyList(), failure = "did not answer in time"),
        )
        assertEquals(GetMoreState.FOUND, GetMoreState.of(outcomes, listOf(UnavailableSource("C", "is turned off"))))
    }

    @Test
    fun `each search state has its own entry subtitle`() {
        val lines = GetMoreState.entries.map { GetGamesEntry.searchSubtitle(it) }
        assertTrue(lines.all { it.isNotBlank() })
        assertNotEquals(GetGamesEntry.searchSubtitle(GetMoreState.NO_SOURCE), GetGamesEntry.searchSubtitle(GetMoreState.NOT_READY))
        assertTrue(GetGamesEntry.searchSubtitle(GetMoreState.NO_SOURCE).contains("Plugins"))
    }
}
