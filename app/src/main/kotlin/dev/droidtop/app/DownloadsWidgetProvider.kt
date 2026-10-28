package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Downloads widget (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): the downloads running or
 * paused, each with its line and a bar for its progress, then how many games have an update. A download row opens Downloads, the updates row
 * opens Updates. Drawn again when the jobs change, at most every [THROTTLE_MS], while one is placed ([watch]).
 */
class DownloadsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, DownloadsWidgetProvider::class.java, ids, ::render)
    }

    companion object {
        private const val THROTTLE_MS = 2_000L
        private const val DOWNLOADS_SCREEN = "plugin_jobs"
        private const val UPDATES_SCREEN = "updates"

        @Volatile private var watching = false

        /**
         * Follows the jobs for the life of the process (from :app's start), drawing the placed Downloads widgets again
         * when what they show changes. With none placed it only compares the summary; nothing is drawn.
         */
        @OptIn(FlowPreview::class)
        fun watch(context: Context) {
            if (watching) return
            watching = true
            val app = context.applicationContext
            DroidtopWidgets.scope.launch {
                PluginJobsCenter.entries()
                    .map { jobs -> downloads(jobs).map { it.jobId to it.percent / 5 } + jobs.count { it.paused } }
                    .distinctUntilChanged()
                    .debounce(THROTTLE_MS)
                    .collect {
                        val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, DownloadsWidgetProvider::class.java))
                        if (ids.isNotEmpty()) DroidtopWidgets.update(app, DownloadsWidgetProvider::class.java, ids, ::render)
                    }
            }
        }

        private fun downloads(jobs: List<PluginJobsCenter.Entry>) = jobs.filter { !it.done && PluginJobsCenter.isDownloadKind(it.nativeKind) }

        private suspend fun render(context: Context, widgetId: Int): RemoteViews {
            val open = DroidtopWidgets.activity(context, widgetId, "downloads", CatalogScreenLink.intent(context, DOWNLOADS_SCREEN))
            // A job's line is the one every job row says (time left, Paused, Waiting for the connection), and its progress
            // is the bar under it, never a percent in the text (Droidtop/tracker#643: no flickering numbers).
            val rows = downloads(PluginJobsCenter.entries().value).map { job ->
                DroidtopWidgets.Row(
                    "${job.title} · ${dev.droidtop.library.integrations.PluginJobsScreen.oneLine(job)}",
                    open,
                    progress = if (job.paused) job.percent.coerceAtLeast(0) else job.percent,
                )
            }
            val updates = dev.droidtop.library.GameUpdates.waiting(DroidtopWidgets.libraryGames(context)).size
            val updateRow = updates.takeIf { it > 0 }?.let { n ->
                DroidtopWidgets.Row(
                    if (n == 1) "1 game update" else "$n game updates",
                    DroidtopWidgets.activity(context, widgetId, "updates", CatalogScreenLink.intent(context, UPDATES_SCREEN)),
                )
            }
            // The updates row is always shown when there is one, so it takes the last of the four places.
            val shown = rows.take(DroidtopWidgets.MAX_ROWS - if (updateRow != null) 1 else 0) + listOfNotNull(updateRow)
            return DroidtopWidgets.rows(
                context,
                DroidtopWidgets.look(context),
                context.getString(R.string.widget_downloads_title),
                shown,
                context.getString(R.string.widget_downloads_empty),
                emptyTap = open,
            )
        }
    }
}
