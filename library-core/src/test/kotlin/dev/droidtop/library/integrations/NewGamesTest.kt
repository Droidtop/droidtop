package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Get new games opens on (docs/SPEC.md 13; Droidtop/tracker#612): only what the library and the sources say. */
class NewGamesTest {
    private fun game(title: String, plays: Int = 0, developer: String? = null, series: String? = null, update: String? = null, hidden: Boolean = false) =
        LibraryEntry(
            id = "/games/$title",
            title = title,
            kind = LibraryEntryKind.UNITY,
            playCount = plays,
            developer = developer,
            series = series,
            availableUpdate = update,
            hidden = hidden,
        )

    private fun find(title: String) = NewGames.Find(
        "Source",
        AcquireContentResult(id = title, title = title, subtitle = null, sizeLabel = null, platform = null, artUrl = null, raw = "{}"),
    )

    @Test
    fun `the most played games are asked about, by series first and else developer, each name once`() {
        val asks = NewGames.asks(
            listOf(
                game("Little", plays = 2, developer = "Studio A"),
                game("Most", plays = 9, developer = "Studio B", series = "Saga"),
                game("Next", plays = 5, developer = "Studio B"),
                game("Again", plays = 4, developer = "studio b"),
            ),
        )
        assertEquals(
            listOf(NewGames.Ask("Saga", "Most"), NewGames.Ask("Studio B", "Next"), NewGames.Ask("Studio A", "Little")),
            asks,
        )
    }

    @Test
    fun `games never played, hidden, or with nothing to look for are not asked about`() {
        val asks = NewGames.asks(
            listOf(
                game("Unplayed", plays = 0, developer = "Studio A"),
                game("Hidden", plays = 3, developer = "Studio B", hidden = true),
                game("Nameless", plays = 7),
            ),
        )
        assertEquals(emptyList<NewGames.Ask>(), asks)
    }

    @Test
    fun `an answer the library already has, under any spelling, is left out, and so is a repeat`() {
        val kept = NewGames.fresh(
            listOf(find("The Witcher: Enhanced Edition"), find("New Game"), find("new game"), find("Other")),
            owned = listOf(game("Witcher")),
        )
        assertEquals(listOf("New Game", "Other"), kept.map { it.result.title })
    }

    @Test
    fun `new versions are the games a source names a newer one of, by title, hidden ones left out`() {
        val updates = NewGames.updates(
            listOf(game("b game", update = "1.2"), game("A game", update = "0.9"), game("Current"), game("Secret", update = "2", hidden = true)),
        )
        assertEquals(listOf("A game", "b game"), updates.map { it.title })
    }

    @Test
    fun `a source's featured list alone is something to show`() {
        val featured = NewGames.Featured("F95zone", listOf(find("New Game")))
        assertEquals(false, NewGames.Loaded(listOf(featured), emptyList(), emptyList(), sources = 1).empty)
        assertEquals(true, NewGames.Loaded(emptyList(), emptyList(), emptyList(), sources = 1).empty)
        // Sources that all failed are "could not reach", not "nothing new"; no source at all is neither.
        assertEquals(true, NewGames.Loaded(emptyList(), emptyList(), emptyList(), sources = 2, answered = 0).unreachable)
        assertEquals(false, NewGames.Loaded(emptyList(), emptyList(), emptyList(), sources = 2, answered = 1).unreachable)
        assertEquals(false, NewGames.Loaded(emptyList(), emptyList(), emptyList(), sources = 0, answered = 0).unreachable)
    }
}
