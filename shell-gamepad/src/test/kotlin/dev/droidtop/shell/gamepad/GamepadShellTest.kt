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
    fun `empty entries produce no sections`() {
        assertEquals(emptyList<Any>(), buildAppSections(emptyList()))
    }
}
