package dev.droidtop.stores.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Steam bar only goes forward though the downloader's events arrive out of order (console, 0.2.0-dev.2127). */
class DepotProgressTest {
    @Test
    fun `a late lower report neither moves the bar back nor counts bytes twice`() {
        val progress = DepotProgress()
        val first = progress.record(depot = 7, fraction = 0.5f, bytesSoFar = 500)
        assertEquals(500L, first.newBytes)
        val late = progress.record(depot = 7, fraction = 0.3f, bytesSoFar = 300)
        assertEquals(0.5f, late.fraction)
        assertFalse(late.moved)
        assertEquals(0L, late.newBytes)
        val next = progress.record(depot = 7, fraction = 0.6f, bytesSoFar = 600)
        assertTrue(next.moved)
        assertEquals(100L, next.newBytes)
    }

    @Test
    fun `depots are kept apart and a fraction never passes the end`() {
        val progress = DepotProgress()
        progress.record(1, 0.9f, 900)
        val other = progress.record(2, 0.1f, 10)
        assertEquals(0.1f, other.fraction)
        assertEquals(1f, progress.record(1, 1.4f, 1000).fraction)
    }

    @Test
    fun `a status line names its phase, not its file`() {
        assertEquals("Allocating files", DepotProgress.phaseOf("Allocating file: bin/game.exe"))
        assertEquals("Validating", DepotProgress.phaseOf("Validating: data/a.pak"))
        assertEquals("Downloading manifest", DepotProgress.phaseOf("Downloading manifest"))
    }
}

/** A failure the downloader reports ends the wait for the download, so the job fails instead of staying where it was. */
class SteamDownloadListenerTest {
    @Test
    fun `a reported failure completes the failure the download waits on`() {
        val info = dev.droidtop.stores.data.DownloadInfo(jobCount = 1, gameId = 1, downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList())
        val listener = SteamDownload.Listener(info, mapOf(10 to 0))
        val cause = IllegalStateException("manifest 123 could not be downloaded")
        listener.onDownloadFailed(
            `in`.dragonbra.javasteam.depotdownloader.data.AppItem(1, installDirectory = "/nowhere", depot = listOf(10), branch = "public", branchPassword = null),
            cause,
        )
        assertTrue(listener.failed.isCompleted)
        assertEquals(cause, listener.failure)
    }

    @Test
    fun `progress reaches the job only when a depot moves forward`() {
        val info = dev.droidtop.stores.data.DownloadInfo(jobCount = 1, gameId = 1, downloadingAppIds = java.util.concurrent.CopyOnWriteArrayList())
        val seen = mutableListOf<Float>()
        info.addProgressListener { seen += it }
        val listener = SteamDownload.Listener(info, mapOf(10 to 0))
        listener.onChunkCompleted(10, 0.4f, 400, 800)
        listener.onChunkCompleted(10, 0.2f, 200, 400)
        listener.onChunkCompleted(10, 0.6f, 600, 1200)
        assertEquals(listOf(0.4f, 0.6f), seen)
    }
}
