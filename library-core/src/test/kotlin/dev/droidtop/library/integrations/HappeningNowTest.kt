package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryRescanJob
import dev.droidtop.library.scraper.LibraryScrapeJob
import dev.droidtop.library.settings.JobLook
import dev.droidtop.library.stores.StoreSaves
import dev.droidtop.pluginhost.PluginJobsCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The status area's one contextual spot: what is happening now, read from the jobs (Droidtop/tracker#742). */
class HappeningNowTest {

    private fun job(id: String, native: String?, percent: Int = -1, done: Boolean = false, paused: Boolean = false) = PluginJobsCenter.Entry(
        jobId = id, pluginId = "droidtop", pluginLabel = "droidtop", capability = null, title = id, startedAtMs = 0L,
        percent = percent, done = done, paused = paused, nativeKind = native,
    )

    @Test
    fun `nothing running is nothing to show`() {
        assertNull(HappeningNow.of(emptyList()))
        assertNull(HappeningNow.of(listOf(job("a", LibraryScrapeJob.KIND, done = true), job("b", LibraryRescanJob.KIND, paused = true))))
    }

    @Test
    fun `each job is the kind of thing it does`() {
        PluginJobsCenter.registerNative("happening_now_download", download = true) { _, _, _ -> "ok" }
        assertEquals(HappeningNow.Kind.DOWNLOAD, HappeningNow.kindOf(job("a", "happening_now_download")))
        assertEquals(HappeningNow.Kind.SCAN, HappeningNow.kindOf(job("b", LibraryRescanJob.KIND)))
        assertEquals(HappeningNow.Kind.SCRAPE, HappeningNow.kindOf(job("c", LibraryScrapeJob.KIND)))
        assertEquals(HappeningNow.Kind.SYNC, HappeningNow.kindOf(job("d", StoreSaves.JOB_KIND)))
        assertEquals(HappeningNow.Kind.UPDATE_CHECK, HappeningNow.kindOf(job("e", HappeningNow.UPDATE_CHECK)))
        assertEquals(HappeningNow.Kind.PLUGIN, HappeningNow.kindOf(job("f", null)))
    }

    @Test
    fun `the most important running thing leads, with how many run in all`() {
        PluginJobsCenter.registerNative("happening_now_download", download = true) { _, _, _ -> "ok" }
        val now = HappeningNow.of(
            listOf(job("scrape", LibraryScrapeJob.KIND), job("dl", "happening_now_download", percent = 40), job("plugin", null)),
        )!!
        assertEquals(HappeningNow.Kind.DOWNLOAD, now.kind)
        assertEquals(0.4f, now.fraction, 0.001f)
        assertEquals(JobLook.ACTIVE, now.look)
        assertEquals(3, now.running)
        assertEquals("Downloading, 3 things running", now.words)
    }

    @Test
    fun `a job that does not know how far it is turns rather than fills`() {
        val now = HappeningNow.of(listOf(job("check", HappeningNow.UPDATE_CHECK)))!!
        assertEquals(-1f, now.fraction, 0f)
        assertEquals(JobLook.CHECKING, now.look)
        assertEquals("Checking for updates", now.words)
    }
}
