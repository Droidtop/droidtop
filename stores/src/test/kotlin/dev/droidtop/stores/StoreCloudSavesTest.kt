package dev.droidtop.stores

import dev.droidtop.library.stores.WinePrefixLocation
import dev.droidtop.stores.data.EpicGame
import dev.droidtop.stores.epic.EpicCloudSaves
import dev.droidtop.stores.epic.manifest.EpicManifest
import dev.droidtop.stores.gog.GOGCloudSaves
import dev.droidtop.stores.util.CloudSaveRun
import dev.droidtop.stores.util.CloudSaveRun.Folder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** GOG's and Epic's cloud saves: where the saves are, what the clouds list, and Epic's snapshot format (Droidtop/tracker#224). */
class StoreCloudSavesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val prefixDir = File("/data/prefix")
    private val prefix = WinePrefixLocation(prefixDir, "xuser")
    private val home = "/data/prefix/drive_c/users/xuser"

    // GOG

    @Test
    fun `GOG's remote config names the save locations, or none when its cloud is off`() {
        val on = """{"content":{"Windows":{"cloudStorage":{"enabled":true,"locations":[
            {"name":"__default","location":"<?APPLICATION_DATA_LOCAL?>/Game/Saves"},{"name":"cfg","location":"<?INSTALL?>/cfg"},{"name":"x","location":""}]}}}}"""
        assertEquals(
            listOf(GOGCloudSaves.Location("__default", "<?APPLICATION_DATA_LOCAL?>/Game/Saves"), GOGCloudSaves.Location("cfg", "<?INSTALL?>/cfg")),
            GOGCloudSaves.parseLocations(on, "123"),
        )
        assertNull(GOGCloudSaves.parseLocations("""{"content":{"Windows":{"cloudStorage":{"enabled":false}}}}""", "123"))
        assertNull(GOGCloudSaves.parseLocations("""{"content":{}}""", "123"))
        assertNull(GOGCloudSaves.parseLocations("not json", "123"))
    }

    @Test
    fun `a GOG game whose cloud is on with no locations keeps the Galaxy SDK's folder, unprefixed`() {
        val sdk = GOGCloudSaves.parseLocations("""{"content":{"Windows":{"cloudStorage":{"enabled":true,"locations":[]}}}}""", "555")!!
        assertEquals(listOf(GOGCloudSaves.Location("", "%LOCALAPPDATA%/GOG.com/Galaxy/Applications/555/Storage/Shared/Files")), sdk)
        assertEquals(File("$home/AppData/Local/GOG.com/Galaxy/Applications/555/Storage/Shared/Files"), GOGCloudSaves.resolve(sdk[0].template, prefix, null))
    }

    @Test
    fun `GOG's folder names map into the Wine prefix and the game's folder`() {
        val game = File("/storage/games/Game")
        assertEquals(File("$home/AppData/Local/Game/Saves"), GOGCloudSaves.resolve("<?APPLICATION_DATA_LOCAL?>/Game/Saves", prefix, game))
        assertEquals(File("$home/AppData/LocalLow/Dev/Game"), GOGCloudSaves.resolve("<?APPLICATION_DATA_LOCAL_LOW?>\\Dev\\Game", prefix, game))
        assertEquals(File("$home/Saved Games/Game"), GOGCloudSaves.resolve("<?SAVED_GAMES?>/Game", prefix, game))
        assertEquals(File("$home/Documents/My Games/G"), GOGCloudSaves.resolve("<?DOCUMENTS?>/My Games/./G", prefix, game))
        assertEquals(File("/storage/games/Game/save"), GOGCloudSaves.resolve("<?INSTALL?>/bin/../save", prefix, game))
    }

    @Test
    fun `a GOG folder this device cannot place is no folder`() {
        // The game's own folder, for a game not installed here.
        assertNull(GOGCloudSaves.resolve("<?INSTALL?>/save", prefix, null))
        assertNull(GOGCloudSaves.resolve("<?SOMETHING_NEW?>/save", prefix, null))
        // Out of the prefix.
        assertNull(GOGCloudSaves.resolve("<?DOCUMENTS?>/../../../../../../etc", prefix, null))
    }

    @Test
    fun `GOG's cloud list leaves out deleted files and reads its times`() {
        val listing = GOGCloudSaves.parseListing(
            """[{"name":"__default/slot1.sav","hash":"abc","bytes":12,"last_modified":"2026-04-02T20:34:00.123456+00:00"},
               {"name":"__default/old.sav","hash":"${GOGCloudSaves.DELETED_MD5}","last_modified":""},
               {"name":"","hash":"x"}]""",
        )
        assertEquals(setOf("__default/slot1.sav"), listing.keys)
        assertEquals("abc", listing.getValue("__default/slot1.sav").sha)
        assertEquals(Instant.parse("2026-04-02T20:34:00.123Z").toEpochMilli(), listing.getValue("__default/slot1.sav").timeMs)
        assertEquals(emptyMap<String, Any>(), GOGCloudSaves.parseListing(""))
    }

    @Test
    fun `GOG's config is kept and read back`() {
        val config = GOGCloudSaves.Config("1", "s", listOf(GOGCloudSaves.Location("", "%APPDATA%/x"), GOGCloudSaves.Location("a", "<?INSTALL?>")))
        assertEquals(config, GOGCloudSaves.Config.decode(config.encode()))
        assertNull(GOGCloudSaves.Config.decode("{}"))
    }

    @Test
    fun `a file's GOG fingerprint is the MD5 of the gzip droidtop sends, the same every time`() {
        val file = temp.newFile("slot.sav").apply { writeText("save data") }
        val first = GOGCloudSaves.fingerprint(file)
        file.setLastModified(1_000L)
        assertEquals(first, GOGCloudSaves.fingerprint(file))
        val gz = GOGCloudSaves.gzip(file.readBytes())
        assertEquals(first, GOGCloudSaves.md5(gz))
        assertEquals("save data", GZIPInputStream(gz.inputStream()).bufferedReader().readText())
        file.writeText("other")
        assertNotEquals(first, GOGCloudSaves.fingerprint(file))
    }

    // What both share

    @Test
    fun `a cloud name finds its folder and never leaves it`() {
        val root = temp.newFolder()
        val saves = File(root, "Saves").apply { mkdirs() }
        File(saves, "Profile").mkdirs()
        val folders = listOf(Folder("__default", saves), Folder("__default/deep", File(root, "Deep")), Folder("", File(root, "Sdk")))
        // Windows does not care about case: an existing folder is used however the cloud spells it.
        assertEquals(File(saves, "Profile/slot.sav"), CloudSaveRun.localFile(folders, "__default/profile/slot.sav"))
        assertEquals(File(root, "Deep/a.sav"), CloudSaveRun.localFile(folders, "__default/deep/a.sav"))
        assertEquals(File(root, "Sdk/loose.sav"), CloudSaveRun.localFile(folders, "loose.sav"))
        assertNull(CloudSaveRun.localFile(folders, "__default/../../escape"))
        assertNull(CloudSaveRun.localFile(listOf(Folder("a", saves)), "b/x.sav"))
        assertNull(CloudSaveRun.localFile(listOf(Folder("", saves)), "C:/Windows/x"))
    }

    @Test
    fun `a scan names files the way the cloud does and skips droidtop's half-written ones`() {
        val dir = temp.newFolder()
        File(dir, "a/b").mkdirs()
        File(dir, "a/b/slot.sav").writeText("1")
        File(dir, "top.sav").writeText("2")
        File(dir, ".top.sav.droidtop-cloud").writeText("partial")
        assertEquals(setOf("n/a/b/slot.sav", "n/top.sav"), CloudSaveRun.scan(listOf(Folder("n", dir))).keys)
        assertEquals(setOf("a/b/slot.sav", "top.sav"), CloudSaveRun.scan(listOf(Folder("", dir))).keys)
        assertEquals(emptyMap<String, File>(), CloudSaveRun.scan(listOf(Folder("", File(dir, "missing")))))
    }

    // Epic

    @Test
    fun `Epic's save folder names map into the Wine prefix, whatever their case`() {
        val game = File("/storage/games/Epic")
        assertEquals(File("$home/AppData/Local/Game/Saved/SaveGames"), EpicCloudSaves.resolve("{AppData}/Game/Saved/SaveGames", prefix, game, "acc", "App"))
        assertEquals(File("$home/AppData/Roaming/Game/acc"), EpicCloudSaves.resolve("{RoamingAppData}\\Game\\{EpicId}", prefix, game, "acc", "App"))
        assertEquals(File("$home/Documents/My Games/App"), EpicCloudSaves.resolve("{UserDir}/My Games/{AppName}", prefix, game, "acc", "App"))
        assertEquals(File("$home/Saved Games/G"), EpicCloudSaves.resolve("{UserSavedGames}/G", prefix, game, "acc", "App"))
        assertEquals(File("/storage/games/Epic/Saved"), EpicCloudSaves.resolve("{InstallDir}/Saved", prefix, game, "acc", "App"))
        assertNull(EpicCloudSaves.resolve("{InstallDir}/Saved", prefix, null, "acc", "App"))
        assertNull(EpicCloudSaves.resolve("{Unknown}/Saved", prefix, game, "acc", "App"))
        assertNull(EpicCloudSaves.resolve("{AppData}/../../../../../../x", prefix, game, "acc", "App"))
        assertNull(EpicCloudSaves.resolve("", prefix, game, "acc", "App"))
    }

    @Test
    fun `Epic's newest snapshot is the manifest that changed last`() {
        val listing = EpicCloudSaves.parseListing(
            """{"files":{"x/acc/App/manifests/a.manifest":{"readLink":"r1","lastModified":"2026-01-01T00:00:00.000Z"},
               "x/acc/App/manifests/b.manifest":{"readLink":"r2","lastModified":"2026-02-01T00:00:00.000Z"},
               "x/acc/App/ChunksV4/01/A_B.chunk":{"readLink":"r3","lastModified":"2026-02-01T00:00:00.000Z"}}}""",
        )
        assertEquals("x/acc/App/manifests/b.manifest", EpicCloudSaves.latestManifest(listing)?.first)
        assertNull(EpicCloudSaves.latestManifest(emptyMap()))
        assertEquals(emptyMap<String, Any>(), EpicCloudSaves.parseListing(""))
        assertEquals(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), EpicCloudSaves.parseTime("2026-02-01T00:00:00.000Z"))
    }

    @Test
    fun `a chunk file reads back the data written into it`() {
        val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(data)
        val chunk = EpicCloudSaves.writeChunk(data, intArrayOf(1, 2, 3, 4), EpicCloudSaves.rollingHash(data), sha)
        assertArrayEquals(data, EpicCloudSaves.readChunk(chunk))
    }

    @Test
    fun `a snapshot's manifest names every file, and its chunks rebuild each one`() {
        val dir = temp.newFolder()
        val big = ByteArray(1024 * 1024 + 4321) { (it * 7).toByte() }
        val files = mapOf(
            "Profile/slot1.sav" to File(dir, "slot1.sav").apply { writeBytes(big) },
            "settings.ini" to File(dir, "settings.ini").apply { writeText("volume=3") },
            "empty.dat" to File(dir, "empty.dat").apply { writeBytes(ByteArray(0)) },
        )
        val game = EpicGame(appName = "App", saveFolder = "{AppData}/Game")
        val packed = EpicCloudSaves.pack(files, game, "acc", Instant.parse("2026-03-04T05:06:07Z"), SecureRandom())
        assertEquals("manifests/2026.03.04-05.06.07.manifest", packed.manifestPath)
        assertEquals(2, packed.chunks.size)

        val manifest = EpicManifest.readAll(packed.manifest)
        val remote = EpicCloudSaves.remoteFacts(manifest, 9L)
        assertEquals(setOf("Profile/slot1.sav", "settings.ini"), remote.keys)
        assertEquals(EpicCloudSaves.sha1(big), remote.getValue("Profile/slot1.sav").sha)
        assertEquals(big.size.toLong(), remote.getValue("Profile/slot1.sav").size)

        val chunks = manifest.chunkDataList!!.elements.associate { info ->
            val path = packed.chunks.keys.single { it == info.getPath() }
            info.guidStr to EpicCloudSaves.readChunk(packed.chunks.getValue(path))
        }
        for (file in manifest.fileManifestList!!.elements) {
            val out = ByteArrayOutputStream()
            for (part in file.chunkParts) out.write(chunks.getValue(part.guidStr), part.offset, part.size)
            assertArrayEquals(files.getValue(file.filename).readBytes(), out.toByteArray())
        }
        // Legendary's place for a chunk: beside the manifest's first three path parts.
        val info = manifest.chunkDataList!!.elements.first()
        val listing = mapOf("x/acc/App/${info.getPath()}" to EpicCloudSaves.Listed("r", ""))
        assertEquals("x/acc/App/${info.getPath()}", EpicCloudSaves.chunkPath(listing, "x/acc/App/manifests/m.manifest", info))
        assertTrue(EpicCloudSaves.chunkPath(emptyMap(), "x/acc/App/manifests/m.manifest", info) == null)
    }
}
