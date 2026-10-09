package dev.droidtop.app.settings

import dev.droidtop.runtime.windows.PcLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a store page says about its library and when it last synced (docs/SPEC.md 7j "Places"). */
class StoresCatalogTest {

    private fun game(id: String, source: PcLibrary.Source, installed: Boolean) = PcLibrary.Game(
        id = id,
        source = source,
        nativeId = id,
        title = id,
        installed = installed,
        installPath = null,
        sizeBytes = 0L,
        artUrl = null,
    )

    private val games = listOf(
        game("a", PcLibrary.Source.GOG, installed = true),
        game("b", PcLibrary.Source.GOG, installed = false),
        game("c", PcLibrary.Source.GOG, installed = false),
        game("d", PcLibrary.Source.EPIC, installed = true),
    )

    @Test
    fun `counts are per store and say how many are installed`() {
        assertEquals(StoreCounts(3, 1), storeCounts(games, PcLibrary.Source.GOG))
        assertEquals(StoreCounts(1, 1), storeCounts(games, PcLibrary.Source.EPIC))
        assertEquals(StoreCounts(0, 0), storeCounts(games, PcLibrary.Source.AMAZON))
    }

    @Test
    fun `the library line is honest about an empty or signed out store`() {
        assertEquals("3 games, 1 installed", countsLine(StoreCounts(3, 1), signedIn = true))
        // The family and free counts have rows of their own, so this one stays short enough to fit.
        assertEquals("3 games, 1 installed", countsLine(StoreCounts(3, 1, family = 2, free = 5), signedIn = true))
        assertEquals("1 game, 0 installed", countsLine(StoreCounts(1, 0), signedIn = true))
        assertEquals("No games read from this store yet", countsLine(StoreCounts(0, 0), signedIn = true))
        assertEquals("Sign in to read this store's library", countsLine(StoreCounts(0, 0), signedIn = false))
    }

    @Test
    fun `the synced line uses the coarsest honest unit`() {
        val now = 10_000_000_000L
        val minute = 60_000L
        assertEquals("Not synced from here yet", syncedAgo(now, null))
        assertEquals("Synced just now", syncedAgo(now, now - 30_000L))
        assertEquals("Synced 5 min ago", syncedAgo(now, now - 5 * minute))
        assertEquals("Synced 3 h ago", syncedAgo(now, now - 3 * 60 * minute))
        assertEquals("Synced 1 day ago", syncedAgo(now, now - 24 * 60 * minute))
        assertEquals("Synced 4 days ago", syncedAgo(now, now - 4 * 24 * 60 * minute))
        // A clock that went backwards is not a negative age.
        assertEquals("Synced just now", syncedAgo(now, now + 5 * minute))
    }
}
