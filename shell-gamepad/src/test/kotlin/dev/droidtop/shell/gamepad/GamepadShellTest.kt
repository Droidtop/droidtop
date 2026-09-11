package dev.droidtop.shell.gamepad

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadShellTest {
    private fun entry(
        id: String,
        kind: LibraryEntryKind,
        lastPlayedEpochMs: Long? = null,
    ) = LibraryEntry(id = id, title = id, kind = kind, lastPlayedEpochMs = lastPlayedEpochMs)

    @Test
    fun `one section per kind actually present, in enum declaration order`() {
        val entries = listOf(
            entry("app1", LibraryEntryKind.NATIVE_ANDROID_APP),
            entry("wine1", LibraryEntryKind.WINE_PROFILE),
        )

        val sections = buildAppSections(entries)

        assertEquals(listOf("Apps", "Windows"), sections.map { it.title })
    }

    @Test
    fun `every RPG Maker kind merges into one section, not three`() {
        val entries = listOf(
            entry("mv", LibraryEntryKind.RPG_MAKER_MV),
            entry("mz", LibraryEntryKind.RPG_MAKER_MZ),
            entry("vxace", LibraryEntryKind.RPG_MAKER_VX_ACE),
        )

        val sections = buildAppSections(entries)

        assertEquals(1, sections.size)
        assertEquals("RPG Maker", sections.single().title)
        assertEquals(3, sections.single().entries.size)
    }

    @Test
    fun `an engine game with no systemId belongs to the PC group`() {
        assertEquals(GameGroupForTest.PC, groupOf(entry("renpy", LibraryEntryKind.RENPY)))
        assertEquals(GameGroupForTest.PC, groupOf(entry("rm", LibraryEntryKind.RPG_MAKER_MV)))
        assertEquals(GameGroupForTest.PC, groupOf(entry("kiri", LibraryEntryKind.KIRIKIRI)))
    }

    @Test
    fun `a store or Wine title belongs to the PC group too`() {
        assertEquals(
            GameGroupForTest.PC,
            groupOf(entry("steam", LibraryEntryKind.WINE_PROFILE).copy(systemId = "pc")),
        )
        assertEquals(GameGroupForTest.PC, groupOf(entry("wine", LibraryEntryKind.WINE_PROFILE)))
    }

    @Test
    fun `a real console system keeps its own card`() {
        assertEquals(
            GameGroupForTest.system("n3ds"),
            groupOf(entry("kid icarus", LibraryEntryKind.CONSOLE_ROM).copy(systemId = "n3ds")),
        )
    }

    @Test
    fun `ROM files in a folder named pc do not claim a second PC card`() {
        // The bug this replaces: a games-root folder literally named `pc`
        // made ConsoleRomProvider tag plain ROMs with systemId "pc", which
        // became a rival "PC" card -- it reported its own game count and
        // opened a surface that filtered every one of them back out.
        val rom = entry("dosgame", LibraryEntryKind.CONSOLE_ROM).copy(systemId = "pc")

        assertEquals(GameGroupForTest.PC, groupOf(rom))
    }

    @Test
    fun `empty entries produce no sections`() {
        assertEquals(emptyList<Any>(), buildAppSections(emptyList()))
    }

    @Test
    fun `entries within a section are sorted alphabetically by title, not scan order`() {
        val entries = listOf(
            entry("zebra", LibraryEntryKind.NATIVE_ANDROID_APP).copy(title = "Zebra"),
            entry("apple", LibraryEntryKind.NATIVE_ANDROID_APP).copy(title = "apple"),
            entry("mango", LibraryEntryKind.NATIVE_ANDROID_APP).copy(title = "Mango"),
        )

        val sections = buildAppSections(entries)

        assertEquals(listOf("apple", "Mango", "Zebra"), sections.single().entries.map { it.title })
    }

    /**
     * The grouping answer as a plain value: GameGroup itself is private to
     * GamepadShell.kt (it carries Compose-side theming), so the test reads
     * the one thing it is asserting about -- which card an entry lands on.
     */
    private data class GameGroupForTest(val key: String) {
        companion object {
            val PC = GameGroupForTest("system:pc")
            fun system(id: String) = GameGroupForTest("system:$id")
        }
    }

    private fun groupOf(entry: LibraryEntry) = GameGroupForTest(gameGroupKey(entry))
}
