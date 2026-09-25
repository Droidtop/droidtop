package dev.droidtop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Continue playing" home-screen widget: a Nova/Apex-class launcher can
 * host any app's widget, but none of them can offer one of droidtop's own
 * games, because they have no library to draw from. This is droidtop's
 * answer -- the same "tap the game I was playing yesterday" idea as the
 * companion's rail (docs/SPEC.md, "Continue-playing rail"), placed as an
 * ordinary home-screen widget so it works whether or not a companion
 * screen exists, and whether or not Gaming is even on (docs/SPEC.md 2c:
 * the library and its launch resolution are never mode-gated).
 *
 * Data comes from the same place every other surface reads it:
 * [dev.droidtop.library.Library.backgroundScanState], the in-RAM index
 * (docs/SPEC.md 7g performance rule: no per-game disk lookups where a
 * list is drawn). A tap dispatches through [GameLaunchActivity.intentFor]
 * -- the same launch-screen-memory-and-play-history path as a pinned
 * game icon -- never a second launch mechanism.
 *
 * Rows are rendered directly onto the widget's [RemoteViews] (no
 * RemoteViewsService/collection widget) because the row count is small
 * and fixed ([MAX_ROWS]); that keeps this a plain, statically-laid-out
 * widget rather than a second list-rendering stack to maintain.
 */
class ContinuePlayingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        // Kick the same background scan every other surface would start,
        // so the first render (before any Games grid/Gaming shell has
        // ever run in this process) has something to show rather than
        // waiting for the next 30-minute system-driven onUpdate.
        requestUpdate(context)
    }

    companion object {
        private const val MAX_ROWS = 4
        private val ROW_IDS = intArrayOf(
            R.id.widget_continue_playing_row1,
            R.id.widget_continue_playing_row2,
            R.id.widget_continue_playing_row3,
            R.id.widget_continue_playing_row4,
        )

        // The widget provider's own process-owned scope: it must outlive
        // any single onUpdate() call (goAsync()'s PendingResult keeps the
        // process alive while this runs), but never outlives the process
        // itself, so it is not tied to any Activity/Compose lifecycle.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Called after a game actually launches, so the widget need not wait for the next periodic tick to show it as "last played". */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, ContinuePlayingWidgetProvider::class.java),
            )
            for (id in ids) updateOne(context.applicationContext, manager, id)
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int) {
            scope.launch {
                val recents = recentEntries(context)
                val views = RemoteViews(context.packageName, R.layout.widget_continue_playing)
                if (recents.isEmpty()) {
                    views.setViewVisibility(R.id.widget_continue_playing_empty, android.view.View.VISIBLE)
                    for (rowId in ROW_IDS) views.setViewVisibility(rowId, android.view.View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_continue_playing_empty, android.view.View.GONE)
                    for ((index, rowId) in ROW_IDS.withIndex()) {
                        val entry = recents.getOrNull(index)
                        if (entry == null) {
                            views.setViewVisibility(rowId, android.view.View.GONE)
                            continue
                        }
                        views.setViewVisibility(rowId, android.view.View.VISIBLE)
                        views.setTextViewText(rowId, entry.title)
                        views.setOnClickPendingIntent(
                            rowId,
                            PendingIntent.getActivity(
                                context,
                                entry.id.hashCode(),
                                GameLaunchActivity.intentFor(context, entry.id),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                    }
                }
                manager.updateAppWidget(widgetId, views)
            }
        }

        /**
         * The most recently played entries, from the same in-RAM index
         * [dev.droidtop.app.CompanionRecents] reads -- never a folder walk
         * from here. With no scan published yet in this process (a cold
         * process, the widget is the first surface to touch the library),
         * this starts one and waits briefly rather than showing an empty
         * widget for up to 30 minutes.
         */
        private suspend fun recentEntries(context: Context): List<LibraryEntry> {
            val library = LibraryCore.library(context)
            val state = library.backgroundScanState(LibraryKinds.GAMES)
            val entries = state.value ?: run {
                library.scanInBackground(LibraryKinds.GAMES)
                withTimeoutOrNull(TIMEOUT_MS) { state.filterNotNull().first() }
            } ?: emptyList()
            return entries
                .filter { it.lastPlayedEpochMs != null }
                .sortedByDescending { it.lastPlayedEpochMs }
                .take(MAX_ROWS)
        }

        private const val TIMEOUT_MS = 5_000L
    }
}
