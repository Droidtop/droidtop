package dev.droidtop.library.stores

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Droidtop/tracker#580: droidtop names the game's own save folder to Enginehost, and Enginehost reports exactly that
 * folder when it took it, so the upload after the game ends is decided by equality.
 */
class EnginehostSavesTest {

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
}
