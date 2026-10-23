package dev.droidtop.net.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The transfer loop against an engine that answers from a script (docs/SPEC.md 12a "One download engine"). */
class EngineFetchTest {
    private val dir: File = Files.createTempDirectory("engine-fetch").toFile()
    private val target = File(dir, "acquire_1.bin")
    private val mib = 1024L * 1024

    @After
    fun clean() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /** Each `add` takes the next script; its `status` calls answer the script's entries in turn, the last one again. */
    private class FakeEngine(scripts: List<List<Any>>) : TransferEngine {
        private val scripts = ArrayDeque(scripts)
        private val current = HashMap<String, ArrayDeque<Any>>()
        val added = mutableListOf<Pair<String, Map<String, String>>>()
        val removed = mutableListOf<String>()

        override fun add(url: String, options: Map<String, String>): String {
            added += url to options
            val gid = "gid${added.size}"
            current[gid] = ArrayDeque(scripts.removeFirst())
            return gid
        }

        override fun status(gid: String): Transfer? {
            val queue = current.getValue(gid)
            val next = if (queue.size > 1) queue.removeFirst() else queue.first()
            if (next is IOException) throw next
            return next as Transfer
        }

        override fun remove(gid: String) {
            removed += gid
        }
    }

    private fun active(done: Long, total: Long, speed: Long = 1_000_000) = Transfer("active", total, done, speed, 0)

    private fun complete(total: Long) = Transfer("complete", total, total, 0, 0)

    private fun failed(code: Int, done: Long = 0) = Transfer("error", 100 * mib, done, 0, code)

    private fun request(maxBytes: Long = 0L) = TransferRequest("http://example.test/f", mapOf("Accept" to "*/*"), target, maxBytes)

    private val size: SizeFormat = { if (it >= mib) "${it / mib} MB" else "${it / 1024} KB" }

    private val passThrough: (String, Map<String, String>) -> RedirectGuard.Resolved = { url, headers -> RedirectGuard.Resolved(url, headers) }

    @Test
    fun aWholeFileReportsItsProgressAndReturns() = runTest {
        val engine = FakeEngine(listOf(listOf(active(0, 100 * mib, 0), active(40 * mib, 100 * mib), complete(100 * mib))))
        val reports = mutableListOf<Pair<Int, String>>()
        EngineFetch.fetch(engine, request(), { reports += it.percent to it.line }, size, passThrough, Dispatchers.Unconfined)
        assertEquals(listOf(0, 40), reports.map { it.first })
        assertEquals("Connecting…", reports[0].second)
        assertEquals("Downloading… 40 MB of 100 MB (976 KB/s)", reports[1].second)
        assertEquals(1, engine.added.size)
        assertTrue("a finished download is not removed", engine.removed.isEmpty())
        val options = engine.added.single().second
        assertEquals("acquire_1.bin", options["out"])
        assertEquals("1", options["split"])
        assertEquals("Accept: */*", options["header"])
    }

    @Test
    fun aDownloadTheEngineSaysEndedIsReadAtOnceNotAtTheNextPoll() = runTest {
        // A store's chunk of a megabyte is done long before a poll: the engine's word ends the wait.
        val ends = MutableSharedFlow<String>(replay = 1)
        val engine = object : TransferEngine by FakeEngine(listOf(listOf(complete(1 * mib)))) {
            override val ended: Flow<String> = ends
        }
        ends.tryEmit("gid1")
        EngineFetch.fetch(engine, request(), { }, size, passThrough, Dispatchers.Unconfined)
        assertEquals("no poll was waited for", 0L, testScheduler.currentTime)
    }

    @Test
    fun anEndTheEngineNeverToldIsReadByThePoll() = runTest {
        EngineFetch.fetch(FakeEngine(listOf(listOf(complete(1 * mib)))), request(), { }, size, passThrough, Dispatchers.Unconfined)
        assertEquals(EngineFetch.POLL_MS, testScheduler.currentTime)
    }

    @Test
    fun eachReportCarriesTheEnginesNumbersAndAWaitIsMarked() = runTest {
        val engine = FakeEngine(listOf(listOf(active(40 * mib, 100 * mib), failed(6, 40 * mib)), listOf(complete(100 * mib))))
        val reports = mutableListOf<EngineProgress>()
        EngineFetch.fetch(engine, request(), { reports += it }, size, passThrough, Dispatchers.Unconfined)
        val moving = reports.first()
        assertEquals(40 * mib, moving.transfer?.completedLength)
        assertEquals(100 * mib, moving.transfer?.totalLength)
        assertTrue(!moving.waiting)
        val waiting = reports.last()
        assertTrue(waiting.waiting)
        assertEquals(null, waiting.transfer)
    }

    @Test
    fun aPauseRemovesTheDownloadFromTheEngineAndKeepsTheFile() = runTest {
        val engine = FakeEngine(listOf(listOf(active(1 * mib, 100 * mib))))
        target.writeBytes(ByteArray(10))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { EngineFetch.fetch(engine, request(), {}, size, passThrough, Dispatchers.Unconfined) }
        advanceTimeBy(2_500)
        runCurrent()
        assertTrue(engine.removed.isEmpty())
        job.cancel()
        runCurrent()
        assertEquals(listOf("gid1"), engine.removed)
        assertTrue("the partial file stays for the next add", target.isFile)
    }

    @Test
    fun aLostConnectionWaitsAndThenAsksAgain() = runTest {
        val engine = FakeEngine(listOf(listOf(failed(6)), listOf(complete(100 * mib))))
        val reports = mutableListOf<Pair<Int, String>>()
        EngineFetch.fetch(engine, request(), { reports += it.percent to it.line }, size, passThrough, Dispatchers.Unconfined)
        assertEquals(2, engine.added.size)
        assertEquals(-1 to "Waiting for the connection", reports.single())
    }

