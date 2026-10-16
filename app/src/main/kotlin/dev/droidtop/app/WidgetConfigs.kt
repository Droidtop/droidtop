package dev.droidtop.app

import android.content.Context

/**
 * What each placed droidtop widget was set up to show, by its appWidgetId (Droidtop/tracker#563): the game a Quick
 * launch widget plays, a Shelf widget's content and layout, and a Plugin widget's source. Kept in droidtop's own preferences, written by the widget's configure activity and
 * forgotten when the launcher removes the widget. Read off the main thread, in the widget's render.
 */
internal object WidgetConfigs {
    private const val PREFS = "droidtop_widgets"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun entryId(context: Context, widgetId: Int): String? = prefs(context).getString("$widgetId.entry", null)

    fun setEntryId(context: Context, widgetId: Int, entryId: String) {
        prefs(context).edit().putString("$widgetId.entry", entryId).apply()
    }

    /** The plugin source a Plugin widget shows ([dev.droidtop.library.integrations.PluginWidgets.Source.key]), or null. */
    fun pluginSource(context: Context, widgetId: Int): String? = prefs(context).getString("$widgetId.plugin", null)

    fun setPluginSource(context: Context, widgetId: Int, key: String) {
        prefs(context).edit().putString("$widgetId.plugin", key).apply()
    }

    /** What a Shelf widget shows, or null when it was never set up. */
    fun shelf(context: Context, widgetId: Int): ShelfContent? {
        val p = prefs(context)
        val kind = p.getString("$widgetId.shelf", null)?.let { name -> ShelfContent.Kind.entries.firstOrNull { it.name == name } } ?: return null
        val layout = p.getString("$widgetId.layout", null)?.let { name -> ShelfContent.Layout.entries.firstOrNull { it.name == name } } ?: ShelfContent.Layout.GRID
        val art = p.getString("$widgetId.art", null)?.let { name -> ShelfContent.Art.entries.firstOrNull { it.name == name } } ?: ShelfContent.Art.COVER
        val size = p.getString("$widgetId.text", null)?.let { name -> ShelfContent.TextSize.entries.firstOrNull { it.name == name } } ?: ShelfContent.TextSize.NORMAL
        return ShelfContent(kind, p.getString("$widgetId.arg", null), layout, art, size)
    }

    fun setShelf(context: Context, widgetId: Int, content: ShelfContent) {
        prefs(context).edit()
            .putString("$widgetId.shelf", content.kind.name)
            .putString("$widgetId.arg", content.arg)
            .putString("$widgetId.layout", content.layout.name)
            .putString("$widgetId.art", content.art.name)
            .putString("$widgetId.text", content.textSize.name)
            .apply()
    }

    fun forget(context: Context, widgetIds: IntArray) {
        val edit = prefs(context).edit()
        for (id in widgetIds) KEYS.forEach { edit.remove("$id.$it") }
        edit.apply()
    }

    private val KEYS = listOf("entry", "shelf", "arg", "layout", "art", "text", "plugin")
}
