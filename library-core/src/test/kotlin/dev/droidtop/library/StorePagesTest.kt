package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorePagesTest {

    private fun entry(id: String, storeId: String? = null) = LibraryEntry(
        id = id,
        title = id,
        kind = LibraryEntryKind.CONSOLE_ROM,
        pcInfo = storeId?.let { PcInfo(source = "test", storeId = it, installed = true) },
    )

    @Test
    fun `a store row and a folder that absorbed a store install both carry an ownership`() {
        assertEquals(Ownership("steam", "440"), entry("steam:440").ownership())
        assertEquals(Ownership("gog", "1207"), entry("/games/x", storeId = "gog:1207").ownership())
    }

    @Test
    fun `a plain folder or an app id is owned by no store`() {
        assertNull(entry("/games/x").ownership())
        assertNull(entry("com.example.app").ownership())
        assertNull(entry("folder:CUSTOM_GAME_1").ownership())
    }

    @Test
    fun `ownership reads in the specs store order and names each store once`() {
        val owned = listOf(Ownership("gog", "1"), Ownership("steam", "2"), Ownership("gog", "3"))
        assertEquals("Owned on Steam and GOG", owned.ownershipLabel())
        assertEquals("Owned on Steam, GOG and Epic", (owned + Ownership("epic", "9")).ownershipLabel())
        assertEquals("", emptyList<Ownership>().ownershipLabel())
    }

    @Test
    fun `store and support links become their own rows and the rest stay plain`() {
        val links = listOf(
            GameLink("Steam", "https://store.steampowered.com/app/440"),
            GameLink("Official site", "https://example.org/game"),
            GameLink("Patreon", "https://www.patreon.com/dev"),
            GameLink("Store", "https://dev.itch.io/game"),
            GameLink("Epic", "https://store.epicgames.com/p/game"),
        )
        assertEquals(listOf("Get it on Steam", "Get it on itch.io", "Get it on Epic"), StorePages.getItOn(links).map { it.label })
        assertEquals(listOf("https://www.patreon.com/dev"), StorePages.support(links).map { it.url })
        assertEquals(listOf("Official site"), StorePages.other(links).map { it.label })
    }
}
