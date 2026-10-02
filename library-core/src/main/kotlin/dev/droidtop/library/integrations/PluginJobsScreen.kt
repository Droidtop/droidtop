package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The one shared "what plugin jobs are running" screen (docs/SPEC.md 12a
 * "Jobs": "a shared jobs surface... e.g. a Jobs screen plus a progress
 * row in context, reused by acquire_content downloads so there's one
 * mechanism"). Reads [PluginJobsCenter], which every job -- a Get-games
 * download, an app_status action, a [dev.droidtop.pluginhost.PluginEvent]
 * reacting on its own -- is tracked through regardless of where it was
 * started, so this screen shows all of them, not just ones started from
 * itself.
 *
 * Registered once (in `:app`'s `AppSettingsCatalogs`) and reached from
 * the "Downloads and installs" entry of Settings and, in the Gaming
 * shell, from the left menu's place of the same name (docs/SPEC.md 7j
 * "Places") -- same shared-catalog registry every other cross-module
 * management screen uses, so every renderer gets it for free. It used to
 * also appear as "Jobs" under Accounts and sources (docs/SPEC.md 12a
 * "Jobs").
 */
object PluginJobsScreen {
    const val ID = "plugin_jobs"

    /**
     * [extraGroups] are rows the owner of some other queue adds below the jobs (`:app` adds the
     * store installs queue, which lives in gamenative's own screen), so this stays the one place
     * the list of "what is running" is reached. [headed] false is for a host that already
     * names the screen in its own header (the Quick Menu's panel), so the heading is not said twice.
     */
    fun screen(headed: Boolean = true, extraGroups: suspend (Context) -> List<CatalogGroup> = { emptyList() }): CatalogScreen = CatalogScreen(
        id = ID,
        title = "Downloads and installs",
        subtitle = "Plugin downloads, library scrapes and other long-running actions, wherever they were started from".takeIf { headed },
        groups = { context ->
            val snapshot = PluginJobsCenter.entries().value
            listOf(
                CatalogGroup(
                    id = "plugin_jobs_list",
                    title = null,
                    items = if (snapshot.isEmpty()) {
                        // A short title: the navigator's detail strip repeats any title longer than a row shows.
                        listOf(ActionItem(id = "plugin_jobs_none", title = "Nothing running", subtitle = "No jobs running or recently finished", run = {}))
                    } else {
                        snapshot.flatMap { entry -> jobItems(entry) }
                    },
                ),
            ) + extraGroups(context)
        },
    )

    /** The owner's name, the job, and for a job a provider runs for another plugin, who ran it (docs/plugin-api.md 2.4). */
    private fun jobTitle(entry: PluginJobsCenter.Entry): String =
        "${entry.pluginLabel}: ${entry.title}" + (entry.via?.let { " (via $it)" } ?: "")

    private fun jobItems(entry: PluginJobsCenter.Entry): List<CatalogItem> {
        if (entry.done) {
            val outcome = if (entry.result?.ok == true) entry.result?.values?.get("summary") ?: "Done" else (entry.result?.error ?: "Failed")
            return listOf(
                ActionItem(
                    id = "plugin_job_${entry.jobId}",
                    title = jobTitle(entry),
                    subtitle = outcome,
                    run = {},
                ),
            )
        }
        val progressRow = AsyncActionItem(
            id = "plugin_job_${entry.jobId}",
            title = jobTitle(entry),
            subtitle = if (entry.percent >= 0) "${entry.statusLine} (${entry.percent}%)" else entry.statusLine,
            run = { _, onStatus ->
                var finalEntry: PluginJobsCenter.Entry? = null
                coroutineScope {
                    // Explicitly labelled: collect's own lambda receiver
                    // (FlowCollector) shadows launch's CoroutineScope
                    // receiver, so an unqualified cancel() inside collect
                    // does not resolve to this coroutine's own cancel.
                    val collector = launch collectorJob@{
                        PluginJobsCenter.entries().collect { list ->
                            val current = list.firstOrNull { it.jobId == entry.jobId } ?: run {
                                this@collectorJob.cancel()
                                return@collect
                            }
                            onStatus(if (current.percent >= 0) "${current.statusLine} (${current.percent}%)" else current.statusLine)
                            if (current.done) {
                                finalEntry = current
                                this@collectorJob.cancel()
                            }
                        }
                    }
                    collector.join()
                }
                finalEntry?.let { if (it.result?.ok == true) it.result?.values?.get("summary") ?: "Done" else (it.result?.error ?: "Failed") }
                    ?: "This job is no longer tracked"
            },
        )
        val cancelRow = ActionItem(
            id = "plugin_job_${entry.jobId}_cancel",
            title = "Cancel \"${entry.title}\"",
            subtitle = when (entry.nativeKind) {
                null -> "Best-effort -- the plugin decides whether it actually stops"
                DownloadJobs.KIND -> "Stops the download and deletes what was downloaded so far"
                else -> "Stops at the next game"
            },
            confirmTitle = "Cancel this job?",
            run = { PluginJobsCenter.cancel(entry.jobId) },
        )
        val controls = buildList {
            add(progressRow)
            // A native job with no checkpoint yet pauses to a fresh start, so it may always be paused.
            if (entry.pausable && entry.resumable && (entry.paused || entry.resumePayload != null || entry.nativeKind != null)) {
                add(ActionItem(
                    id = "plugin_job_${entry.jobId}_${if (entry.paused) "resume" else "pause"}",
                    title = if (entry.paused) "Resume \"${entry.title}\"" else "Pause \"${entry.title}\"",
                    run = { if (entry.paused) PluginJobsCenter.resume(entry.jobId) else PluginJobsCenter.pause(entry.jobId) },
                ))
            }
            add(cancelRow)
        }
        return controls
    }
}
