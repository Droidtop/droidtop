package dev.droidtop.app

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The widget maker for a Shelf widget (Droidtop/tracker#563): what the shelf shows (recently played, recently added,
 * favourites, one of the person's collections, a system that has games) and how (a grid of art or a list of names).
 * Opened by the launcher when the widget is placed, again where it can reconfigure one, and by a tap on a shelf never
 * set up. Done saves it ([WidgetConfigs]) and draws the widget; leaving without Done cancels a new placement, as the
 * AppWidget contract asks.
 */
class ShelfConfigureActivity : ComponentActivity() {

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
                val choices by produceState<List<ShelfContent.Choice>?>(null) { value = ShelfContent.choices(applicationContext) }
                val saved = remember { mutableStateOf<ShelfContent?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    saved.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WidgetConfigs.shelf(applicationContext, widgetId) }
                }
                var picked by remember { mutableStateOf<ShelfContent.Choice?>(null) }
                var layout by remember { mutableStateOf<ShelfContent.Layout?>(null) }
                val current = saved.value
                val chosenLayout = layout ?: current?.layout ?: ShelfContent.Layout.GRID
                val chosen = picked ?: choices?.firstOrNull { it.kind == current?.kind && it.arg == current.arg } ?: choices?.firstOrNull()
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Shelf", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ShelfContent.Layout.entries.forEach { option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.selectable(selected = option == chosenLayout, onClick = { layout = option }),
                                ) {
                                    RadioButton(selected = option == chosenLayout, onClick = null)
                                    Text(if (option == ShelfContent.Layout.GRID) "Grid of art" else "List of names")
                                }
                            }
                        }
                        Text("Shows", style = MaterialTheme.typography.titleMedium)
                        val list = choices
                        if (list == null) {
                            Text("Reading your library", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(list, key = { "${it.kind}:${it.arg}" }) { choice ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable { picked = choice },
                                    ) {
                                        RadioButton(selected = choice === chosen, onClick = { picked = choice })
                                        Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                        Button(
                            enabled = chosen != null,
                            onClick = { chosen?.let { save(widgetId, ShelfContent(it.kind, it.arg, chosenLayout)) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Done") }
                    }
                }
            }
        }
    }

    private fun save(widgetId: Int, content: ShelfContent) {
        WidgetConfigs.setShelf(applicationContext, widgetId, content)
        ShelfWidgetProvider.requestUpdate(applicationContext, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
