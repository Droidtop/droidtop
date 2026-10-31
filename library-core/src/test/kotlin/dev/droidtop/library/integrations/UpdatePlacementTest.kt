package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.WebSessionDownload
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An update's new version folder, its saves, and a source's record page (docs/SPEC.md 7g, "Getting an update"). */
class UpdatePlacementTest {
    private val dir: File = Files.createTempDirectory("update-placement").toFile()

    @Test
    fun theVersionFolderIsTheGamesNameAndTheVersionLessItsV() {
        assertEquals("Sample Game v0.6", UpdatePlacement.folderName("Sample Game", "v0.6"))
        assertEquals("Sample Game v0.6 Ep 2", UpdatePlacement.folderName("Sample Game", "0.6 Ep 2"))
        assertEquals("A B v1.0", UpdatePlacement.folderName("A: B", "1.0"))
        assertNull(UpdatePlacement.folderName("Sample Game", " "))
        assertNull(UpdatePlacement.folderName("Sample Game", null))
    }

    @Test
    fun savesBesideTheGameAreCopiedAndTheReleasesOwnFilesWin() {
        val old = File(dir, "Game v0.5").apply { File(this, "game/saves").mkdirs() }
        File(old, "game/saves/1-1-LT1.save").writeText("progress")
        File(old, "game/saves/persistent").writeText("old persistent")
        File(old, "game/script.rpy").writeText("old script")
        val new = File(dir, "Game v0.6").apply { File(this, "game/saves").mkdirs() }
        File(new, "game/saves/persistent").writeText("shipped")

        assertEquals(1, UpdatePlacement.copyBeside(old, new, listOf("game/saves/**")))
        assertEquals("progress", File(new, "game/saves/1-1-LT1.save").readText())
        assertEquals("a file the release brought stays", "shipped", File(new, "game/saves/persistent").readText())
        assertFalse("only saves are copied", File(new, "game/script.rpy").exists())
        assertEquals("the old folder keeps its copy", "progress", File(old, "game/saves/1-1-LT1.save").readText())
    }

    @Test
    fun theJobSaysWhatItCopiedOrWhyNothing() {
        val old = File(dir, "G v1").apply { File(this, "save").mkdirs() }
        File(old, "save/a.sav").writeText("a")
        File(old, "save/b.sav").writeText("b")
        val new = File(dir, "G v2").apply { mkdirs() }
        val args = mapOf(DownloadJobs.ARG_VERSION_FOLDER to "G v2") + UpdatePlacement.savesArgs(old, listOf("save/*.sav"))
        assertEquals("Copied 2 save files from G v1", UpdatePlacement.copySaves(new, args))
        assertEquals(
            "No saves were copied: droidtop knows of none kept in this game's folder",
            UpdatePlacement.copySaves(new, mapOf(DownloadJobs.ARG_VERSION_FOLDER to "G v2")),
        )
        assertNull("a download that is not an update says nothing", UpdatePlacement.copySaves(new, emptyMap()))
    }

    @Test
    fun aRecordIsCheckedAndCut() {
        val reply = JSONObject(
            """
            {"id": "18978", "title": "Sample Game", "version": "v0.6", "rating": 0.86, "votes": 120, "status": "Ongoing",
             "url": "https://f95zone.to/threads/18978", "cover": "http://insecure.example/c.png",
             "tags": ["2dcg", "", "2dcg", "${"x".repeat(41)}"], "changelog": "${"c".repeat(25_000)}",
             "downloads": [
               {"section": "Win/Linux", "host": "MEGA", "label": "MEGA", "link": "0"},
               {"section": "Win/Linux", "host": "PIXELDRAIN", "label": "Part 1", "link": "1"},
               {"section": "Mac", "host": "MEGA", "label": "MEGA", "link": ""}
             ]}
            """.trimIndent(),
        )
        val record = PluginUpdateSources.parseRecord(reply, "18978")!!
        assertEquals("Sample Game", record.title)
        assertEquals(listOf("2dcg"), record.tags)
        assertNull("a cover must be https", record.cover)
        assertEquals(20_000, record.changelog!!.length)
        assertEquals(listOf("0", "1"), record.downloads.map { it.link })
        assertEquals(120, record.votes)
        assertNull("another record's page is refused", PluginUpdateSources.parseRecord(reply, "77"))
        assertTrue(PluginUpdateSources.parseRecord(JSONObject(), "5")!!.downloads.isEmpty())
    }

