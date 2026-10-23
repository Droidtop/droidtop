package dev.droidtop.stores

import dev.droidtop.stores.epic.EpicDownloadManager
import dev.droidtop.stores.gog.GOGDownloadManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chunks GOG and Epic fetch on the download engine (docs/SPEC.md 12a, rollout step 4). */
class StoreChunkNamesTest {
    @Test
    fun `each install's chunks have names of their own in the downloads area`() {
        val epic = EpicDownloadManager.areaPrefix("/storage/games/Overlay")
        assertTrue(epic.startsWith("epic_") && epic.endsWith("_"))
        assertEquals(epic, EpicDownloadManager.areaPrefix("/storage/games/Overlay"))
        assertNotEquals("two installs named alike do not share", epic, EpicDownloadManager.areaPrefix("/storage/other/Overlay"))

        val gog = GOGDownloadManager.areaPrefix(File("/cache/gog_chunks/1207658924"))
        assertTrue(gog.startsWith("gog_") && gog.endsWith("_"))
        assertNotEquals(gog, GOGDownloadManager.areaPrefix(File("/cache/gog_chunks/1207658925")))
        assertTrue("a name is a bare file name", Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(gog + "0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `a CDN's refusal is the status GOG's expired links are refreshed on`() {
        assertEquals(404, GOGDownloadManager.linkRefusal(3))
        assertEquals(403, GOGDownloadManager.linkRefusal(22))
        assertEquals(401, GOGDownloadManager.linkRefusal(24))
        assertNull("a network failure is not an expired link", GOGDownloadManager.linkRefusal(6))
        assertNull(GOGDownloadManager.linkRefusal(null))
    }
}
