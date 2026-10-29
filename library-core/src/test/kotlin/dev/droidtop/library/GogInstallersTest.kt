package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** GOG's offline installer names (Droidtop/tracker#405), as GOG's own downloads name them. */
class GogInstallersTest {

    @Test
    fun `the name, version and build come out of the file name`() {
        assertEquals(
            FolderInstaller("setup_the_witcher_3_wild_hunt_goty_1.32_(a)_(10709).exe", "The Witcher 3 Wild Hunt Goty", "1.32", "10709"),
            GogInstallers.parse("setup_the_witcher_3_wild_hunt_goty_1.32_(a)_(10709).exe"),
        )
        assertEquals(
            FolderInstaller("setup_stardew_valley_1.5.6.1988831614_(64bit)_(55193).exe", "Stardew Valley", "1.5.6.1988831614", "55193"),
            GogInstallers.parse("setup_stardew_valley_1.5.6.1988831614_(64bit)_(55193).exe"),
        )
        // No version or build: the name is all there is.
        assertEquals(FolderInstaller("SETUP_GAME.EXE", "GAME"), GogInstallers.parse("SETUP_GAME.EXE"))
    }

    @Test
    fun `other files are not installers`() {
        assertNull(GogInstallers.parse("setup.exe"))
        assertNull(GogInstallers.parse("setup_celeste_1.4.0.0_(20669)-1.bin"))
        assertNull(GogInstallers.parse("Celeste.exe"))
    }

    @Test
    fun `a game and its DLC installers are the game, different games are none`() {
        val witcher = "setup_the_witcher_3_wild_hunt_goty_1.32_(a)_(10709).exe"
        assertEquals(
            witcher,
            GogInstallers.baseOf(listOf("setup_the_witcher_3_wild_hunt_-_hearts_of_stone_1.32_(10709).exe", witcher))?.file,
        )
        assertNull(GogInstallers.baseOf(listOf("setup_celeste_1.4.0.0_(20669).exe", "setup_stardew_valley_1.5_(1).exe")))
        assertNull(GogInstallers.baseOf(listOf("readme.txt")))
    }

    @Test
    fun `the installer's row and the game it unpacked to are one card`() {
        val installer = GogInstallers.parse("setup_celeste_1.4.0.0_(20669).exe")
        fun folder(id: String, path: String, installer: FolderInstaller?) = LibraryEntry(
            id = id,
            title = installer?.title ?: path.substringAfterLast('/'),
            kind = LibraryEntryKind.WINE_PROFILE,
            pcInfo = PcInfo(installed = installer == null, installPath = path, installer = installer),
        )
        val groups = LibraryGrouping.group(
            listOf(
                // Kept in a folder the person named, unpacked into droidtop's own storage under the installer's name.
                folder("folder:CUSTOM_GAME_1", "/sdcard/Games/Installers/celeste-gog", installer),
                folder("folder:CUSTOM_GAME_2", "/data/Unpacked/Celeste", null),
            ),
            roots = listOf("/sdcard/Games", "/data/Unpacked"),
        )
        assertEquals(1, groups.size)
        assertEquals("Celeste", groups.single().game.name)
    }
}
