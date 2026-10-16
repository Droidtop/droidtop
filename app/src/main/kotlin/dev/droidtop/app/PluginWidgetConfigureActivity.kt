package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidtop.library.integrations.PluginWidgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Plugin widget's configure activity (Droidtop/tracker#563): picks which plugin source the widget shows, from
 * [PluginWidgets.sources] (manifests only; no plugin is loaded to list them). Opened by the launcher when the widget is
 * placed or reconfigured, and by a tap on a widget whose source is unset or gone. Leaving without a pick cancels the
 * placement, as the AppWidget contract asks.
 */
class PluginWidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        setContent {
            dev.droidtop.app.ui.DroidtopTheme {
                val sources by produceState<List<PluginWidgets.Source>?>(null) {
                    value = withContext(Dispatchers.IO) { PluginWidgets.sources(applicationContext) }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(getString(R.string.widget_plugin_pick), style = MaterialTheme.typography.titleLarge)
                        val list = sources
                        when {
                            list == null -> Text("Reading your plugins", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            list.isEmpty() -> Text(getString(R.string.widget_plugin_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(list, key = { it.key }) { source ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable { pick(widgetId, source) }
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                    ) {
                                        Text(source.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${source.pluginLabel} · ${kindName(source.kind)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pick(widgetId: Int, source: PluginWidgets.Source) {
        WidgetConfigs.setPluginSource(applicationContext, widgetId, source.key)
        PluginWidgetProvider.requestUpdate(applicationContext, widgetId, force = true)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private fun kindName(kind: PluginWidgets.Kind): String = when (kind) {
        PluginWidgets.Kind.WIDGET -> "widget"
        PluginWidgets.Kind.QUICK -> "quick tile"
        PluginWidgets.Kind.STATUS -> "status"
    }
}
