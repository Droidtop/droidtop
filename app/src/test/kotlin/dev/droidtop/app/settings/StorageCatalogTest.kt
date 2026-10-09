package dev.droidtop.app.settings

import dev.droidtop.library.stores.StoreGame
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Storage page lists installed games biggest first (docs/SPEC.md 7j "Places", Droidtop/tracker#227). */
class StorageCatalogTest {
    private fun game(id: String, title: String, bytes: Long, installed: Boolean = true) =
        StoreGame("gog", id, title, installed, if (installed) "/storage/emulated/0/Games/GOG/$title" else null, bytes, null)

    @Test
    fun `installed games come biggest first and games not installed are left out`() {
        val sorted = installedBySize(
            listOf(game("1", "Small", 10), game("2", "Big", 900), game("3", "Not here", 5000, installed = false), game("4", "Middle", 400)),
        )
        assertEquals(listOf("Big", "Middle", "Small"), sorted.map { it.title })
    }

    @Test
    fun `equal sizes fall back to the title, so the order does not jump between reads`() {
        val sorted = installedBySize(listOf(game("1", "banana", 0), game("2", "Apple", 0), game("3", "cherry", 0)))
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }
}
