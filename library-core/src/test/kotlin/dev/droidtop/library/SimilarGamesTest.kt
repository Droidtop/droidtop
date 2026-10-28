package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which games are offered as "the same game as this one" (docs/SPEC.md 7m). */
class SimilarGamesTest {

    private fun game(path: String, missing: Boolean = false) =
        LibraryEntry(id = path, title = path.substringAfterLast('/'), kind = LibraryEntryKind.RENPY, missing = missing)

    private val library = listOf(
        game("/games/renpy/StarHarbor-0.3-pc"),
        game("/games/renpy/Star_Harbour-0.4-pc"),
        game("/games/renpy/Moon Garden-1.0"),
        game("/games/renpy/StarHarbr Gone-0.1", missing = true),
    ) + LibraryEntry(id = "steam:1", title = "Star Harbour", kind = LibraryEntryKind.WINE_PROFILE)

    private val groups = LibraryGrouping.group(library)
    // By an entry the group holds: a folder's card is named in words, so a name alone can be a store row's too.
    private fun holding(id: String) = groups.first { id in it.entriesByPath }

    @Test
    fun `a game whose name drifted is offered, and nothing unlike it is`() {
        val candidates = SimilarGames.candidates(holding("/games/renpy/StarHarbor-0.3-pc"), groups)

        assertEquals(listOf("Star Harbour"), candidates.map { it.group.game.name })
        assertTrue(candidates.single().score >= GameNaming.NAME_SIMILARITY_THRESHOLD)
    }

    @Test
    fun `a store row and a game that is only missing are not offered`() {
        // The store row's name is the store's; the missing game is the
        // missing-game fold's question (docs/SPEC.md 7g).
        val names = SimilarGames.candidates(holding("/games/renpy/Star_Harbour-0.4-pc"), groups).map { it.group.game.name }

        assertEquals(listOf("Star Harbor"), names)
    }

    @Test
    fun `a store row is never the game asked about`() {
        assertTrue(SimilarGames.candidates(holding("steam:1"), groups).isEmpty())
    }
}
