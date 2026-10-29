package dev.droidtop.stores

import dev.droidtop.library.stores.WishlistGame
import dev.droidtop.stores.steam.SteamWishlist
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamWishlistTest {
    @Test
    fun `the wished app ids come in Steam's order, each once`() {
        val body = """{"response":{"items":[
            {"appid":620,"priority":2,"date_added":1},
            {"appid":400,"priority":1,"date_added":2},
            {"appid":0,"priority":0},
            {"appid":730,"priority":0,"date_added":3},
            {"appid":400,"priority":5}
        ]}}"""
        assertEquals(listOf(730, 400, 620), SteamWishlist.parseIds(body))
    }

    @Test
    fun `an empty, private or missing answer is no ids`() {
        assertEquals(emptyList<Int>(), SteamWishlist.parseIds("""{"response":{}}"""))
        assertEquals(emptyList<Int>(), SteamWishlist.parseIds("""{}"""))
    }

    @Test
    fun `a known game keeps its name, and only so many new ones are looked up per sync`() {
        val ids = (1..SteamWishlist.NEW_PER_SYNC + 10).toList()
        val known = mapOf("1" to WishlistGame("1", "Known", "https://cover"))
        val asked = mutableListOf<Int>()
        val games = SteamWishlist.merge(ids, known) { id ->
            asked += id
            if (id == 3) null else WishlistGame(id.toString(), "Game $id")
        }
        assertEquals(WishlistGame("1", "Known", "https://cover"), games.first())
        assertEquals(SteamWishlist.NEW_PER_SYNC, asked.size)
        assertEquals((2..SteamWishlist.NEW_PER_SYNC + 1).toList(), asked)
        // The known one, plus every new one looked up except the one that failed (left for the next sync).
        assertEquals(1 + SteamWishlist.NEW_PER_SYNC - 1, games.size)
    }
}
