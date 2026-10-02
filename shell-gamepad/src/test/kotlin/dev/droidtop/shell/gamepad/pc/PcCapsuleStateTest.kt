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
        source: String = "Steam",
    ) = LibraryEntry(
        id = id,
        title = "Game $id",
        kind = LibraryEntryKind.WINE_PROFILE,
        favorite = favorite,
        availableUpdate = availableUpdate,
        pcInfo = PcInfo(source = source, storeId = id, installed = installed, sizeBytes = size, update = update),
    )

    private fun folder(id: String = "/games/a", missing: Boolean = false, update: String? = null) =
        LibraryEntry(
            id = id,
            title = "Folder game",
            kind = LibraryEntryKind.RENPY,
            missing = missing,
            availableUpdate = update,
            pcInfo = PcInfo(source = "Folder", installed = true),
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
        assertEquals("Folder game", focusLine(folder(), null, parts = 1))
        assertTrue(focusLine(folder(), null, parts = 3).endsWith("3 copies"))
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

    @Test
    fun `the strip counts each built-in view`() {
        val counts = pcViewCounts(library, scope)
        assertEquals(4, counts[VIEW_ALL])
        assertEquals(3, counts[VIEW_INSTALLED])
        assertEquals(1, counts[VIEW_UPDATES])
        assertEquals(1, counts[VIEW_FAVOURITES])
        assertEquals(0, counts[VIEW_CONTINUE])
    }

    @Test
    fun `updates and favourites chips appear only when they hold something`() {
        val none = pcStripViews(mapOf(VIEW_ALL to 2, VIEW_UPDATES to 0, VIEW_FAVOURITES to 0), emptyList()).map { it.name }
        assertEquals(listOf(VIEW_ALL, VIEW_INSTALLED, VIEW_CONTINUE), none)

        val some = pcStripViews(mapOf(VIEW_UPDATES to 1, VIEW_FAVOURITES to 3), emptyList()).map { it.name }
        assertEquals(listOf(VIEW_ALL, VIEW_INSTALLED, VIEW_UPDATES, VIEW_FAVOURITES, VIEW_CONTINUE), some)
    }

    @Test
    fun `a built-in chip carries its count and a saved view only its name`() {
        val counts = mapOf(VIEW_INSTALLED to 12)
        assertEquals("Installed · 12", pcStripLabel(pcBuiltInViews.first { it.name == VIEW_INSTALLED }, counts))
        assertEquals("Installed", pcStripLabel(pcBuiltInViews.first { it.name == VIEW_INSTALLED }, emptyMap()))
        assertEquals("Mine", pcStripLabel(dev.droidtop.shell.gamepad.query.NamedLibraryView("Mine", LibraryQuery()), counts))
    }
}
