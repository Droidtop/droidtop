package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Store markers (docs/SPEC.md 7g, Droidtop/tracker#397 slice E): the PC
 * folder scan reads GOG's `goggame-<id>.info` and Epic's `.egstore` off the
 * listing it already has, and the folder game's row says which store's game
 * it is.
 */
class StoreMarkerScanTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(path: String, text: String) {
        File(temp.root, path).also { it.parentFile?.mkdirs() }.writeText(text)
    }

    @Test
    fun `a GOG offline install reads as GOG with its build and DLC`() {
        file("games/Witcher/witcher.exe", "MZ")
        file("games/Witcher/goggame-1207658924.info", """{"gameId":"1207658924","rootGameId":"1207658924","buildId":"5132","name":"The Witcher"}""")
        file("games/Witcher/goggame-1207659999.info", """{"gameId":"1207659999","rootGameId":"1207658924","name":"Some DLC"}""")
        val marker = PcFolderScan.storeMarkerOf(File(temp.root, "games/Witcher"))
        assertEquals(StoreMarker("gog", "1207658924", buildId = "5132", dlcIds = listOf("1207659999")), marker)
        assertEquals("gog:1207658924", marker?.key)
    }

    @Test
    fun `GOG ids written as numbers read the same`() {
        assertEquals(
            StoreMarker("gog", "1207658924", buildId = "51"),
            StoreMarkers.gogOf(listOf("""{"gameId":1207658924,"rootGameId":1207658924,"buildId":51}""")),
        )
    }

    @Test
    fun `a folder holding only DLC info files is the game they name`() {
        assertEquals(
            StoreMarker("gog", "100", dlcIds = listOf("200")),
            StoreMarkers.gogOf(listOf("""{"gameId":"200","rootGameId":"100"}""")),
        )
    }

    @Test
    fun `a Heroic or legendary Epic install reads as Epic by its catalog id`() {
        file("games/Hades/Hades.exe", "MZ")
        file("games/Hades/.egstore/ABCDEF0123.mancpn", """{"FormatVersion":0,"AppName":"Min","CatalogItemId":"cat-123","CatalogNamespace":"ns"}""")
        assertEquals(StoreMarker("epic", "cat-123"), PcFolderScan.storeMarkerOf(File(temp.root, "games/Hades")))
    }

    @Test
    fun `a folder without a marker, or with an unreadable one, is a plain folder game`() {
        file("games/Plain/game.exe", "MZ")
        assertNull(PcFolderScan.storeMarkerOf(File(temp.root, "games/Plain")))
        file("games/Broken/game.exe", "MZ")
        file("games/Broken/goggame-1.info", "not json")
        assertNull(PcFolderScan.storeMarkerOf(File(temp.root, "games/Broken")))
    }

    @Test
    fun `the marker is kept with the cached listing, so an unchanged folder is not read again`() {
        file("games/Witcher/witcher.exe", "MZ")
        file("games/Witcher/goggame-1.info", """{"gameId":"1","buildId":"7"}""")
        val folder = File(temp.root, "games/Witcher")
        val cache = PcFolderScan.ListingCache()
        val first = PcFolderScan.storeMarkerOf(folder, cache)
        // The file changes without the folder's listing changing: the kept answer stands.
        File(folder, "goggame-1.info").writeText("""{"gameId":"1","buildId":"8"}""")
        folder.setLastModified(folder.lastModified())
        assertEquals(first, PcFolderScan.storeMarkerOf(folder, cache))
        assertEquals("7", first?.buildId)
    }

    @Test
    fun `the walk still lists the game folder and never the egstore folder`() {
        file("games/Hades/Hades.exe", "MZ")
        file("games/Hades/.egstore/A.mancpn", """{"CatalogItemId":"cat"}""")
        assertEquals(listOf(File(temp.root, "games/Hades")), PcFolderScan.gamesUnder(File(temp.root, "games")))
    }

    @Test
    fun `a marker folder is its store's source`() {
        val entry = LibraryEntry(
            id = "folder:CUSTOM_GAME_5",
            title = "The Witcher",
            kind = LibraryEntryKind.WINE_PROFILE,
            pcInfo = PcInfo(
                storeId = "folder:CUSTOM_GAME_5",
                installed = true,
                installPath = "/sdcard/Games/Witcher",
                marker = StoreMarker("gog", "1207658924"),
            ),
        )
        assertEquals(PcSource.Store("gog"), PcSource.of(entry, listOf("/sdcard/Games")))
    }
}
