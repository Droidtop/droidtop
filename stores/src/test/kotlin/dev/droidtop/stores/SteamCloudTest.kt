package dev.droidtop.stores

import dev.droidtop.stores.steam.CloudKey
import dev.droidtop.stores.steam.SaveLayout
import dev.droidtop.stores.steam.SavePattern
import dev.droidtop.stores.steam.SaveRoot
import dev.droidtop.stores.steam.SteamCloudPlan
import dev.droidtop.stores.steam.SteamCloudPlan.Decision
import dev.droidtop.stores.steam.SteamCloudPlan.Facts
import dev.droidtop.stores.steam.SteamCloudState
import dev.droidtop.stores.steam.SteamCloudSync
import dev.droidtop.stores.steam.SteamUfs
import dev.droidtop.stores.steam.SteamUfsParser
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Steam Cloud saves: the decision, the names, the folders and the save locations (docs/SPEC.md 7g, "Stores"). */
class SteamCloudTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun facts(sha: String, time: Long = 1_000L) = Facts(sha, 10L, time)

    @Test
    fun `equal files on both sides need nothing`() {
        val both = mapOf("a" to facts("1"))
        assertEquals(Decision.UpToDate, SteamCloudPlan.decide(emptyMap(), both, both))
        assertEquals(Decision.UpToDate, SteamCloudPlan.decide(mapOf("a" to "0"), both, both))
        assertEquals(Decision.UpToDate, SteamCloudPlan.decide(emptyMap(), emptyMap(), emptyMap()))
    }

    @Test
    fun `only this device changed, so the files go up and a deleted one is deleted there`() {
        val base = mapOf("a" to "1", "b" to "2", "c" to "3")
        val local = mapOf("a" to facts("1"), "b" to facts("22"), "d" to facts("4"))
        val remote = mapOf("a" to facts("1"), "b" to facts("2"), "c" to facts("3"))
        val decision = SteamCloudPlan.decide(base, local, remote) as Decision.Upload
        assertEquals(setOf("b", "d"), decision.send.toSet())
        assertEquals(listOf("c"), decision.removeRemote)
    }

    @Test
    fun `only the cloud changed, so the files come down and a file it dropped goes here`() {
        val base = mapOf("a" to "1", "b" to "2")
        val local = mapOf("a" to facts("1"), "b" to facts("2"))
        val remote = mapOf("a" to facts("11"), "c" to facts("3"))
        val decision = SteamCloudPlan.decide(base, local, remote) as Decision.Download
        assertEquals(setOf("a", "c"), decision.fetch.toSet())
        assertEquals(listOf("b"), decision.removeLocal)
    }

    @Test
    fun `a first sync with files on one side only takes them across`() {
        val files = mapOf("a" to facts("1"))
        assertTrue(SteamCloudPlan.decide(emptyMap(), files, emptyMap()) is Decision.Upload)
        assertTrue(SteamCloudPlan.decide(emptyMap(), emptyMap(), files) is Decision.Download)
    }

    @Test
    fun `a first sync with different files on both sides is a conflict`() {
        val local = mapOf("a" to facts("1", time = 5_000L))
        val remote = mapOf("a" to facts("2", time = 9_000L), "b" to facts("3", time = 2_000L))
        val conflict = SteamCloudPlan.decide(emptyMap(), local, remote) as Decision.Conflict
        assertEquals(5_000L, conflict.local.timeMs)
        assertEquals(9_000L, conflict.cloud.timeMs)
        assertEquals(1, conflict.local.files)
        assertEquals(2, conflict.cloud.files)
    }

    @Test
    fun `both sides changed since the last sync is a conflict, and choosing a side makes it win`() {
        val base = mapOf("a" to "1")
        val local = mapOf("a" to facts("2"))
        val remote = mapOf("a" to facts("3"))
        assertTrue(SteamCloudPlan.decide(base, local, remote) is Decision.Conflict)
        // Treating the cloud as unchanged is how "keep this device" is applied.
        val keepLocal = SteamCloudPlan.decide(mapOf("a" to "3"), local, remote) as Decision.Upload
        assertEquals(listOf("a"), keepLocal.send)
        val keepCloud = SteamCloudPlan.decide(mapOf("a" to "2"), local, remote) as Decision.Download
        assertEquals(listOf("a"), keepCloud.fetch)
    }

    @Test
    fun `a file both sides lost says nothing and does not make a false conflict later`() {
        val base = mapOf("gone" to "1", "a" to "5")
        val local = mapOf("a" to facts("5"))
        val remote = mapOf("a" to facts("6"))
        assertTrue(SteamCloudPlan.decide(base, local, remote) is Decision.Download)
    }

    @Test
    fun `the last-synced state round trips and a damaged one is empty`() {
        val state = SteamCloudState(mapOf("%gameinstall%save.dat" to Facts("abc", 12L, 99L)))
        assertEquals(state, SteamCloudState.decode(SteamCloudState.encode(state)))
        assertEquals(SteamCloudState(), SteamCloudState.decode("nonsense"))
        assertEquals(mapOf("%gameinstall%save.dat" to "abc"), state.shas)
        val dir = temp.newFolder()
        SteamCloudState.save(dir, 7, state)
        assertEquals(state, SteamCloudState.load(dir, 7))
        assertEquals(SteamCloudState(), SteamCloudState.load(dir, 8))
    }

    @Test
    fun `Steam's prefix and file name meet however it spells them`() {
        assertEquals("%GameInstall%save0.dat", CloudKey.join("%GameInstall%", "save0.dat"))
        assertEquals("%WinAppDataLocal%Game/x.dat", CloudKey.join("%WinAppDataLocal%Game", "x.dat"))
        assertEquals("%WinAppDataLocal%Game/x.dat", CloudKey.join("%WinAppDataLocal%Game/", "x.dat"))
        assertEquals("%GameInstall%save0.dat", CloudKey.join("", "%GameInstall%save0.dat"))
        assertEquals("slot1.sav", CloudKey.join("", "slot1.sav"))
    }

    @Test
    fun `a name is one spelling whatever slashes and case it came in`() {
        val a = CloudKey.parse("%WinAppDataLocal%My Game\\Saves/Slot1.SAV")
        val b = CloudKey.parse("%winappdatalocal%my game/saves/slot1.sav")
        assertEquals(SaveRoot.WinAppDataLocal, a.root)
        assertEquals(listOf("My Game", "Saves", "Slot1.SAV"), a.segments)
        assertEquals("%WinAppDataLocal%My Game/Saves/Slot1.SAV", a.name)
        assertEquals(a.key, b.key)
    }

    @Test
    fun `a file with no root is one the Steam API wrote, in the game's remote folder`() {
        val parsed = CloudKey.parse("profile/slot1.dat")
        assertEquals(SaveRoot.SteamUserData, parsed.root)
        assertEquals("profile/slot1.dat", parsed.name)
        assertEquals(SaveRoot.None, CloudKey.parse("%Nonsense%x").root)
    }

    @Test
    fun `Steam's older root names are the roots they always meant`() {
        assertEquals(SaveRoot.SteamUserData, SaveRoot.from("%SteamUserBaseStorage%"))
        assertEquals(SaveRoot.WinMyDocuments, SaveRoot.from("SteamCloudDocuments"))
        assertEquals(SaveRoot.Root, SaveRoot.from("%WindowsHome%"))
        assertEquals(SaveRoot.WinAppDataLocalLow, SaveRoot.from("WinAppDataLocalLow"))
        assertFalse(SaveRoot.LinuxHome.isWindows)
        assertTrue(SaveRoot.WinSavedGames.isWindows)
    }

    @Test
    fun `a pattern matches whole names, case-insensitively`() {
        val glob = SaveLayout.glob("*.sav")
        assertTrue(glob.matches("Slot1.SAV"))
        assertFalse(glob.matches("slot1.sav.bak"))
        assertTrue(SaveLayout.glob("slot?.dat").matches("slot3.dat"))
        assertTrue(SaveLayout.glob("").matches("anything.bin"))
        assertFalse(SaveLayout.glob("a.b").matches("axb"))
    }

    private fun write(file: File, text: String = "x"): File {
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun layout(vararg patterns: SavePattern, accountId: Long = 42L): Pair<SaveLayout, File> {
        val prefix = temp.newFolder("prefix")
        val install = temp.newFolder("game")
        val dirs = SaveLayout.windowsDirs(prefix, "xuser", install, accountId, 10)
        return SaveLayout(dirs, 76561198000000042L, accountId, SteamUfs(patterns = patterns.toList())) to prefix
    }

    @Test
    fun `the Wine prefix's Windows folders are where Wine puts them`() {
        val dirs = SaveLayout.windowsDirs(File("/p/.wine"), "xuser", File("/games/G"), 42L, 10)
        assertEquals(File("/p/.wine/drive_c/users/xuser/Documents"), dirs[SaveRoot.WinMyDocuments])
        assertEquals(File("/p/.wine/drive_c/users/xuser/AppData/LocalLow"), dirs[SaveRoot.WinAppDataLocalLow])
        assertEquals(File("/p/.wine/drive_c/users/xuser/Saved Games"), dirs[SaveRoot.WinSavedGames])
        assertEquals(File("/p/.wine/drive_c/ProgramData"), dirs[SaveRoot.WinProgramData])
        assertEquals(File("/p/.wine/drive_c/Program Files (x86)/Steam/userdata/42/10/remote"), dirs[SaveRoot.SteamUserData])
        assertEquals(File("/games/G"), dirs[SaveRoot.GameInstall])
    }

    @Test
    fun `a scan finds what the patterns match, named the way the cloud names it`() {
        val (layout, prefix) = layout(
            SavePattern(SaveRoot.WinAppDataLocal, "My Game/{Steam3AccountID}", "*.sav", recursive = 0),
            SavePattern(SaveRoot.GameInstall, "", "profile*.dat", recursive = 1),
        )
        val local = File(prefix, "drive_c/users/xuser/AppData/Local/My Game/42")
        write(File(local, "slot1.sav"))
        write(File(local, "slot2.SAV"))
        write(File(local, "notes.txt"))
        write(File(local, "deeper/slot3.sav"))
        val install = File(prefix.parentFile, "game")
        write(File(install, "profile1.dat"))
        write(File(install, "saves/profile2.dat"))
        val names = layout.scan(emptyList()).map { it.name.name }.sorted()
        assertEquals(
            listOf(
                "%GameInstall%profile1.dat",
                "%GameInstall%saves/profile2.dat",
                "%WinAppDataLocal%My Game/42/slot1.sav",
                "%WinAppDataLocal%My Game/42/slot2.SAV",
            ),
            names,
        )
    }

    @Test
    fun `files in the game's remote folder are saves too`() {
        val (layout, prefix) = layout()
        write(File(prefix, "drive_c/Program Files (x86)/Steam/userdata/42/10/remote/profile/slot1.dat"))
        assertEquals(listOf("profile/slot1.dat"), layout.scan(emptyList()).map { it.name.name })
    }

    @Test
    fun `a file the cloud names is compared even where no pattern would look`() {
        val (layout, prefix) = layout(SavePattern(SaveRoot.WinMyDocuments, "My Games/G", "*.sav", recursive = 0))
        write(File(prefix, "drive_c/users/xuser/Documents/My Games/G/sub/odd.sav"))
        assertTrue(layout.scan(emptyList()).isEmpty())
        val known = layout.scan(listOf("%WinMyDocuments%My Games/G/sub/odd.sav")).map { it.name.name }
        assertEquals(listOf("%WinMyDocuments%My Games/G/sub/odd.sav"), known)
    }

    @Test
    fun `a cloud name finds its file whatever case Wine made the folders in`() {
        val (layout, prefix) = layout(SavePattern(SaveRoot.WinMyDocuments, "My Games/G", "*"))
        val made = write(File(prefix, "drive_c/users/xuser/Documents/my games/g/Save1.dat"))
        assertEquals(made.canonicalFile, layout.localFile(CloudKey.parse("%WinMyDocuments%My Games/G/Save1.dat"))?.canonicalFile)
    }

    @Test
    fun `a root override puts a file where this device keeps it and the cloud still names it by Steam's root`() {
        // The cloud says %GameInstall%saves; this device keeps those under AppData/Roaming/MyGame/saves.
        val (layout, prefix) = layout(
            SavePattern(
                root = SaveRoot.WinAppDataRoaming, path = "MyGame/saves", pattern = "*.dat", recursive = 0,
                uploadRoot = SaveRoot.GameInstall, uploadPath = "saves",
            ),
        )
        val file = layout.localFile(CloudKey.parse("%GameInstall%saves/slot1.dat"))
        assertEquals(File(prefix, "drive_c/users/xuser/AppData/Roaming/MyGame/saves/slot1.dat"), file)
        write(file!!)
        assertEquals(listOf("%GameInstall%saves/slot1.dat"), layout.scan(emptyList()).map { it.name.name })
    }

    @Test
    fun `a root nobody mapped is the root's own folder, and a root a prefix lacks has no file`() {
        val (layout, prefix) = layout()
        assertEquals(
            File(prefix, "drive_c/users/xuser/Saved Games/G/x.sav"),
            layout.localFile(CloudKey.parse("%WinSavedGames%G/x.sav")),
        )
        assertNull(layout.localFile(CloudKey.parse("%LinuxHome%x")))
    }

    private fun ufsOf(vararg children: KeyValue) = KeyValue("1234").apply {
        this.children.add(KeyValue("ufs").apply { this.children.addAll(children) })
    }

    private fun kv(name: String, value: String? = null, vararg children: KeyValue) = KeyValue(name, value).apply { this.children.addAll(children) }

    @Test
    fun `the save locations are read from product info, Windows entries only`() {
        val app = ufsOf(
            kv("quota", "1000"),
            kv("maxnumfiles", "50"),
            kv(
                "savefiles", null,
                kv("0", null, kv("root", "WinAppDataLocal"), kv("path", "Game/{64BitSteamID}"), kv("pattern", "*.sav"), kv("recursive", "1")),
                kv("1", null, kv("root", "LinuxHome"), kv("path", ".game"), kv("pattern", "*"), kv("platforms", null, kv("0", "linux"))),
                kv("2", null, kv("root", "gameinstall"), kv("path", "/"), kv("pattern", "*.dat")),
            ),
        )
        val ufs = SteamUfsParser.parse(app)
        assertEquals(1000, ufs.quota)
        assertEquals(50, ufs.maxNumFiles)
        assertEquals(2, ufs.patterns.size)
        assertEquals(SavePattern(SaveRoot.WinAppDataLocal, "Game/{64BitSteamID}", "*.sav", 1), ufs.patterns[0])
        // "/" is the root itself
        assertEquals("", ufs.patterns[1].path)
        assertEquals(SaveRoot.GameInstall, ufs.patterns[1].root)
    }

    @Test
    fun `a root override moves the local folder and keeps the cloud name`() {
        val app = ufsOf(
            kv("savefiles", null, kv("0", null, kv("root", "gameinstall"), kv("path", "saves"), kv("pattern", "*.dat"))),
            kv(
                "rootoverrides", null,
                kv(
                    "0", null,
                    kv("root", "gameinstall"), kv("os", "Windows"), kv("useinstead", "WinAppDataRoaming"), kv("addpath", "MyGame\\data"),
                    kv("pathtransforms", null, kv("0", null, kv("find", "saves"), kv("replace", "slots"))),
                ),
                kv("1", null, kv("root", "gameinstall"), kv("os", "MacOS"), kv("useinstead", "MacHome"), kv("addpath", "x")),
            ),
        )
        val pattern = SteamUfsParser.parse(app).patterns.single()
        assertEquals(SaveRoot.WinAppDataRoaming, pattern.root)
        assertEquals("MyGame/data/slots", pattern.path)
        assertEquals(SaveRoot.GameInstall, pattern.uploadRoot)
        assertEquals("saves", pattern.uploadPath)
    }

    @Test
    fun `a file's hash is its SHA-1 in lower-case hex`() {
        val file = write(temp.newFile("abc.txt"), "abc")
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SteamCloudSync.sha1Hex(file))
        assertEquals("0aff", SteamCloudSync.hex(byteArrayOf(0x0a, 0xff.toByte())))
        assertEquals(listOf<Byte>(0x0a, 0xff.toByte()), SteamCloudSync.unhex("0aff").toList())
    }
}
