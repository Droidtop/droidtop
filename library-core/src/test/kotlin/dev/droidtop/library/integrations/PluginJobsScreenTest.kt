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
    fun `jobs fall into Current and Paused, each headed with its count, empty ones absent`() {
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
        val failed = job("d", 100, done = true).copy(result = dev.droidtop.pluginhost.PluginResult.failure("Steam refused"))
        val done = PluginJobsScreen.jobRow(failed, nowMs = 0L)
        // A finished job opens what it did: its page, with Open, Retry and Clear.
        assertTrue(done is NestedScreenItem)
        assertNull(done.progress)
        assertTrue(done.subtitle!!.endsWith("Steam refused"))
    }

    @Test
    fun `jobs fall into Current, Waiting, Paused and Finished, with Clear finished last`() {
        val held = job("h", 10, paused = true).copy(hold = "Waiting for Wi-Fi")
        val finished = job("f", 100, done = true).copy(finishedAtMs = 5L)
        val groups = PluginJobsScreen.groupsFor(listOf(job("a", 10), held, job("p", 5, paused = true), finished), nowMs = 0L)
        assertEquals(listOf("Current (1)", "Waiting (1)", "Paused (1)", "Finished (1)"), groups.map { it.title })
        assertEquals("plugin_jobs_clear_finished", groups.last().items.last().id)
    }

    @Test
    fun `a job's numbers say how far it has got`() {
        val bytes = dev.droidtop.pluginhost.JobNumbers(bytesDone = 120_000_000, bytesTotal = 300_000_000, downloadBps = 4_000_000)
        assertEquals("120 MB of 300 MB, 4 MB/s", PluginJobsScreen.amountLine(bytes))
        assertEquals("12 of 40", PluginJobsScreen.amountLine(dev.droidtop.pluginhost.JobNumbers(itemsDone = 12, itemsTotal = 40)))
        assertNull(PluginJobsScreen.amountLine(dev.droidtop.pluginhost.JobNumbers()))
        assertNull(PluginJobsScreen.amountLine(null))
        // The numbers come before the job's own line, which stays for what they cannot say.
        val running = job("a", 40, status = "Checking files").copy(numbers = bytes)
        assertEquals("40%\n120 MB of 300 MB, 4 MB/s\nChecking files", PluginJobsScreen.progressLine(running, null))
    }

    @Test
    fun `a report is counted failures first`() {
        val report = dev.droidtop.pluginhost.JobReport(
            listOf(
                dev.droidtop.pluginhost.ReportItem("A", dev.droidtop.pluginhost.Outcome.DONE),
                dev.droidtop.pluginhost.ReportItem("B", dev.droidtop.pluginhost.Outcome.DONE),
                dev.droidtop.pluginhost.ReportItem("C", dev.droidtop.pluginhost.Outcome.SKIPPED, "already has a cover"),
                dev.droidtop.pluginhost.ReportItem("D", dev.droidtop.pluginhost.Outcome.FAILED, "no match"),
            ),
        )
        assertEquals("1 failed, 2 done, 1 skipped", PluginJobsScreen.reportCounts(report))
        assertNull(PluginJobsScreen.reportCounts(dev.droidtop.pluginhost.JobReport()))
    }

    @Test
    fun `the value says how far, how long is left and the size line`() {
        assertEquals("42% · About 3 min left\nDownloading… 12 MB of 300 MB", PluginJobsScreen.progressLine(job("a", 42), "About 3 min left"))
        assertEquals("Paused\nDownloading… 12 MB of 300 MB", PluginJobsScreen.progressLine(job("a", 42, paused = true), null))
        assertEquals("Waiting for Wi-Fi", PluginJobsScreen.progressLine(job("a", status = "Waiting for Wi-Fi"), null))
        val held = job("h", 42, paused = true, status = "Waiting for Wi-Fi").copy(hold = "Waiting for Wi-Fi")
        // A status that repeats the first line is said once (tracker#85: "Paused Paused").
        assertEquals("Waiting for Wi-Fi", PluginJobsScreen.progressLine(held, null))
        assertEquals("Paused", PluginJobsScreen.progressLine(job("p", paused = true, status = "Paused"), null))
    }

    @Test
    fun `a row leads with the job's own name, and who runs it is the second line`() {
        val running = PluginJobsScreen.jobRow(job("a", 42).copy(title = "Love and Corruption"), nowMs = 0L)
        assertEquals("Love and Corruption", running.title)
        assertEquals("GOG", running.subtitle)
        val done = PluginJobsScreen.jobRow(job("d", 100, done = true).copy(title = "Theme pack"), nowMs = 0L)
        assertEquals("Theme pack", done.title)
        // A finished job with no error is done; only an error makes it a failure.
        assertEquals("GOG · Done", done.subtitle)
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
