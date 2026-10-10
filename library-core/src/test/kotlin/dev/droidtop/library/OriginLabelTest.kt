package dev.droidtop.library

import dev.droidtop.library.stores.StoreHolding
import org.junit.Assert.assertEquals
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
        // An engine game names its engine, whatever the source and however many there are.
        Row(store("steam:9", kind = LibraryEntryKind.RENPY), 1, "Engine · Ren'Py", steam, null),
        Row(folder("/sd/Games/Some VN", LibraryEntryKind.RPG_MAKER_MZ), 3, "Engine · RPG Maker MZ", "Folder: Games", null),
        // Ownership is never text on the capsule: a glyph, and the words on the focus line.
        Row(store("steam:10", StoreHolding.FAMILY), 1, "PC", "$steam · Shared with you", OwnershipMark.SHARED),
        Row(store("steam:11", StoreHolding.NOT_OWNED), 2, "PC · $steam", "$steam · No longer in your library", OwnershipMark.LEFT),
        Row(store("steam:12", StoreHolding.FREE), 1, "PC", "$steam · Free to play (not in your library)", null),
        // A folder game, and one that came through a launcher.
        Row(folder("/sd/Games/Portable"), 2, "PC · Games", "Folder: Games", null),
        // A long folder name is cut for the badge (initials of its words, else its first letters); the focus line keeps it whole.
        Row(folder("/sd/BstSharedFolder/Portable"), 2, "PC · BSF", "Folder: BstSharedFolder", null),
        Row(folder("/sd/Retro-Games-Collection/x"), 2, "PC · RGC", "Folder: Retro-Games-Collection", null),
        Row(folder("/sd/Longfoldername/x"), 2, "PC · Longfolde…", "Folder: Longfoldername", null),
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
}
