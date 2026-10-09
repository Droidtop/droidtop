package dev.droidtop.library.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RaMatchingTest {
    private fun game(id: Int, title: String, vararg hashes: String) = RaGame(id, title, 1, 10, 100, hashes.toList())

    @Test
    fun `titles compare without region tags, articles, punctuation or case`() {
        assertEquals(RaMatching.normalize("The Legend of Zelda: A Link to the Past"), RaMatching.normalize("Legend of Zelda, The - A Link to the Past (USA) [!]"))
        assertEquals(RaMatching.normalize("Sonic & Knuckles"), RaMatching.normalize("sonic and knuckles"))
        assertEquals("sonic3", RaMatching.normalize("Sonic 3 ~Hack~"))
    }

    @Test
    fun `a name matches only when exactly one game has it`() {
        val games = listOf(game(1, "Sonic the Hedgehog"), game(2, "Sonic the Hedgehog 2"), game(3, "Alex Kidd"), game(4, "Alex Kidd ~Hack~"))
        assertEquals(1, RaMatching.byName(games, "Sonic The Hedgehog (USA, Europe)")?.id)
        assertNull(RaMatching.byName(games, "Sonic"))
        assertNull(RaMatching.byName(games, "Alex Kidd"))
        assertNull(RaMatching.byName(games, ""))
    }

    @Test
    fun `the game list survives being written and read back`() {
        val games = listOf(game(1, "A", "aabb", "ccdd"), game(2, "B"))
        assertEquals(games, RetroAchievementsClient.parseGameList(RaCache.serializeGames(games)))
    }
}
