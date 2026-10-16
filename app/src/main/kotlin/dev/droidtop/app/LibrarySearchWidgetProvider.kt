package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.droidtop.shell.standard.LauncherSearch

/**
 * The Library search widget (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): a bar on any launcher's
 * page that opens droidtop's one search (`LauncherSearchActivity`): installed apps, the library's games, the download
 * sources and the web in one ranked list. Not gated by a mode: the library is never mode-gated (docs/SPEC.md 2c).
 */
class LibrarySearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, LibrarySearchWidgetProvider::class.java, ids, ::render)
    }

    private companion object {
        fun render(context: Context, widgetId: Int): RemoteViews {
            val look = DroidtopWidgets.look(context)
            val views = RemoteViews(context.packageName, R.layout.widget_search)
            DroidtopWidgets.plate(views, look)
            views.setTextColor(R.id.widget_title, look.muted)
            views.setInt(R.id.widget_icon, "setColorFilter", look.ink)
            val search = Intent(LauncherSearch.ACTION_SEARCH).setPackage(context.packageName)
            views.setOnClickPendingIntent(R.id.widget_root, DroidtopWidgets.activity(context, widgetId, "search", search))
            return views
        }
    }
}
