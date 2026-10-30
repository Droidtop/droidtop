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

        // The folder game and, since Droidtop/tracker#745, the store's row of the same name (a link, not a merge).
        assertEquals(setOf("/games/renpy/Star_Harbour-0.4-pc", "steam:1"), candidates.flatMap { it.group.entriesByPath.keys }.toSet())
        assertTrue(candidates.all { it.score >= GameNaming.NAME_SIMILARITY_THRESHOLD })
        assertEquals("steam:1", candidates.single { "steam:1" in it.group.entriesByPath }.store)
    }

    @Test
    fun `a game that is only missing is not offered, and a store row of an equal name comes first`() {
        // The missing game is the missing-game fold's question (docs/SPEC.md 7g); the store row is offered to link
        // the folders to (7m, "A store's copy and a local copy").
        val candidates = SimilarGames.candidates(holding("/games/renpy/Star_Harbour-0.4-pc"), groups)

        assertEquals(listOf("steam:1", null), candidates.map { it.store })
        assertEquals("Star Harbor", candidates[1].group.game.name)
    }

    @Test
    fun `a store row is never the game asked about`() {
        assertTrue(SimilarGames.candidates(holding("steam:1"), groups).isEmpty())
    }
}
