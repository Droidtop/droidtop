package dev.droidtop.pluginhost

import dev.droidtop.net.Conditions
import dev.droidtop.net.DownloadSettings
import dev.droidtop.net.JobFacts
import dev.droidtop.net.NetworkClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadGateTest {
    private val kind = "gate_test_download"
    private val settings = DownloadSettings()
    private val offline = Conditions(NetworkClass.OFFLINE, 12 * 60, false)
    private val wifi = Conditions(NetworkClass.UNMETERED, 12 * 60, false)
    private val facts = { _: PluginJobsCenter.Entry -> JobFacts(10L * 1024 * 1024) }

    @Before
    fun register() {
        PluginJobsCenter.nativeDispatcher = Dispatchers.Default
        PluginJobsCenter.registerNative(kind, download = true) { _, _, _ -> "ok" }
    }

    @After
    fun reset() {
        PluginJobsCenter.nativeDispatcher = Dispatchers.IO
        PluginJobsCenter.admission = { _, _ -> null }
    }

    private fun entry(
        id: String,
        paused: Boolean = false,
        hold: String? = null,
        override: Boolean = false,
        done: Boolean = false,
        nativeKind: String? = kind,
    ) = PluginJobsCenter.Entry(
        jobId = id, pluginId = "droidtop", pluginLabel = "Test", capability = null, title = "t", startedAtMs = 0,
        done = done, paused = paused, hold = hold, policyOverride = override, nativeKind = nativeKind,
    )

    @Test
    fun `a running download is held when the policy says wait`() {
        val moves = DownloadGate.plan(listOf(entry("a")), facts, settings, offline)
        assertEquals(listOf<DownloadGate.Move>(DownloadGate.Move.Hold("a", "Waiting for a network")), moves)
    }

    @Test
    fun `a held download is released when the verdict turns to go and keeps its place while it is still held`() {
        val held = entry("a", paused = true, hold = "Waiting for a network")
        assertEquals(listOf<DownloadGate.Move>(DownloadGate.Move.Release("a")), DownloadGate.plan(listOf(held), facts, settings, wifi))
        assertTrue(DownloadGate.plan(listOf(held), facts, settings, offline).isEmpty())
        val mobile = Conditions(NetworkClass.METERED, 12 * 60, false)
        val big = { _: PluginJobsCenter.Entry -> JobFacts(900L * 1024 * 1024) }
        assertEquals(
            listOf<DownloadGate.Move>(DownloadGate.Move.Hold("a", "Waiting for Wi-Fi")),
            DownloadGate.plan(listOf(held), big, settings, mobile),
        )
    }

    @Test
    fun `a download the person paused or resumed themselves, a finished one and another kind of job are left alone`() {
        val entries = listOf(
            entry("paused", paused = true),
            entry("overridden", override = true),
            entry("done", done = true),
            entry("plugin", nativeKind = "something_else"),
        )
        assertTrue(DownloadGate.plan(entries, facts, settings, offline).isEmpty())
    }

    @Test
    fun `a second game download waits for the first, and an art download is not held by the games' pool`() {
        val first = entry("g1").copy(startedAtMs = 1)
        val second = entry("g2").copy(startedAtMs = 2)
        val art = entry("a1").copy(startedAtMs = 3)
        val category = { e: PluginJobsCenter.Entry -> if (e.jobId.startsWith("a")) dev.droidtop.net.DownloadCategory.ART else dev.droidtop.net.DownloadCategory.GAMES }
        assertEquals(
            listOf<DownloadGate.Move>(DownloadGate.Move.Hold("g2", "Queued: one game download at a time")),
            DownloadGate.plan(listOf(first, second, art), facts, settings, wifi, category),
        )
        val queued = second.copy(paused = true, hold = "Queued: one game download at a time")
        assertEquals(
            "the slot frees when the first finishes",
            listOf<DownloadGate.Move>(DownloadGate.Move.Release("g2")),
            DownloadGate.plan(listOf(first.copy(done = true), queued), facts, settings, wifi, category),
        )
        assertEquals(
            "while the policy holds, the policy's line wins",
            listOf<DownloadGate.Move>(DownloadGate.Move.Hold("g2", "Waiting for a network")),
            DownloadGate.plan(listOf(queued), facts, settings, offline, category),
        )
    }

    @Test
    fun `a new download is admitted, or queued when its pool is full of running ones`() {
        val running = listOf(entry("g1"))
        val games = mapOf("category" to "games", "bytes" to "1")
        val category = { _: PluginJobsCenter.Entry -> dev.droidtop.net.DownloadCategory.GAMES }
        assertEquals("Queued: one game download at a time", DownloadGate.admit(games, running, category, settings, wifi))
        assertEquals(null, DownloadGate.admit(mapOf("category" to "art"), running, category, settings, wifi))
        assertEquals("Waiting for a network", DownloadGate.admit(games, emptyList(), category, settings, offline))
        assertEquals(dev.droidtop.net.DownloadCategory.GAMES, DownloadGate.categoryOf(mapOf()))
    }

    @Test
    fun `size and automatic come from the job's arguments and default to large and asked for`() {
        assertEquals(JobFacts(123L, true), DownloadGate.factsOf(mapOf("bytes" to "123", "automatic" to "1")))
        assertEquals(JobFacts(0L, false), DownloadGate.factsOf(mapOf("bytes" to "junk")))
        assertEquals(JobFacts(0L, false), DownloadGate.factsOf(null))
    }

    @Test
    fun `a download the policy refuses starts paused with the reason and runs once released`() = runBlocking {
        val ran = CompletableDeferred<Unit>()
        PluginJobsCenter.registerNative("gate_test_start", download = true) { _, _, _ ->
            ran.complete(Unit)
            "ok"
        }
        PluginJobsCenter.admission = { _, _ -> "Waiting for Wi-Fi" }
        val id = PluginJobsCenter.startNative(null, "gate_test_start", "Held job", mapOf("bytes" to "1"))!!
        val held = PluginJobsCenter.find(id)!!
        assertTrue(held.paused)
        assertEquals("Waiting for Wi-Fi", held.hold)
        assertEquals("Waiting for Wi-Fi", held.statusLine)
        assertFalse(ran.isCompleted)

        PluginJobsCenter.admission = { _, _ -> null }
        assertTrue(PluginJobsCenter.releaseHold(id))
        withTimeout(5_000) { ran.await() }
        assertFalse(PluginJobsCenter.find(id)?.policyOverride ?: false)
    }

    @Test
    fun `the person resuming a held download overrides the policy for good`() = runBlocking {
        PluginJobsCenter.registerNative("gate_test_override", download = true) { _, _, _ -> "ok" }
        PluginJobsCenter.admission = { _, _ -> "Waiting for Wi-Fi" }
        val id = PluginJobsCenter.startNative(null, "gate_test_override", "Held job", mapOf("bytes" to "1"))!!
        assertTrue(PluginJobsCenter.resume(id))
        val entry = PluginJobsCenter.find(id)
        assertNull(entry?.hold)
        assertTrue(entry?.policyOverride ?: true)
    }
}
