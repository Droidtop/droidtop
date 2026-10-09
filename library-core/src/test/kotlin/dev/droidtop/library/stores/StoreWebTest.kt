package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreWebTest {
    private val steam = StoreWebPages(
        home = "https://store.steampowered.com/",
        hosts = listOf("steampowered.com", "steamcommunity.com"),
        searchPage = "https://store.steampowered.com/search/?term=",
    )

    @Test
    fun `a store's pages cover its sites and their subdomains over https only`() {
        assertTrue(steam.covers("https://store.steampowered.com/app/440/"))
        assertTrue(steam.covers("https://checkout.steampowered.com/checkout/"))
        assertTrue(steam.covers("https://steamcommunity.com/id/someone"))
        assertFalse(steam.covers("http://store.steampowered.com/app/440/"))
        assertFalse(steam.covers("https://steampowered.com.example.net/"))
        assertFalse(steam.covers("https://notsteampowered.com/"))
        assertFalse(steam.covers("not an address"))
    }

    @Test
    fun `a search page carries the query encoded`() {
        assertEquals("https://store.steampowered.com/search/?term=half+life+%26+more", steam.search("  half life & more "))
    }

    @Test
    fun `an order page is recognised from its address on the store's own sites`() {
        assertTrue(StoreWeb.isOrderDone("https://checkout.steampowered.com/checkout/receipt/?transid=1", steam))
        assertTrue(StoreWeb.isOrderDone("https://store.steampowered.com/checkout/?purchasetype=self#success", steam))
        assertFalse(StoreWeb.isOrderDone("https://store.steampowered.com/app/440/", steam))
        assertFalse(StoreWeb.isOrderDone("https://store.steampowered.com/cart/", steam))
        // The same words on another site are not the store's order.
        assertFalse(StoreWeb.isOrderDone("https://example.com/checkout/success", steam))
    }

    @Test
    fun `the games a sync added are the keys that were not there before, by title`() {
        val before = mapOf("steam:1" to "Alpha")
        val after = mapOf("steam:1" to "Alpha", "steam:3" to "charlie", "steam:2" to "Bravo")
        assertEquals(listOf("Bravo", "charlie"), StoreWeb.added(before, after))
        assertEquals(emptyList<String>(), StoreWeb.added(after, after))
    }

    @Test
    fun `the added line names a few games and counts the rest`() {
        assertNull(StoreWeb.addedLine("Steam", emptyList()))
        assertEquals("Added to your library from Steam: Alpha", StoreWeb.addedLine("Steam", listOf("Alpha")))
        assertEquals(
            "Added to your library from GOG: A, B and 2 more",
            StoreWeb.addedLine("GOG", listOf("A", "B", "C", "D")),
        )
    }
}
