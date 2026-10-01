package dev.droidtop.pluginhost

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * Unit tests for [PluginJobsCenter]'s own routing/filtering logic --
 * which job's progress/completion updates which tracked [PluginJobsCenter.Entry]
 * -- using a fake [PluginJobRunner] (via [PluginJobsCenter.runnerFactory])
 * instead of a real Android Service/RemoteCallbackList/binder connection.
 * Reproduces, deterministically, the two real bugs found on the rig
 * 2026-09-27 (dq-pluginui-01) and fixed the same day:
 *
 * 1. A job whose own completion callback fires BEFORE [PluginJobsCenter.start]
 *    itself returns (a real, fast buildbot core download did exactly
 *    this) must still be reflected -- [entryExistsBeforeStartReturns].
 * 2. Once [PluginRuntimeService] broadcasts every event to every
 *    registered connection (the actual fix for the callback-theft bug),
 *    one job's event delivered to a DIFFERENT job's runner instance must
 *    never update the wrong [PluginJobsCenter.Entry] --
 *    [concurrentJobsDoNotCrossContaminate].
 */
class PluginJobsCenterTest {

    @After
    fun resetFactory() {
        PluginJobsCenter.nativeDispatcher = Dispatchers.IO
        // Every test replaces [PluginJobsCenter.runnerFactory]; put the
        // real production default back so no other test (in this class
        // or, since it's a JVM-static singleton, any other class run in
        // the same process) ever accidentally runs against a fake left
        // behind by a previous test.
        PluginJobsCenter.runnerFactory = { context, onProgress, onComplete ->
            PluginCrashPolicy(
                requireNotNull(context) { "a real Context is required outside tests" },
                onJobProgress = onProgress,
                onJobComplete = onComplete,
            )
        }
    }

    /** A [PluginJobRunner] whose [startJob] hands the test full manual control over when (and with what jobId) progress/completion fire. */
    private class FakeJobRunner(
        private val onJobProgress: (pluginId: String, jobId: String, percent: Int, statusLine: String, resumePayload: String?) -> Unit,
        private val onJobComplete: (pluginId: String, jobId: String, result: PluginResult) -> Unit,
        private val onStart: (FakeJobRunner) -> Boolean = { true },
    ) : PluginJobRunner {
        override val supportsCheckpointResume: Boolean get() = true
        lateinit var pluginId: String
            private set
        var cancelledJobId: String? = null
            private set
        var shutdownCalled = false
            private set
        var pauseCalled = false
            private set
        var resumedPayload: String? = null
            private set
        val resumed = CountDownLatch(1)

        override suspend fun startJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String): Boolean {
            pluginId = record.manifest.id
            return onStart(this)
        }

        override fun cancelJob(pluginId: String, jobId: String) {
            cancelledJobId = jobId
        }

        override fun pauseJob(pluginId: String, jobId: String, resumePayload: String): Boolean { pauseCalled = true; return true }

