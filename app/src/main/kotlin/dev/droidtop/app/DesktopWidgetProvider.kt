package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.droidtop.app.settings.ContainersCatalog
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Mode
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Desktop widget (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): start or go back to droidtop's
 * Desktop, saying whether its session is running, open the containers, and run the apps containers exported to the
 * desktop, two at most, through the one export launch ([ExportLaunch]: the running session, else Desktop starts one).
 * Gated by Desktop mode: its provider is enabled only while Desktop is on ([ModeStartup]), which is how Android hides a
 * widget from every launcher's picker.
 */
class DesktopWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, DesktopWidgetProvider::class.java, ids, ::render)
    }

    companion object {
        @Volatile private var watching = false

        /** After the exported apps changed (a container page's export switch, a container removed). */
        fun requestUpdate(context: Context) {
            DroidtopWidgets.update(context, DesktopWidgetProvider::class.java, build = ::render)
        }

        /** Follows the desktop session for the life of the process (from :app's start), so the widget says whether it runs. */
        fun watch(context: Context) {
            if (watching) return
            watching = true
            val app = context.applicationContext
            DroidtopWidgets.scope.launch {
                DesktopSessionService.state
                    .map { it is DesktopSessionState.Connected }
                    .distinctUntilChanged()
                    .collect {
                        val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, DesktopWidgetProvider::class.java))
                        if (ids.isNotEmpty()) DroidtopWidgets.update(app, DesktopWidgetProvider::class.java, ids, ::render)
                    }
            }
        }

        private fun render(context: Context, widgetId: Int): RemoteViews {
            val running = DesktopSessionService.state.value is DesktopSessionState.Connected
            val open = Intent(Intent.ACTION_MAIN)
                .setClassName(context.packageName, MainActivity::class.java.name)
                .putExtra(BackButtonMenu.EXTRA_MODE, Mode.DESKTOP.id)
            return DroidtopWidgets.rows(
                context,
                DroidtopWidgets.look(context),
                context.getString(R.string.widget_desktop_title),
                listOf(
                    DroidtopWidgets.Row(if (running) "Go to the desktop (running)" else "Start the desktop", DroidtopWidgets.activity(context, widgetId, "desktop", open)),
                    DroidtopWidgets.Row("Containers", DroidtopWidgets.activity(context, widgetId, "containers", CatalogScreenLink.intent(context, ContainersCatalog.SCREEN_ID))),
                ) + ExportLaunch.published(context).sortedBy { it.app.name.lowercase() }.take(DroidtopWidgets.MAX_ROWS - 2).map { export ->
                    DroidtopWidgets.Row(export.title, DroidtopWidgets.activity(context, widgetId, "export:${export.token}", ExportLaunchActivity.intentFor(context, export)))
                },
                "",
            )
        }
    }
}
