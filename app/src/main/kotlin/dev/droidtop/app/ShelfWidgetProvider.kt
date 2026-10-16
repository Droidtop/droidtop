package dev.droidtop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * The Shelf widget (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): a shelf of games the person chose in
 * its maker ([ShelfConfigureActivity]): recently played, recently added, favourites, a collection or a system, as a
 * list of names (the shared rows) or a grid of art ([ShelfWidgetService]). A tap plays through [GameLaunchActivity].
 */
class ShelfWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, ShelfWidgetProvider::class.java, ids, ::render)
        // The grid's cells are read again too: the library may have moved, or the colours changed.
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_grid)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetConfigs.forget(context, appWidgetIds)
    }

    companion object {
        fun requestUpdate(context: Context, widgetId: Int) {
            DroidtopWidgets.update(context, ShelfWidgetProvider::class.java, intArrayOf(widgetId), ::render)
            // The grid's cells are read again by its service.
            AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(widgetId, R.id.widget_grid)
        }

        private suspend fun render(context: Context, widgetId: Int): RemoteViews {
            val look = DroidtopWidgets.look(context)
            val content = WidgetConfigs.shelf(context, widgetId)
                ?: return DroidtopWidgets.rows(
                    context, look, context.getString(R.string.widget_shelf_title),
                    listOf(DroidtopWidgets.Row("Choose what this shelf shows", DroidtopWidgets.activity(context, widgetId, "shelf:$widgetId", maker(context, widgetId)))),
                    "",
                )
            val title = ShelfContent.title(context, content)
            val empty = context.getString(R.string.widget_shelf_empty)
            if (content.layout == ShelfContent.Layout.LIST) {
                val rows = ShelfContent.entries(context, content).map { entry ->
                    DroidtopWidgets.Row(ShelfContent.name(entry), DroidtopWidgets.activity(context, widgetId, "play:${entry.id}", GameLaunchActivity.intentFor(context, entry.id)))
                }
                return DroidtopWidgets.rows(context, look, title, rows, empty, textSp = content.textSize.sp)
            }
            val views = RemoteViews(context.packageName, R.layout.widget_shelf)
            DroidtopWidgets.plate(views, look)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextColor(R.id.widget_title, look.ink)
            views.setTextViewText(R.id.widget_empty, empty)
            views.setTextColor(R.id.widget_empty, look.muted)
            val service = Intent(context, ShelfWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // A data URI of its own, so each widget gets its own factory rather than sharing a cached one.
            service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_grid, service)
            views.setEmptyView(R.id.widget_grid, R.id.widget_empty)
            // Each cell fills in its game's id (ShelfWidgetService); the template must be mutable for that.
            // Keyed by this widget as every tap is (WidgetTaps): a cell fills in only its game's id, never the address.
            val template = PendingIntent.getActivity(
                context,
                dev.droidtop.runtime.WidgetTaps.requestCode(widgetId, "shelf-play"),
                GameLaunchActivity.intentFor(context, "").apply { removeExtra(GameLaunchActivity.EXTRA_ENTRY_ID) }
                    .setData(Uri.parse(dev.droidtop.runtime.WidgetTaps.address(widgetId, "shelf-play")))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.widget_grid, template)
            return views
        }

        private fun maker(context: Context, widgetId: Int): Intent =
            Intent(context, ShelfConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    }
}
