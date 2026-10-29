package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LibraryRescan
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * "Rescan library" and "Rescan PC game folders" as a job in the one jobs center (docs/SPEC.md "Jobs and their
 * results", Droidtop/tracker#595): the walk shows under Downloads and installs and on the Home strip with Cancel, and
 * its progress stays when the menu that started it closes (a rescan of a slow card takes minutes). One rescan at a
 * time: starting one while another runs follows the running one. The walk is [LibraryRescan.walk], the handlers :app
 * registers.
 */
object LibraryRescanJob {
    const val KIND = "library_rescan"
    private const val ARG_SCOPE = "scope"
    private const val SCOPE_PC = "pc"
    private const val SCOPE_ALL = "all"

    /** Once, at process start, after :app has set [LibraryRescan.handler] and [LibraryRescan.pcFoldersHandler]. */
    fun register(context: Context) {
        val app = context.applicationContext
        // A rescan a restart interrupted walks again: there is nothing to resume from, and a paused rescan would wait.
        PluginJobsCenter.registerNative(KIND, reattachOnRestart = true) { args, _, report ->
            report(-1, "Starting", null)
            LibraryRescan.walk(app, pcOnly = args[ARG_SCOPE] == SCOPE_PC) { line -> report(-1, line, null) }
        }
        LibraryRescan.asJob = { ctx, pcOnly, onStatus -> runAsJob(ctx, pcOnly, onStatus) }
        LibraryRescan.cancelJob = {
            running().onEach { PluginJobsCenter.cancel(it.jobId) }.isNotEmpty()
        }
        LibraryRescan.jobRunning = { running().isNotEmpty() }
    }

    private fun running() = PluginJobsCenter.entries().value.filter { it.nativeKind == KIND && !it.done }

    /** Title of the job for [pcOnly]: the same words as the rows that start it. Pure. */
    internal fun title(pcOnly: Boolean): String = if (pcOnly) "Rescan PC game folders" else "Rescan library"

    /**
     * Starts the rescan job, or follows the one already running, telling [onStatus] its line until it ends; returns
     * how it ended. A job cancelled from Downloads ends with "Rescan cancelled.".
     */
    private suspend fun runAsJob(context: Context, pcOnly: Boolean, onStatus: (String) -> Unit): String =
        try {
            runJob(context, pcOnly, onStatus)
        } finally {
            // The scan's lines are on disk when it is over, whoever reads the file next.
            withContext(NonCancellable + Dispatchers.IO) { ScanLog.flush() }
        }

    private suspend fun runJob(context: Context, pcOnly: Boolean, onStatus: (String) -> Unit): String {
        val jobId = running().firstOrNull()?.jobId
            // The jobs center is attached at start (SettingsCatalogInitProvider), so no context is passed to attach it again.
            ?: PluginJobsCenter.startNative(
                null,
                KIND,
                title(pcOnly),
                mapOf(ARG_SCOPE to if (pcOnly) SCOPE_PC else SCOPE_ALL),
                owner = "Library",
                pausable = false,
            )
            ?: return LibraryRescan.walk(context, pcOnly, onStatus)
        return coroutineScope {
            val follower = launch {
                PluginJobsCenter.entries().map { list -> list.firstOrNull { it.jobId == jobId }?.statusLine }
                    .distinctUntilChanged()
                    .collect { line -> line?.let(onStatus) }
            }
            val end = PluginJobsCenter.entries().first { list -> list.none { it.jobId == jobId && !it.done } }
                .firstOrNull { it.jobId == jobId }
            follower.cancel()
            when {
                end == null -> "Rescan cancelled."
                end.result?.ok == false -> end.result?.error ?: "The rescan failed."
                else -> end.result?.values?.get("summary") ?: "Rescan finished."
            }
        }
    }
}
