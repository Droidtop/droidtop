package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

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
 * Each plugin gets exactly one short-lived [PluginCrashPolicy]
 * connection per refresh tick, torn down right after (the same
 * "no ongoing binder connection to keep warm" shape the Quick Menu's
 * plugin panels use, `PluginTiles.refresh`) -- this widget holds no
 * plugin process alive between updates.
 */
class PluginStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        requestUpdate(context)
    }

    companion object {
        private const val CALL_TIMEOUT_MS = 5_000L

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
                statusTiles(context).map { DroidtopWidgets.Row("${it.label}: ${it.value}", null) },
                context.getString(R.string.widget_plugin_status_empty),
            )

        /**
         * One real invoke() per candidate plugin ([PluginStore.runnableFor]
         * -- installed, approved, enabled and re-verified), reading
         * `label`/`value` from its result the same way a real
         * status-tile plugin returns them,
         * falling back to the manifest's own label and a joined dump of
         * whatever the plugin returned when it uses different keys --
         * `status_tile`'s contract names no required keys, so this can't
         * assume more than that. A plugin that times out or fails is
         * dropped for this refresh, not shown as broken (the approval
         * screen is where a crashed/disabled plugin's own status lives).
         */
        private suspend fun statusTiles(context: Context): List<StatusTile> {
            val app = context.applicationContext
            val candidates = kotlinx.coroutines.withContext(Dispatchers.IO) {
                PluginStore.runnableFor(app, PluginCapability.STATUS_TILE)
            }
            if (candidates.isEmpty()) return emptyList()
            val policy = PluginCrashPolicy(app)
            try {
                return candidates.take(DroidtopWidgets.MAX_ROWS).mapNotNull { record -> tileFor(policy, record) }
            } finally {
                policy.shutdown()
            }
        }

        private suspend fun tileFor(policy: PluginCrashPolicy, record: PluginRecord): StatusTile? {
            val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                runCatching { policy.invoke(record, PluginCapability.STATUS_TILE, emptyMap()) }.getOrNull()
            } ?: return null
            if (!result.ok) return null
            val label = result.values["label"] ?: record.manifest.label
            val value = result.values["value"] ?: result.values.entries
                .joinToString(", ") { (k, v) -> "$k=$v" }
                .ifEmpty { "OK" }
            return StatusTile(label, value)
        }

        private data class StatusTile(val label: String, val value: String)
    }
}
