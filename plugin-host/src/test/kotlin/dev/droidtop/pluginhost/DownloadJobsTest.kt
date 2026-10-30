package dev.droidtop.pluginhost

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The download runner's rules (docs/SPEC.md 12a "Downloads", Droidtop/tracker#181): placing, unpacking and moving what a
 * job fetched, its arguments, and what a restart does with a job whose bytes had all arrived. The transfer itself is
 * the download engine's, tested with the engine (net-core's EngineFetchTest, and aria2-engine's on-device tests).
 */
class DownloadJobsTest {
    private val dir: File = Files.createTempDirectory("download-jobs").toFile()
    private val data = ByteArray(200_000) { (it * 7 % 253).toByte() }
    @After
    fun cleanUp() {
        dir.deleteRecursively()
        PluginJobsCenter.nativeDispatcher = Dispatchers.IO
    }

    @Test
    fun acquireDownloadDescriptorParsesOptionalMetadataAndHeaders() {
        val descriptor = AcquireDownloadDescriptor.parse(
            """{"url":"https://example.invalid/game.zip","headers":{"Authorization":"Bearer secret","Accept":"application/zip"},"fileName":"game.zip","size":1234}""",
        )!!
        assertEquals("https://example.invalid/game.zip", descriptor.url)
        assertEquals(mapOf("Authorization" to "Bearer secret", "Accept" to "application/zip"), descriptor.headers)
        assertEquals("game.zip", descriptor.fileName)
        assertEquals(1234L, descriptor.size)
        assertNull(descriptor.sha256)
    }

