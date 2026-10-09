package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.StoreUpdate
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.LibraryViewPrefs
import dev.droidtop.shell.gamepad.query.NamedLibraryView
import dev.droidtop.shell.gamepad.query.StripTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one state function the capsule badge, the page button, the menu row and
 * the hint pill share (docs/SPEC.md 7i, "Capsules and the primary action"),
 * and the strip's counts.
 */
class PcCapsuleStateTest {

    private fun store(
        id: String = "steam:1",
        installed: Boolean = true,
        update: StoreUpdate = StoreUpdate.UNKNOWN,
        size: Long = 0L,
        availableUpdate: String? = null,
        favorite: Boolean = false,
    ) = LibraryEntry(
        id = id,
        title = "Game $id",
        kind = LibraryEntryKind.WINE_PROFILE,
        favorite = favorite,
        availableUpdate = availableUpdate,
        pcInfo = PcInfo(storeId = id, installed = installed, sizeBytes = size, update = update),
    )

    private fun folder(id: String = "/games/a", missing: Boolean = false, update: String? = null) =
        LibraryEntry(
            id = id,
            title = "Folder game",
            kind = LibraryEntryKind.RENPY,
            missing = missing,
            availableUpdate = update,
            pcInfo = PcInfo(installed = true),
        )

    @Test
    fun `an installed store game with no known update has no stage`() {
        assertNull(storeStageOf(store(), null))
        assertNull(storeStageOf(store(update = StoreUpdate.CURRENT), null))
    }

    @Test
    fun `a store game that is not installed offers Install`() {
        assertEquals(StoreStage.INSTALL, storeStageOf(store(installed = false), null))
    }

    @Test
    fun `an update a store reported offers Update`() {
        assertEquals(StoreStage.UPDATE, storeStageOf(store(update = StoreUpdate.AVAILABLE), null))
    }

    @Test
    fun `a running download beats install and update`() {
        val running = StoreDownloads.Progress(0.4f, paused = false)
        assertEquals(StoreStage.DOWNLOADING, storeStageOf(store(installed = false), running))
        assertEquals(StoreStage.DOWNLOADING, storeStageOf(store(update = StoreUpdate.AVAILABLE), running))
        assertEquals(StoreStage.PAUSED, storeStageOf(store(installed = false), StoreDownloads.Progress(0.4f, paused = true)))
    }

    @Test
    fun `a folder game and a Wine shortcut have no store stage`() {
        assertNull(storeStageOf(folder(), null))
        assertNull(storeStageOf(folder(), StoreDownloads.Progress(0.5f, paused = false)))
    }

    @Test
    fun `the primary button says Install with the size, Update with the one wording, and the download's progress`() {
        val install = playStateOf(null, store(installed = false, size = 12_400_000_000L))
        assertEquals("Install", install.verb)
        assertTrue(install.detail.contains("12.4 GB"))
        assertTrue(install.pressable)
        assertFalse(install.ready)

        val update = playStateOf(null, store(update = StoreUpdate.AVAILABLE))
        assertEquals("Update", update.verb)
        assertTrue(update.detail.startsWith("A newer build is available"))

        val running = playStateOf(null, store(installed = false), StoreDownloads.Progress(0.42f, paused = false))
        assertEquals("Downloading", running.verb)
        assertEquals(StoreStage.DOWNLOADING, running.store)
        assertEquals(0.42f, running.progress)
        assertTrue(running.detail.startsWith("42%"))

        val paused = playStateOf(null, store(installed = false), StoreDownloads.Progress(0.42f, paused = true))
        assertEquals("Resume", paused.verb)
        assertEquals(StoreStage.PAUSED, paused.store)
    }

    @Test
    fun `a game no runner offers gets a Choose a runner button that opens the engine picker`() {
        val none = playStateOf(null, folder())
        assertEquals("Choose a runner", none.verb)
        assertTrue(none.pressable)
        assertTrue(none.chooseEngine)
        assertFalse(playStateOf(null, store(installed = false)).chooseEngine)
    }

