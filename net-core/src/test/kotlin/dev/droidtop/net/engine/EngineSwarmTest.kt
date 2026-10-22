package dev.droidtop.net.engine

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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

/** Torrents, magnets and Metalink on the engine (docs/SPEC.md 12a, rollout step 3), against an engine that answers from a script. */
class EngineSwarmTest {
    private val dir: File = Files.createTempDirectory("engine-swarm").toFile()
    private val folder = File(dir, "acquire_1")
    private val mib = 1024L * 1024
    private val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Some+Game"

    @After
    fun clean() {
        folder.delete()
        dir.delete()
    }

    /**
     * Each `addSwarm` takes the next script as its gid's; [followers] are the scripts of gids a download becomes. Every
     * `status` answers the gid's next entry, the last one again.
     */
    private class FakeEngine(adds: List<List<Transfer>>, followers: Map<String, List<Transfer>> = emptyMap()) : TransferEngine {
        private val adds = ArrayDeque(adds)
        private val scripts = HashMap(followers.mapValues { ArrayDeque(it.value) })
        val added = mutableListOf<Pair<SwarmSource, File>>()
        val removed = mutableListOf<String>()

        override fun add(url: String, options: Map<String, String>): String = fail("a swarm download is not a single file") as String

        override fun addSwarm(source: SwarmSource, folder: File, options: Map<String, String>): String {
            added += source to folder
            val gid = "gid${added.size}"
            scripts[gid] = ArrayDeque(adds.removeFirst())
            return gid
        }

        override fun status(gid: String): Transfer? {
            val queue = scripts[gid] ?: return null
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        override fun remove(gid: String) {
            removed += gid
        }
    }

    private fun active(done: Long, total: Long, speed: Long = 1_000_000) = Transfer("active", total, done, speed, 0)

    private fun complete(total: Long, followedBy: List<String> = emptyList()) = Transfer("complete", total, total, 0, 0, followedBy)

    private val size: SizeFormat = { if (it >= mib) "${it / mib} MB" else "${it / 1024} KB" }

    @Test
    fun aMagnetFetchesItsDetailsAndThenFollowsTheTorrentItBecame() = runTest {
        val engine = FakeEngine(
            listOf(listOf(active(0, 0, 0), complete(16 * 1024, followedBy = listOf("t1")))),
            mapOf("t1" to listOf(active(30 * mib, 100 * mib), complete(100 * mib))),
        )
        val reports = mutableListOf<Pair<Int, String>>()
        EngineFetch.fetchSwarm(engine, SwarmRequest(SwarmSource.Magnet(magnet), folder), { reports += it.percent to it.line }, size, Dispatchers.Unconfined)
        assertEquals("Fetching the torrent's details…", reports.first().second)
        assertEquals(30 to "Downloading… 30 MB of 100 MB (976 KB/s)", reports.last())
        assertEquals(folder, engine.added.single().second)
        assertTrue("the folder is made before the engine writes into it", folder.isDirectory)
        assertTrue("a finished download is not removed", engine.removed.isEmpty())
    }

    @Test
    fun aMetalinkOfSeveralFilesIsWholeWhenEveryFileIs() = runTest {
        val engine = FakeEngine(
            listOf(listOf(complete(2 * 1024, followedBy = listOf("a", "b")))),
            mapOf(
                "a" to listOf(active(10 * mib, 20 * mib), complete(20 * mib)),
                "b" to listOf(active(10 * mib, 20 * mib), active(15 * mib, 20 * mib), complete(20 * mib)),
            ),
        )
        val reports = mutableListOf<Int>()
        EngineFetch.fetchSwarm(engine, SwarmRequest(SwarmSource.Metalink(ByteArray(4)), folder), { reports += it.percent }, size, Dispatchers.Unconfined)
        assertEquals("the two files' progress is summed", 50, reports.first())
        assertEquals(87, reports.last())
    }

    @Test
    fun aPauseRemovesEveryLiveDownloadAndKeepsTheFiles() = runTest {
        val engine = FakeEngine(listOf(listOf(complete(1024, followedBy = listOf("t1")))), mapOf("t1" to listOf(active(5 * mib, 100 * mib))))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            EngineFetch.fetchSwarm(engine, SwarmRequest(SwarmSource.Magnet(magnet), folder), { }, size, Dispatchers.Unconfined)
        }
        advanceTimeBy(EngineFetch.POLL_MS * 3)
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf("t1"), engine.removed)
        assertTrue(folder.isDirectory)
    }

    @Test
    fun aDownloadOverTheCapIsRefused() = runTest {
        val engine = FakeEngine(listOf(listOf(active(0, 900 * mib))))
        try {
            EngineFetch.fetchSwarm(engine, SwarmRequest(SwarmSource.Torrent(ByteArray(4)), folder, maxBytes = 512 * mib), { }, size, Dispatchers.Unconfined)
            fail("over the cap")
        } catch (e: TransferException) {
            assertEquals("the download is larger than the 512 MiB cap", e.message)
        }
        assertEquals(listOf("gid1"), engine.removed)
    }

    @Test
    fun aFailedPieceEndsTheTransferWithASentenceAndStopsTheOthers() = runTest {
        val engine = FakeEngine(
            listOf(listOf(complete(1024, followedBy = listOf("a", "b")))),
            mapOf("a" to listOf(Transfer("error", 10 * mib, 0, 0, 9)), "b" to listOf(active(1 * mib, 10 * mib))),
        )
        try {
            EngineFetch.fetchSwarm(engine, SwarmRequest(SwarmSource.Metalink(ByteArray(4)), folder), { }, size, Dispatchers.Unconfined)
            fail("a full disk fails the download")
        } catch (e: TransferException) {
            assertEquals("there is not enough space for this download", e.message)
        }
        assertEquals(listOf("b"), engine.removed)
    }

    @Test
    fun onlyAMagnetNamingItsTorrentIsTaken() = runTest {
        val engine = FakeEngine(emptyList())
        try {
            EngineFetch.fetchSwarm(engine, SwarmRequest(SwarmSource.Magnet("magnet:?dn=just+a+name"), folder), { }, size, Dispatchers.Unconfined)
            fail("not a torrent's magnet")
        } catch (e: TransferException) {
            assertEquals("that is not a magnet link", e.message)
        }
        assertTrue(engine.added.isEmpty())
        assertTrue(SwarmSource.isMagnet(magnet))
        assertTrue(SwarmSource.isMagnet("magnet:?dn=x&xt=urn:btmh:1220abcd"))
        assertFalse(SwarmSource.isMagnet("https://example.test/?xt=urn:btih:abc"))
    }
}
