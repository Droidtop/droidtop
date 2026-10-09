package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a store said about a newer build reaches the card, the shelf and the
 * filter through [LibraryEntry.availableUpdate] (docs/SPEC.md 7g, "Where an
 * update comes from"), and only when a store actually said so.
 */
class StoreUpdateStateTest {

    private fun pc(installed: Boolean = true, update: StoreUpdate = StoreUpdate.UNKNOWN, latest: String? = null) =
        PcInfo(storeId = "steam:1", installed = installed, latestVersion = latest, update = update)

    private fun row(id: String, pcInfo: PcInfo) =
        LibraryEntry(id = id, title = "Game", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = pcInfo)

    @Test
    fun `a store that named a version gives that version`() {
        assertEquals("1.2.0", GameUpdates.forStore(pc(update = StoreUpdate.AVAILABLE, latest = "1.2.0")))
    }

    @Test
    fun `a store that only said newer gives the one wording for it`() {
        val update = GameUpdates.forStore(pc(update = StoreUpdate.AVAILABLE))
        assertEquals(GameUpdates.NEWER_BUILD, update)
        assertEquals("A newer build is available", GameUpdates.line(update!!))
    }

    @Test
    fun `unknown and current are never an update`() {
        assertNull(GameUpdates.forStore(pc(update = StoreUpdate.UNKNOWN)))
        assertNull(GameUpdates.forStore(pc(update = StoreUpdate.CURRENT)))
        assertNull(GameUpdates.forStore(null))
    }

    @Test
    fun `a game that is not installed has nothing to update`() {
        assertNull(GameUpdates.forStore(pc(installed = false, update = StoreUpdate.AVAILABLE, latest = "2.0")))
    }

    @Test
    fun `the card a list draws carries the store's update`() {
        val groups = LibraryGrouping.group(listOf(row("steam:1", pc(update = StoreUpdate.AVAILABLE))))
        assertEquals(GameUpdates.NEWER_BUILD, groups.single().displayEntry.availableUpdate)
    }

    @Test
    fun `a store game with no answer carries no update`() {
        val groups = LibraryGrouping.group(listOf(row("steam:1", pc())))
        assertNull(groups.single().displayEntry.availableUpdate)
    }
}
