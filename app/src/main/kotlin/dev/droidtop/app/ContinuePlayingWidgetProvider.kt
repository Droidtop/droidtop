package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import dev.droidtop.library.LibraryEntry

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
 * Drawn by [DroidtopWidgets.rows], the renderer every droidtop widget shares (Droidtop/tracker#563): a title and up to
 * four rows, in the role tokens' colours.
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
        /** Called after a game actually launches, so the widget need not wait for the next periodic tick to show it as "last played". */
        fun requestUpdate(context: Context) {
            DroidtopWidgets.update(context, ContinuePlayingWidgetProvider::class.java, build = ::render)
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int) {
            DroidtopWidgets.update(context, ContinuePlayingWidgetProvider::class.java, intArrayOf(widgetId), ::render)
        }

        private suspend fun render(context: Context, widgetId: Int): RemoteViews {
            val rows = recentEntries(context).map { entry ->
                DroidtopWidgets.Row(
                    dev.droidtop.library.GameNaming.displayName(entry.title),
                    DroidtopWidgets.activity(context, "play:${entry.id}", GameLaunchActivity.intentFor(context, entry.id)),
                )
            }
            return DroidtopWidgets.rows(
                context,
                DroidtopWidgets.look(context),
                context.getString(R.string.widget_continue_playing_title),
                rows,
                context.getString(R.string.widget_continue_playing_empty),
            )
        }

        /** The most recently played games, from the library's in-RAM index ([DroidtopWidgets.libraryGames]). */
        private suspend fun recentEntries(context: Context): List<LibraryEntry> =
            DroidtopWidgets.libraryGames(context)
                .filter { it.lastPlayedEpochMs != null }
                .sortedByDescending { it.lastPlayedEpochMs }
                .take(DroidtopWidgets.MAX_ROWS)
    }
}