    @Test
    fun md5AndSha1AreAcceptedAndTheStrongestGivenIsTheOneChecked() {
        val md5 = "d41d8cd98f00b204e9800998ecf8427e"
        val sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709"
        val sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val descriptor = AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a.zip","fileName":"a.zip","md5":"$md5"}""")!!
        assertEquals(md5, descriptor.md5)
        assertNull(descriptor.sha256)
        assertEquals("MD5" to md5, DownloadJobs.strongestDigest(null, null, md5))
        assertEquals("SHA-1" to sha1, DownloadJobs.strongestDigest(null, sha1, md5))
        assertEquals("SHA-256" to sha256, DownloadJobs.strongestDigest(sha256, sha1, md5))
        assertNull(DownloadJobs.strongestDigest(null, null, null))
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a.zip","fileName":"a.zip","md5":"abc"}"""))
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a.zip","fileName":"a.zip","sha1":"$md5"}"""))
    }

    @Test
    fun theDigestsOfAnEmptyFileMatchTheKnownValues() {
        val file = File(dir, "empty.bin").also { it.writeBytes(ByteArray(0)) }
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", DownloadJobs.digestHex(file, "MD5"))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", DownloadJobs.digestHex(file, "SHA-1"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DownloadJobs.digestHex(file, "SHA-256"))
    }

    @Test
    fun aWrongDigestDeletesTheFileAndFailsAndARightOneKeepsIt() = runBlocking {
        val file = File(dir, "digest.bin").also { it.writeBytes(ByteArray(0)) }
        DownloadJobs.verifyDigest(file, mapOf("md5" to "D41D8CD98F00B204E9800998ECF8427E"))
        assertTrue(file.exists())
        try {
            DownloadJobs.verifyDigest(file, mapOf("md5" to "00000000000000000000000000000000"))
            fail("a wrong digest must fail the job")
        } catch (e: IllegalStateException) {
            assertEquals(DownloadJobs.DIGEST_MISMATCH, e.message)
        }
        assertFalse(file.exists())
    }

    @Test
    fun aFileNameIsKeptAsGivenWithSpacesAndOnlyIllegalCharactersAreReplaced() {
        assertEquals("Pro Race (USA) [!].zip", AcquireFileName.clean("Pro Race (USA) [!].zip"))
        assertEquals("Pokémon Rouge.7z", AcquireFileName.clean("Pokémon Rouge.7z"))
        assertEquals("Who_ What_.zip", AcquireFileName.clean("Who? What*.zip"))
        assertEquals("a_b.zip", AcquireFileName.clean("a:b.zip"))
        assertNull(AcquireFileName.clean("../game.zip"))
        assertNull(AcquireFileName.clean("dir/game.zip"))
        assertNull(AcquireFileName.clean("dir\\game.zip"))
        assertNull(AcquireFileName.clean(".hidden.zip"))
        assertNull(AcquireFileName.clean("   "))
        assertNull(AcquireFileName.clean("x".repeat(AcquireFileName.MAX_BYTES + 1)))
        assertEquals("x".repeat(AcquireFileName.MAX_BYTES), AcquireFileName.clean("x".repeat(AcquireFileName.MAX_BYTES)))
        val descriptor = AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a","fileName":"Pro Race.zip","unpack":"archive"}""")!!
        assertEquals("Pro Race.zip", descriptor.fileName)
        assertTrue(descriptor.unpack)
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a","fileName":"a.zip","unpack":"everything"}"""))
        assertFalse(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a","fileName":"a.zip"}""")!!.unpack)
    }

    @Test
    fun theFileInDroidtopsDownloadsAreaIsNamedSafelyWhateverTheDisplayName() {
        assertEquals("acquire_5.zip", AcquireFileName.areaName(5, "Pro Race (USA).zip"))
        assertEquals("acquire_5", AcquireFileName.areaName(5, "Readme"))
        assertEquals("acquire_5", AcquireFileName.areaName(5, "weird.ext ension"))
    }

    private fun zipOf(file: File, vararg entries: Pair<String, String>): File {
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun anArchiveIsUnpackedIntoItsOwnFolderAndTheArchiveIsDeleted() = runBlocking {
        val archive = zipOf(File(dir, "downloads/acquire_1.zip").also { it.parentFile.mkdirs() }, "GAME/RUN.EXE" to "exe", "readme.txt" to "hi")
        val games = File(dir, "games/dos")
        val folder = DownloadJobs.unpackIntoFolder(archive, mapOf("destinationPath" to games.path, "targetName" to "Prince of Persia.zip"))
        assertEquals(File(games, "Prince of Persia"), folder)
        assertEquals("exe", File(folder, "GAME/RUN.EXE").readText())
        assertEquals("hi", File(folder, "readme.txt").readText())
        assertFalse(archive.exists())
        assertEquals(listOf("Prince of Persia"), games.list()!!.toList())
    }

    @Test
    fun anArchiveThatReachesOutsideItsFolderIsRefusedAndNothingIsLeftBehind() = runBlocking {
        val archive = zipOf(File(dir, "downloads/acquire_2.zip").also { it.parentFile.mkdirs() }, "ok.txt" to "fine", "../evil.txt" to "bad")
        val games = File(dir, "games/dos")
        try {
            DownloadJobs.unpackIntoFolder(archive, mapOf("destinationPath" to games.path, "targetName" to "Evil.zip"))
            fail("an entry outside the folder must refuse the archive")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("could not unpack Evil.zip"))
        }
        assertTrue("the download is kept", archive.exists())
        assertEquals(emptyList<String>(), games.list()!!.toList())
        assertFalse(File(games.parentFile, "evil.txt").exists())
    }

    @Test
    fun aDownloadLimitedToAPluginsSitesRefusesEveryOtherAddress() {
        val guard = DownloadJobs.sitesGuard("""["f95zone.to", "*.example.org"]""")!!
        guard("https://f95zone.to/a.png")
        guard("https://cdn.example.org/b.jpg")
        // As net.domains: a bare domain is that host alone; `*.` covers the subdomains.
        for (refused in listOf("http://f95zone.to/a", "https://attachments.f95zone.to/a.png", "https://evil.test/x", "https://f95zone.to.evil.test/x")) {
            try {
                guard(refused)
                fail("$refused is not one of the download's sites")
            } catch (e: dev.droidtop.net.engine.TransferException) {
                assertTrue(e.message!!.contains("not one of the sites"))
            }
        }
        assertNull("a download not limited has no guard", DownloadJobs.sitesGuard(null))
        assertEquals(dev.droidtop.net.DownloadCategory.SYSTEM, DownloadJobs.categoryFor(DownloadJobs.POST_KEEP, emptyMap()))
        assertEquals(dev.droidtop.net.DownloadCategory.GAMES, DownloadJobs.categoryFor(DownloadJobs.POST_PLACE_IN_FOLDER, emptyMap()))
        assertEquals(dev.droidtop.net.DownloadCategory.UPDATES, DownloadJobs.categoryFor(DownloadJobs.POST_PLACE_IN_FOLDER, mapOf(DownloadJobs.ARG_VERSION_FOLDER to "G v2")))
    }

    @Test
    fun aDownloadOfDroidtopsOwnIsMovedOnlyIntoItsOwnStorage() {
        val files = File(dir, "files").apply { mkdirs() }
        assertEquals(File(files, "imagefs.txz").canonicalFile, DownloadJobs.ownTarget(listOf(files), File(files, "imagefs.txz").path))
        for (outside in listOf(File(dir, "elsewhere/x").path, File(files, "../escape").path, files.path)) {
            try {
                DownloadJobs.ownTarget(listOf(files), outside)
                fail("$outside is not inside droidtop's storage")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("own storage"))
            }
        }
    }

    @Test
    fun anUpdateUnpacksIntoItsVersionFolderLiftingTheReleasesOneFolder() = runBlocking {
        val archive = zipOf(File(dir, "downloads/acquire_4.zip").also { it.parentFile.mkdirs() }, "Game-0.6-pc/game/script.rpy" to "new", "Game-0.6-pc/Game.exe" to "exe")
        val games = File(dir, "games/renpy")
        File(games, "Game v0.5/game").mkdirs()
        val args = mapOf("destinationPath" to games.path, "targetName" to "Game-0.6-pc.zip", DownloadJobs.ARG_VERSION_FOLDER to "Game v0.6")
        val folder = DownloadJobs.unpackIntoFolder(archive, args)
        // The release's one folder is the version: the game sits at the same depth as its other versions.
        assertEquals(File(games, "Game v0.6"), folder)
        assertEquals("new", File(folder, "game/script.rpy").readText())
        assertTrue(File(games, "Game v0.5/game").isDirectory)
        assertEquals(listOf("Game v0.5", "Game v0.6"), games.list()!!.sorted())
    }

    @Test
    fun anUpdateNeverLandsOnAVersionFolderThatIsThere() = runBlocking {
        val archive = zipOf(File(dir, "downloads/acquire_5.zip").also { it.parentFile.mkdirs() }, "a.txt" to "a")
        val games = File(dir, "games/renpy")
        File(games, "Game v0.6").mkdirs()
        File(games, "Game v0.6/mine.txt").writeText("mine")
        try {
            DownloadJobs.unpackIntoFolder(archive, mapOf("destinationPath" to games.path, "targetName" to "x.zip", DownloadJobs.ARG_VERSION_FOLDER to "Game v0.6"))
            fail("a version folder that exists must not be unpacked into")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("already exists"))
        }
        assertEquals(listOf("mine.txt"), File(games, "Game v0.6").list()!!.toList())
        assertTrue("the download is kept", archive.exists())
    }

    @Test
    fun anUpdatesLoneFileIsPlacedInItsNewVersionFolder() {
        val file = File(dir, "downloads/acquire_6.bin").also { it.parentFile.mkdirs(); it.writeText("game") }
        val games = File(dir, "games/html")
        val placed = DownloadJobs.placeInFolder(file, mapOf("destinationPath" to games.path, "targetName" to "Game.html", DownloadJobs.ARG_VERSION_FOLDER to "Game v2"))
        assertEquals(File(games, "Game v2"), placed)
        assertEquals("game", File(placed, "Game.html").readText())
        assertFalse(file.exists())
    }

    @Test
    fun anExistingFolderIsNeverUnpackedOver() = runBlocking {
        val archive = zipOf(File(dir, "downloads/acquire_3.zip").also { it.parentFile.mkdirs() }, "a.txt" to "a")
        val games = File(dir, "games/dos")
        File(games, "Game").mkdirs()
        File(games, "Game/mine.txt").writeText("mine")
        // #355 G5: the existing folder is untouched and the new game is unpacked beside it.
        val unpacked = DownloadJobs.unpackIntoFolder(archive, mapOf("destinationPath" to games.path, "targetName" to "Game.zip"))
        assertEquals(File(games, "Game (2)"), unpacked)
        assertEquals("a", File(unpacked, "a.txt").readText())
        assertEquals("mine", File(games, "Game/mine.txt").readText())
        assertFalse("the archive is gone once unpacked", archive.exists())
        assertNull(DownloadJobs.archiveStem("Game.exe"))
        assertEquals("My Game", DownloadJobs.archiveStem("My Game.RAR"))
    }

    @Test
    fun theReplyCarriesOneDownloadOrAListOfUpToSixteenButNotBoth() {
        val one = """{"url":"https://h.example/a.bin","fileName":"A one.bin"}"""
        val two = """{"url":"https://h.example/b.bin","fileName":"B two.bin","md5":"d41d8cd98f00b204e9800998ecf8427e"}"""
        assertNull(AcquireDownloads.parse(emptyMap()))
        assertEquals(listOf("A one.bin"), AcquireDownloads.parse(mapOf("download" to one))!!.map { it.fileName })
        assertEquals(listOf("A one.bin", "B two.bin"), AcquireDownloads.parse(mapOf("downloads" to "[$one,$two]"))!!.map { it.fileName })
        assertTrue("both forms are invalid", AcquireDownloads.parse(mapOf("download" to one, "downloads" to "[$two]"))!!.isEmpty())
        assertTrue("an empty list is invalid", AcquireDownloads.parse(mapOf("downloads" to "[]"))!!.isEmpty())
        assertTrue("one bad descriptor spoils the list", AcquireDownloads.parse(mapOf("downloads" to "[$one,{\"url\":\"file:///x\",\"fileName\":\"x\"}]"))!!.isEmpty())
        val seventeen = List(DownloadJobs.MAX_FILES + 1) { one }.joinToString(",", "[", "]")
        assertTrue(AcquireDownloads.parse(mapOf("downloads" to seventeen))!!.isEmpty())
        assertEquals(DownloadJobs.MAX_FILES, AcquireDownloads.parse(mapOf("downloads" to List(DownloadJobs.MAX_FILES) { one }.joinToString(",", "[", "]")))!!.size)
    }

    @Test
    fun theFilesOfAJobComeBackOneArgumentMapEachWithTheirOwnNamesAndDigests() {
        val args = mapOf(
            "url" to "https://h.example/1", "name" to "acquire_1.bin", "targetName" to "Disc 1.bin", "md5" to "aa",
            "more" to """[{"url":"https://h.example/2","name":"acquire_2.bin","targetName":"Disc 2.bin"}]""",
            "destinationPath" to "/games/psx", "post" to "place_in_folder",
        )
        val parts = DownloadJobs.partsOf(args)
        assertEquals(2, parts.size)
        assertEquals("Disc 1.bin", parts[0]["targetName"])
        assertEquals("aa", parts[0]["md5"])
        assertEquals("Disc 2.bin", parts[1]["targetName"])
        assertNull("a digest belongs to its own file", parts[1]["md5"])
        assertEquals("/games/psx", parts[1]["destinationPath"])
        assertEquals("place_in_folder", parts[1]["post"])
        assertEquals(listOf(mapOf("url" to "u")), DownloadJobs.partsOf(mapOf("url" to "u")))
    }

    @Test
    fun severalFilesArePlacedTogetherAndAnExistingNameStopsAllOfThem() {
        val games = File(dir, "games/psx")
        val a = File(dir, "downloads/acquire_1.bin").also { it.parentFile.mkdirs(); it.writeText("a") }
        val b = File(dir, "downloads/acquire_2.cue").also { it.writeText("b") }
        val parts = listOf(
            mapOf("destinationPath" to games.path, "targetName" to "Disc (Track 1).bin"),
            mapOf("destinationPath" to games.path, "targetName" to "Disc.cue"),
        )
        File(games, "Disc.cue").also { it.parentFile.mkdirs(); it.writeText("mine") }
        try {
            DownloadJobs.placeAllInFolder(listOf(a, b), parts)
            fail("an existing name stops them all")
        } catch (e: IllegalArgumentException) {
            assertEquals("${File(games, "Disc.cue").absolutePath} already exists", e.message)
        }
        assertTrue(a.exists() && b.exists())
        assertFalse(File(games, "Disc (Track 1).bin").exists())
        assertEquals("mine", File(games, "Disc.cue").readText())

        File(games, "Disc.cue").delete()
        val placed = DownloadJobs.placeAllInFolder(listOf(a, b), parts)
        assertEquals(listOf(File(games, "Disc (Track 1).bin"), File(games, "Disc.cue")), placed)
        assertFalse(a.exists() || b.exists())
        assertEquals("b", File(games, "Disc.cue").readText())
    }

    @Test
    fun twoFilesWithTheSameNameAreRefused() {
        val games = File(dir, "games/x")
        val a = File(dir, "downloads/a").also { it.parentFile.mkdirs(); it.writeText("a") }
        val b = File(dir, "downloads/b").also { it.writeText("b") }
        val part = mapOf("destinationPath" to games.path, "targetName" to "Same.bin")
        try {
            DownloadJobs.placeAllInFolder(listOf(a, b), listOf(part, part))
            fail("two files cannot share a name")
        } catch (e: IllegalArgumentException) {
            assertEquals("two of the files have the same name", e.message)
        }
        assertTrue(a.exists() && b.exists())
    }

    @Test
    fun theNamesOfASplitArchiveAreRecognisedInAnyOrderAndOnlyWhenComplete() {
        val numbered = SplitArchives.detect(listOf("Game.7z.002", "Game.7z.001", "Game.7z.003"))!!
        assertFalse(numbered.volumes)
        assertEquals("Game.7z", numbered.baseName)
        assertEquals("7z", numbered.extension)
        assertEquals(listOf(1, 0, 2), numbered.order)
        val volumes = SplitArchives.detect(listOf("Big Game.part2.rar", "Big Game.part1.rar"))!!
        assertTrue(volumes.volumes)
        assertEquals("Big Game", volumes.baseName)
        assertEquals(listOf(1, 0), volumes.order)
        assertNull("a gap", SplitArchives.detect(listOf("G.7z.001", "G.7z.003")))
        assertNull("not from 1", SplitArchives.detect(listOf("G.7z.002", "G.7z.003")))
        assertNull("a repeat", SplitArchives.detect(listOf("G.7z.001", "G.7z.001")))
        assertNull("two sets", SplitArchives.detect(listOf("A.7z.001", "B.7z.002")))
        assertNull("mixed", SplitArchives.detect(listOf("A.7z.001", "A.part2.rar")))
        assertNull("one file is not a set", SplitArchives.detect(listOf("A.7z.001")))
        assertNull("plain files", SplitArchives.detect(listOf("a.bin", "b.bin")))
    }

    @Test
    fun theBytePartsOfASplitZipAreJoinedInOrderAndUnpackedOnce() = runBlocking {
        val whole = zipOf(File(dir, "whole.zip"), "run.exe" to "exe", "data/level 1.txt" to "one")
        val bytes = whole.readBytes()
        val third = bytes.size / 3
        val chunks = listOf(bytes.copyOfRange(0, third), bytes.copyOfRange(third, 2 * third), bytes.copyOfRange(2 * third, bytes.size))
        val games = File(dir, "games/dos")
        val files = chunks.mapIndexed { i, chunk -> File(dir, "downloads/acquire_${i + 1}.001").also { it.parentFile.mkdirs(); it.writeBytes(chunk) } }
        // Handed over out of order: part 3, part 1, part 2.
        val order = listOf(2, 0, 1)
        val names = listOf("Split Game.zip.003", "Split Game.zip.001", "Split Game.zip.002")
        val parts = names.map { mapOf("destinationPath" to games.path, "targetName" to it, "unpack" to "archive") }
        val (placed, summary) = DownloadJobs.placeSplitSet(order.map { files[it] }, parts)
        assertEquals(listOf(File(games, "Split Game")), placed)
        assertEquals("Added Split Game", summary)
        assertEquals("one", File(games, "Split Game/data/level 1.txt").readText())
        assertTrue(files.none { it.exists() })
        assertEquals(listOf("Split Game"), games.list()!!.toList())
    }

    @Test
    fun rarVolumesThatCannotBeUnpackedArePlacedTogetherAndNotHalfUnpacked() = runBlocking {
        // Not RAR at all (and no native libarchive on the JVM): the unpack fails, so the parts are placed as they are.
        val games = File(dir, "games/pc")
        val files = (1..2).map { File(dir, "downloads/v$it.rar").also { f -> f.parentFile.mkdirs(); f.writeText("v$it") } }
        val parts = listOf("Big.part1.rar", "Big.part2.rar").map { mapOf("destinationPath" to games.path, "targetName" to it, "unpack" to "archive") }
        val (placed, summary) = DownloadJobs.placeSplitSet(files, parts)
        assertEquals(2, placed.size)
        assertTrue(summary, summary.startsWith("Added 2 parts as they are: could not unpack the 2 RAR parts"))
        assertTrue(File(games, "Big.part1.rar").isFile)
        assertTrue(games.listFiles()!!.none { it.name.endsWith(".unpacking") })
        assertEquals("v2", File(games, "Big.part2.rar").readText())
    }

    @Test
    fun acquireDownloadDescriptorRejectsUnsafeOrInvalidFields() {
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"file:///etc/passwd","fileName":"game.zip"}"""))
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a","fileName":"../game.zip"}"""))
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a","fileName":"game.zip","size":0}"""))
        assertNull(AcquireDownloadDescriptor.parse("not json"))
    }

    @Test
    fun aRestoredJobOfAReattachKindRunsAgainFromItsCheckpointWithoutResume() = runBlocking {
        PluginJobsCenter.nativeDispatcher = Dispatchers.Default
        val starts = CopyOnWriteArrayList<String?>()
        val cancelled = CopyOnWriteArrayList<String?>()
        val running = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        PluginJobsCenter.registerNative("test_reattach", onCancel = { _, checkpoint -> cancelled += checkpoint }, reattachOnRestart = true) { _, checkpoint, report ->
            starts += checkpoint
            if (checkpoint == null) {
                report(-1, "Starting…", "fetched")
                running.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            second.complete(Unit)
            "finished from $checkpoint"
        }
        val id = PluginJobsCenter.startNative(null, "test_reattach", "A download", mapOf("url" to "u"))!!
        withTimeout(5_000) { running.await() }
        assertTrue("a download can be paused", PluginJobsCenter.find(id)!!.pausable)

        // The process dies: only the stored text is left.
        val stored = PluginJobsCenter.encodeEntries(listOf(PluginJobsCenter.find(id)!!))
        PluginJobsCenter.cancel(id)
        withTimeout(5_000) { while (cancelled.isEmpty()) delay(5) }
        assertEquals(listOf<String?>("fetched"), cancelled.toList())
        assertNull(PluginJobsCenter.find(id))
        val restored = PluginJobsCenter.decodeEntries(stored).single()
        assertEquals("fetched", restored.resumePayload)

        PluginJobsCenter.restore(listOf(restored))
        withTimeout(5_000) { second.await() }
        withTimeout(5_000) { while (PluginJobsCenter.find(id)?.done != true) delay(5) }
        assertEquals(listOf<String?>(null, "fetched"), starts.toList())
        assertEquals("finished from fetched", PluginJobsCenter.find(id)!!.result?.values?.get("summary"))
    }

    @Test
    fun aReattachingJobThePersonPausedStaysPausedAfterARestart() = runBlocking {
        PluginJobsCenter.nativeDispatcher = Dispatchers.Default
        val starts = CopyOnWriteArrayList<String?>()
        val running = CompletableDeferred<Unit>()
        PluginJobsCenter.registerNative("test_reattach_paused", reattachOnRestart = true) { _, checkpoint, report ->
            starts += checkpoint
            report(30, "Working", "item-3")
            running.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val id = PluginJobsCenter.startNative(null, "test_reattach_paused", "A scrape")!!
        withTimeout(5_000) { running.await() }
        assertTrue(PluginJobsCenter.pause(id))

        // The process dies while the person has it paused.
        val stored = PluginJobsCenter.encodeEntries(listOf(PluginJobsCenter.find(id)!!))
        PluginJobsCenter.cancel(id)
        withTimeout(5_000) { while (PluginJobsCenter.find(id) != null) delay(5) }
        val restored = PluginJobsCenter.decodeEntries(stored).single()
        assertTrue(restored.pausedByPerson)

        PluginJobsCenter.restore(listOf(restored))
        delay(200)
        assertTrue("paused by the person, it waits for Resume", PluginJobsCenter.find(id)!!.paused)
        assertEquals(listOf<String?>(null), starts.toList())
        PluginJobsCenter.cancel(id)
    }

    @Test
    fun aDownloadIsPlacedInTheGameFolderUnderItsTargetName() {
        val downloaded = File(dir, "downloads/acquire_1_Game.zip").also { it.parentFile.mkdirs(); it.writeText("zip") }
        val games = File(dir, "games/snes")
        val target = DownloadJobs.placeInFolder(downloaded, mapOf("destinationPath" to games.path, "targetName" to "Game.zip"))
        assertEquals(File(games, "Game.zip"), target)
        assertTrue(target.isFile)
        assertFalse(downloaded.exists())

        // #355 G5: an existing file is never replaced; the new one is kept beside it as "Game (2).zip".
        val again = File(dir, "downloads/acquire_2_Game.zip").also { it.writeText("zip2") }
        val second = DownloadJobs.placeInFolder(again, mapOf("destinationPath" to games.path, "targetName" to "Game.zip"))
        assertEquals(File(games, "Game (2).zip"), second)
        assertEquals("zip", File(games, "Game.zip").readText())
        assertEquals("zip2", second.readText())
        assertEquals(File(games, "Game (3).zip"), DownloadJobs.freeName(games, "Game.zip"))
    }

    @Test
    fun aMoveTheVolumeRefusesIsACopyThatLandsWholeAndOnlyThenDropsTheDownload() {
        // The downloads area and an SD card game folder are two volumes, where a rename fails (Droidtop/tracker#558).
        val downloaded = File(dir, "downloads/acquire_1_bios.bin").also { it.parentFile.mkdirs(); it.writeBytes(data) }
        val target = File(dir, "sd/bios/ps2-0230a-20080220.bin").also { it.parentFile.mkdirs() }
        var renames = 0
        DownloadJobs.moveFile(downloaded, target) { from, to -> if (renames++ == 0) false else from.renameTo(to) }
        assertArrayEquals(data, target.readBytes())
        assertFalse("the download goes once the copy is in place", downloaded.exists())
        assertFalse("no staged copy is left", File(target.parentFile, ".${target.name}.placing").exists())
    }

    @Test
    fun aMoveThatCannotBeDoneKeepsTheDownloadAndNamesTheTarget() {
        val downloaded = File(dir, "downloads/acquire_2_bios.bin").also { it.parentFile.mkdirs(); it.writeBytes(data) }
        val target = File(dir, "sd/bios/scph.bin").also { it.parentFile.mkdirs() }
        try {
            DownloadJobs.moveFile(downloaded, target) { _, _ -> false }
            fail("a move with no way in place fails")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("the download could not be placed at ${target.absolutePath}"))
        }
        assertTrue("the download stays for a retry", downloaded.exists())
        assertFalse(target.exists())
        assertFalse(File(target.parentFile, ".${target.name}.placing").exists())
    }

    @Test
    fun aJobWhoseFileWasPlacedBeforeTheProcessEndedIsFinishedAndReportedNotDownloadedAgain() = runBlocking {
        // The previous run had every byte, renamed the file into the game folder and died before the job was marked done.
        val games = File(dir, "games/snes").also { it.mkdirs() }
        File(games, "Game.zip").writeText("zip")
        val args = mapOf("destinationPath" to games.path, "targetName" to "Game.zip")
        val reported = CopyOnWriteArrayList<File>()

        val line = DownloadJobs.placedBeforeTheProcessEnded(
            File(dir, "downloads/acquire_1_Game.zip"), DownloadJobs.POST_PLACE_IN_FOLDER, args, "fetched",
        ) { reported += it }

        assertEquals("Added Game.zip", line)
        assertEquals(listOf(File(games, "Game.zip")), reported.toList())
    }

    @Test
    fun aJobThatIsStillDownloadingOrWasNotPlacedIsNotTakenForPlaced() = runBlocking {
        val games = File(dir, "games/snes").also { it.mkdirs() }
        val args = mapOf("destinationPath" to games.path, "targetName" to "Game.zip")
        val file = File(dir, "downloads/acquire_1_Game.zip")
        val reported = CopyOnWriteArrayList<File>()
        val report = { placed: File -> reported += placed; Unit }

        // Nothing in the game folder yet.
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(file, DownloadJobs.POST_PLACE_IN_FOLDER, args, "fetched", report))

        // The target is there but the downloaded file still is too: the post step had not run.
        File(games, "Game.zip").writeText("an older file")
        file.parentFile.mkdirs()
        file.writeText("whole")
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(file, DownloadJobs.POST_PLACE_IN_FOLDER, args, "fetched", report))

        // Bytes still arriving (no checkpoint), and another kind of job, are never this.
        file.delete()
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(file, DownloadJobs.POST_PLACE_IN_FOLDER, args, null, report))
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(file, DownloadJobs.POST_KEEP, args, "fetched", report))
        assertTrue(reported.isEmpty())
    }

    @Test
    fun theSummaryCountsEveryRunningJobDownloadsIncluded() {
        fun entry(done: Boolean = false, paused: Boolean = false, nativeKind: String? = null) =
            PluginJobsCenter.Entry("j", "p", "P", null, "t", 0L, done = done, paused = paused, nativeKind = nativeKind)
        val list = listOf(
            entry(), entry(nativeKind = "library_scrape"), entry(nativeKind = DownloadJobs.KIND),
            entry(done = true), entry(paused = true),
        )
        assertEquals(3, JobsSummary.runningCount(list))
        assertEquals("1 job running", JobsSummary.text(1))
        assertEquals("3 jobs running", JobsSummary.text(3))
    }

    @Test
    fun theSummaryBarMovesInWholeStepsAndIsIndeterminateWhileNoJobKnows() {
        fun entry(percent: Int, paused: Boolean = false) = PluginJobsCenter.Entry("j", "p", "P", null, "t", 0L, percent = percent, paused = paused)
        // 23 and 48 average 35.5: the bar shows 35, and a paused job is not counted.
        assertEquals(35, JobsSummary.runningPercent(listOf(entry(23), entry(48), entry(90, paused = true), entry(-1))))
        assertEquals(-1, JobsSummary.runningPercent(listOf(entry(-1))))
        assertEquals(-1, JobsSummary.runningPercent(emptyList()))
    }

    @Test
    fun openGoesToWhatTheDownloadBecameInTheGameFolder() {
        val games = "/sdcard/Games/PC"
        val file = mapOf("targetName" to "Game.exe", "destinationPath" to games)
        assertEquals(File(games, "Game.exe") to false, DownloadJobs.placedTarget(file))
        val archive = file + ("targetName" to "Game-1.2.zip") + ("unpack" to DownloadJobs.UNPACK_ARCHIVE)
        assertEquals(File(games, "Game-1.2") to true, DownloadJobs.placedTarget(archive))
        val version = file + (DownloadJobs.ARG_VERSION_FOLDER to "v1.2")
        assertEquals(File(games, "v1.2") to true, DownloadJobs.placedTarget(version))
        assertNull(DownloadJobs.placedTarget(mapOf("targetName" to "Game.exe")))
        val torrent = mapOf("targetName" to "Some Game", "destinationPath" to games, DownloadJobs.ARG_SWARM to DownloadJobs.SWARM_MAGNET)
        assertEquals("a torrent's download is a folder", File(games, "Some Game") to true, DownloadJobs.placedTarget(torrent))
    }

    // Torrents and magnets (docs/plugin-api.md 1.6, SPEC 12a): a download of a whole folder.

    private val torrent = "d8:announce14:http://t.test/4:infod6:lengthi1e4:name1:a12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaaee".toByteArray()
    private val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Some+Game"

    @Test
    fun aMagnetOrATorrentIsADownloadOfAFolder() {
        val byMagnet = AcquireDownloadDescriptor.parse("""{"url":"$magnet","fileName":"Some Game"}""")!!
        assertEquals(DownloadJobs.SWARM_MAGNET, byMagnet.swarm)
        val byAddress = AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/g.torrent","kind":"torrent","fileName":"Some Game","headers":{"Cookie":"a=b"}}""")!!
        assertEquals(DownloadJobs.SWARM_TORRENT, byAddress.swarm)
        assertNull(byAddress.torrent)
        val encoded = java.util.Base64.getEncoder().encodeToString(torrent)
        val given = AcquireDownloadDescriptor.parse("""{"torrent":"$encoded","fileName":"Some Game"}""")!!
        assertEquals(DownloadJobs.SWARM_TORRENT, given.swarm)
        assertArrayEquals(torrent, given.torrent)
        assertEquals("", given.url)
        assertNull("a plain file is no swarm", AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a.zip","fileName":"a.zip"}""")!!.swarm)
    }

    @Test
    fun aSwarmDescriptorThatCouldNotBeOneIsRefused() {
        val encoded = java.util.Base64.getEncoder().encodeToString("not a torrent".toByteArray())
        assertNull("not a torrent's bytes", AcquireDownloadDescriptor.parse("""{"torrent":"$encoded","fileName":"G"}"""))
        assertNull("a magnet naming no torrent", AcquireDownloadDescriptor.parse("""{"url":"magnet:?dn=G","fileName":"G"}"""))
        assertNull("a digest of a folder", AcquireDownloadDescriptor.parse("""{"url":"$magnet","fileName":"G","md5":"d41d8cd98f00b204e9800998ecf8427e"}"""))
        assertNull("unpacking a folder", AcquireDownloadDescriptor.parse("""{"url":"$magnet","fileName":"G","unpack":"archive"}"""))
        assertNull("headers on a magnet", AcquireDownloadDescriptor.parse("""{"url":"$magnet","fileName":"G","headers":{"Cookie":"a=b"}}"""))
        assertNull("an unknown kind", AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/g","kind":"metalink","fileName":"G"}"""))
        assertNull("a torrent among several files", AcquireDownloads.parse(mapOf("downloads" to """[{"url":"$magnet","fileName":"G"},{"url":"https://example.invalid/a","fileName":"a"}]"""))?.takeIf { it.isNotEmpty() })
    }

    @Test
    fun onlyABencodedDictionaryWithAnInfoDictionaryLooksLikeATorrent() {
        assertTrue(DownloadJobs.looksLikeTorrent(torrent))
        assertFalse(DownloadJobs.looksLikeTorrent("<html>4:infod</html>".toByteArray()))
        assertFalse(DownloadJobs.looksLikeTorrent(ByteArray(0)))
        assertFalse(DownloadJobs.looksLikeTorrent(ByteArray((DownloadJobs.TORRENT_MAX_BYTES + 1).toInt()) { 'd'.code.toByte() }))
    }

    @Test
    fun aTorrentsFolderIsPlacedAsOneEntryWithItsTopFolderUnwrapped() {
        val games = File(dir, "games").apply { mkdirs() }
        File(games, "Some Game").mkdirs()
        val fetched = File(dir, "acquire_1")
        File(fetched, "Some.Game.v1/data").mkdirs()
        File(fetched, "Some.Game.v1/data/a.bin").writeBytes(data)
        val placed = DownloadJobs.placeFolder(fetched, mapOf("destinationPath" to games.path, "targetName" to "Some Game"))
        assertEquals("a folder of that name is kept", File(games, "Some Game (2)"), placed)
        assertArrayEquals(data, File(placed, "data/a.bin").readBytes())
        assertFalse(fetched.exists())
    }

    @Test
    fun anUpdatesTorrentIsItsNewVersionFolder() {
        val games = File(dir, "games").apply { mkdirs() }
        val fetched = File(dir, "acquire_2").apply { mkdirs() }
        File(fetched, "a.bin").writeBytes(data)
        File(fetched, "b.bin").writeBytes(data)
        val placed = DownloadJobs.placeFolder(fetched, mapOf("destinationPath" to games.path, "targetName" to "G", DownloadJobs.ARG_VERSION_FOLDER to "G v2"))
        assertEquals(File(games, "G v2"), placed)
        assertEquals(setOf("a.bin", "b.bin"), placed.list()!!.toSet())
    }

    @Test
    fun aFolderIsCopiedWholeWhenItCannotBeRenamedAndOnlyThenRemoved() {
        val from = File(dir, "from").apply { mkdirs() }
        File(from, "sub").mkdirs()
        File(from, "sub/a.bin").writeBytes(data)
        val to = File(dir, "elsewhere/to").apply { parentFile!!.mkdirs() }
        var refusedOnce = false
        DownloadJobs.moveFolder(from, to) { a, b -> if (!refusedOnce) { refusedOnce = true; false } else a.renameTo(b) }
        assertArrayEquals(data, File(to, "sub/a.bin").readBytes())
        assertFalse(from.exists())
        assertFalse(File(to.parentFile, ".to.placing").exists())
    }

    @Test
    fun `an unpack cut short by the process dying is done again from the archive, its leftover folder cleared`(): Unit = kotlinx.coroutines.runBlocking {
        // Droidtop/tracker#842: ".Bare Witness vSteam Build.unpacking" was left behind in the game folder.
        val games = java.nio.file.Files.createTempDirectory("unpack842").toFile()
        val archive = zipOf(File(games.parentFile, "bw_${System.nanoTime()}.zip"), "Game/game.exe" to "exe", "Game/data.rpa" to "data")
        val stale = File(games, ".Game v0.5.unpacking").apply { mkdirs() }
        File(stale, "half-written.bin").writeText("cut short")
        val folder = DownloadJobs.unpackIntoFolder(archive, mapOf("destinationPath" to games.path, "targetName" to "x.zip", DownloadJobs.ARG_VERSION_FOLDER to "Game v0.5"))
        assertEquals("Game v0.5", folder.name)
        assertTrue(File(folder, "game.exe").isFile)
        assertFalse(File(folder, "half-written.bin").exists())
        assertFalse("the leftover folder is gone", stale.exists())
        games.deleteRecursively()
    }

    @Test
    fun `an unpack's percent is by bytes, else by entries`() {
        assertEquals(50, DownloadJobs.unpackPercent(500, 1000, 1, 10))
        assertEquals(30, DownloadJobs.unpackPercent(0, 0, 3, 10))
        assertEquals(0, DownloadJobs.unpackPercent(0, 0, 0, 0))
    }
}
