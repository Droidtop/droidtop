package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which prefix a game starts in, and the folder scanner's forgetting (Droidtop/tracker#731). */
class PcPrefixIdsTest {
    @Test
    fun `a store game's own prefix is the id gamenative gave it`() {
        assertEquals("STEAM_440", PcContainers.ownId("steam:440"))
        assertEquals("GOG_1207658691", PcContainers.ownId("gog:1207658691"))
        assertEquals("EPIC_abc", PcContainers.ownId("epic:abc"))
        assertEquals("AMAZON_x1", PcContainers.ownId("amazon:x1"))
        // A folder game's entry id is the scanner's own app id.
        assertEquals("CUSTOM_GAME_7", PcContainers.ownId("folder:CUSTOM_GAME_7"))
    }

    @Test
    fun `anything gamenative never named gets a stable id of droidtop's own`() {
        val one = PcContainers.ownId("itch:12345")
        assertTrue(one.startsWith("DROIDTOP_"))
        assertEquals(one, PcContainers.ownId("itch:12345"))
        assertNotEquals(one, PcContainers.ownId("itch:12346"))
        // An id with nothing after its source is not a store game's.
        assertTrue(PcContainers.ownId("steam:").startsWith("DROIDTOP_"))
        assertTrue(PcContainers.ownId("plain").startsWith("DROIDTOP_"))
    }

    @Test
    fun `a gone folder takes the folders under it, not a neighbour sharing its name`() {
        val folders = setOf("/sdcard/Games", "/sdcard/Games/Doom", "/sdcard/Games-2", "/sdcard/Other")
        assertEquals(setOf("/sdcard/Games-2", "/sdcard/Other"), PcLibrary.foldersKept(folders, "/sdcard/Games"))
        assertEquals(folders, PcLibrary.foldersKept(folders, "/sdcard/Nothing"))
    }
}
