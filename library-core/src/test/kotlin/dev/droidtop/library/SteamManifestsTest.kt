package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Steam library manifests, on synthetic library folders. */
class SteamManifestsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    private fun manifest(id: String, name: String, installDir: String, flags: Int = 4) {
        val file = File(tmp.root, "Steam/steamapps/appmanifest_$id.acf")
        file.parentFile?.mkdirs()
        file.writeText(
            "\"AppState\"\n{\n\t\"appid\"\t\t\"$id\"\n\t\"Universe\"\t\t\"1\"\n\t\"name\"\t\t\"$name\"\n" +
                "\t\"StateFlags\"\t\t\"$flags\"\n\t\"installdir\"\t\t\"$installDir\"\n}\n",
        )
    }

    private fun file(path: String, text: String = "MZ") {
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    @Test
    fun `a manifest says the app, its folder and whether it is installed`() {
        val app = SteamManifests.parse(
            "\"AppState\"\n{\n\t\"appid\"\t\"1086940\"\n\t\"name\"\t\"Baldur's Gate 3\"\n\t\"StateFlags\"\t\"4\"\n\t\"installdir\"\t\"Baldurs Gate 3\"\n}",
        )!!
        assertEquals("1086940", app.appId)
        assertEquals("Baldur's Gate 3", app.name)
        assertEquals("Baldurs Gate 3", app.installDir)
        assertEquals(true, app.installed)
        assertNull(SteamManifests.parse("{}"))
    }

    @Test
    fun `a library lists its installed games however deep the program sits, and no tools`() {
        manifest("1", "Baldur's Gate 3", "Baldurs Gate 3")
        manifest("2", "Kingdom Come: Deliverance", "KingdomComeDeliverance")
        manifest("3", "Half Installed", "Half Installed", flags = 1026)
        manifest("4", "Proton 9.0", "Proton 9.0")
        manifest("5", "No Folder", "Missing")
        file("Steam/steamapps/common/Baldurs Gate 3/bin/bg3.exe")
        file("Steam/steamapps/common/KingdomComeDeliverance/Bin/Win64/KingdomCome.exe")
        file("Steam/steamapps/common/Half Installed/Game.exe")
        file("Steam/steamapps/common/Proton 9.0/proton")

        val folders = SteamManifests.installedGameFolders(File(tmp.root, "Steam")).map { it.name }

        assertEquals(listOf("Baldurs Gate 3", "KingdomComeDeliverance"), folders)
    }

    @Test
    fun `the PC walk lists a Steam game whose program is in a sub-folder`() {
        file("Steam/libraryfolder.vdf", "{}")
        manifest("1", "Baldur's Gate 3", "Baldurs Gate 3")
        manifest("2", "Mimic Logic", "Mimic Logic")
        file("Steam/steamapps/common/Baldurs Gate 3/bin/bg3.exe")
        file("Steam/steamapps/common/Mimic Logic/MimicLogic/Binaries/Win64/Mimic.exe")
        // A game folder with a program of its own and no manifest is kept.
        file("Steam/steamapps/common/Old Game/Old.exe")

        val found = PcFolderScan.gamesUnder(tmp.root, defs).map { it.toRelativeString(tmp.root).replace(File.separatorChar, '/') }

        assertEquals(
            listOf(
                "Steam/steamapps/common/Baldurs Gate 3",
                "Steam/steamapps/common/Mimic Logic",
                "Steam/steamapps/common/Old Game",
            ),
            found,
        )
    }

    @Test
    fun `a manifest's folder replaces what the walk found inside it`() {
        file("Steam/libraryfolder.vdf", "{}")
        manifest("1", "Two Payloads", "Two Payloads")
        file("Steam/steamapps/common/Two Payloads/bin/a.exe")
        file("Steam/steamapps/common/Two Payloads/bin_plus/a.exe")
        val found = PcFolderScan.gamesUnder(tmp.root, defs).map { it.name }
        assertEquals(listOf("Two Payloads"), found)
    }
}
