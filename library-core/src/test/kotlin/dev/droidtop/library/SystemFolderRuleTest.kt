package dev.droidtop.library

import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.holdsSystemFiles
import dev.droidtop.library.consoles.resolveSystemFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A folder is a ROM system folder only when it holds that system's files.
 * Synthetic trees shaped like the owner's audit (`Manual/ags`, `adult/flash`);
 * none of the owner's files are read.
 */
class SystemFolderRuleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    private val ags = ConsoleSystemDef("ags", "Adventure Game Studio", setOf("desktop", "sh"), null)
    private val flash = ConsoleSystemDef("flash", "Adobe Flash", setOf("ruf", "swf"), null)
    private val gba = ConsoleSystemDef("gba", "Game Boy Advance", setOf("gba"), null)
    private val systems = listOf(ags, flash, gba).associateBy { it.id }

    private fun file(path: String, text: String = "x") {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    @Test
    fun `a folder holds a system's files directly or in a folder of its own`() {
        file("a/Game.gba")
        file("b/Mario/Mario.gba")
        file("c/readme.txt")
        file("d/Game/readme.txt")
        assertTrue(holdsSystemFiles(File(tmp.root, "a"), gba))
        assertTrue(holdsSystemFiles(File(tmp.root, "b"), gba))
        assertFalse(holdsSystemFiles(File(tmp.root, "c"), gba))
        assertFalse(holdsSystemFiles(File(tmp.root, "d"), gba))
    }

    @Test
    fun `an unfilled system folder still counts, a collection that only shares the name does not`() {
        File(tmp.root, "gba").mkdirs()
        file("flash/systeminfo.txt")
        file("flash/.hidden")
        assertNotNull(resolveSystemFolder(File(tmp.root, "gba"), systems))
        assertNotNull(resolveSystemFolder(File(tmp.root, "flash"), systems))
        file("Manual/ags/Apprentice/App.exe", "MZ")
        assertNull(resolveSystemFolder(File(tmp.root, "Manual/ags"), systems))
    }

    @Test
    fun `the engine walk finds the AGS games in a folder called ags`() {
        file("Manual/ags/Apprentice I Deluxe/acsetup.cfg")
        file("Manual/ags/Apprentice I Deluxe/App.exe", "MZ")
        file("Manual/ags/Heroines Quest/Heroines Quest.ags")
        file("Manual/ags/Heroines Quest/acsetup.cfg")

        val found = GameEngineDetector.scan(tmp.root, systems, defs)

        assertEquals(listOf("Apprentice I Deluxe", "Heroines Quest"), found.map { it.displayFolder.name }.sorted())
        assertTrue(found.all { it.engine == GameEngine.AGS })
    }

    @Test
    fun `the PC walk finds a plain program in a folder called ags`() {
        file("Manual/ags/5 Days a Stranger/5days.exe", "MZ")
        val top = PcFolderScan.scanTopLevel(File(tmp.root, "Manual"), defs, PcFolderScan.Options(systemsById = systems))
        assertEquals(listOf("5 Days a Stranger"), top.games.map { it.name })
        assertEquals(emptyMap<String, Int>(), top.skips.counts())
    }

    @Test
    fun `an AIR game in a folder called flash is the game, and its runtime's swf files are not ROMs`() {
        file("adult/flash/Bunnycop/Bunnycop.exe", "MZ")
        file("adult/flash/Bunnycop/mimetype", "application/vnd.adobe.air-application-installer-package+zip")
        file("adult/flash/Bunnycop/META-INF/AIR/extensions/com.example.ane/library.swf", "FWS")
        file("adult/flash/Bunnycop/META-INF/AIR/extensions/com.example.ane/META-INF/ANE/default/library.swf", "FWS")

        val found = GameEngineDetector.scan(tmp.root, systems, defs)

        assertEquals(listOf("Bunnycop"), found.map { it.displayFolder.name })
        assertEquals(GameEngine.FLASH_AIR, found.single().engine)
        assertFalse(GameEngineDetector.isConsoleSystemFolder(File(tmp.root, "adult/flash"), systems, 2))
    }

    @Test
    fun `a folder of swf files is still the flash system`() {
        file("roms/flash/Some Game.swf", "FWS")
        assertTrue(GameEngineDetector.isConsoleSystemFolder(File(tmp.root, "roms/flash"), systems, 2))
    }
}
