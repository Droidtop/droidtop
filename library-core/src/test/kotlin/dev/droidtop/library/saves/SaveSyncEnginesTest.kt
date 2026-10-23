package dev.droidtop.library.saves

import android.content.Context
import dev.droidtop.library.integrations.AppInterfaceSaves
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A game's saves an app reported reach every sync engine that covers the game, at once (docs/plugin-api.md 3 A7a; the
 * owner, 2026-10-23: "there's multiple sync engines. If we don't sync somewhere, there's nowhere to copy it to, and
 * that's fine").
 */
@RunWith(RobolectricTestRunner::class)
class SaveSyncEnginesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun saves(gameId: String = "/storage/emulated/0/Games/DDLC", storeKey: String? = null, at: Long = 1_000L) = ReportedSaves(
        gameId = gameId,
        title = "DDLC",
        gameFolder = "$gameId/DDLC-1.1.1-pc",
        saveFolder = "/storage/emulated/0/Enginehost/saves/DDLC-1454445547",
        storeKey = storeKey,
        reportedAtMs = at,
        by = "dev.enginehost",
    )

    private class Fake(override val id: String, val covers: Boolean, val fails: Boolean = false) : SaveSyncEngine {
        val seen = mutableListOf<ReportedSaves>()

        override suspend fun saved(context: Context, saves: ReportedSaves): SaveHandoff? {
            seen += saves
            if (fails) error("offline")
            return if (covers) SaveHandoff(id, "$id: started", started = true) else null
        }
    }

    @Test
    fun `every engine that covers the game takes it, and one failing does not stop the others`() = runBlocking {
        val computers = Fake("test_computers", covers = true, fails = true)
        val share = Fake("test_share", covers = true)
        val elsewhere = Fake("test_elsewhere", covers = false)
        listOf(computers, share, elsewhere).forEach(SaveSyncEngines::register)

        // The engine list is the process's; another test's engines may be in it, so only these three are looked at.
        val mine = setOf("test_computers", "test_share", "test_elsewhere")
        val handoffs = SaveSyncEngines.saved(context, saves()).filter { it.engine in mine }
        assertEquals(1, computers.seen.size)
        assertEquals(1, share.seen.size)
        assertEquals(1, elsewhere.seen.size)
        // No store key, so the store cloud does not cover it; the failing engine still says what happened.
        assertEquals(listOf("test_computers", "test_share"), handoffs.map { it.engine })
        assertFalse(handoffs.first().started)
        assertTrue("offline" in handoffs.first().line)
    }

    @Test
    fun `an engine registered again replaces the old one`() {
        SaveSyncEngines.register(Fake("test_twice", covers = false))
        SaveSyncEngines.register(Fake("test_twice", covers = true))
        assertEquals(1, SaveSyncEngines.all().count { it.id == "test_twice" })
        assertTrue(SaveSyncEngines.all().any { it.id == StoreCloudSaveEngine.id })
    }

    @Test
    fun `with no engine for the game nothing is copied, and that is no failure`() {
        val reply = AppInterfaceSaves.reply(emptyList())
        assertTrue(reply.getBoolean("accepted"))
        assertFalse(reply.getBoolean("synced"))
        assertEquals(0, reply.getJSONArray("engines").length())
        val synced = AppInterfaceSaves.reply(listOf(SaveHandoff("store_cloud", "Cloud saves: uploading", started = true)))
        assertTrue(synced.getBoolean("synced"))
        assertEquals("Cloud saves: uploading", synced.getString("message"))
    }

    @Test
    fun `where a game saved is kept for every game, the newest report winning`() {
        val id = "/storage/emulated/0/Games/Kept"
        ReportedSaveFolders.record(context, saves(gameId = id, at = 2_000L))
        ReportedSaveFolders.record(context, saves(gameId = id, at = 1_000L))
        assertEquals(2_000L, ReportedSaveFolders.last(context, id)?.reportedAtMs)
        assertNull(ReportedSaveFolders.last(context, "/storage/emulated/0/Games/Never"))
    }

    @Test
    fun `a report reads back as it was written, a missing store key included`() {
        val plain = saves()
        assertEquals(plain, ReportedSaves.fromJson(plain.toJson()))
        val store = saves(storeKey = "steam:698780")
        assertEquals(store, ReportedSaves.fromJson(store.toJson()))
        assertNull(ReportedSaves.fromJson(org.json.JSONObject().put("gameId", "x")))
    }
}
