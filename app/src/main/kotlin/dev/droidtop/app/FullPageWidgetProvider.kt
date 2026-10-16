package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.DroidtopScreens
import dev.droidtop.shell.standard.LauncherSearch
import dev.droidtop.shell.standard.ModeSwitcher

/**
 * droidtop's full-page widget (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): a whole launcher page of
 * droidtop, so the person can swipe across their own home screen to it. The one search's bar, the games last played
 * (as Continue playing shows them), then droidtop's places: the Quick Menu, Downloads, Switch mode, and Gaming or
 * Desktop while that mode is on. Drawn by the shared renderer in the `widget_page` layout.
 */
class FullPageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, FullPageWidgetProvider::class.java, ids, ::render)
    }

    companion object {
        /** After a game launches, so its row moves to the top. */
        fun requestUpdate(context: Context) {
            DroidtopWidgets.update(context, FullPageWidgetProvider::class.java, build = ::render)
        }

        private val ACTION_IDS = intArrayOf(R.id.widget_action1, R.id.widget_action2, R.id.widget_action3, R.id.widget_action4)

        private suspend fun render(context: Context, widgetId: Int): RemoteViews {
            val look = DroidtopWidgets.look(context)
            val recent = DroidtopWidgets.libraryGames(context)
                .filter { it.lastPlayedEpochMs != null && !it.hidden && !it.missing }
                .sortedByDescending { it.lastPlayedEpochMs }
                .take(DroidtopWidgets.MAX_ROWS)
                .map { entry ->
                    DroidtopWidgets.Row(
                        dev.droidtop.library.GameNaming.displayName(entry.title),
                        DroidtopWidgets.activity(context, widgetId, "play:${entry.id}", GameLaunchActivity.intentFor(context, entry.id)),
                    )
                }
            val views = DroidtopWidgets.rows(
                context, look,
                context.getString(R.string.widget_continue_playing_title),
                recent,
                context.getString(R.string.widget_continue_playing_empty),
                layout = R.layout.widget_page,
            )
            val pkg = context.packageName
            views.setTextColor(R.id.widget_search_hint, look.muted)
            views.setInt(R.id.widget_icon, "setColorFilter", look.ink)
            views.setOnClickPendingIntent(R.id.widget_search, DroidtopWidgets.activity(context, widgetId, "page-search", Intent(LauncherSearch.ACTION_SEARCH).setPackage(pkg)))
            fun mode(mode: Mode) = Intent(Intent.ACTION_MAIN).setClassName(pkg, MainActivity::class.java.name).putExtra(BackButtonMenu.EXTRA_MODE, mode.id)
            val actions = listOfNotNull(
                "Quick Menu" to Intent(Intent.ACTION_MAIN).setClassName(pkg, DroidtopScreens.QUICK_MENU_ACTIVITY),
                "Downloads" to CatalogScreenLink.intent(context, "plugin_jobs"),
                ("Gaming" to mode(Mode.GAMING)).takeIf { Modes.isEnabled(Mode.GAMING) },
                ("Desktop" to mode(Mode.DESKTOP)).takeIf { Modes.isEnabled(Mode.DESKTOP) },
                "Switch mode" to Intent(Intent.ACTION_MAIN).setClassName(pkg, ModeSwitcher.ACTIVITY),
            ).take(ACTION_IDS.size)
            for ((index, id) in ACTION_IDS.withIndex()) {
                val action = actions.getOrNull(index)
                views.setViewVisibility(id, if (action == null) View.GONE else View.VISIBLE)
                if (action == null) continue
                views.setTextViewText(id, action.first)
                views.setTextColor(id, look.ink)
                views.setOnClickPendingIntent(id, DroidtopWidgets.activity(context, widgetId, "page:${action.first}", action.second))
            }
            return views
        }
    }
}
