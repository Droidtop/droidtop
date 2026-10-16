package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.droidtop.library.GameNaming
import dev.droidtop.library.search.LibraryArtwork

/**
 * The Quick launch widget (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): one game the person chose
 * when placing it ([QuickLaunchConfigureActivity]), its art and title; a tap plays it through [GameLaunchActivity], as a
 * pinned game icon does. A game that has left the library says so, and a tap then opens the picker again.
 */
class QuickLaunchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, QuickLaunchWidgetProvider::class.java, ids, ::render)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetConfigs.forget(context, appWidgetIds)
    }

    companion object {
        /** The art's size: enough for a 2x2 cell on a large phone, small enough for a widget's bitmap budget. */
        private const val ART_PX = 256

        fun requestUpdate(context: Context, widgetId: Int) {
            DroidtopWidgets.update(context, QuickLaunchWidgetProvider::class.java, intArrayOf(widgetId), ::render)
        }

        private suspend fun render(context: Context, widgetId: Int): RemoteViews {
            val look = DroidtopWidgets.look(context)
            val views = RemoteViews(context.packageName, R.layout.widget_quick_launch)
            DroidtopWidgets.plate(views, look)
            views.setTextColor(R.id.widget_title, look.ink)
            val entryId = WidgetConfigs.entryId(context, widgetId)
            val entry = entryId?.let { id -> DroidtopWidgets.libraryGames(context).firstOrNull { it.id == id } }
            if (entry == null) {
                // Never set up, or the game left the library: say so, and a tap picks again.
                views.setTextViewText(R.id.widget_title, context.getString(if (entryId == null) R.string.widget_quick_launch_pick else R.string.widget_quick_launch_gone))
                views.setImageViewResource(R.id.widget_icon, R.mipmap.ic_launcher)
                val pick = Intent(context, QuickLaunchConfigureActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                views.setOnClickPendingIntent(R.id.widget_root, DroidtopWidgets.activity(context, "pick:$widgetId", pick))
                return views
            }
            views.setTextViewText(R.id.widget_title, GameNaming.displayName(entry.title))
            val art = (entry.iconUri ?: entry.artworkUri)?.takeIf { it.isNotBlank() }
                ?.let { LibraryArtwork.decodeSquareBitmap(context, it, ART_PX) }
            if (art != null) views.setImageViewBitmap(R.id.widget_icon, art) else views.setImageViewResource(R.id.widget_icon, R.mipmap.ic_launcher)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                DroidtopWidgets.activity(context, "play:$widgetId", GameLaunchActivity.intentFor(context, entry.id)),
            )
            return views
        }
    }
}
