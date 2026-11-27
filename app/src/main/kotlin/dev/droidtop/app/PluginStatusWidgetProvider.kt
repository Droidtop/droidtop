package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import dev.droidtop.library.integrations.PluginWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * droidtop's own plugin-fed launcher surface (docs/SPEC.md, Launcher
 * mode, "Plugin contributions reach the launcher" -- §12a's
 * `status_tile` capability): an approved, enabled plugin's own status
 * (a network/VPN state, a reading, a toggle's current value) on the home
 * screen, refreshed on this widget's own schedule -- never a background
 * loop a plugin owns itself (the same constraint `PluginCapability
 * .STATUS_TILE`'s own doc comment states).
 *
 * A droidtop-drawn home-screen widget, not a row inside Murine's
 * workspace grid -- same seam and the same "own home-screen widget"
 * shape as [ContinuePlayingWidgetProvider], placed like any Nova/Apex
 * widget from the Standard launcher's stock widget picker.
 *
 * Its sources and state go through [PluginWidgets], as the configurable
 * Plugin widget's do. That path honours each tile's id, declared surfaces,
 * game scope and state budget; this widget owns no separate plugin call path.
 */
class PluginStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        requestUpdate(context)
    }

    companion object {
        /** Called after a plugin's approval/enabled state changes, so the widget need not wait for the next 30-minute system tick. */
        fun requestUpdate(context: Context) {
            DroidtopWidgets.update(context, PluginStatusWidgetProvider::class.java, build = ::render)
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int) {
            DroidtopWidgets.update(context, PluginStatusWidgetProvider::class.java, intArrayOf(widgetId), ::render)
        }

        private suspend fun render(context: Context, widgetId: Int): RemoteViews =
            DroidtopWidgets.rows(
                context,
                DroidtopWidgets.look(context),
                context.getString(R.string.widget_plugin_status_title),
                statusLines(context).map { DroidtopWidgets.Row(it, null) },
                context.getString(R.string.widget_plugin_status_empty),
            )

        /**
         * The same declared sources, tile ids, widget context and cached state as the configurable Plugin widget.
         * A game-scoped tile never appears on a home screen. A missing value never becomes a made-up "OK".
         */
        private suspend fun statusLines(context: Context): List<String> = withContext(Dispatchers.IO) {
            PluginWidgets.sources(context).filter { it.kind == PluginWidgets.Kind.STATUS }
                .take(DroidtopWidgets.MAX_ROWS).flatMap { source ->
                    val state = PluginWidgets.tileState(context, source) ?: return@flatMap emptyList()
                    PluginWidgetContent.tile(source.label, state, quick = false).lines.map { it.text }
                }
        }
    }
}