    @Test
    fun whatTheWebViewCapturedIsDownloadedWithItsHeadersAndUnpackedWhenAnArchive() {
        val one = UpdateLinks.descriptorsFor(
            listOf(WebSessionDownload("https://dl.host/f/1", "UA/1", "attachment; filename=\"Game 0.6.zip\"", null, 1234L, "https://host/f", "sid=1")),
        ).single()
        assertEquals("Game_0.6.zip", one.fileName)
        assertEquals(mapOf("Cookie" to "sid=1", "User-Agent" to "UA/1", "Referer" to "https://host/f"), one.headers)
        assertTrue(one.unpack)
        assertEquals(1234L, one.size)
        val parts = UpdateLinks.descriptorsFor(
            listOf(
                WebSessionDownload("https://a/Game.7z.001", null, null, null, null, null, "bad\r\nX-Injected: 1"),
                WebSessionDownload("https://a/Game.7z.002", null, null, null, null, null, null),
            ),
        )
        assertTrue("the numbered parts of one archive are unpacked together", parts.all { it.unpack })
        assertTrue("a value with a line break is dropped", parts.first().headers.isEmpty())
        val exe = UpdateLinks.descriptorsFor(listOf(WebSessionDownload("https://a/setup.exe", null, null, null, null, null, null))).single()
        assertFalse(exe.unpack)
    }

    @Test
    fun aFileHostsOwnHeadersAndDigestTravelWithTheDownload() {
        val digest = "ab".repeat(32)
        val one = UpdateLinks.descriptorsFor(
            listOf(WebSessionDownload.direct("https://pixeldrain.com/api/file/abc?download", "Game.zip", 5L, mapOf("Authorization" to "Basic OnNlY3JldA=="), digest, "via your PixelDrain")),
        ).single()
        assertEquals(mapOf("Authorization" to "Basic OnNlY3JldA=="), one.headers)
        assertEquals(digest, one.sha256)
        assertEquals("Game.zip", one.fileName)
    }

    @Test
    fun anMd5AndACookieAHostGivesTravelWithTheDownload() {
        val md5 = "0f8adc1149b1a2c3d4e5f60718293a4b"
        val one = UpdateLinks.descriptorsFor(
            listOf(WebSessionDownload.direct("https://store-1.gofile.io/download/web/f1/Game.zip", "Game.zip", 5L, mapOf("Cookie" to "accountToken=tok"), null, "via your GoFile", md5)),
        ).single()
        assertEquals(md5, one.md5)
        assertNull(one.sha256)
        assertEquals(mapOf("Cookie" to "accountToken=tok"), one.headers)
    }

    @Test
    fun aRecordIsSavedWholeAndRefusesAPathForAName() {
        val cache = RecordCache(File(dir, "records"))
        cache.save("f95zone", "18978", JSONObject().put("title", "Sample"), nowMs = 42L)
        val saved = cache.load("f95zone", "18978")!!
        assertEquals("Sample", saved.record.getString("title"))
        assertEquals(42L, saved.savedAtMs)
        cache.save("../evil", "1", JSONObject())
        assertFalse(File(dir, "evil").exists())
        assertNull(cache.load("f95zone", "77"))
    }

    @Test
    fun aDownloadMayBeAnAddressDroidtopOpensItself() {
        val reply = JSONObject().put(
            "downloads",
            org.json.JSONArray()
                .put(JSONObject().put("section", "Win").put("host", "MEGA").put("url", "https://mega.nz/file/abc"))
                .put(JSONObject().put("section", "Win").put("host", "X").put("url", "http://plain.example/f"))
                .put(JSONObject().put("section", "Win").put("host", "Y").put("link", "1:Y")),
        )
        val downloads = PluginUpdateSources.parseRecord(reply, "5")!!.downloads
        assertEquals(listOf("https://mega.nz/file/abc", null), downloads.map { it.url })
        assertEquals("a link that is not https and has no token is dropped", 2, downloads.size)
        assertEquals("1:Y", downloads.last().link)
    }
}
