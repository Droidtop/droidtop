package dev.droidtop.library.integrations

import dev.droidtop.library.consoles.ConsoleSystemDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a source result picked from a list with no system of its own downloads to (docs/SPEC.md 12a "One search"). */
class SourceDestinationsTest {
    private fun system(id: String, name: String) = ConsoleSystemDef(id, name, emptySet(), null)

    private val psp = system("psp", "Sony PlayStation Portable")
    private val snes = system("snes", "Super Nintendo")
    private val windows = system("windows", "Windows")
    private val vita = system("psvita", "Sony PlayStation Vita")
    private val systems = listOf(psp, snes, windows, vita)

    private val pspFolder = File("/games/psp")
    private val winFolder = File("/games/windows")
    private val root = File("/games")

    @Test
    fun `a console list uses its own system and folder`() {
        val pick = SourceDestinations.pick("snes", File("/games/snes"), null, systems, emptyList(), emptyList())
        assertEquals("snes", pick.systemId)
        assertEquals(File("/games/snes"), pick.folder)
    }

    @Test
    fun `a console list whose folder is not resolved yet still finds it`() {
        val pick = SourceDestinations.pick("snes", null, null, systems, listOf(File("/games/snes") to snes), emptyList())
        assertEquals(File("/games/snes"), pick.folder)
        assertEquals("Super Nintendo", pick.systemName)
    }

    @Test
    fun `a console system with no folder of its own gets one under the first games folder`() {
        val root = File("/games")
        val pick = SourceDestinations.pick("snes", null, null, systems, emptyList(), emptyList(), firstRoot = root)
        assertEquals(File(root, "snes"), pick.folder)
        // With no games folder at all there is still nowhere to put it.
        assertEquals(null, SourceDestinations.pick("snes", null, null, systems, emptyList(), emptyList()).folder)
    }

    @Test
    fun `a result that names a console system goes to that system folder`() {
        val pick = SourceDestinations.pick(null, null, "PSP", systems, listOf(pspFolder to psp), listOf(root))
        assertEquals("psp", pick.systemId)
        assertEquals(pspFolder, pick.folder)
    }

    @Test
    fun `a console system with no folder says which one is missing instead of using the PC folder`() {
        val pick = SourceDestinations.pick(null, null, "Sony PlayStation Portable", systems, emptyList(), listOf(root))
        assertEquals("psp", pick.systemId)
        assertNull(pick.folder)
    }

    @Test
    fun `a PC result goes to the windows folder, else the first games folder`() {
        assertEquals(winFolder, SourceDestinations.pick("pc", null, "Windows", systems, listOf(winFolder to windows), listOf(root)).folder)
        val noSystemFolder = SourceDestinations.pick("pc", null, null, systems, emptyList(), listOf(root))
        assertEquals("pc", noSystemFolder.systemId)
        assertEquals(root, noSystemFolder.folder)
        assertNull(SourceDestinations.pick("pc", null, null, systems, emptyList(), emptyList()).folder)
    }

    @Test
    fun `the PC list drops results that name a console system`() {
        val names = SourceDestinations.consoleNamesOf(systems)
        assertFalse("windows is not a console", "windows" in names)
        assertTrue(SourceDestinations.isPcResult(null, names))
        assertTrue(SourceDestinations.isPcResult("", names))
        assertTrue(SourceDestinations.isPcResult("Windows, Linux", names))
        assertTrue(SourceDestinations.isPcResult("Something nobody knows", names))
        assertFalse(SourceDestinations.isPcResult("PSP", names))
        assertFalse(SourceDestinations.isPcResult("Sony PlayStation Portable", names))
        assertFalse(SourceDestinations.isPcResult("snes", names))
        assertTrue(SourceDestinations.isPcResult("PSP / Windows", names))
    }

    @Test
    fun `a source writing the system its own way is still not a PC result`() {
        val names = SourceDestinations.consoleNamesOf(systems)
        // The word of the name, not the ES-DE id or the whole name: how a torrent site writes it.
        assertFalse(SourceDestinations.isPcResult("Vita", names))
        assertFalse(SourceDestinations.isPcResult("PS Vita", names))
        assertFalse(SourceDestinations.isPcResult("PlayStation Portable", names))
        assertFalse(SourceDestinations.isPcResult("Games - PSP", names))
        // Words that say nothing about the system do not turn a PC result into a console one.
        assertTrue(SourceDestinations.isPcResult("Games", names))
        assertTrue(SourceDestinations.isPcResult("Sony", names))
        assertTrue(SourceDestinations.isPcResult("Windows 10 64-bit", names))
    }

    @Test
    fun `a badge can name the console system when the platform field is empty`() {
        val names = SourceDestinations.consoleNamesOf(systems)
        fun result(platform: String?, vararg badges: String) =
            AcquireContentResult("id", "title", null, null, platform, null, "{}", badges = badges.toList())
        assertFalse(SourceDestinations.isPcResult(result(null, "PSP"), names))
        assertFalse(SourceDestinations.isPcResult(result("", "Vita", "English"), names))
        assertTrue(SourceDestinations.isPcResult(result(null, "English"), names))
        assertTrue(SourceDestinations.isPcResult(result("Windows", "PSP"), names))
    }
}
