package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolvedCheckpointTest {
    private val download = mapOf("url" to "https://archive.org/download/doom2/Doom2.zip", "name" to "1731400000000-Doom2.zip", "post" to "place_in_folder")

    @Test
    fun `a resolved job keeps its download and that download's own checkpoint`() {
        val (back, inner) = DownloadJobs.ResolvedCheckpoint.decode(DownloadJobs.ResolvedCheckpoint.encode(download, "fetched"))
        assertEquals(download, back)
        assertEquals("fetched", inner)
    }

    @Test
    fun `before the download has its own checkpoint there is none inside`() {
        val (back, inner) = DownloadJobs.ResolvedCheckpoint.decode(DownloadJobs.ResolvedCheckpoint.encode(download, null))
        assertEquals(download, back)
        assertNull(inner)
    }

    @Test
    fun `any other job's checkpoint passes through unchanged`() {
        assertEquals(null to "fetched", DownloadJobs.ResolvedCheckpoint.decode("fetched"))
        assertEquals(null to null, DownloadJobs.ResolvedCheckpoint.decode(null))
        assertEquals(null to "{\"other\":1}", DownloadJobs.ResolvedCheckpoint.decode("{\"other\":1}"))
    }
}
