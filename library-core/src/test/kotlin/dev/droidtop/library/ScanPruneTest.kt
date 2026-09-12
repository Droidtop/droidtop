package dev.droidtop.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one prune rule, against the real shapes it was written from: the
 * rig's own `G:\games` (a Steam library folder with `steamapps` beside
 * `libraryfolder.vdf`, `EA/SimCity/__Installer`, a `GOG/Prison
 * Architect/Launcher` that is a GAME's folder) and a real Steam client
 * install root.
 */
class ScanPruneTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(path: String): File = File(temp.root, path).apply { mkdirs() }

    private fun file(path: String): File =
        File(temp.root, path).apply { parentFile?.mkdirs(); writeText("x") }

    @Test
    fun `hidden and filesystem marker folders are never games`() {
        for (name in listOf(
            ".stfolder", ".stversions", ".stignore", ".thumbnails", ".gamenative", ".Trash-1000",
            "System Volume Information", "\$RECYCLE.BIN", "lost+found", "FOUND.000",
        )) {
            assertFalse(name, ScanPrune.isScannableFolder(dir(name)))
        }
    }

    @Test
    fun `an ordinary games folder is scannable`() {
        for (name in listOf(
            "adult", "renpy", "My Cat Girl Lover", "roms", "Launcher", "content",
            "Sonic Adventure", "renpy-game", "found", "recycle bin of doom",
        )) {
            assertTrue(name, ScanPrune.isScannableFolder(dir("plain/$name")))
        }
    }

    @Test
    fun `a steam library folder exposes steamapps common and nothing else`() {
        file("Steam/libraryfolder.vdf")
        dir("Steam/steamapps/common/Amorous")
        dir("Steam/steamapps/workshop/content/108600/123456")
        dir("Steam/steamapps/downloading")
        dir("Steam/steamapps/shadercache")
        dir("Steam/steamapps/temp")
        dir("Steam/steamapps/sourcemods")
        dir("Steam/userdata")

        // On the way to the games subtree, and inside it, stays open --
        // yardstick item 5: a store-installed engine game is detected.
        assertTrue(ScanPrune.isScannableFolder(dir("Steam/steamapps")))
        assertTrue(ScanPrune.isScannableFolder(dir("Steam/steamapps/common")))
        assertTrue(ScanPrune.isScannableFolder(dir("Steam/steamapps/common/Amorous")))
        assertTrue(ScanPrune.isScannableFolder(dir("Steam/steamapps/common/Amorous/game")))

        for (managed in listOf(
            "Steam/steamapps/workshop",
            "Steam/steamapps/workshop/content",
            "Steam/steamapps/workshop/content/108600/123456",
            "Steam/steamapps/downloading",
            "Steam/steamapps/shadercache",
            "Steam/steamapps/temp",
            "Steam/steamapps/sourcemods",
            "Steam/userdata",
        )) {
            val reason = ScanPrune.skipReason(File(temp.root, managed))
            assertNotNull(managed, reason)
            assertTrue(managed, reason!!.contains("Steam owns this tree"))
        }
    }

    @Test
    fun `a steam client install root is the same shape and pruned the same way`() {
        // No libraryfolder.vdf here, only steamapps -- the client's own
        // root, verified against a real install.
        for (clientFolder in listOf(
            "appcache", "bin", "clientui", "config", "controller_base", "depotcache",
            "dumps", "friends", "graphics", "logs", "music", "package", "public",
            "resource", "steam", "steamui", "tenfoot", "userdata",
        )) {
            dir("Program Files (x86)/Steam/$clientFolder")
        }
        dir("Program Files (x86)/Steam/steamapps/common/Left 4 Dead 2")

        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "Program Files (x86)/Steam/steamapps/common/Left 4 Dead 2")))
        for (clientFolder in listOf("bin", "config", "logs", "userdata", "steamui")) {
            assertFalse(
                clientFolder,
                ScanPrune.isScannableFolder(File(temp.root, "Program Files (x86)/Steam/$clientFolder")),
            )
        }
    }

    @Test
    fun `a games root that merely holds a folder named steam is not pruned`() {
        // The rig's own layout: G:\games\Steam IS a Steam library folder,
        // but the games root above it is not, and its siblings are games.
        file("games/Steam/libraryfolder.vdf")
        dir("games/adult/renpy/SomeGame")
        dir("games/roms/ps2")
        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "games/adult")))
        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "games/adult/renpy/SomeGame")))
        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "games/roms/ps2")))
        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "games/Steam")))
    }

    @Test
    fun `a store installer payload folder is not a game`() {
        dir("EA/SimCity/__Installer")
        val reason = ScanPrune.skipReason(File(temp.root, "EA/SimCity/__Installer"))
        assertEquals("it holds a store's installer payload, not a game", reason)
        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "EA/SimCity")))
    }

    @Test
    fun `a gog galaxy client root exposes only its games folder`() {
        dir("GOG Galaxy/Dependencies")
        dir("GOG Galaxy/Dependencies-Temp")
        dir("GOG Galaxy/Games/Cyberpunk 2077")
        assertFalse(ScanPrune.isScannableFolder(File(temp.root, "GOG Galaxy/Dependencies")))
        assertFalse(ScanPrune.isScannableFolder(File(temp.root, "GOG Galaxy/Dependencies-Temp")))
        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "GOG Galaxy/Games")))
        assertTrue(ScanPrune.isScannableFolder(File(temp.root, "GOG Galaxy/Games/Cyberpunk 2077")))
    }

    @Test
    fun `a gog install folder of games is not a galaxy client root`() {
        // G:\games\GOG holds game folders; one of them (Prison Architect)
        // ships its own Launcher folder, which must stay scannable.
        dir("GOG/Prison Architect/Launcher")
        dir("GOG/Games")
        dir("GOG/Dependencies")
        assertNull(ScanPrune.skipReason(File(temp.root, "GOG/Prison Architect/Launcher")))
        assertNull(ScanPrune.skipReason(File(temp.root, "GOG/Dependencies")))
    }
}
