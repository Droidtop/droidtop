package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A download draws a bar, not a spinner, once anything says how big it is (Droidtop/tracker#877): the engine's length,
 * else the size the caller was told, and a row reads either its percent or its numbers.
 */
class DownloadProgressTest {
    @Test fun `the engine's own percent wins`() {
        assertEquals(42, DownloadJobs.percentOf(42, 10L, 1_000L))
    }

    @Test fun `with no length from the server, the announced size gives the percent, held under 100`() {
        assertEquals(25, DownloadJobs.percentOf(-1, 250L, 1_000L))
        assertEquals(99, DownloadJobs.percentOf(-1, 1_000L, 1_000L))
        assertEquals(99, DownloadJobs.percentOf(-1, 1_500L, 1_000L))
    }

    @Test fun `with neither, the percent stays unknown`() {
        assertEquals(-1, DownloadJobs.percentOf(-1, 250L, 0L))
        assertEquals(-1, DownloadJobs.percentOf(-1, null, 1_000L))
    }

    @Test fun `the numbers' total is the engine's, else the announced size`() {
        assertEquals(500L, DownloadJobs.totalOf(500L, 1_000L))
        assertEquals(1_000L, DownloadJobs.totalOf(0L, 1_000L))
        assertEquals(-1L, DownloadJobs.totalOf(0L, 0L))
    }

    @Test fun `the announced size is the policy's for one file, else the file's declared cap`() {
        assertEquals(700L, DownloadJobs.expectedOf(mapOf(DownloadGate.ARG_BYTES to "700"), 0L))
        assertEquals(300L, DownloadJobs.expectedOf(mapOf(DownloadGate.ARG_BYTES to "700", "more" to "[]"), 300L))
        assertEquals(300L, DownloadJobs.expectedOf(emptyMap(), 300L))
        assertEquals(0L, DownloadJobs.expectedOf(emptyMap(), 0L))
    }

    @Test fun `a row reads its percent, else its bytes, else its items`() {
        assertEquals(40, jobPercent(40, JobNumbers(bytesDone = 10, bytesTotal = 100)))
        assertEquals(10, jobPercent(-1, JobNumbers(bytesDone = 10, bytesTotal = 100)))
        assertEquals(50, jobPercent(-1, JobNumbers(bytesDone = 10, itemsDone = 2, itemsTotal = 4)))
        assertEquals(-1, jobPercent(-1, JobNumbers(bytesDone = 10)))
        assertEquals(-1, jobPercent(-1, null))
        assertEquals(100, jobPercent(130, null))
    }
}
