package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistTest {
    @Test
    fun `a wishlist round-trips and a title with a tab or a newline stays one line`() {
        val games = listOf(
            WishlistGame("1207658691", "Alpha\tBeta", "https://img/a.webp"),
            WishlistGame("42", "No cover\nhere"),
        )
        val back = Wishlist.parse(Wishlist.format(games))
        assertEquals(listOf("Alpha Beta", "No cover here"), back.map { it.title })
        assertEquals("https://img/a.webp", back[0].artUrl)
        assertEquals(null, back[1].artUrl)
    }

    @Test
    fun `a line without an id or a title is dropped`() {
        assertEquals(1, Wishlist.parse("\t\tx\n7\tSeven\t\n9\t\t\n").size)
    }

    @Test
    fun `rows are not installed and are held as wishlist`() {
        val row = Wishlist.rows("gog", listOf(WishlistGame("5", "Five", "u"))).single()
        assertEquals("gog:5", row.key)
        assertEquals(StoreHolding.WISHLIST, row.holding)
        assertTrue(!row.installed && row.installPath == null)
    }

    private fun owned(store: String, id: String, title: String) =
        StoreGame(store, id, title, installed = false, installPath = null, sizeBytes = 0, artUrl = null)

    @Test
    fun `a wished game the person already has, in any store, is left off`() {
        val wished = Wishlist.rows(
            "gog",
            listOf(WishlistGame("1", "Hades"), WishlistGame("2", "Disco Elysium: The Final Cut"), WishlistGame("3", "Celeste")),
        )
        val have = listOf(
            owned("steam", "1145360", "HADES"),
            owned("gog", "3", "Celeste"),
        )
        assertEquals(listOf("Disco Elysium: The Final Cut"), Wishlist.notOwned(wished, have).map { it.title })
    }

    @Test
    fun `an edition label does not make a wished game a different one`() {
        val wished = Wishlist.rows("gog", listOf(WishlistGame("9", "Cyberpunk 2077 Ultimate Edition")))
        assertTrue(Wishlist.notOwned(wished, listOf(owned("steam", "1091500", "Cyberpunk 2077"))).isEmpty())
        // a different number is a different game
        assertEquals(1, Wishlist.notOwned(Wishlist.rows("gog", listOf(WishlistGame("8", "Doom 3"))), listOf(owned("steam", "2280", "Doom"))).size)
    }
}
