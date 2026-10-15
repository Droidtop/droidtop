package dev.droidtop.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** The self-update's download is droidtop's own job (Droidtop/tracker#226): its file is the one [UpdateFiles] sweeps. */
class UpdateDownloadTest {
    @Test
    fun `the downloaded file is named for the sweeper to find`() {
        assertEquals(1748L, UpdateFiles.buildOf(UpdateDownload.fileName(1748)))
    }

    @Test
    fun `the post step has its own name`() {
        assertEquals("droidtop.self_update", UpdateDownload.POST)
    }
}
