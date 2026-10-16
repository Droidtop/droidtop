package dev.droidtop.app

import android.content.Context

/**
 * What each placed droidtop widget was set up to show, by its appWidgetId (Droidtop/tracker#563): today, the game a
 * Quick launch widget plays. Kept in droidtop's own preferences, written by the widget's configure activity and
 * forgotten when the launcher removes the widget. Read off the main thread, in the widget's render.
 */
internal object WidgetConfigs {
    private const val PREFS = "droidtop_widgets"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entryId(context: Context, widgetId: Int): String? = prefs(context).getString("$widgetId.entry", null)

    fun setEntryId(context: Context, widgetId: Int, entryId: String) {
        prefs(context).edit().putString("$widgetId.entry", entryId).apply()
    }

    fun forget(context: Context, widgetIds: IntArray) {
        val edit = prefs(context).edit()
        for (id in widgetIds) edit.remove("$id.entry")
        edit.apply()
    }
}
