package dev.droidtop.app.settings

import dev.droidtop.library.settings.AsyncActionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keys and accounts (Droidtop/tracker#12): one row per key, grouped by kind, each with where it is used and Remove. */
class KeysPageTest {
    private fun key(id: String, title: String) = HeldKey(id, title, "used for $title") { "removed $title" }

    @Test
    fun `keys are grouped by kind in the order met, one row each, saying where they are used`() {
        val groups = keysGroups(
            listOf(
                "Game sources" to key("store_steam", "Steam sign-in"),
                "Accounts" to key("github", "GitHub token"),
                "Game sources" to key("store_itch", "itch.io API key"),
            ),
        )
        assertEquals(listOf("Game sources", "Accounts"), groups.map { it.title })
        assertEquals(listOf("keys_store_steam", "keys_store_itch"), groups[0].items.map { it.id })
        val row = groups[0].items[1] as AsyncActionItem
        assertEquals("itch.io API key", row.title)
        assertEquals("used for itch.io API key", row.subtitle)
        assertEquals("Remove", row.value)
        assertEquals("Remove itch.io API key?", row.confirmTitle)
    }

    @Test
    fun `holding nothing says so in one line`() {
        val groups = keysGroups(emptyList())
        assertNull(groups.single().title)
        assertEquals("droidtop holds no keys or sign-ins", groups.single().items.single().title)
    }
}
