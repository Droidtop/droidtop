package dev.droidtop.library.integrations

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.pluginhost.PluginJobsCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Downloads place's sections, rows and time-left estimate (docs/SPEC.md 7j "Places"). */
class PluginJobsScreenTest {

    private fun job(id: String, percent: Int = -1, paused: Boolean = false, done: Boolean = false, status: String = "Downloading… 12 MB of 300 MB") =
        PluginJobsCenter.Entry(
            jobId = id,
            pluginId = "droidtop",
            pluginLabel = "GOG",
            capability = null,
            title = "A game",
            startedAtMs = 0L,
            percent = percent,
            statusLine = status,
            paused = paused,
            done = done,
        )

    @Test
    fun `jobs fall into Current, Paused and Completed, each headed with its count, empty ones absent`() {
        val groups = PluginJobsScreen.groupsFor(listOf(job("a", 10), job("b", 20), job("c", 5, paused = true)), nowMs = 0L)
        assertEquals(listOf("Current (2)", "Paused (1)"), groups.map { it.title })
    }

    @Test
    fun `nothing running is one quiet row`() {
        val groups = PluginJobsScreen.groupsFor(emptyList(), nowMs = 0L)
        assertEquals(listOf("plugin_jobs_none"), groups.single().items.map { it.id })
    }

    @Test
    fun `a running job is one row with its progress, and a finished one says how it ended`() {
        val running = PluginJobsScreen.jobRow(job("a", 42), nowMs = 0L)
        assertTrue(running is NestedScreenItem)
        assertEquals(0.42f, running.progress!!, 0.001f)
        val unknown = PluginJobsScreen.jobRow(job("u"), nowMs = 0L)
        assertTrue(unknown.progress!! < 0f)
        val done = PluginJobsScreen.jobRow(job("d", 100, done = true), nowMs = 0L)
        assertTrue(done is ActionItem)
        assertNull(done.progress)
        assertEquals("Failed", done.value)
    }

    @Test
    fun `the value says how far, how long is left and the size line`() {
        assertEquals("42% · About 3 min left\nDownloading… 12 MB of 300 MB", PluginJobsScreen.progressLine(job("a", 42), "About 3 min left"))
        assertEquals("Paused\nDownloading… 12 MB of 300 MB", PluginJobsScreen.progressLine(job("a", 42, paused = true), null))
        assertEquals("Waiting for Wi-Fi", PluginJobsScreen.progressLine(job("a", status = "Waiting for Wi-Fi"), null))
    }

    @Test
    fun `time left comes from the rate seen, and only once the job has moved`() {
        assertNull(JobEta.remainingMs(startMs = 0L, startPercent = 10, nowMs = 60_000L, percent = 10))
        assertNull(JobEta.remainingMs(startMs = 0L, startPercent = 10, nowMs = 2_000L, percent = 20))
        // 10 percent a minute, 80 percent to go: eight minutes.
        assertEquals(480_000L, JobEta.remainingMs(startMs = 0L, startPercent = 10, nowMs = 60_000L, percent = 20))
    }

    @Test
    fun `time left is said in the coarsest honest words`() {
        assertEquals("Under a minute left", JobEta.words(30_000L))
        assertEquals("About 5 min left", JobEta.words(300_000L))
        assertEquals("About 2 h left", JobEta.words(100 * 60_000L))
    }

    @Test
    fun `the estimate starts again after a pause`() {
        val id = "eta"
        assertNull(JobEta.line(job(id, 10), nowMs = 0L))
        assertEquals("About 8 min left", JobEta.line(job(id, 20), nowMs = 60_000L))
        assertNull(JobEta.line(job(id, 50, paused = true), nowMs = 61_000L))
        assertNull(JobEta.line(job(id, 55), nowMs = 62_000L))
    }
}
