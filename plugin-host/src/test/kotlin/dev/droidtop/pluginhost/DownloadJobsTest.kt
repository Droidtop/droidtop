package dev.droidtop.pluginhost

import android.app.DownloadManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The download runner's rules (docs/SPEC.md 12a "Downloads", Droidtop/tracker#181) against a fake
 * [DownloadBackend]: DownloadManager's status to a job state, the checkpoint that makes a restart
 * re-attach to the same download instead of queueing it again, and the ways a download fails.
 */
class DownloadJobsTest {
    private val dir: File = Files.createTempDirectory("download-jobs").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
        PluginJobsCenter.nativeDispatcher = Dispatchers.IO
    }

    private fun snapshot(status: Int, reason: Int = 0, downloaded: Long = 0, total: Long = 0) = DownloadSnapshot(status, reason, downloaded, total)

    private class FakeBackend(private val destinationBytes: ByteArray = "payload".toByteArray()) : DownloadBackend {
        val enqueued = CopyOnWriteArrayList<DownloadRequest>()
        val removed = CopyOnWriteArrayList<Long>()
        private val scripts = HashMap<Long, MutableList<DownloadSnapshot>>()
        private var nextId = 7L
        var scriptForNew: List<DownloadSnapshot> = listOf(DownloadSnapshot(DownloadManager.STATUS_SUCCESSFUL, 0, 7, 7))

        /** An entry Android already holds, as it would after a restart. */
        fun existing(id: Long, vararg states: DownloadSnapshot) {
            scripts[id] = states.toMutableList()
        }

        override fun enqueue(request: DownloadRequest, destination: File): Long {
            enqueued += request
            val id = nextId++
            scripts[id] = scriptForNew.toMutableList()
            destination.writeBytes(destinationBytes)
            return id
        }

        override fun query(id: Long): DownloadSnapshot? = scripts[id]?.let { list -> if (list.size > 1) list.removeAt(0) else list.first() }

        override fun remove(id: Long) {
            removed += id
            scripts.remove(id)
        }
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
    fun acquireDownloadDescriptorRejectsUnsafeOrInvalidFields() {
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"file:///etc/passwd","fileName":"game.zip"}"""))
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a","fileName":"../game.zip"}"""))
        assertNull(AcquireDownloadDescriptor.parse("""{"url":"https://example.invalid/a","fileName":"game.zip","size":0}"""))
        assertNull(AcquireDownloadDescriptor.parse("not json"))
    }

    @Test
    fun statusAndReasonMapToTheJobState() {
        assertEquals(DownloadState.Waiting("Waiting to start"), DownloadState.of(snapshot(DownloadManager.STATUS_PENDING)))
        assertEquals(DownloadState.Running(25, 25, 100), DownloadState.of(snapshot(DownloadManager.STATUS_RUNNING, downloaded = 25, total = 100)))
        // The server has not said how long the file is: no percentage rather than a wrong one.
        assertEquals(DownloadState.Running(-1, 5, -1), DownloadState.of(snapshot(DownloadManager.STATUS_RUNNING, downloaded = 5, total = -1)))
        assertEquals(DownloadState.Waiting("Waiting for a network"), DownloadState.of(snapshot(DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_WAITING_FOR_NETWORK)))
        assertEquals(DownloadState.Waiting("Waiting for Wi-Fi"), DownloadState.of(snapshot(DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_QUEUED_FOR_WIFI)))
        assertEquals(DownloadState.Waiting("Waiting to retry"), DownloadState.of(snapshot(DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_WAITING_TO_RETRY)))
        assertEquals(DownloadState.Succeeded, DownloadState.of(snapshot(DownloadManager.STATUS_SUCCESSFUL)))
        assertEquals(DownloadState.Failed("the server answered HTTP 404"), DownloadState.of(snapshot(DownloadManager.STATUS_FAILED, 404)))
        assertEquals(DownloadState.Failed("there is not enough free space"), DownloadState.of(snapshot(DownloadManager.STATUS_FAILED, DownloadManager.ERROR_INSUFFICIENT_SPACE)))
        assertEquals(DownloadState.Failed("the connection broke"), DownloadState.of(snapshot(DownloadManager.STATUS_FAILED, DownloadManager.ERROR_HTTP_DATA_ERROR)))
    }

    @Test
    fun aFreshDownloadIsQueuedOnceAndItsIdIsTheFirstCheckpointReported() = runBlocking {
        val backend = FakeBackend()
        backend.scriptForNew = listOf(
            snapshot(DownloadManager.STATUS_PENDING),
            snapshot(DownloadManager.STATUS_RUNNING, downloaded = 50, total = 100),
            snapshot(DownloadManager.STATUS_SUCCESSFUL, downloaded = 100, total = 100),
        )
        val reports = mutableListOf<Triple<Int, String, String?>>()
        val file = File(dir, "a.bin")
        val id = DownloadJobs.fetch(backend, DownloadRequest("https://example.invalid/a", "A", "a.bin"), file, null, 0L, { p, s, c -> reports += Triple(p, s, c) }, pollMs = 1)

        assertEquals(7L, id)
        assertEquals(1, backend.enqueued.size)
        assertEquals(Triple(-1, "Starting…", "7"), reports.first())
        assertTrue(reports.any { it.first == 50 })
        assertTrue(file.isFile)
    }

    @Test
    fun aRestartReattachesToTheSameDownloadWithoutQueueingAgain() = runBlocking {
        val backend = FakeBackend()
        backend.existing(7L, snapshot(DownloadManager.STATUS_RUNNING, downloaded = 10, total = 100), snapshot(DownloadManager.STATUS_SUCCESSFUL))
        val file = File(dir, "b.bin").apply { writeText("finished while the process was gone") }
        val id = DownloadJobs.fetch(backend, DownloadRequest("https://example.invalid/b", "B", "b.bin"), file, "7", 0L, { _, _, _ -> }, pollMs = 1)

        assertEquals(7L, id)
        assertTrue("re-attaching must not queue a second download", backend.enqueued.isEmpty())
        assertEquals("finished while the process was gone", file.readText())
    }

    @Test
    fun anEntryAndroidNoLongerHasIsQueuedAgain() = runBlocking {
        val backend = FakeBackend()
        val file = File(dir, "c.bin").apply { writeText("stale") }
        val id = DownloadJobs.fetch(backend, DownloadRequest("https://example.invalid/c", "C", "c.bin"), file, "99", 0L, { _, _, _ -> }, pollMs = 1)

        assertEquals(7L, id)
        assertEquals(1, backend.enqueued.size)
        assertEquals("payload", file.readText())
    }

    @Test
    fun aFailedDownloadIsRemovedAndSaysWhy() = runBlocking {
        val backend = FakeBackend()
        backend.scriptForNew = listOf(snapshot(DownloadManager.STATUS_FAILED, 404))
        val file = File(dir, "d.bin")
        try {
            DownloadJobs.fetch(backend, DownloadRequest("https://example.invalid/d", "D", "d.bin"), file, null, 0L, { _, _, _ -> }, pollMs = 1)
            fail("a failed download must throw")
        } catch (e: IllegalStateException) {
            assertEquals("the server answered HTTP 404", e.message)
        }
        assertEquals(listOf(7L), backend.removed.toList())
        assertFalse(file.exists())
    }

    @Test
    fun aFileOverTheCapIsRefusedAndRemoved() = runBlocking {
        val backend = FakeBackend()
        backend.scriptForNew = listOf(snapshot(DownloadManager.STATUS_RUNNING, downloaded = 1, total = 10L * 1024 * 1024))
        try {
            DownloadJobs.fetch(backend, DownloadRequest("https://example.invalid/e", "E", "e.bin"), File(dir, "e.bin"), null, 1024L * 1024, { _, _, _ -> }, pollMs = 1)
            fail("a file over the cap must throw")
        } catch (e: IllegalStateException) {
            assertEquals("the file is larger than the 1 MiB cap", e.message)
        }
        assertEquals(listOf(7L), backend.removed.toList())
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
                report(-1, "Starting…", "dm-42")
                running.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            second.complete(Unit)
            "finished from $checkpoint"
        }
        val id = PluginJobsCenter.startNative(null, "test_reattach", "A download", mapOf("url" to "u"), pausable = false)!!
        withTimeout(5_000) { running.await() }
        assertFalse("a download is not pausable", PluginJobsCenter.find(id)!!.pausable)
        assertFalse(PluginJobsCenter.pause(id))

        // The process dies: only the stored text is left.
        val stored = PluginJobsCenter.encodeEntries(listOf(PluginJobsCenter.find(id)!!))
        PluginJobsCenter.cancel(id)
        withTimeout(5_000) { while (cancelled.isEmpty()) kotlinx.coroutines.delay(5) }
        assertEquals(listOf<String?>("dm-42"), cancelled.toList())
        assertNull(PluginJobsCenter.find(id))
        val restored = PluginJobsCenter.decodeEntries(stored).single()
        assertEquals("dm-42", restored.resumePayload)

        PluginJobsCenter.restore(listOf(restored))
        withTimeout(5_000) { second.await() }
        withTimeout(5_000) { while (PluginJobsCenter.find(id)?.done != true) kotlinx.coroutines.delay(5) }
        assertEquals(listOf<String?>(null, "dm-42"), starts.toList())
        assertEquals("finished from dm-42", PluginJobsCenter.find(id)!!.result?.values?.get("summary"))
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
    fun aJobWhoseFileWasPlacedBeforeTheProcessEndedIsFinishedAndReportedNotFailed() = runBlocking {
        // The previous run renamed the file into the game folder and died before the job was marked done:
        // Android still holds the finished entry (id 42), the downloads area no longer holds the file.
        val backend = FakeBackend()
        backend.existing(42L, snapshot(DownloadManager.STATUS_SUCCESSFUL, downloaded = 7, total = 7))
        val games = File(dir, "games/snes").also { it.mkdirs() }
        File(games, "Game.zip").writeText("zip")
        val args = mapOf("destinationPath" to games.path, "targetName" to "Game.zip")
        val reported = CopyOnWriteArrayList<File>()

        val line = DownloadJobs.placedBeforeTheProcessEnded(
            backend, File(dir, "downloads/acquire_1_Game.zip"), DownloadJobs.POST_PLACE_IN_FOLDER, args, "42",
        ) { reported += it }

        assertEquals("Added Game.zip", line)
        assertEquals(listOf(File(games, "Game.zip")), reported.toList())
        assertEquals("Android's entry is released", listOf(42L), backend.removed.toList())
    }

    @Test
    fun aJobThatIsStillDownloadingOrWasNotPlacedIsNotTakenForPlaced() = runBlocking {
        val games = File(dir, "games/snes").also { it.mkdirs() }
        val args = mapOf("destinationPath" to games.path, "targetName" to "Game.zip")
        val file = File(dir, "downloads/acquire_1_Game.zip")
        val reported = CopyOnWriteArrayList<File>()
        val report = { placed: File -> reported += placed; Unit }

        // Nothing in the game folder yet.
        val notPlaced = FakeBackend().also { it.existing(42L, snapshot(DownloadManager.STATUS_SUCCESSFUL)) }
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(notPlaced, file, DownloadJobs.POST_PLACE_IN_FOLDER, args, "42", report))

        // The target is there, but the transfer is still running and its partial file is where it was.
        File(games, "Game.zip").writeText("an older file")
        file.parentFile.mkdirs()
        file.writeText("partial")
        val running = FakeBackend().also { it.existing(42L, snapshot(DownloadManager.STATUS_RUNNING, downloaded = 1, total = 7)) }
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(running, file, DownloadJobs.POST_PLACE_IN_FOLDER, args, "42", report))

        // Another kind of job, and a job with no checkpoint, are never this.
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(running, file, DownloadJobs.POST_KEEP, args, "42", report))
        assertNull(DownloadJobs.placedBeforeTheProcessEnded(running, file, DownloadJobs.POST_PLACE_IN_FOLDER, args, null, report))
        assertTrue(reported.isEmpty())
        assertTrue(running.removed.isEmpty())
    }

    @Test
    fun theSummaryCountsOnlyJobsWithNoNotificationOfTheirOwn() {
        fun entry(done: Boolean = false, paused: Boolean = false, nativeKind: String? = null) =
            PluginJobsCenter.Entry("j", "p", "P", null, "t", 0L, done = done, paused = paused, nativeKind = nativeKind)
        val list = listOf(
            entry(), entry(nativeKind = "library_scrape"), entry(nativeKind = DownloadJobs.KIND),
            entry(done = true), entry(paused = true),
        )
        assertEquals(2, JobsSummary.runningCount(list))
        assertEquals("1 job running", JobsSummary.text(1))
        assertEquals("3 jobs running", JobsSummary.text(3))
    }
}