    @Test
    fun `the capsule badge reads the same stage as the button`() {
        assertEquals(CapsuleStatus.NOT_INSTALLED, capsuleStatusOf(store(installed = false), null))
        assertEquals(CapsuleStatus.UPDATE, capsuleStatusOf(store(update = StoreUpdate.AVAILABLE), null))
        assertEquals(CapsuleStatus.DOWNLOADING, capsuleStatusOf(store(installed = false), StoreDownloads.Progress(0.1f, false)))
        assertEquals(CapsuleStatus.PAUSED, capsuleStatusOf(store(installed = false), StoreDownloads.Progress(0.1f, true)))
        assertEquals(CapsuleStatus.INSTALLED, capsuleStatusOf(store(), null))
    }

    @Test
    fun `a folder game shows a badge only for an update or when it is missing`() {
        assertNull(capsuleStatusOf(folder(), null))
        assertEquals(CapsuleStatus.UPDATE, capsuleStatusOf(folder(update = "v2"), null))
        assertEquals(CapsuleStatus.MISSING, capsuleStatusOf(folder(missing = true), null))
    }

    @Test
    fun `the focused line names the game once with its store, stage, version and size`() {
        val entry = store(installed = false, size = 2_500_000_000L)
        val line = focusLine(entry, playStateOf(null, entry), parts = 1)
        assertEquals("Game steam:1 · Steam · Install · 2.5 GB", line)
        // Where it came from, in full: a folder game names its game folder.
        assertEquals("Folder game · Folder: games", focusLine(folder(), null, parts = 1, roots = listOf("/games")))
        assertTrue(focusLine(folder(), null, parts = 3).endsWith("3 copies"))
        // Version management on the line: the version a source knows of.
        assertEquals("Folder game · Folder: games · v0.9.6 is available", focusLine(folder(update = "0.9.6"), null, parts = 1, roots = listOf("/games")))
        // A shared game says so in words here, never on the capsule.
        assertEquals("Game steam:1 · Steam · Shared with you", focusLine(store().let { it.copy(pcInfo = it.pcInfo?.copy(holding = dev.droidtop.library.stores.StoreHolding.FAMILY)) }, null, parts = 1))
        assertEquals(SHARED_GLYPH, ownershipGlyph(store().let { it.copy(pcInfo = it.pcInfo?.copy(holding = dev.droidtop.library.stores.StoreHolding.FAMILY)) }))
        assertNull(ownershipGlyph(store()))
    }

    @Test
    fun `a missing folder game says where it was and that its history is kept`() {
        assertEquals(
            "/games/a is not there any more. Its history, favourite and collections are kept.",
            missingFolderLine(folder("/games/a", missing = true)),
        )
        assertEquals(
            "Nothing droidtop scanned still has this game. Its history, favourite and collections are kept.",
            missingFolderLine(store(installed = false)),
        )
    }

    @Test
    fun `download sizes read as megabytes or gigabytes`() {
        assertEquals("1.5 GB", downloadSizeLabel(1_500_000_000L))
        assertEquals("420 MB", downloadSizeLabel(420_000_000L))
        assertEquals("1 MB", downloadSizeLabel(10L))
    }

    private val scope = LibraryQueryScope(
        id = "pc",
        facets = listOf(LibraryFacet.INSTALLED, LibraryFacet.FAVOURITES, LibraryFacet.UPDATE, LibraryFacet.RECENTLY_PLAYED),
        sorts = listOf(LibrarySortKey.NAME),
    )

    private val library = listOf(
        store("steam:1"),
        store("steam:2", installed = false),
        store("gog:3", update = StoreUpdate.AVAILABLE, availableUpdate = "A newer build", favorite = true),
        folder("/games/a"),
    )

    private val updates = LibraryViewPrefs.withUpdatesView(emptyList()).single()
    private val mine = NamedLibraryView("Mine", LibraryQuery(facets = mapOf(LibraryFacet.FAVOURITES.key to setOf("Favourites"))), id = "mine")
    private val hidden = NamedLibraryView("Saved only", LibraryQuery(), id = "saved", pinned = false)

    private fun labels(tabs: List<PcTab>) = tabs.map { pcTabLabel(it, emptyMap()) }