    @Test
    fun theEnginesProcessEndingIsAskedAgainToo() = runTest {
        val engine = FakeEngine(listOf(listOf(EngineGoneException("gone")), listOf(complete(100 * mib))))
        EngineFetch.fetch(engine, request(), {}, size, passThrough, Dispatchers.Unconfined)
        assertEquals(2, engine.added.size)
    }

    @Test
    fun sixFailuresWithoutProgressFailTheTransfer() = runTest {
        val engine = FakeEngine(List(20) { listOf(failed(6)) })
        try {
            EngineFetch.fetch(engine, request(), {}, size, passThrough, Dispatchers.Unconfined)
            fail()
        } catch (e: TransferException) {
            assertEquals("the download failed: the connection kept failing", e.message)
        }
        assertEquals(EngineFetch.MAX_FAILURES + 1, engine.added.size)
    }

    @Test
    fun aFailureThatMadeProgressDoesNotCountAgainstTheLimit() = runTest {
        val scripts = List(10) { listOf(failed(2, done = (it + 1) * mib)) } + listOf(listOf(complete(100 * mib)))
        val engine = FakeEngine(scripts)
        EngineFetch.fetch(engine, request(), {}, size, passThrough, Dispatchers.Unconfined)
        assertEquals(11, engine.added.size)
    }

    @Test
    fun aRefusalEndsTheTransferWithASentence() = runTest {
        val engine = FakeEngine(listOf(listOf(failed(3))))
        try {
            EngineFetch.fetch(engine, request(), {}, size, passThrough, Dispatchers.Unconfined)
            fail()
        } catch (e: TransferException) {
            assertEquals("the server does not have that file", e.message)
        }
        assertEquals(1, engine.added.size)
    }

    @Test
    fun aFileOverTheCapIsRefusedAndNothingIsKept() = runTest {
        val engine = FakeEngine(listOf(listOf(active(1 * mib, 5 * mib))))
        target.writeBytes(ByteArray(10))
        TransferRequest.controlFile(target).writeBytes(ByteArray(10))
        try {
            EngineFetch.fetch(engine, request(maxBytes = 2 * mib), {}, size, passThrough, Dispatchers.Unconfined)
            fail()
        } catch (e: TransferException) {
            assertEquals("the file is larger than the 2 MiB cap", e.message)
        }
        assertEquals(listOf("gid1"), engine.removed)
        assertFalse(target.exists())
        assertFalse(TransferRequest.controlFile(target).exists())
    }

    @Test
    fun aRangeTheServerWillNotSendFailsRatherThanStartingAgain() = runTest {
        // GOG's Gen 1 main.bin: a server that ignores the range would send the whole file every time.
        val engine = FakeEngine(listOf(listOf(failed(8))))
        try {
            EngineFetch.fetch(engine, TransferRequest("http://example.test/main.bin", emptyMap(), target, range = 10L..20L), { }, size, passThrough, Dispatchers.Unconfined)
            fail("a range the server will not send")
        } catch (e: TransferException) {
            assertEquals("the server did not send that part of the file", e.message)
            assertEquals(8, e.code)
        }
        assertEquals("asked once, not again", 1, engine.added.size)
        assertEquals("10-20", engine.added.single().second["byte-range"])
    }

    @Test
    fun aServerThatCannotResumeStartsTheFileAgainAndSaysSo() = runTest {
        val engine = FakeEngine(listOf(listOf(failed(8)), listOf(active(1 * mib, 100 * mib), complete(100 * mib))))
        target.writeBytes(ByteArray(10))
        val reports = mutableListOf<String>()
        EngineFetch.fetch(engine, request(), { reports += it.line }, size, passThrough, Dispatchers.Unconfined)
        assertEquals(2, engine.added.size)
        assertFalse("the stale partial file was dropped", target.exists())
        assertEquals("Started again: the server cannot resume. Downloading… 1 MB of 100 MB (976 KB/s)", reports.single())
    }

    @Test
    fun theWalkedAddressAndItsHeadersAreWhatTheEngineGets() = runTest {
        val engine = FakeEngine(listOf(listOf(complete(100 * mib))))
        val request = TransferRequest("http://a.test/f", mapOf("Cookie" to "s=1", "Referer" to "http://a.test/"), target)
        EngineFetch.fetch(engine, request, {}, size, { _, _ -> RedirectGuard.Resolved("http://cdn.test/f?sig=1", mapOf("Accept" to "*/*")) }, Dispatchers.Unconfined)
        assertEquals("http://cdn.test/f?sig=1", engine.added.single().first)
        val options = engine.added.single().second
        assertEquals("Accept: */*", options["header"])
        assertFalse("referer" in options)
    }

    @Test
    fun statusLines() {
        assertEquals("Connecting…", EngineFetch.line(active(0, 0, 0), restarted = false, stalled = false, size = size))
        assertEquals("Waiting for the connection", EngineFetch.line(active(5 * mib, 10 * mib, 0), restarted = false, stalled = true, size = size))
        assertEquals("Downloading… 5 MB (2 MB/s)", EngineFetch.line(active(5 * mib, 0, 2 * mib), restarted = false, stalled = false, size = size))
        assertEquals(-1, EngineFetch.percentOf(active(5 * mib, 0)))
        assertEquals(50, EngineFetch.percentOf(active(5 * mib, 10 * mib)))
    }

    @Test
    fun backoffDoublesFromOneSecondToThirty() {
        assertEquals(1_000L, EngineFetch.backoff(1))
        assertEquals(4_000L, EngineFetch.backoff(3))
        assertEquals(30_000L, EngineFetch.backoff(9))
    }
}
