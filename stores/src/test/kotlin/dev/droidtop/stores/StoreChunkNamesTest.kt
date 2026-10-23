package dev.droidtop.stores

import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.stores.epic.EpicDownloadManager
import dev.droidtop.stores.gog.GOGDownloadManager
import dev.droidtop.stores.steam.SteamFetcher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chunks GOG, Epic and Steam fetch on the download engine (docs/SPEC.md 12a, rollout step 4). */
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
    fun `a CDN's refusal is the status GOG refreshes its links on and Steam asks for a token on`() {
        assertEquals(404, DownloadJobs.EngineFailure(3, "x").refusedStatus)
        assertEquals(403, DownloadJobs.EngineFailure(22, "x").refusedStatus)
        assertEquals(401, DownloadJobs.EngineFailure(24, "x").refusedStatus)
        assertNull("a network failure is not a refusal", DownloadJobs.EngineFailure(6, "x").refusedStatus)
        assertNull(DownloadJobs.EngineFailure(null, "x").refusedStatus)
    }

    @Test
    fun `JavaSteam's downloads have bare names of their own in the downloads area`() {
        assertEquals("steam_chunk_731_0a1b2c3d4e", SteamFetcher.areaName("chunk_731_0a1b2c3d4e"))
        assertEquals("steam_web_480_My_Mod_.zip", SteamFetcher.areaName("web_480_My Mod!.zip"))
    }
}
