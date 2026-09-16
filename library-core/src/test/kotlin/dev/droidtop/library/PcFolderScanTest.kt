package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rig's own layout, folder for folder: every case here is a real
 * folder under the user's games root on 2026-09-16, where build 531
 * listed seven wrapper folders as games and hid the games inside them.
 */
class PcFolderScanTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun game(path: String, exe: String = "Game.exe") {
        val dir = File(temp.root, path)
        dir.mkdirs()
        File(dir, exe).writeText("MZ")
    }

    private fun dir(path: String) {
        File(temp.root, path).mkdirs()
    }

    private fun found(): List<String> =
        PcFolderScan.gamesUnder(temp.root).map { it.toRelativeString(temp.root).replace(File.separatorChar, '/') }

    @Test
    fun `a container folder contributes the games below it and never itself`() {
        game("EA/SimCity")
        dir("EA/.gamenative")
        game("Ubisoft/Far Cry 5")
        game("Ubisoft/Far Cry New Dawn")
        assertEquals(listOf("EA/SimCity", "Ubisoft/Far Cry 5", "Ubisoft/Far Cry New Dawn"), found())
    }

    @Test
    fun `a store folder holding no games contributes nothing`() {
        dir("Epic/.gamenative")
        dir("GamePass/.gamenative")
        dir("battle.net/.gamenative")
        assertEquals(emptyList<String>(), found())
    }

    @Test
    fun `a rom tree is not a PC game`() {
        dir("roms/ps2")
        dir("roms/wiiu")
        assertEquals(emptyList<String>(), found())
    }

    @Test
    fun `a hidden marker folder is not a PC game`() {
        dir(".stfolder")
        game("Eternum-0.9.5-pc")
        assertEquals(listOf("Eternum-0.9.5-pc"), found())
    }

    @Test
    fun `a game's own subfolders are not further games`() {
        game("Ghost Recon Breakpoint")
        game("Ghost Recon Breakpoint/benchmark", exe = "benchmark.exe")
        assertEquals(listOf("Ghost Recon Breakpoint"), found())
    }

    @Test
    fun `an executable two levels down still names the outer folder`() {
        dir("Unreal Game/Binaries/Win64")
        File(temp.root, "Unreal Game/readme.txt").writeText("hi")
        File(temp.root, "Unreal Game/Binaries/Win64/Game-Win64-Shipping.exe").writeText("MZ")
        assertEquals(listOf("Unreal Game"), found())
    }

    @Test
    fun `a store tree yields its installed games and never its own folders`() {
        dir("Steam/steamapps/common")
        File(temp.root, "Steam/libraryfolder.vdf").writeText("{}")
        File(temp.root, "Steam/steam.dll").writeText("MZ")
        File(temp.root, "Steam/steamapps/appmanifest_1.acf").writeText("{}")
        game("Steam/steamapps/common/REPO")
        assertEquals(listOf("Steam/steamapps/common/REPO"), found())
    }

    @Test
    fun `a store's non-game tree is never walked`() {
        dir("Steam/steamapps/common")
        File(temp.root, "Steam/libraryfolder.vdf").writeText("{}")
        game("Steam/steamapps/workshop/content/1234/mod")
        assertEquals(emptyList<String>(), found())
    }
}
