package dev.droidtop.shell.gamepad.query

import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.integrations.SourceDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The search entries (docs/SPEC.md 12a, "One search: where it opens"; Droidtop/tracker#485 F7c). */
class SearchEntryTest {
    private val systems = mapOf("snes" to "SNES", "psx" to "PlayStation", "n64" to "Nintendo 64", "sfc" to "Super Famicom")

    @Test
    fun `a system's search is named for the system`() {
        assertEquals("Search SNES", SearchEntry.systemLabel("SNES"))
        assertEquals("Search SNES: zelda", SearchEntry.systemLabel("SNES", " zelda "))
    }

    @Test
    fun `the global search finds systems by id and by name`() {
        assertEquals(listOf("snes" to "SNES"), SearchEntry.matchingSystems("snes", systems))
        assertEquals(listOf("psx" to "PlayStation"), SearchEntry.matchingSystems("play", systems))
        assertEquals(listOf("n64" to "Nintendo 64"), SearchEntry.matchingSystems("ninten", systems))
        assertEquals(listOf("sfc" to "Super Famicom"), SearchEntry.matchingSystems("famicom", systems))
        assertTrue(SearchEntry.matchingSystems("s", systems).isEmpty())
    }

    @Test
    fun `a system's search downloads into that system's folder`() {
        val snes = ConsoleSystemDef("snes", "SNES", emptySet(), null)
        val folder = File("/games/snes")
        val pick = SourceDestinations.pick("snes", null, platform = "Windows", systems = listOf(snes), folders = listOf(folder to snes), pcRoots = emptyList())
        assertEquals("snes", pick.systemId)
        assertEquals(folder, pick.folder)
    }
}
