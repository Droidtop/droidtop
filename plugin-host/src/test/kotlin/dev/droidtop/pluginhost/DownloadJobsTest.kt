package dev.droidtop.pluginhost

import dev.droidtop.net.ResumableDownload
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
 * The download runner's rules (docs/SPEC.md 12a "Downloads", Droidtop/tracker#181) against a local server: the file
 * lands whole, a pause keeps the partial file and the next run carries on with a Range request, the ways a download
 * fails, and what a restart does with a job whose bytes had all arrived.
 */
class DownloadJobsTest {
    private val dir: File = Files.createTempDirectory("download-jobs").toFile()
    private val data = ByteArray(200_000) { (it * 7 % 253).toByte() }
    private val ranges = CopyOnWriteArrayList<String?>()
    private var server: java.net.ServerSocket? = null
    @Volatile private var honourRanges = true

    /** A bare-bones HTTP/1.0 server on a socket (the unit-test classpath here has no com.sun.net.httpserver). */
    private fun serve(slow: Boolean = false): String {
        val s = java.net.ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (_: java.io.IOException) { return@Thread }
                Thread { handle(socket, slow) }.start()
            }
        }.start()
        return "http://127.0.0.1:${s.localPort}"
    }

    private fun handle(socket: java.net.Socket, slow: Boolean) {
        socket.use {
            val input = socket.getInputStream().bufferedReader()
            val path = input.readLine()?.split(" ")?.getOrNull(1) ?: return
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(":").trim()
            }
            val out = socket.getOutputStream()
            if (path == "/gone") {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            ranges += range
            val partial = range != null && honourRanges
            val from = if (partial) range!!.removePrefix("bytes=").removeSuffix("-").toInt() else 0
            val head = StringBuilder(if (partial) "HTTP/1.0 206 Partial Content\r\n" else "HTTP/1.0 200 OK\r\n")
            head.append("ETag: \"e1\"\r\nContent-Length: ${data.size - from}\r\n")
            if (partial) head.append("Content-Range: bytes $from-${data.size - 1}/${data.size}\r\n")
            out.write((head.toString() + "\r\n").toByteArray())
            try {
                var at = from
                while (at < data.size) {
                    val n = minOf(10_000, data.size - at)
                    out.write(data, at, n)
                    out.flush()
                    at += n
                    if (slow) Thread.sleep(50)
                }
            } catch (_: java.io.IOException) {
            }
        }
    }

    @After
    fun cleanUp() {
        server?.close()
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
    fun anExistingFolderIsNeverUnpackedOver() = runBlocking {
        val archive = zipOf(File(dir, "downloads/acquire_3.zip").also { it.parentFile.mkdirs() }, "a.txt" to "a")
        val games = File(dir, "games/dos")
        File(games, "Game").mkdirs()
        File(games, "Game/mine.txt").writeText("mine")
        try {
            DownloadJobs.unpackIntoFolder(archive, mapOf("destinationPath" to games.path, "targetName" to "Game.zip"))
            fail("an existing folder is never replaced")
        } catch (e: IllegalArgumentException) {
            assertEquals("a folder with that name already exists", e.message)
        }
        assertEquals("mine", File(games, "Game/mine.txt").readText())
        assertTrue(archive.exists())
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
            assertEquals("a file with that name already exists", e.message)
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
    fun rarVolumesArePlacedTogetherAndNotHalfUnpacked() = runBlocking {
        val games = File(dir, "games/pc")
        val files = (1..2).map { File(dir, "downloads/v$it.rar").also { f -> f.parentFile.mkdirs(); f.writeText("v$it") } }
        val parts = listOf("Big.part1.rar", "Big.part2.rar").map { mapOf("destinationPath" to games.path, "targetName" to it, "unpack" to "archive") }
        val (placed, summary) = DownloadJobs.placeSplitSet(files, parts)
        assertEquals(2, placed.size)
        assertTrue(summary.contains("cannot unpack split RAR"))
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
    fun aFreshDownloadLandsWholeAndReportsItsProgress() = runBlocking {
        val base = serve()
        val reports = CopyOnWriteArrayList<Pair<Int, String>>()
        val file = File(dir, "a.bin")
        DownloadJobs.fetch("$base/f", emptyMap(), file, 0L, { percent, line, _ -> reports += percent to line })
        assertArrayEquals(data, file.readBytes())
        assertTrue(reports.any { it.first == 100 })
        assertTrue(reports.all { it.second.startsWith("Downloading… ") })
        assertFalse(ResumableDownload.partOf(file).exists())
    }

    @Test
    fun aPausedDownloadKeepsItsPartialFileAndCarriesOnWithARangeRequest() = runBlocking {
        val base = serve(slow = true)
        val file = File(dir, "b.bin")
        val running = launch(Dispatchers.IO) { DownloadJobs.fetch("$base/f", emptyMap(), file, 0L, { _, _, _ -> }) }
        withTimeout(10_000) { while (ResumableDownload.partOf(file).length() < 20_000) delay(10) }
        running.cancel()
        withTimeout(10_000) { running.join() }
        val kept = ResumableDownload.partOf(file).length()
        assertTrue("the partial file survives a pause", kept in 1 until data.size)
        assertFalse(file.exists())

        DownloadJobs.fetch("$base/f", emptyMap(), file, 0L, { _, _, _ -> })
        assertArrayEquals(data, file.readBytes())
        assertNull(ranges.first())
        assertEquals("bytes=$kept-", ranges.last())
    }

    @Test
    fun aServerThatCannotResumeStartsAgainAndTheLineSays() = runBlocking {
        honourRanges = false
        val base = serve()
        val file = File(dir, "c.bin")
        ResumableDownload.partOf(file).writeBytes(data.copyOf(50_000))
        ResumableDownload.metaOf(file).writeText(ResumableDownload.Meta("\"e1\"", null, data.size.toLong()).encode())
        val lines = CopyOnWriteArrayList<String>()
        DownloadJobs.fetch("$base/f", emptyMap(), file, 0L, { _, line, _ -> lines += line })
        assertArrayEquals(data, file.readBytes())
        assertTrue(lines.any { it.startsWith("Started again: the server cannot resume. Downloading") })
    }

    @Test
    fun aMissingFileFailsWithTheServersAnswer() = runBlocking {
        val base = serve()
        try {
            DownloadJobs.fetch("$base/gone", emptyMap(), File(dir, "d.bin"), 0L, { _, _, _ -> })
            fail("a 404 must throw")
        } catch (e: IllegalStateException) {
            assertEquals("the server answered HTTP 404", e.message)
        }
    }

    @Test
    fun aFileOverTheCapIsRefusedAndNothingIsKept() = runBlocking {
        val base = serve()
        val file = File(dir, "e.bin")
        try {
            DownloadJobs.fetch("$base/f", emptyMap(), file, 1024L * 1024 / 1024, { _, _, _ -> })
            fail("a file over the cap must throw")
        } catch (e: IllegalStateException) {
            assertEquals("the file is larger than the 0 MiB cap", e.message)
        }
        assertFalse(ResumableDownload.partOf(file).exists())
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
    fun aDownloadIsPlacedInTheGameFolderUnderItsTargetName() {
        val downloaded = File(dir, "downloads/acquire_1_Game.zip").also { it.parentFile.mkdirs(); it.writeText("zip") }
        val games = File(dir, "games/snes")
        val target = DownloadJobs.placeInFolder(downloaded, mapOf("destinationPath" to games.path, "targetName" to "Game.zip"))
        assertEquals(File(games, "Game.zip"), target)
        assertTrue(target.isFile)
        assertFalse(downloaded.exists())

        val again = File(dir, "downloads/acquire_2_Game.zip").also { it.writeText("zip") }
        try {
            DownloadJobs.placeInFolder(again, mapOf("destinationPath" to games.path, "targetName" to "Game.zip"))
            fail("an existing file is never replaced")
        } catch (e: IllegalArgumentException) {
            assertEquals("a file with that name already exists", e.message)
        }
        assertTrue("the download stays for a retry", again.exists())
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
}
