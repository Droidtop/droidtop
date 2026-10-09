package dev.droidtop.runtime.windows.utils

import dev.droidtop.library.stores.StorePlayer
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [SteamworksShim]'s pure parts (docs/SPEC.md 5b, "Steamworks in the prefix"). Names are placeholders. */
class SteamworksShimTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(path: String, text: String = "x"): File =
        File(tmp.root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun `a Steam library game is found by its app manifest's installdir`() {
        file("lib/steamapps/appmanifest_111.acf", "\"AppState\"\n{\n\t\"appid\"\t\t\"111\"\n\t\"installdir\"\t\t\"Other Game\"\n}\n")
        file(
            "lib/steamapps/appmanifest_222.acf",
            "\"AppState\"\n{\n\t\"appid\"\t\t\"222\"\n\t\"installdir\"\t\t\"Sample Game\"\n" +
                "\t\"InstalledDepots\"\n\t{\n\t\t\"223\"\n\t\t{\n\t\t\t\"manifest\"\t\t\"1\"\n\t\t\t\"dlcappid\"\t\t\"333\"\n\t\t}\n\t}\n}\n",
        )
        val game = File(tmp.root, "lib/steamapps/common/Sample Game").apply { mkdirs() }
        assertEquals(222, SteamworksShim.appIdFromManifest(game))
        assertEquals(setOf("333"), SteamworksShim.installedDlcFromManifest(game, 222))
        assertNull(SteamworksShim.appIdFromManifest(File(tmp.root, "lib").apply { mkdirs() }))
    }

    @Test
    fun `a game is identified by its store id, its library's manifest or its own app id file`() {
        file("b/Game.exe")
        assertEquals(10, SteamworksShim.detect("steam:10", File(tmp.root, "b"))?.appId)
        // Not a Steam game by any sign: nothing to answer as.
        assertNull(SteamworksShim.detect("gog:5", File(tmp.root, "b")))
        file("b/steam_appid.txt", "42\n")
        val shipped = SteamworksShim.detect("folder:1", File(tmp.root, "b"))
        assertEquals(42, shipped?.appId)
        // The id says which Steam app it is, not that Steam owns this copy.
        assertEquals(false, shipped?.owned)
        assertEquals(true, SteamworksShim.detect("steam:10", null)?.owned)
    }

    @Test
    fun `Steamworks is on for a game Steam owns and off for any other until its own choice says otherwise`() {
        val owned = SteamworksShim.Need(10, "droidtop's Steam")
        val shipped = SteamworksShim.Need(42, "the game's steam_appid.txt", owned = false)
        assertEquals(owned, SteamworksShim.resolve(null, null, owned))
        assertNull(SteamworksShim.resolve("off", null, owned))
        assertNull(SteamworksShim.resolve(null, null, shipped))
        assertNull(SteamworksShim.resolve(null, null, null))
        assertEquals(42, SteamworksShim.resolve("on", null, shipped)?.appId)
        // A typed app id wins over the detected one, and is enough on its own once switched on.
        assertEquals(99, SteamworksShim.resolve("on", 99, shipped)?.appId)
        assertEquals(99, SteamworksShim.resolve("on", 99, null)?.appId)
        // Switched on with no app id known anywhere: nothing to start as.
        assertNull(SteamworksShim.resolve("on", null, null))
    }

    @Test
    fun `paths are named by the drive that maps them, else below Z`() {
        val drives = listOf("D" to "/mnt/games", "E" to "/mnt/games/big")
        assertEquals("E:\\Some Game\\Game.exe", SteamworksShim.windowsPath(drives, File("/mnt/games/big/Some Game/Game.exe")))
        assertEquals("D:\\Other\\Game.exe", SteamworksShim.windowsPath(drives, File("/mnt/games/Other/Game.exe")))
        assertEquals("Z:\\sdcard\\Game.exe", SteamworksShim.windowsPath(drives, File("/sdcard/Game.exe")))
        // A folder whose name only starts like a drive's is not on it.
        assertEquals("Z:\\mnt\\gamesX\\Game.exe", SteamworksShim.windowsPath(drives, File("/mnt/gamesX/Game.exe")))
    }

    @Test
    fun `the player's account names the saves folder Steam keeps them in`() {
        val ini = SteamworksShim.userIni(StorePlayer(76561197960287930L, "someone", emptySet()), "english")
        assertTrue(ini.contains("account_steamid=76561197960287930\n"))
        assertTrue(ini.contains("account_name=someone\n"))
        // The 32-bit account id is Steam's userdata folder name.
        assertTrue(ini.contains("local_save_path=./userdata/22202\n"))
        val local = SteamworksShim.userIni(null, "german")
        assertFalse(local.contains("account_steamid"))
        assertTrue(local.contains("language=german\n"))
        assertTrue(local.contains("local_save_path=./userdata/0\n"))
    }

    @Test
    fun `only the DLC the account has is reported`() {
        assertEquals("[app::dlcs]\nunlock_all=0\n2=2\n9=9\n", SteamworksShim.appIni(setOf("9", "2")))
    }

    @Test
    fun `the loader matches the program's bitness, read off its PE header`() {
        fun pe(machine: Int): File {
            val bytes = ByteArray(0x90)
            bytes[0] = 'M'.code.toByte(); bytes[1] = 'Z'.code.toByte()
            bytes[0x3C] = 0x80.toByte()
            bytes[0x80] = 'P'.code.toByte(); bytes[0x81] = 'E'.code.toByte()
            bytes[0x84] = (machine and 0xFF).toByte(); bytes[0x85] = (machine shr 8).toByte()
            return File(tmp.root, "pe$machine.exe").apply { writeBytes(bytes) }
        }
        assertFalse(SteamworksShim.is64Bit(pe(0x014C)))
        assertTrue(SteamworksShim.is64Bit(pe(0x8664)))
    }

    @Test
    fun `the device language becomes Steam's language code`() {
        assertEquals("german", SteamworksShim.language(Locale.GERMANY))
        assertEquals("brazilian", SteamworksShim.language(Locale("pt", "BR")))
        assertEquals("tchinese", SteamworksShim.language(Locale.TAIWAN))
        assertEquals("schinese", SteamworksShim.language(Locale.CHINA))
        assertEquals("english", SteamworksShim.language(Locale("xx")))
    }
}
