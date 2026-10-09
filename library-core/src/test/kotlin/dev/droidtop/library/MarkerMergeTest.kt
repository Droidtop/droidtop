package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A copy a store installed outside droidtop is one card with the account's
 * row of the same game (docs/SPEC.md 7g, "Store markers", Droidtop/tracker#397
 * slice F): by the store id its marker names, never by its title.
 */
class MarkerMergeTest {

    private fun folder(n: Int, path: String, marker: StoreMarker? = null) = LibraryEntry(
        id = "folder:CUSTOM_GAME_$n",
        title = path.substringAfterLast('/'),
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = "folder:CUSTOM_GAME_$n", installed = true, installPath = path, marker = marker),
    )

    private fun store(id: String, title: String) = LibraryEntry(
        id = id,
        title = title,
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = id, installed = false),
    )

    private fun groups(vararg entries: LibraryEntry) = LibraryGrouping.group(entries.toList(), roots = listOf("/sd/Games"))

    @Test
    fun `an offline GOG install and the account's GOG row are one card under the store's name`() {
        val offline = folder(1, "/sd/Games/witcher_enhanced", StoreMarker("gog", "1207658924", buildId = "5132"))
        val account = store("gog:1207658924", "The Witcher: Enhanced Edition")
        val result = groups(offline, account)
        assertEquals(1, result.size)
        val card = result.single()
        assertEquals("The Witcher: Enhanced Edition", card.game.name)
        assertEquals(setOf(offline.id, account.id), card.entriesByPath.keys)
        // What is here is what the card stands on, so Play starts the folder.
        assertEquals(offline.id, card.displayEntry.id)
    }

    @Test
    fun `a Heroic-installed Epic game joins the Epic row by its catalog id`() {
        val heroic = folder(2, "/sd/Games/Hades", StoreMarker("epic", "cat-hades"))
        val result = groups(heroic, store("epic:cat-hades", "Hades"), store("epic:cat-other", "Other"))
        assertEquals(2, result.size)
        assertEquals(setOf(heroic.id, "epic:cat-hades"), result.first { it.game.name == "Hades" }.entriesByPath.keys)
    }

    @Test
    fun `without the account's row the marker folder stays its own card`() {
        val offline = folder(3, "/sd/Games/witcher", StoreMarker("gog", "1"))
        val result = groups(offline, store("gog:2", "Something Else"))
        assertEquals(2, result.size)
        assertEquals(setOf(offline.id), result.first { offline.id in it.entriesByPath }.entriesByPath.keys)
    }

    @Test
    fun `a folder with no marker never joins a store row, even under the same title`() {
        val plain = folder(4, "/sd/Games/Hades")
        val result = groups(plain, store("epic:cat-hades", "Hades"))
        assertEquals(2, result.size)
    }

    @Test
    fun `two folders with the same marker join the one card`() {
        val a = folder(5, "/sd/Games/witcher", StoreMarker("gog", "9"))
        val b = folder(6, "/sd/More/witcher", StoreMarker("gog", "9"))
        val result = groups(a, b, store("gog:9", "The Witcher"))
        assertEquals(1, result.size)
        assertEquals(setOf(a.id, b.id, "gog:9"), result.single().entriesByPath.keys)
    }

    @Test
    fun `a marker folder signed out has no ownership, signed in the account row holds it`() {
        val offline = folder(7, "/sd/Games/witcher", StoreMarker("gog", "1"))
        assertNull(offline.ownership())
        assertEquals(PcSource.Store("gog"), PcSource.of(offline, listOf("/sd/Games")))
    }
}
