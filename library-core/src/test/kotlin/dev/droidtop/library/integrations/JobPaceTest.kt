package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.JobNumbers
import dev.droidtop.pluginhost.PluginJobsCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A job's smoothed pace and its calm time left (docs/SPEC.md 7j "Places", Droidtop/tracker#643). */
class JobPaceTest {

    private fun job(id: String, percent: Int = -1, paused: Boolean = false, numbers: JobNumbers? = null) =
        PluginJobsCenter.Entry(
            jobId = id,
            pluginId = "droidtop",
            pluginLabel = "GOG",
            capability = null,
            title = "A game",
            startedAtMs = 0L,
            percent = percent,
            paused = paused,
            numbers = numbers,
        )

    private fun bytes(done: Long, bps: Long = -1) = JobNumbers(bytesDone = done, bytesTotal = 600_000_000, downloadBps = bps)

    @Test
    fun `the average follows a sample by how late it came`() {
        assertEquals(10.0, JobPace.smooth(null, 10.0, 1_000L), 0.0)
        // A sample a moment after the last barely moves it; one a time constant later moves it about two thirds.
        assertTrue(JobPace.smooth(0.0, 10.0, 100L) < 0.3)
        assertEquals(6.32, JobPace.smooth(0.0, 10.0, JobPace.TAU_MS.toLong()), 0.01)
    }

    @Test
    fun `time left is said in coarse honest words`() {
        assertEquals("Under a minute left", JobPace.words(30_000L))
        assertEquals("About 1 min left", JobPace.words(50_000L))
        assertEquals("About 5 min left", JobPace.words(300_000L))
        assertEquals("About 2 h left", JobPace.words(100 * 60_000L))
        assertNull(JobPace.remainingMs(left = 10, ratePerMs = 0.0))
    }

    @Test
    fun `a said time stands for a while before it may change`() {
        assertEquals("About 4 min left", JobPace.steady("About 4 min left", saidAtMs = 0L, fresh = "About 3 min left", nowMs = 2_000L))
        assertEquals("About 3 min left", JobPace.steady("About 4 min left", saidAtMs = 0L, fresh = "About 3 min left", nowMs = JobPace.HOLD_MS))
        assertEquals("About 3 min left", JobPace.steady(null, saidAtMs = 0L, fresh = "About 3 min left", nowMs = 0L))
    }

    @Test
    fun `nothing is said until the job has moved for a while, and bytes set the pace`() {
        val id = "pace-bytes"
        assertNull(JobPace.line(job(id, numbers = bytes(0)), nowMs = 0L))
        assertNull(JobPace.line(job(id, numbers = bytes(1_000_000)), nowMs = 1_000L))
        // 1 MB a second, 594 MB to go once 6 MB are in: a little under ten minutes.
        JobPace.line(job(id, numbers = bytes(3_000_000)), nowMs = 3_000L)
        assertEquals("About 10 min left", JobPace.line(job(id, numbers = bytes(6_000_000)), nowMs = 6_000L))
    }

    @Test
    fun `a drawing between reports is no sample, and the estimate starts again after a pause`() {
        val id = "pace-percent"
        assertNull(JobPace.line(job(id, 10), nowMs = 0L))
        assertEquals("About 8 min left", JobPace.line(job(id, 20), nowMs = 60_000L))
        // Drawn again with nothing new: the rate is not dragged down by the wait.
        assertEquals("About 8 min left", JobPace.line(job(id, 20), nowMs = 61_000L))
        assertNull(JobPace.line(job(id, 50, paused = true), nowMs = 62_000L))
        assertNull(JobPace.line(job(id, 55), nowMs = 63_000L))
    }

    @Test
    fun `the speed shown is smoothed`() {
        val id = "pace-speed"
        JobPace.line(job(id, numbers = bytes(0, bps = 4_000_000)), nowMs = 0L)
        JobPace.line(job(id, numbers = bytes(1_000_000, bps = 12_000_000)), nowMs = 500L)
        val shown = JobPace.speed(job(id, numbers = bytes(1_000_000, bps = 12_000_000)), nowMs = 500L)!!
        // One spike half a second later lifts it only a little above where it was.
        assertTrue(shown in 4_000_001L until 6_000_000L)
    }
}
