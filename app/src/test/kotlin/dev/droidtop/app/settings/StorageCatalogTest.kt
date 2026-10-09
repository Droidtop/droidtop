package dev.droidtop.app.settings

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
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

    private fun pc(id: String, title: String, path: String?, hidden: Boolean = false, kind: LibraryEntryKind = LibraryEntryKind.WINE_PROFILE) =
        LibraryEntry(id = id, title = title, kind = kind, hidden = hidden, pcInfo = PcInfo(storeId = id, installed = true, installPath = path))

    @Test
    fun `folder games are listed once each by title, store installs and ROMs are not`() {
        val rows = folderGameRows(
            listOf(
                pc("folder:CUSTOM_GAME_2", "Zeta", "/sd/Games/Zeta"),
                pc("folder:CUSTOM_GAME_1", "alpha", "/sd/Games/Alpha"),
                // A second entry of the same folder (a part) is one row.
                pc("folder:CUSTOM_GAME_3", "Alpha part", "/sd/Games/Alpha"),
                // A store's install is listed under its store.
                pc("steam:440", "TF2", "/sd/Games/Steam/TF2"),
                LibraryEntry(id = "/sd/Games/snes/a.sfc", title = "A ROM", kind = LibraryEntryKind.CONSOLE_ROM),
                pc("folder:CUSTOM_GAME_4", "Hidden one", "/sd/Games/Hidden", hidden = true),
            ),
        )
        assertEquals(listOf("alpha", "Hidden one", "Zeta"), rows.map { it.title })
        assertEquals(listOf(false, true, false), rows.map { it.hidden })
    }

    @Test
    fun `hidden games are one row`() {
        assertEquals("1 hidden game", hiddenRowTitle(1))
        assertEquals("3 hidden games", hiddenRowTitle(3))
    }
}
