package dev.droidtop.library.integrations

import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.CatalogScreen
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
 * Registered once (see `SCREEN_JOBS` in `:app`'s `AppSettingsCatalogs`)
 * and reached from Settings > App integrations, next to Plugins -- same
 * shared-catalog registry every other cross-module management screen
 * uses, so both the Gaming and Standard settings renderers get it for
 * free.
 */
object PluginJobsScreen {
    const val ID = "plugin_jobs"

    fun screen(): CatalogScreen = CatalogScreen(
        id = ID,
        title = "Jobs",
        subtitle = "Plugin downloads and long-running actions, wherever they were started from",
        groups = { _ ->
            val snapshot = PluginJobsCenter.entries().value
            listOf(
                CatalogGroup(
                    id = "plugin_jobs_list",
                    title = null,
                    items = if (snapshot.isEmpty()) {
                        listOf(ActionItem(id = "plugin_jobs_none", title = "No jobs running or recently finished", run = {}))
                    } else {
                        snapshot.flatMap { entry -> jobItems(entry) }
                    },
                ),
            )
        },
    )

    private fun jobItems(entry: PluginJobsCenter.Entry): List<CatalogItem> {
        if (entry.done) {
            val outcome = if (entry.result?.ok == true) "Done" else (entry.result?.error ?: "Failed")
            return listOf(
                ActionItem(
                    id = "plugin_job_${entry.jobId}",
                    title = "${entry.pluginLabel}: ${entry.title}",
                    subtitle = outcome,
                    run = {},
                ),
            )
        }
        val progressRow = AsyncActionItem(
            id = "plugin_job_${entry.jobId}",
            title = "${entry.pluginLabel}: ${entry.title}",
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
                finalEntry?.let { if (it.result?.ok == true) "Done" else (it.result?.error ?: "Failed") }
                    ?: "This job is no longer tracked"
            },
        )
        val cancelRow = ActionItem(
            id = "plugin_job_${entry.jobId}_cancel",
            title = "Cancel \"${entry.title}\"",
            subtitle = "Best-effort -- the plugin decides whether it actually stops",
            confirmTitle = "Cancel this job?",
            run = { PluginJobsCenter.cancel(entry.jobId) },
        )
        return listOf(progressRow, cancelRow)
    }
}
