package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.FolderSizes
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.integrations.OldVersions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows that remove a game's older version folders on Versions (docs/SPEC.md 7i; Droidtop/tracker#624). */
class OldVersionRowsTest {
    private val old = OldVersions.Old(LibraryEntry(id = "/g/Game v0.37", title = "Game v0.37", kind = LibraryEntryKind.RENPY), File("/g/Game v0.37"), "0.37")
    private val sizes = mapOf("/g/Game v0.37" to FolderSizes.Size(1_288_490_189L))

    private fun rows(armed: String? = null, removing: Boolean = false, status: String? = null, onRemove: (OldVersions.Old) -> Unit = {}) =
        oldVersionRows(listOf(old), sizes, armed, removing, status, "Press A again", onRemove)

    @Test
    fun aVersionShowsItsSizeAndThatItsSavesAreKept() {
        val row = rows().single()
        assertEquals("Version v0.37", row.title)
        assertEquals("Remove", row.value)
        assertTrue(row.subtitle!!.contains("GB"))
        assertTrue(row.subtitle!!.contains("saves are copied"))
        assertEquals(PageTab.VERSIONS, row.tab)
    }

    @Test
    fun theFirstPressArmsAndTheArmedRowSaysWhatTheSecondDoes() {
        var pressed: OldVersions.Old? = null
        val armed = rows(armed = "/g/Game v0.37") { pressed = it }.single()
        assertEquals("Remove?", armed.value)
        assertTrue(armed.subtitle!!.startsWith("Press A again to remove it and free "))
        armed.onActivate!!()
        assertEquals(old, pressed)
    }

    @Test
    fun theLastRemovalIsSaidOnARowThatStays() {
        val after = oldVersionRows(emptyList(), emptyMap(), null, false, "Removed v0.37", "Press A again") {}
        assertEquals(listOf(OLD_VERSIONS_ROW), after.map { it.title })
        assertEquals("Removed v0.37", after.single().subtitle)
        assertEquals("Removing…", rows(removing = true).first().value)
    }
}
