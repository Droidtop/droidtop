package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * "no ongoing binder connection to keep warm" shape
 * `AppSettingsCatalogs.pluginsScreen`'s own status-tile test button
 * already uses) -- this widget holds no plugin process alive between
 * updates.
 */
class PluginStatusWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        requestUpdate(context)
    }

    companion object {
        private const val MAX_ROWS = 4
        private const val CALL_TIMEOUT_MS = 5_000L
        private val ROW_IDS = intArrayOf(
            R.id.widget_plugin_status_row1,
            R.id.widget_plugin_status_row2,
            R.id.widget_plugin_status_row3,
            R.id.widget_plugin_status_row4,
        )

        // Off the process scope: an update must not die with whatever
        // screen asked for it (a plugin being approved/disabled in
        // Settings, or the periodic system tick).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Called after a plugin's approval/enabled state changes, so the widget need not wait for the next 30-minute system tick. */
        fun requestUpdate(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(app, PluginStatusWidgetProvider::class.java),
            )
            for (id in ids) updateOne(app, manager, id)
        }

        private fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int) {
            scope.launch {
                val tiles = statusTiles(context)
                val views = RemoteViews(context.packageName, R.layout.widget_plugin_status)
                if (tiles.isEmpty()) {
                    views.setViewVisibility(R.id.widget_plugin_status_empty, View.VISIBLE)
                    for (rowId in ROW_IDS) views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_plugin_status_empty, View.GONE)
                    for ((index, rowId) in ROW_IDS.withIndex()) {
                        val tile = tiles.getOrNull(index)
                        if (tile == null) {
                            views.setViewVisibility(rowId, View.GONE)
                            continue
                        }
                        views.setViewVisibility(rowId, View.VISIBLE)
                        views.setTextViewText(rowId, "${tile.label}: ${tile.value}")
                    }
                }
                manager.updateAppWidget(widgetId, views)
            }
        }

        /**
         * One real invoke() per candidate plugin ([PluginStore.runnableFor]
         * -- installed, approved, enabled and re-verified), reading
         * `label`/`value` from its result the same way the one real
         * sample plugin (`plugin-sample-statustile`) returns them,
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
                return candidates.take(MAX_ROWS).mapNotNull { record -> tileFor(policy, record) }
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
