package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a download ending says, and which endings are news (docs/SPEC.md "Motion and visual status", Droidtop/tracker#643). */
class JobNoticesTest {

    private fun ended(id: String, atMs: Long, failed: Boolean = false) = PluginJobsCenter.Entry(
        jobId = id, pluginId = "droidtop", pluginLabel = "GOG", capability = null, title = "Hades", startedAtMs = 0L,
        done = true, finishedAtMs = atMs, result = if (failed) PluginResult.failure("Steam refused") else null,
    )

    @Test
    fun `only endings since the start that were not yet said are news`() {
        val list = listOf(ended("old", atMs = 10L), ended("said", atMs = 200L), ended("new", atMs = 300L))
        assertEquals(listOf("new"), JobNotices.newlyEnded(known = setOf("said"), finished = list, sinceMs = 100L).map { it.jobId })
    }

    @Test
    fun `a flash says the game and how it ended, never the error`() {
        assertEquals("Hades is ready", JobNotices.line(ended("a", 1L)))
        assertEquals("Hades did not download", JobNotices.line(ended("b", 1L, failed = true)))
        assertEquals("3 downloads finished", JobNotices.groupLine(failed = false, count = 3))
        assertEquals("2 downloads did not finish", JobNotices.groupLine(failed = true, count = 2))
    }
}
