package dev.droidtop.library.computers

import dev.droidtop.library.stores.SaveSyncResult
import dev.droidtop.library.stores.StoreSaves
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent's `conflict` answer as the question droidtop asks (Droidtop/tracker#814: "nobody 'wins' ... We ask the user"). */
class ComputerSavesConflictTest {
    private val answer = JSONObject(
        """{"outcome":"conflict","here":{"device":"Retroid Pocket 5","files":3,"newest_ms":1000},"there":{"device":"DESKTOP_PC","files":4,"newest_ms":2000}}""",
    )

    @Test
    fun `both sides come with their device, files and date, and the replaced one is kept`() {
        val conflict = ComputerSaves.conflictOf(answer, "Desktop")!!
        assertEquals("Retroid Pocket 5", conflict.local.device)
        assertEquals(3, conflict.local.files)
        assertEquals(1000L, conflict.local.timestampMs)
        assertEquals("DESKTOP_PC", conflict.cloud.device)
        assertEquals(4, conflict.cloud.files)
        assertEquals("Desktop", conflict.cloudLabel)
        assertTrue(conflict.computer)
        assertTrue(conflict.kept)
    }

    @Test
    fun `a store game names this device's side as the store's copy too`() {
        assertEquals("This device and Steam", ComputerSaves.conflictOf(answer, "Desktop", "This device and Steam")!!.local.device)
    }

    @Test
    fun `any other answer is no question`() {
        assertNull(ComputerSaves.conflictOf(JSONObject("""{"outcome":"copied","from":"there","files":2}"""), "Desktop"))
        assertNull(ComputerSaves.conflictOf(JSONObject("""{"outcome":"conflict"}"""), "Desktop"))
    }

    @Test
    fun `a store's sync and the computers' read as one result`() {
        val both = StoreSaves.merged(SaveSyncResult("Cloud saves: 2 down", downloaded = 2), SaveSyncResult("Saves differ", unresolved = true))!!
        assertEquals("Cloud saves: 2 down; Saves differ", both.line)
        assertEquals(2, both.downloaded)
        assertTrue(both.unresolved)
        assertNull(StoreSaves.merged(null, null))
        assertEquals("only", StoreSaves.merged(null, SaveSyncResult("only"))!!.line)
    }
}
