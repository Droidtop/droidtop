package dev.droidtop.app.settings

import dev.droidtop.library.PcSource
import dev.droidtop.runtime.windows.PcLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a store page says about its library and when it last synced (docs/SPEC.md 7j "Places"). */
class StoresCatalogTest {

    private fun game(id: String, store: String, installed: Boolean) = PcLibrary.Game(
        id = id,
        source = PcSource.Store(store),
        nativeId = id,
        title = id,
        installed = installed,
        installPath = null,
        sizeBytes = 0L,
        artUrl = null,
    )

    private val games = listOf(
        game("a", "gog", installed = true),
        game("b", "gog", installed = false),
        game("c", "gog", installed = false),
        game("d", "epic", installed = true),
    )

    @Test
    fun `counts are per store and say how many are installed`() {
        assertEquals(StoreCounts(3, 1), storeCounts(games, "gog"))
        assertEquals(StoreCounts(1, 1), storeCounts(games, "epic"))
        assertEquals(StoreCounts(0, 0), storeCounts(games, "amazon"))
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
        assertEquals("Not synced yet", syncedAgo(now, null))
        assertEquals("Synced just now", syncedAgo(now, now - 30_000L))
        assertEquals("Synced 5 min ago", syncedAgo(now, now - 5 * minute))
        assertEquals("Synced 3 h ago", syncedAgo(now, now - 3 * 60 * minute))
        assertEquals("Synced 1 day ago", syncedAgo(now, now - 24 * 60 * minute))
        assertEquals("Synced 4 days ago", syncedAgo(now, now - 4 * 24 * 60 * minute))
        // A clock that went backwards is not a negative age.
        assertEquals("Synced just now", syncedAgo(now, now + 5 * minute))
    }

    @Test
    fun `a Game sources row says signed in and how many games, or that it is not`() {
        assertEquals("Not signed in", sourceValue(signedIn = false, games = 12))
        assertEquals("Signed in", sourceValue(signedIn = true, games = 0))
        assertEquals("Signed in · 412 games", sourceValue(signedIn = true, games = 412))
        assertEquals("1 game", gamesWord(1))
    }

    @Test
    fun `the Folders row and a folder's own row say what was found and whether it can be read`() {
        val counts = FolderCounts(
            listOf(RootCount("/sd/Games", 400, true, null), RootCount("/sd/More", 12, false, 1_000L)),
            shortcuts = 2,
        )
        assertEquals("2 folders · 412 games", FoldersCatalog.summary(counts))
        assertEquals("No folders yet", FoldersCatalog.summary(FolderCounts(emptyList(), 0)))
        assertEquals("400 games", FoldersCatalog.rootValue(counts.roots[0]))
        assertEquals("Not available", FoldersCatalog.rootValue(counts.roots[1]))
        assertEquals("/sd/Games · Not scanned yet. Select to stop looking here", FoldersCatalog.rootLine(counts.roots[0], 0L))
        assertEquals("/sd/More · Last scanned just now. Select to stop looking here", FoldersCatalog.rootLine(counts.roots[1], 1_000L))
    }
}
