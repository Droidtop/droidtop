package dev.droidtop.app.update

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the self-update reads Android's DownloadManager (Droidtop/tracker#445). */
class UpdateDownloadTest {
    @Test
    fun `queued, running and paused downloads are still going`() {
        assertEquals(UpdateDownload.State.ACTIVE, UpdateDownload.stateOf(DownloadManager.STATUS_PENDING))
        assertEquals(UpdateDownload.State.ACTIVE, UpdateDownload.stateOf(DownloadManager.STATUS_RUNNING))
        assertEquals(UpdateDownload.State.ACTIVE, UpdateDownload.stateOf(DownloadManager.STATUS_PAUSED))
        assertEquals(UpdateDownload.State.SUCCESSFUL, UpdateDownload.stateOf(DownloadManager.STATUS_SUCCESSFUL))
        assertEquals(UpdateDownload.State.FAILED, UpdateDownload.stateOf(DownloadManager.STATUS_FAILED))
        assertEquals(UpdateDownload.State.GONE, UpdateDownload.stateOf(0))
    }

    @Test
    fun `the watching pass says how far the download is`() {
        val mb = 1024L * 1024
        assertEquals(
            "Downloading 0.2.0-dev.1748: 45 of 123 MB",
            UpdateDownload.progressLine(
                "0.2.0-dev.1748",
                UpdateDownload.Progress(UpdateDownload.State.ACTIVE, done = 45 * mb + 7, total = 123 * mb + 1),
            ),
        )
        assertEquals(
            "Downloading 0.2.0-dev.1748...",
            UpdateDownload.progressLine("0.2.0-dev.1748", UpdateDownload.Progress(UpdateDownload.State.ACTIVE)),
        )
        assertEquals(
            "Downloading 0.2.0-dev.1748: paused, waiting for a network or to retry (Android's download manager goes on by itself)",
            UpdateDownload.progressLine(
                "0.2.0-dev.1748",
                UpdateDownload.Progress(UpdateDownload.State.ACTIVE, paused = true, done = 5, total = 10),
            ),
        )
    }

    @Test
    fun `the update's file is the one the cleanup rules know`() {
        assertEquals(1748L, UpdateFiles.buildOf(UpdateDownload.fileName(1748)))
    }
}
