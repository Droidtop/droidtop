package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which search result is the game: a wrong game's description is worse than none (tracker#251). */
class TheGamesDbMatchTest {

    @Test
    fun `an exact title beats earlier fuzzy results`() {
        val titles = listOf("Alpha Quest Genesis", "Alpha Quest Crystal Edition", "Alpha Quest")
        assertEquals(1, TheGamesDbClient.bestMatchIndex(titles, "Alpha Quest - Crystal Edition"))
    }

    @Test
    fun `no title that is the game means no match instead of the first result`() {
        val titles = listOf("Beta Saga Genesis", "Beta Saga Online")
        assertNull(TheGamesDbClient.bestMatchIndex(titles, "Beta Saga - Crystal Edition"))
    }

    @Test
    fun `accents and dashes do not stop a match`() {
        assertEquals(0, TheGamesDbClient.bestMatchIndex(listOf("Café Quest: Gold Edition"), "Cafe Quest - Gold Edition"))
    }

    @Test
    fun `a title with a subtitle matches on whole words when two words are shared`() {
        assertEquals(0, TheGamesDbClient.bestMatchIndex(listOf("Delta Force Rising"), "Delta Force"))
    }

    @Test
    fun `a one word title never matches a longer one`() {
        assertNull(TheGamesDbClient.bestMatchIndex(listOf("Echo Reborn", "Echo Online"), "Echo"))
        assertNull(TheGamesDbClient.bestMatchIndex(listOf("Echo"), "Echo Reborn Part Two"))
    }

    @Test
    fun `a No-Intro trailing article matches the database title`() {
        assertEquals(0, TheGamesDbClient.bestMatchIndex(listOf("The Golf Story: Gold"), "Golf Story, The - Gold"))
    }

    @Test
    fun `a result on another platform is never the game`() {
        assertEquals(true, TheGamesDbClient.onPlatform("41", "41"))
        assertEquals(true, TheGamesDbClient.onPlatform("", "41"))
        assertEquals(false, TheGamesDbClient.onPlatform("8", "41"))
        // findMetadata gives an off-platform result an empty title, which matches nothing.
        assertNull(TheGamesDbClient.bestMatchIndex(listOf(""), "Alpha Quest"))
    }

    @Test
    fun `a partial word is not a prefix`() {
        assertNull(TheGamesDbClient.bestMatchIndex(listOf("Foxtrot Racing Deluxe"), "Foxtrot Racing Delux"))
    }
}
