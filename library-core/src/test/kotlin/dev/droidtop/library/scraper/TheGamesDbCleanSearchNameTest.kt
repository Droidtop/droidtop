package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

/** [TheGamesDbClient.cleanSearchName]: bracketed region and revision tags are dropped from a search term. */
class TheGamesDbCleanSearchNameTest {
    @Test
    fun `parenthesised tags are removed and the rest trimmed`() {
        assertEquals("Pokemon - Emerald Version", TheGamesDbClient.cleanSearchName("Pokemon - Emerald Version (USA, Europe)"))
        assertEquals("Zelda", TheGamesDbClient.cleanSearchName("Zelda (Japan) (En,Ja)"))
    }

    @Test
    fun `square bracket tags are removed too`() {
        assertEquals("Sonic", TheGamesDbClient.cleanSearchName("Sonic [!] (USA) (Rev A)"))
    }

    @Test
    fun `a name that is nothing but a tag is kept whole`() {
        assertEquals("(USA)", TheGamesDbClient.cleanSearchName("(USA)"))
        assertEquals("Tetris", TheGamesDbClient.cleanSearchName("Tetris"))
    }
}
