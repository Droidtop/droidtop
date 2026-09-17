package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which game is offered as the replacement of which (docs/SPEC.md 7g),
 * over folder names of the shape the user's own library has.
 */
class MissingGamesTest {

    private fun game(path: String, missing: Boolean = false) =
        LibraryEntry(id = path, title = path.substringAfterLast('/'), kind = LibraryEntryKind.RENPY, missing = missing)

    private val detected = listOf(
        game("/games/adult/renpy/Goodbye Eternity-0.9-pc"),
        game("/games/adult/renpy/love_of_magic_book2"),
        game("/games/adult/renpy/Lust Theory-2.0"),
    )

    @Test
    fun `a new version of the same game is the first candidate`() {
        val missing = game("/games/adult/renpy/GoodbyeEternity-0.8-pc", missing = true)

        val candidates = MissingGames.candidates(missing, detected)

        assertEquals("Goodbye Eternity", candidates.first().name)
        assertTrue(candidates.first().certain)
        assertEquals(1.0, candidates.first().score, 0.0)
    }

    @Test
    fun `a similar name is offered after the same name, never instead of it`() {
        val missing = game("/games/adult/renpy/Lust Academy-1.0", missing = true)

        val candidates = MissingGames.candidates(missing, detected)

        // Nothing here IS Lust Academy, so nothing is certain; Lust Theory
        // is 0.75 alike and is offered as a suggestion, which is what
        // Pythia does with the same pair (GameNaming's own doc comment).
        assertTrue(candidates.none { it.certain })
        assertEquals("Lust Theory", candidates.first().name)
        assertTrue(candidates.first().score >= GameNaming.NAME_SIMILARITY_THRESHOLD)
    }

    @Test
    fun `candidates come back most alike first`() {
        val missing = game("/games/adult/renpy/love_of_magic_book1", missing = true)

        val scores = MissingGames.candidates(missing, detected).map { it.score }

        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `a game is never a candidate to replace itself`() {
        val here = detected.first()

        assertTrue(MissingGames.candidates(here, detected).none { it.entry.id == here.id })
    }

    @Test
    fun `a name nothing resembles gets no candidates at all`() {
        val missing = game("/games/adult/renpy/Zzzz Quarantine-0.1", missing = true)

        assertTrue(MissingGames.candidates(missing, detected).isEmpty())
    }

    @Test
    fun `a store row is compared by the title its store gave it`() {
        val missing = LibraryEntry(
            id = "steam:440",
            title = "Team Fortress 2",
            kind = LibraryEntryKind.WINE_PROFILE,
            missing = true,
        )
        val folder = game("/games/pc/Team Fortress 2")

        val candidates = MissingGames.candidates(missing, listOf(folder))

        assertEquals(listOf(folder.id), candidates.map { it.entry.id })
        assertTrue(candidates.first().certain)
    }
}
