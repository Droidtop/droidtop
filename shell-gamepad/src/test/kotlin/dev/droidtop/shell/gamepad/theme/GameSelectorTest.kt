package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks for the real `<gameselector>` `selection` port. Expected values
 * are derived by hand from `GameSelectorComponent::refreshGames()`
 * (GameSelectorComponent.h:51-129), its `applyTheme` value mapping
 * (:144-165) and the two ordered lists it reads
 * (`FileData::updateLastPlayedList`/`updateMostPlayedList`,
 * FileData.cpp:906-940) -- not from recorded output.
 */
class GameSelectorTest {

    private fun entry(id: String, lastPlayed: Long?, playCount: Int) = LibraryEntry(
        id = id,
        title = id,
        kind = LibraryEntryKind.CONSOLE_ROM,
        lastPlayedEpochMs = lastPlayed,
        playCount = playCount,
    )

    private val games = listOf(
        entry("never", lastPlayed = null, playCount = 0),
        entry("old", lastPlayed = 100L, playCount = 3),
        entry("recent", lastPlayed = 900L, playCount = 1),
        entry("middle", lastPlayed = 500L, playCount = 7),
    )

    /** GameSelectorComponent.h:144-165 -- three real values, everything else is random. */
    @Test
    fun `selection parsing covers exactly the three real values`() {
        assertEquals(EsDeGameSelection.RANDOM, EsDeGameSelection.parse("random"))
        assertEquals(EsDeGameSelection.LAST_PLAYED, EsDeGameSelection.parse("lastplayed"))
        assertEquals(EsDeGameSelection.MOST_PLAYED, EsDeGameSelection.parse("mostplayed"))
        assertEquals(EsDeGameSelection.RANDOM, EsDeGameSelection.parse(null))
        // ES-DE logs a warning and keeps RANDOM rather than failing.
        assertEquals(EsDeGameSelection.RANDOM, EsDeGameSelection.parse("similar"))
    }

    /**
     * GameSelectorComponent.h:99-113 -- descending by last-played time,
     * and a game with `lastplayed == "0"` (never played) is skipped
     * entirely rather than sorted to the bottom.
     */
    @Test
    fun `lastplayed orders by recency and drops never-played games`() {
        val selected = GameSelector.select(games, gameCount = 4, allowDuplicates = false, selection = EsDeGameSelection.LAST_PLAYED)
        assertEquals(listOf("recent", "middle", "old"), selected.map { it.id })
    }

    /** GameSelectorComponent.h:114-128 -- descending by play count, zero-count games skipped. */
    @Test
    fun `mostplayed orders by play count and drops unplayed games`() {
        val selected = GameSelector.select(games, gameCount = 4, allowDuplicates = false, selection = EsDeGameSelection.MOST_PLAYED)
        assertEquals(listOf("middle", "old", "recent"), selected.map { it.id })
    }

    /** GameSelectorComponent.h:110-111 -- the list is cut at gameCount. */
    @Test
    fun `ordered selections respect gameCount`() {
        val selected = GameSelector.select(games, gameCount = 2, allowDuplicates = false, selection = EsDeGameSelection.MOST_PLAYED)
        assertEquals(listOf("middle", "old"), selected.map { it.id })
    }

    /**
     * GameSelectorComponent.h:72-74 -- without allowDuplicates the loop
     * breaks out once every game has been drawn, so the result is capped
     * at the library size instead of being padded with repeats. droidtop
     * used to pad here.
     */
    @Test
    fun `random without duplicates never repeats and never pads`() {
        val selected = GameSelector.select(games, gameCount = 10, allowDuplicates = false, random = Random(1))
        assertEquals(4, selected.size)
        assertEquals(4, selected.map { it.id }.toSet().size)
    }

    /** GameSelectorComponent.h:88-90 -- allowDuplicates lets the same game fill several slots. */
    @Test
    fun `random with duplicates fills every requested slot`() {
        val selected = GameSelector.select(games, gameCount = 10, allowDuplicates = true, random = Random(1))
        assertEquals(10, selected.size)
    }

    /** An empty system yields nothing at all, in every mode. */
    @Test
    fun `an empty library selects nothing`() {
        for (mode in EsDeGameSelection.entries) {
            assertEquals(emptyList<LibraryEntry>(), GameSelector.select(emptyList(), 5, false, mode))
        }
        assertEquals(emptyList<LibraryEntry>(), GameSelector.select(games, 0, false))
    }
}