    @Test
    fun `the strip's built-in tabs are fixed, then the pinned views, Updates first`() {
        val saved = LibraryViewPrefs.withUpdatesView(listOf(mine, hidden))
        assertEquals(
            listOf(VIEW_OVERVIEW, VIEW_ALL, VIEW_INSTALLED, VIEW_FAVOURITES, VIEW_COLLECTIONS, "Updates", "Mine"),
            labels(pcStripTabs(saved)),
        )
        // Empty built-ins stay: there is no count to hide them by.
        assertEquals(listOf(VIEW_OVERVIEW, VIEW_ALL, VIEW_INSTALLED, VIEW_FAVOURITES, VIEW_COLLECTIONS), labels(pcStripTabs(emptyList())))
        // Strip tabs hides Favourites and Collections only, and the order stays.
        assertEquals(
            listOf(VIEW_OVERVIEW, VIEW_ALL, VIEW_INSTALLED, "Updates", "Mine"),
            labels(pcStripTabs(saved, StripTabs(favourites = false, collections = false))),
        )
    }

    @Test
    fun `everyone is given the pinned Updates view once, over the existing facet`() {
        assertEquals(LibraryViewPrefs.UPDATES_VIEW_ID, updates.id)
        assertTrue(updates.pinned)
        assertEquals(setOf(dev.droidtop.shell.gamepad.query.UPDATE_YES), updates.query.selected(LibraryFacet.UPDATE))
        // Seeding again changes nothing; a person's renamed Updates keeps its place.
        val renamed = listOf(mine, updates.copy(name = "New builds"))
        assertEquals(renamed, LibraryViewPrefs.withUpdatesView(renamed))
    }

    @Test
    fun `every grid tab carries its count`() {
        val tabs = pcStripTabs(listOf(updates, mine))
        val counts = pcViewCounts(library, scope, tabs.filterIsInstance<PcTab.Grid>().map { it.view })
        assertEquals(4, counts["builtin:all"])
        assertEquals(3, counts["builtin:installed"])
        assertEquals(1, counts["builtin:favourites"])
        assertEquals(1, counts[LibraryViewPrefs.UPDATES_VIEW_ID])
        assertEquals("Installed · 3", pcTabLabel(tabs[2], counts))
        assertEquals("Mine · 1", pcTabLabel(tabs.last(), counts))
        assertEquals(VIEW_COLLECTIONS, pcTabLabel(PcTab.Collections, counts))
    }

    @Test
    fun `pinned tabs move among themselves and to first, saved-only views stay put`() {
        val a = NamedLibraryView("A", LibraryQuery(), id = "a")
        val b = NamedLibraryView("B", LibraryQuery(), id = "b")
        val c = NamedLibraryView("C", LibraryQuery(), id = "c")
        val saved = listOf(a, hidden, b, c)
        assertEquals(listOf("a", "saved", "c", "b"), movePinned(saved, "c", step = -1).map { it.id })
        assertEquals(listOf("c", "saved", "a", "b"), movePinned(saved, "c", toFirst = true).map { it.id })
        assertEquals(saved, movePinned(saved, "a", step = -1))
    }

    @Test
    fun `an edited view is saved in place, a new name at the end`() {
        val saved = listOf(updates, mine)
        val edited = LibraryViewPrefs.withView(saved, updates.copy(name = "Builds", query = LibraryQuery()))
        assertEquals(listOf("Builds", "Mine"), edited.map { it.name })
        assertEquals(LibraryViewPrefs.UPDATES_VIEW_ID, edited.first().id)
        assertEquals(listOf("Updates", "Mine", "New"), LibraryViewPrefs.withView(saved, NamedLibraryView("New", LibraryQuery(), id = "n")).map { it.name })
    }

    @Test
    fun `views saved before ids and pins read back pinned, under their name`() {
        val old = "[{\"name\":\"Mine\",\"query\":{\"sort\":\"NAME\",\"reversed\":false,\"text\":\"\",\"facets\":{}}}]"
        val read = LibraryViewPrefs.decodeViews(old).single()
        assertEquals("Mine", read.id)
        assertTrue(read.pinned)
        assertEquals(listOf(hidden), LibraryViewPrefs.decodeViews(LibraryViewPrefs.encodeViews(listOf(hidden))))
    }
}
