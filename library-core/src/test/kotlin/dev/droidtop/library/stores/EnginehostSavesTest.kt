package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Droidtop/tracker#580: droidtop names the game's own save folder to Enginehost, and Enginehost reports exactly that
 * folder when it took it, so the upload after the game ends is decided by equality.
 */
class EnginehostSavesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val passed = "/storage/emulated/0/droidtop/saves/steam-698780/drive_c/users/droidtop/AppData/Roaming/RenPy/DDLC-1454445547"

    @Test
    fun theFolderDroidtopPassedIsOurs() {
        assertTrue(EnginehostSaves.sameFolder(passed, passed))
        assertTrue(EnginehostSaves.sameFolder(passed, "$passed/"))
    }

    @Test
    fun aFolderInsideOrAboveIsNotTheOnePassed() {
        // Before tracker#580's correction an engine added its own folder inside; that is no longer droidtop's folder.
        assertFalse(EnginehostSaves.sameFolder(passed, "$passed/DDLC-1454445547"))
        assertFalse(EnginehostSaves.sameFolder(passed, passed.substringBeforeLast('/')))
    }

    @Test
    fun aRefusedPathOrAGameSavingBesideItselfUploadsNothing() {
        assertFalse(EnginehostSaves.sameFolder(passed, "/storage/emulated/0/Games/DDLC"))
        assertFalse(EnginehostSaves.sameFolder(passed, null))
        assertFalse(EnginehostSaves.sameFolder(null, passed))
        assertFalse(EnginehostSaves.sameFolder("", ""))
    }

    @Test
    fun onlyFoldersEnginehostWouldTakeArePassed() {
        assertTrue(EnginehostSaves.acceptablePath(passed))
        assertTrue(EnginehostSaves.acceptablePath("/sdcard/droidtop/saves/steam-1"))
        assertTrue(EnginehostSaves.acceptablePath("/storage/1A2B-3C4D/Games/saves"))
        // A volume's root, app-private areas, relative paths and paths that climb out are refused.
        assertFalse(EnginehostSaves.acceptablePath("/storage/emulated/0"))
        assertFalse(EnginehostSaves.acceptablePath("/storage/1A2B-3C4D"))
        assertFalse(EnginehostSaves.acceptablePath("/storage/emulated/0/Android/data/com.game/files"))
        assertFalse(EnginehostSaves.acceptablePath("/sdcard/android/OBB/x"))
        assertFalse(EnginehostSaves.acceptablePath("droidtop/saves"))
        assertFalse(EnginehostSaves.acceptablePath("/sdcard/../data/x"))
        assertFalse(EnginehostSaves.acceptablePath("/data/data/dev.droidtop/files"))
        // A folder named Android deeper down is an ordinary folder.
        assertTrue(EnginehostSaves.acceptablePath("/sdcard/Games/Android/data"))
    }

    @Test
    fun aFolderIsCheckedByWritingInIt() {
        val folder = File(temp.root, "made/here")
        assertTrue(EnginehostSaves.writable(folder))
        assertEquals(0, folder.list()!!.size)
    }

    @Test
    fun savesInTheGameFolderAreTheGamesOwn() {
        val game = File("/sdcard/Games/DDLC")
        assertTrue(EnginehostSaves.inside(File("/sdcard/Games/DDLC"), game))
        assertTrue(EnginehostSaves.inside(File("/sdcard/Games/DDLC/game/saves"), game))
        assertFalse(EnginehostSaves.inside(File("/sdcard/Games/DDLC2/saves"), game))
    }

    @Test
    fun savesMadeElsewhereAreBroughtInNewerWinning() {
        val from = temp.newFolder("person")
        val into = temp.newFolder("synced")
        File(from, "1-1-LT1.save").writeText("new slot")
        File(from, "1-1-LT1.save").setLastModified(2_000_000)
        File(from, "sub").mkdirs()
        File(from, "sub/persistent").writeText("persistent")
        File(from, "older.save").writeText("older")
        File(from, "older.save").setLastModified(1_000_000)
        File(into, "older.save").writeText("kept")
        File(into, "older.save").setLastModified(3_000_000)

        assertEquals(2, EnginehostSaves.adopt(from, into))
        assertEquals("new slot", File(into, "1-1-LT1.save").readText())
        assertEquals("persistent", File(into, "sub/persistent").readText())
        assertEquals("kept", File(into, "older.save").readText())
        // A second pass finds nothing newer.
        assertEquals(0, EnginehostSaves.adopt(from, into))
    }

    @Test
    fun savesAreTakenOnlyFromSharedStorageNeverFromDroidtopsOwnFiles() {
        // Whoever reports names the folder droidtop copies from (the broadcast, an app's plugin): a private folder, or a
        // link to one, copies nothing. The test's own folder is private storage, as droidtop's files are.
        val private = temp.newFolder("files")
        assertEquals(null, EnginehostSaves.readableSaveFolder(private.absolutePath))
        val link = File(temp.root, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), private.toPath())
        assertEquals(null, EnginehostSaves.readableSaveFolder(link.absolutePath))
        assertEquals(null, EnginehostSaves.readableSaveFolder(""))
        assertEquals(null, EnginehostSaves.readableSaveFolder("/storage/emulated/0/no/such/folder/here"))
    }
}
