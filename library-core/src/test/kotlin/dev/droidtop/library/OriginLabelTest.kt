package dev.droidtop.library

import dev.droidtop.library.stores.StoreHolding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one function that names a PC game's origin (docs/SPEC.md 7i,
 * "Badges", Droidtop/tracker#397 slice G): a table over kind, source,
 * holding and how many origins the library has.
 */
class OriginLabelTest {

    private val roots = listOf("/sd/Games", "/sd/BstSharedFolder", "/sd/Retro-Games-Collection", "/sd/Longfoldername")

    // A store is named by its own label, whatever this test's process registered ([PcSource.label]).
    private val steam = PcSource.Store("steam").label()
    private val gog = PcSource.Store("gog").label()

    private fun store(id: String, holding: StoreHolding = StoreHolding.OWNED, kind: LibraryEntryKind = LibraryEntryKind.WINE_PROFILE) =
        LibraryEntry(id = id, title = id, kind = kind, pcInfo = PcInfo(storeId = id, installed = true, holding = holding))

    private fun folder(path: String, kind: LibraryEntryKind = LibraryEntryKind.WINE_PROFILE) =
        if (kind == LibraryEntryKind.WINE_PROFILE) {
            LibraryEntry(id = "folder:CUSTOM_GAME_1", title = "x", kind = kind, pcInfo = PcInfo(storeId = "folder:CUSTOM_GAME_1", installed = true, installPath = path))
        } else {
            LibraryEntry(id = path, title = "x", kind = kind)
        }

    private data class Row(val entry: LibraryEntry, val origins: Int, val badge: String?, val full: String, val mark: OwnershipMark?, val via: String? = null)

    private val table = listOf(
        // A Steam-only library: "PC", the store in full on the focus line.
        Row(store("steam:440"), 1, "PC", steam, null),
        // Once another origin has rows, the PC badge names its source.
        Row(store("gog:1"), 2, "PC · $gog", gog, null),
        // An engine game is "Engine": the engine it is built on is an implementation category, not a badge (#496).
        Row(store("steam:9", kind = LibraryEntryKind.RENPY), 1, "Engine", steam, null),
        Row(store("steam:8", kind = LibraryEntryKind.RENPY), 2, "Engine · $steam", steam, null),
        Row(folder("/sd/Games/Some VN", LibraryEntryKind.RPG_MAKER_MZ), 3, "Engine", "Folder: Games", null),
        // A Unity (or Unreal, AGS, LOVE) build runs in Wine: it is a PC game, store or local (#603).
        Row(folder("/sd/Games/Clover Days", LibraryEntryKind.UNITY), 1, "PC", "Folder: Games", null),
        Row(store("steam:13", kind = LibraryEntryKind.UNREAL), 1, "PC", steam, null),
        // Ownership is never text on the capsule: a glyph, and the words on the focus line.
        Row(store("steam:10", StoreHolding.FAMILY), 1, "PC", "$steam · Shared with you", OwnershipMark.SHARED),
        Row(store("steam:11", StoreHolding.NOT_OWNED), 2, "PC · $steam", "$steam · No longer in your library", OwnershipMark.LEFT),
        Row(store("steam:12", StoreHolding.FREE), 1, "PC", "$steam · Free to play (not in your library)", null),
        // A folder's name is never on a badge, however many origins (#496); the focus line names it.
        Row(folder("/sd/Games/Portable"), 2, "PC", "Folder: Games", null),
        Row(folder("/sd/BstSharedFolder/Portable"), 2, "PC", "Folder: BstSharedFolder", null),
        // One that came through a launcher.
        Row(store("gog:2"), 2, "PC · $gog", "$gog · via Heroic", null, via = "heroic"),
    )

    @Test
    fun `every row of the table`() {
        for (row in table) {
            val label = originLabel(row.entry, row.origins, roots = roots, via = row.via)
            assertEquals("badge of ${row.entry.id}", row.badge, label.badge)
            assertEquals("full of ${row.entry.id}", row.full, label.full)
            assertEquals("mark of ${row.entry.id}", row.mark, label.mark)
        }
    }

    @Test
    fun `the Capsule badge option shortens or drops the badge and nothing else`() {
        val vn = folder("/sd/Games/Some VN", LibraryEntryKind.RENPY)
        assertEquals("Engine", originLabel(vn, 2, CapsuleBadgeStyle.KIND_ONLY, roots).badge)
        assertEquals("PC", originLabel(store("gog:3"), 2, CapsuleBadgeStyle.KIND_ONLY, roots).badge)
        val off = originLabel(store("steam:13", StoreHolding.FAMILY), 2, CapsuleBadgeStyle.OFF, roots)
        assertEquals(null, off.badge)
        assertEquals(OwnershipMark.SHARED, off.mark)
        assertEquals("$steam · Shared with you", off.full)
    }

    @Test
    fun `engine is every kind that is not a program, a remote PC, an app or a ROM`() {
        val programs = setOf(
            LibraryEntryKind.NATIVE_ANDROID_APP, LibraryEntryKind.WINE_PROFILE, LibraryEntryKind.LINUX_CONTAINER_APP,
            LibraryEntryKind.REMOTE_STREAM, LibraryEntryKind.COMPUTER_GAME, LibraryEntryKind.CONSOLE_ROM,
        )
        for (kind in LibraryEntryKind.entries) assertEquals(kind.name, kind !in programs, kind.engineFamily() != null)
    }

    @Test
    fun `engine means Enginehost runs it, and every Wine-run engine is PC`() {
        assertTrue(LibraryEntryKind.RENPY.runsInEnginehost())
        assertTrue(LibraryEntryKind.GODOT.runsInEnginehost())
        assertTrue(LibraryEntryKind.HTML.runsInEnginehost())
        WINE_ENGINE_KINDS.forEach { assertTrue("$it has an engine family", it.engineFamily() != null) }
        WINE_ENGINE_KINDS.forEach { org.junit.Assert.assertFalse("$it runs in Wine", it.runsInEnginehost()) }
        org.junit.Assert.assertFalse(LibraryEntryKind.WINE_PROFILE.runsInEnginehost())
    }
}
