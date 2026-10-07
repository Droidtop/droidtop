package dev.droidtop.stores.util

import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.stores.data.DownloadInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A fresh [DownloadInfo] for one store download: the progress object every
 * lifted download manager reports into and checks for cancellation.
 */
internal fun newDownloadInfo(): DownloadInfo = DownloadInfo(jobCount = 1, gameId = 0, downloadingAppIds = CopyOnWriteArrayList())

/**
 * Runs a store's download [block] as part of the install job that called
 * it: [info]'s progress goes to [progress] (the Downloads place's row), and
 * the job being paused or cancelled marks [info] inactive at once, which is
 * how the lifted download managers stop between files (they check
 * `isActive()`, not the coroutine). What they already wrote stays on disk
 * for the next run to continue from.
 */
internal suspend fun <T> runJobDownload(info: DownloadInfo, progress: StoreProgress, block: suspend () -> T): T {
    val listener: (Float) -> Unit = { fraction ->
        progress.report(fraction, info.getCurrentStatusMessage().ifBlank { "Downloading" })
    }
    info.addProgressListener(listener)
    val finished = AtomicBoolean(false)
    try {
        return coroutineScope {
            // Wakes only when the job is paused or cancelled; never left running.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) info.cancel("Stopped")
                }
            }
            try {
                block()
            } finally {
                finished.set(true)
                watcher.cancel()
            }
        }
    } finally {
        info.removeProgressListener(listener)
    }
}
