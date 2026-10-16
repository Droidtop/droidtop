package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.droidtop.library.search.LibraryArtwork
import kotlinx.coroutines.runBlocking

/**
 * The games of a Shelf widget in its grid layout (Droidtop/tracker#563): a collection the launcher asks for one cell at
 * a time. The list and the art are read in [Factory.onDataSetChanged], which Android runs on a binder thread and lets
 * block; a cell is then only built from what was read. A tap fills the widget's template with the game's id, so it
 * plays through [GameLaunchActivity].
 */
class ShelfWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    private class Factory(private val context: Context, private val widgetId: Int) : RemoteViewsFactory {
        private class Cell(val id: String, val name: String, val art: Bitmap?)

        private var cells: List<Cell> = emptyList()
        private var ink = 0

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            val content = WidgetConfigs.shelf(context, widgetId) ?: run { cells = emptyList(); return }
            ink = DroidtopWidgets.look(context).ink
            cells = runBlocking { ShelfContent.entries(context, content) }.map { entry ->
                val art = (entry.artworkUri ?: entry.iconUri)?.takeIf { it.isNotBlank() }
                    ?.let { LibraryArtwork.decodeSquareBitmap(context, it, ART_PX) }
                Cell(entry.id, ShelfContent.name(entry), art)
            }
        }

        override fun getViewAt(position: Int): RemoteViews {
            val cell = cells.getOrNull(position) ?: return blankCell
            val views = RemoteViews(context.packageName, R.layout.widget_shelf_cell)
            if (cell.art != null) views.setImageViewBitmap(R.id.widget_icon, cell.art) else views.setImageViewResource(R.id.widget_icon, R.mipmap.ic_launcher)
            views.setTextViewText(R.id.widget_title, cell.name)
            views.setTextColor(R.id.widget_title, ink)
            views.setOnClickFillInIntent(R.id.widget_cell, Intent().putExtra(GameLaunchActivity.EXTRA_ENTRY_ID, cell.id))
            return views
        }

        override fun getCount(): Int = cells.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = cells.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true
        override fun onDestroy() {
            cells = emptyList()
        }

        private val blankCell: RemoteViews get() = RemoteViews(context.packageName, R.layout.widget_shelf_cell)
    }

    private companion object {
        /** A grid cell's art: small, as a widget's bitmaps share one budget. */
        const val ART_PX = 160
    }
}
