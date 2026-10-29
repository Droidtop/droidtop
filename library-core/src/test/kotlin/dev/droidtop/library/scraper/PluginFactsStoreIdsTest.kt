package dev.droidtop.library.scraper

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.StoreMarker
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `storeIds` fact a metadata plugin's `match` gets (docs/plugin-api.md 3 A3, Droidtop/tracker#568). */
class PluginFactsStoreIdsTest {
    private fun entry(pc: PcInfo) = LibraryEntry(id = pc.storeId ?: "folder:CUSTOM_GAME_1", title = "Game", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = pc)

    @Test
    fun `a store row gives its own id and the ids it names`() {
        val row = entry(PcInfo(storeId = "gog:1207658924", installed = true, externalIds = mapOf("steam" to "620")))
        assertEquals(mapOf("gog" to "1207658924", "steam" to "620"), PcScraper.storeIdsOf(row, emptyMap()))
    }

    @Test
    fun `a folder game gives its marker, and the person's links win`() {
        val folder = entry(PcInfo(storeId = "folder:CUSTOM_GAME_1", installed = true, marker = StoreMarker("gog", "1")))
        assertEquals(mapOf("gog" to "1"), PcScraper.storeIdsOf(folder, emptyMap()))
        assertEquals(mapOf("gog" to "2", "f95" to "12345"), PcScraper.storeIdsOf(folder, mapOf("gog" to "2", "f95" to "12345")))
    }
}
