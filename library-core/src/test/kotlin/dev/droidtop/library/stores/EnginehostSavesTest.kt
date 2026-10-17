package dev.droidtop.library.stores

import dev.droidtop.library.GameEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Droidtop/tracker#580: the folder named to Enginehost lines its engine's saves up with the game's Windows build. */
class EnginehostSavesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun renpyIsGivenItsWindowsFolderSoItsSaveDirectoryLandsUnderRenPy() {
        val prefix = temp.newFolder("steam-698780")
        val roaming = EnginehostSaves.roamingOf(prefix)
        assertEquals(File(prefix, "drive_c/users/droidtop/AppData/Roaming"), roaming)
        // Ren'Py writes <saveRoot>/<save_directory>; Steam Cloud has %APPDATA%/RenPy/<save_directory>.
        val root = EnginehostSaves.saveRootIn(roaming, GameEngine.RENPY)
        assertEquals(File(roaming, "RenPy"), root)
        assertEquals(File(roaming, "RenPy/DDLC-1454445547"), File(root, "DDLC-1454445547"))
    }

    @Test
    fun godotAndEveryOtherEngineAreGivenAppDataItself() {
        val roaming = EnginehostSaves.roamingOf(temp.newFolder("steam-1"))
        assertEquals(roaming, EnginehostSaves.saveRootIn(roaming, GameEngine.GODOT))
        assertEquals(roaming, EnginehostSaves.saveRootIn(roaming, GameEngine.RPG_MAKER_MV))
    }

    @Test
    fun godotsLowercaseFolderTakesTheWindowsNameForASyncAndGoesBackAfter() {
        val roaming = temp.newFolder("Roaming")
        File(roaming, "godot/app_userdata/MyGame").mkdirs()
        File(roaming, "godot/app_userdata/MyGame/save.dat").writeText("slot 1")

        assertTrue(EnginehostSaves.toWindowsLayout(roaming))
        assertTrue(File(roaming, "Godot/app_userdata/MyGame/save.dat").isFile)
        assertEquals(setOf("Godot"), roaming.list()!!.toSet())

        assertTrue(EnginehostSaves.toEngineLayout(roaming))
        assertEquals(setOf("godot"), roaming.list()!!.toSet())
        assertEquals("slot 1", File(roaming, "godot/app_userdata/MyGame/save.dat").readText())
    }

    @Test
    fun nothingToRenameOrBothNamesPresentLeavesTheFolderAlone() {
        val roaming = temp.newFolder("Roaming")
        assertFalse(EnginehostSaves.toWindowsLayout(roaming))
        File(roaming, "Godot").mkdirs()
        // Already in Windows' name: nothing to do.
        assertFalse(EnginehostSaves.toWindowsLayout(roaming))
        // A case-sensitive card holding both: neither is moved over the other.
        File(roaming, "godot").mkdirs()
        if (roaming.list()!!.size == 2) {
            assertFalse(EnginehostSaves.toWindowsLayout(roaming))
            assertFalse(EnginehostSaves.toEngineLayout(roaming))
        }
    }
}
