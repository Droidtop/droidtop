package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a PC game came from, one vocabulary for every reader (docs/SPEC.md 7g "Stores", 7j "Filters"). */
class PcSourceTest {

    private fun entry(id: String, pcInfo: PcInfo? = null, kind: LibraryEntryKind = LibraryEntryKind.WINE_PROFILE) =
        LibraryEntry(id = id, title = id, kind = kind, pcInfo = pcInfo)

    private val roots = listOf("/storage/card/Games", "/storage/card/Games/BATTLE.NET", "/storage/emulated/0/PC")

    @Test
    fun `a store row is its store, whatever store it is`() {
        assertEquals(PcSource.Store("gog"), PcSource.of(entry("gog:1207658691", PcInfo(storeId = "gog:1207658691", installed = false))))
        // A store this build has no code for is still a store, not dropped.
        assertEquals(PcSource.Store("battlenet"), PcSource.of(entry("battlenet:wow", PcInfo(storeId = "battlenet:wow", installed = true))))
    }

    @Test
    fun `an engine folder that absorbed a store install keeps the store`() {
        val absorbed = entry(
            "/storage/card/Games/Steam/Some Game",
            PcInfo(storeId = "steam:440", installed = true, installPath = "/storage/card/Games/Steam/Some Game"),
            kind = LibraryEntryKind.RENPY,
        )
        assertEquals(PcSource.Store("steam"), PcSource.of(absorbed, roots))
    }

    @Test
    fun `a folder game is its most specific root, counted once`() {
        val plain = entry("/storage/card/Games/Indie/Game One", kind = LibraryEntryKind.RENPY)
        val nested = entry(
            "folder:CUSTOM_GAME_7",
            PcInfo(storeId = "folder:CUSTOM_GAME_7", installed = true, installPath = "/storage/card/Games/BATTLE.NET/Diablo"),
        )
        assertEquals(PcSource.Folder("/storage/card/Games"), PcSource.of(plain, roots))
        assertEquals(PcSource.Folder("/storage/card/Games/BATTLE.NET"), PcSource.of(nested, roots))
        // Outside every root: the one "Folder" value, never a crash.
        assertEquals(PcSource.Folder(""), PcSource.of(entry("/elsewhere/Game", kind = LibraryEntryKind.RENPY), roots))
    }

    @Test
    fun `a hand-made Wine shortcut is its own source`() {
        val shortcut = entry("/data/prefix/desktop/Game.desktop", PcInfo(installed = true))
        assertEquals(PcSource.WineShortcut, PcSource.of(shortcut, roots))
    }

    @Test
    fun `an entry that is no PC game has no source`() {
        assertNull(PcSource.of(entry("com.example.app", kind = LibraryEntryKind.NATIVE_ANDROID_APP)))
    }

    @Test
    fun `ids round trip and labels are read when drawn`() {
        listOf(PcSource.Store("epic"), PcSource.Folder("/storage/card/Games"), PcSource.Folder(""), PcSource.WineShortcut).forEach {
            assertEquals(it, PcSource.fromId(it.id))
        }
        assertEquals("Games", PcSource.Folder("/storage/card/Games").label())
        assertEquals("Folder: Games", PcSource.Folder("/storage/card/Games").detail())
        assertEquals("Folder", PcSource.Folder("").label())
        assertEquals("Wine shortcuts", PcSource.WineShortcut.label())
    }

    @Test
    fun `the store half of a key`() {
        assertEquals("steam", PcSource.storeIdOf("steam:440"))
        assertNull(PcSource.storeIdOf("folder:CUSTOM_GAME_1"))
        assertNull(PcSource.storeIdOf("/a/path:with colon"))
        assertNull(PcSource.storeIdOf("no colon"))
        assertNull(PcSource.storeIdOf(null))
    }

    @Test
    fun `sources are listed stores first, then folders, then Wine shortcuts`() {
        val sorted = listOf(PcSource.WineShortcut, PcSource.Folder("/b/Zed"), PcSource.Folder("/a/Alpha"), PcSource.Store("zzz"))
            .sortedWith(PcSource.ORDER)
        assertEquals(listOf(PcSource.Store("zzz"), PcSource.Folder("/a/Alpha"), PcSource.Folder("/b/Zed"), PcSource.WineShortcut), sorted)
    }
}