        override suspend fun resumeJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String, resumePayload: String): Boolean {
            resumedPayload = resumePayload
            resumed.countDown()
            return true
        }

        override fun shutdown() {
            shutdownCalled = true
        }

        fun fireProgress(jobId: String, percent: Int, statusLine: String, checkpoint: String? = null) = onJobProgress(pluginId, jobId, percent, statusLine, checkpoint)
        fun fireComplete(jobId: String, result: PluginResult) = onJobComplete(pluginId, jobId, result)
    }

    private fun record(id: String) = PluginRecord(
        manifest = PluginManifest(
            id = id,
            origin = "test",
            label = "Test plugin $id",
            description = null,
            version = "1.0",
            kind = PluginKind.NATIVE_BUNDLE,
            capabilities = setOf(PluginCapability.APP_STATUS),
            contractVersion = 1,
            requestsRoot = false,
            abis = emptySet(),
            entryClass = "dev.droidtop.test.Fake",
            payload = emptyList(),
        ),
        archiveDigest = "digest",
        trust = PluginTrustState.APPROVED,
        enabled = true,
        rootApproved = false,
        disabledReason = null,
    )

    @Test
    fun entryExistsBeforeStartReturns() = runBlocking {
        // The exact ordering dq-pluginui-01 found broken: the runner's
        // own startJob() completes the job SYNCHRONOUSLY, before
        // PluginJobsCenter.start() itself has returned a jobId to
        // anyone. The old code compared against a jobId var that did not
        // exist yet at this point, and the Entry itself was only added
        // to state AFTER this call -- both silently dropped the update.
        var capturedJobId: String? = null
        PluginJobsCenter.runnerFactory = { _, onProgress, onComplete ->
            object : PluginJobRunner {
                override suspend fun startJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String): Boolean {
                    capturedJobId = jobId
                    // Fires before this suspend function itself returns
                    // -- the precise race: :pluginhost's own dispatch and
                    // completion can outrun the AIDL call's return trip.
                    onComplete(record.manifest.id, jobId, PluginResult.success(mapOf("core" to "snes9x")))
                    return true
                }

                override fun cancelJob(pluginId: String, jobId: String) {}
                override fun shutdown() {}
            }
        }

        val jobId = PluginJobsCenter.start(
            context = null,
            record = record("droidtop.fake1"),
            capability = PluginCapability.APP_STATUS,
            args = emptyMap(),
            title = "Test job",
        )

        assertNotNull("start() should accept the job", jobId)
        assertEquals(capturedJobId, jobId)
        val entry = PluginJobsCenter.find(jobId!!)
        assertNotNull("the Entry must exist even though completion raced start()'s own return", entry)
        assertTrue("the Entry must be marked done", entry!!.done)
        assertEquals("snes9x", entry.result?.values?.get("core"))
    }

    @Test
    fun concurrentJobsDoNotCrossContaminate() = runBlocking {
        val fakes = mutableListOf<FakeJobRunner>()
        PluginJobsCenter.runnerFactory = { _, onProgress, onComplete ->
            FakeJobRunner(onProgress, onComplete).also { fakes += it }
        }

        val jobA = PluginJobsCenter.start(null, record("droidtop.a"), PluginCapability.APP_STATUS, emptyMap(), "Job A")
        val jobB = PluginJobsCenter.start(null, record("droidtop.b"), PluginCapability.APP_STATUS, emptyMap(), "Job B")
        assertNotNull(jobA)
        assertNotNull(jobB)
        assertEquals(2, fakes.size)
        val fakeA = fakes[0]
        val fakeB = fakes[1]

        // Simulates PluginRuntimeService's real broadcast behavior: BOTH
        // connections receive BOTH jobs' events now (the fix for the
        // callback-theft bug), so fakeA's own registered lambda is
        // called with job B's id here, exactly as the real broadcast
        // would deliver it.
        fakeA.fireProgress(jobB!!, 40, "should be ignored by job A's own entry")
        assertEquals(-1, PluginJobsCenter.find(jobA!!)!!.percent)
        assertEquals(-1, PluginJobsCenter.find(jobB)!!.percent)

        // The correctly-addressed event still works.
        fakeA.fireProgress(jobA, 40, "job A progress")
        assertEquals(40, PluginJobsCenter.find(jobA)!!.percent)
        assertEquals(-1, PluginJobsCenter.find(jobB)!!.percent)

        fakeB.fireComplete(jobB, PluginResult.success(mapOf("k" to "b")))
        fakeA.fireComplete(jobA, PluginResult.success(mapOf("k" to "a")))

        assertTrue(PluginJobsCenter.find(jobA)!!.done)
        assertTrue(PluginJobsCenter.find(jobB)!!.done)
        assertEquals("a", PluginJobsCenter.find(jobA)!!.result?.values?.get("k"))
        assertEquals("b", PluginJobsCenter.find(jobB)!!.result?.values?.get("k"))
    }

    @Test
    fun refusedJobLeavesNoEntry() = runBlocking {
        PluginJobsCenter.runnerFactory = { _, onProgress, onComplete ->
            FakeJobRunner(onProgress, onComplete, onStart = { false })
        }
        val jobId = PluginJobsCenter.start(null, record("droidtop.refused"), PluginCapability.APP_STATUS, emptyMap(), "Refused job")
        assertNull(jobId)
    }

    @Test
    fun cancelReachesTheRightRunner() = runBlocking {
        val fakes = mutableListOf<FakeJobRunner>()
        PluginJobsCenter.runnerFactory = { _, onProgress, onComplete ->
            FakeJobRunner(onProgress, onComplete).also { fakes += it }
        }
        val jobA = PluginJobsCenter.start(null, record("droidtop.a"), PluginCapability.APP_STATUS, emptyMap(), "Job A")!!
        val jobB = PluginJobsCenter.start(null, record("droidtop.b"), PluginCapability.APP_STATUS, emptyMap(), "Job B")!!

        PluginJobsCenter.cancel(jobB)

        assertNull("job A's runner must not see a cancel meant for job B", fakes[0].cancelledJobId)
        assertEquals(jobB, fakes[1].cancelledJobId)
        assertFalse(fakes[0].shutdownCalled)
    }

    @Test
    fun pausableJobKeepsCheckpointAcrossPauseAndResumeThenCompletes() = runBlocking {
        lateinit var fake: FakeJobRunner
        PluginJobsCenter.runnerFactory = { _, onProgress, onComplete -> FakeJobRunner(onProgress, onComplete).also { fake = it } }
        val id = PluginJobsCenter.start(null, record("droidtop.checkpoint"), PluginCapability.APP_STATUS, mapOf("seed" to "x"), "Checkpoint job", pausable = true, resumable = true)!!
        fake.fireProgress(id, 35, "Working", "part-35")
        assertTrue(PluginJobsCenter.pause(id))
        assertTrue(fake.pauseCalled)
        assertTrue(PluginJobsCenter.find(id)!!.paused)
        assertEquals("part-35", PluginJobsCenter.find(id)!!.resumePayload)
        assertTrue(PluginJobsCenter.resume(id))
        assertFalse(PluginJobsCenter.find(id)!!.paused)
        assertTrue(fake.resumed.await(2, TimeUnit.SECONDS))
        assertEquals("part-35", fake.resumedPayload)
        fake.fireComplete(id, PluginResult.success())
        assertTrue(PluginJobsCenter.find(id)!!.done)
    }

    @Test
    fun resumableJobCanBeCancelled() = runBlocking {
        lateinit var fake: FakeJobRunner
        PluginJobsCenter.runnerFactory = { _, onProgress, onComplete -> FakeJobRunner(onProgress, onComplete).also { fake = it } }
        val id = PluginJobsCenter.start(null, record("droidtop.cancel-resumable"), PluginCapability.APP_STATUS, emptyMap(), "Cancelable", pausable = true, resumable = true)!!
        fake.fireProgress(id, 10, "Working", "checkpoint")
        PluginJobsCenter.cancel(id)
        assertEquals(id, fake.cancelledJobId)
        assertNull(PluginJobsCenter.find(id))
    }

    @Test
    fun persistenceRoundTripRestoresResumableEntryPausedWithCheckpoint() {
        val original = PluginJobsCenter.Entry("persist-id", "droidtop.persist", "Persist", PluginCapability.APP_STATUS, "Persist job", 123L,
            percent = 42, statusLine = "Working", pausable = true, resumable = true, resumePayload = "chunk-42")
        val restored = PluginJobsCenter.decodeEntries(PluginJobsCenter.encodeEntries(listOf(original))).single()
        assertEquals(original.jobId, restored.jobId)
        assertEquals(original.percent, restored.percent)
        assertEquals("chunk-42", restored.resumePayload)
        assertTrue(restored.paused)
    }

    // A job droidtop runs itself (Droidtop/tracker#174): same list, same checkpoint, pause and resume.

    @Test
    fun nativeJobPausesAtItsCheckpointAndResumesFromIt() = runBlocking {
        PluginJobsCenter.nativeDispatcher = Dispatchers.Default
        val starts = CopyOnWriteArrayList<String?>()
        val reachedThird = CompletableDeferred<Unit>()
        PluginJobsCenter.registerNative("test_native_pause") { _, checkpoint, report ->
            starts += checkpoint
            val first = checkpoint?.toInt() ?: 0
            for (item in first until 5) {
                // The checkpoint is the next item to do, as a scrape's is the position in its queue.
                report(item * 20, "item $item", (item + 1).toString())
                if (item == 2 && checkpoint == null) {
                    reachedThird.complete(Unit)
                    awaitCancellation()
                }
            }
            "all five"
        }
        val finished = CompletableDeferred<PluginResult>()
        val id = PluginJobsCenter.startNative(null, "test_native_pause", "Native job", onComplete = { finished.complete(it) })!!
        withTimeout(5_000) { reachedThird.await() }

        assertTrue(PluginJobsCenter.pause(id))
        val paused = PluginJobsCenter.find(id)!!
        assertTrue(paused.paused)
        assertEquals("3", paused.resumePayload)
        assertEquals("test_native_pause", paused.nativeKind)

        assertTrue(PluginJobsCenter.resume(id))
        val result = withTimeout(5_000) { finished.await() }
        assertTrue(result.ok)
        assertEquals("all five", result.values["summary"])
        assertEquals(listOf<String?>(null, "3"), starts.toList())
        assertTrue(PluginJobsCenter.find(id)!!.done)
    }

    @Test
    fun nativeJobRestoredAfterARestartResumesFromTheStoredCheckpoint() = runBlocking {
        PluginJobsCenter.nativeDispatcher = Dispatchers.Default
        val starts = CopyOnWriteArrayList<Pair<Map<String, String>, String?>>()
        val reached = CompletableDeferred<Unit>()
        PluginJobsCenter.registerNative("test_native_restart") { args, checkpoint, report ->
            starts += args to checkpoint
            if (checkpoint == null) {
                report(50, "halfway", "queue-position-7")
                reached.complete(Unit)
                awaitCancellation()
            }
            "resumed at $checkpoint"
        }
        val id = PluginJobsCenter.startNative(null, "test_native_restart", "Restart job", mapOf("system" to "snes"))!!
        withTimeout(5_000) { reached.await() }
        assertTrue(PluginJobsCenter.pause(id))
        val stored = PluginJobsCenter.encodeEntries(listOf(PluginJobsCenter.find(id)!!))

        // The process dies: the job and its runner state are gone, the stored text is what is left.
        PluginJobsCenter.cancel(id)
        assertNull(PluginJobsCenter.find(id))
        val restored = PluginJobsCenter.decodeEntries(stored).single()
        assertTrue(restored.paused)
        assertEquals("queue-position-7", restored.resumePayload)
        assertEquals("test_native_restart", restored.nativeKind)

        PluginJobsCenter.restore(listOf(restored))
        assertTrue(PluginJobsCenter.resume(id))
        withTimeout(5_000) {
            while (PluginJobsCenter.find(id)?.done != true) kotlinx.coroutines.delay(10)
        }
        assertEquals(mapOf("system" to "snes") to "queue-position-7", starts.last())
        assertEquals("resumed at queue-position-7", PluginJobsCenter.find(id)!!.result?.values?.get("summary"))
    }

    @Test
    fun startingTheSameNativeJobTwiceIsOneJob() = runBlocking {
        PluginJobsCenter.nativeDispatcher = Dispatchers.Default
        val hold = CompletableDeferred<Unit>()
        PluginJobsCenter.registerNative("test_native_once") { _, _, _ -> hold.await(); "ok" }
        val first = PluginJobsCenter.startNative(null, "test_native_once", "Same job", mapOf("a" to "1"))!!
        val second = PluginJobsCenter.startNative(null, "test_native_once", "Same job", mapOf("a" to "1"))!!
        val other = PluginJobsCenter.startNative(null, "test_native_once", "Same job", mapOf("a" to "2"))!!
        assertEquals(first, second)
        assertTrue(first != other)
        PluginJobsCenter.cancel(first)
        PluginJobsCenter.cancel(other)
        assertNull(PluginJobsCenter.startNative(null, "test_native_unregistered", "Nobody runs this"))
    }
}
