package dev.droidtop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.runtime.CompanionSheet
import dev.droidtop.shell.standard.BackButtonMenu

/**
 * The Companion widget (docs/SPEC.md, "Widgets on any launcher"; Droidtop/tracker#563): the Quick Menu's companion and
 * screen actions on any launcher's page. With one screen it opens the companion as a pull-down sheet
 * ([CompanionSheet]); with a second screen holding the companion it turns that screen back on
 * ([CompanionPrefs.requestWake], through [WAKE], a broadcast to this provider, as no screen of droidtop's is needed for
 * it). Reinitialize displays re-runs droidtop's display roles, as the mode switcher's row does. Drawn again when a
 * screen is added or removed ([watch]), since that decides the first row.
 */
class CompanionWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        DroidtopWidgets.update(context, CompanionWidgetProvider::class.java, ids, ::render)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WAKE) {
            CompanionPrefs.requestWake()
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val WAKE = "dev.droidtop.app.action.WIDGET_COMPANION_WAKE"

        @Volatile private var watching = false

        /** Follows screens being added and removed for the life of the process (from :app's start). */
        fun watch(context: Context) {
            if (watching) return
            watching = true
            val app = context.applicationContext
            val redraw = {
                val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, CompanionWidgetProvider::class.java))
                if (ids.isNotEmpty()) DroidtopWidgets.update(app, CompanionWidgetProvider::class.java, ids, ::render)
            }
            app.getSystemService(DisplayManager::class.java)?.registerDisplayListener(
                object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = redraw()
                    override fun onDisplayRemoved(displayId: Int) = redraw()
                    override fun onDisplayChanged(displayId: Int) = Unit
                },
                Handler(Looper.getMainLooper()),
            )
        }

        private fun render(context: Context, widgetId: Int): RemoteViews {
            val oneScreen = CompanionSheet.offered(context)
            val companion = if (oneScreen) {
                DroidtopWidgets.Row(
                    "Open the companion",
                    DroidtopWidgets.activity(
                        context, "companion",
                        Intent(CompanionSheet.ACTION).setPackage(context.packageName).putExtra(CompanionSheet.EXTRA_TRAY, false),
                    ),
                )
            } else {
                DroidtopWidgets.Row(
                    "Turn the companion screen on",
                    PendingIntent.getBroadcast(
                        context, WAKE.hashCode(),
                        Intent(WAKE).setClass(context, CompanionWidgetProvider::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            val reinit = Intent(Intent.ACTION_MAIN)
                .setClassName(context.packageName, MainActivity::class.java.name)
                .putExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT_FORCE, true)
            return DroidtopWidgets.rows(
                context,
                DroidtopWidgets.look(context),
                context.getString(R.string.widget_companion_title),
                listOf(companion, DroidtopWidgets.Row("Reinitialize displays", DroidtopWidgets.activity(context, "reinit", reinit))),
                "",
            )
        }
    }
}
