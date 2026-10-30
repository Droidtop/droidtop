package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game page's resources row (Droidtop/tracker#363): searches by the game's name. */
class GameResourcesTest {
    @Test
    fun `each resource searches for the game by name, encoded`() {
        val links = GameResources.forGame("  Hollow Knight: Silksong ")
        assertEquals(listOf("PCGamingWiki", "Guides", "Search the web"), links.map { it.label })
        assertEquals("https://www.pcgamingwiki.com/w/index.php?search=Hollow+Knight%3A+Silksong", links[0].url)
        assertEquals("https://gamefaqs.gamespot.com/search?game=Hollow+Knight%3A+Silksong", links[1].url)
        assertEquals("https://duckduckgo.com/?q=Hollow+Knight%3A+Silksong+walkthrough", links[2].url)
    }

    @Test
    fun `a game with no name has no resources`() {
        assertTrue(GameResources.forGame(" ").isEmpty())
    }
}
