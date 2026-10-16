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
import androidx.compose.material3.OutlinedTextField
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
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryGrouping
import dev.droidtop.library.scraper.isPcOrEngineGame

/**
 * The Quick launch widget's configure activity (Droidtop/tracker#563): picks the one game the widget plays. Opened by
 * the launcher when the widget is placed (and again where the launcher can reconfigure a widget), and by a tap on a
 * widget whose game is unset or gone. Lists the library's playable games under the names Gaming gives them, one card
 * per game as the Games grid groups them, filtered by what is typed; a pick saves it ([WidgetConfigs]) and draws the
 * widget. Leaving without a pick cancels the placement, as the AppWidget contract asks.
 */
class QuickLaunchConfigureActivity : ComponentActivity() {

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
                val games by produceState<List<LibraryEntry>?>(null) {
                    value = DroidtopWidgets.libraryGames(applicationContext).let(::playable)
                }
                var filter by remember { mutableStateOf("") }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(getString(R.string.widget_quick_launch_pick), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Filter") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val list = games
                        when {
                            list == null -> Text("Reading your library", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            list.isEmpty() -> Text("No games in your library yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> {
                                val shown = remember(list, filter) {
                                    val words = filter.trim().lowercase().split(' ').filter { it.isNotEmpty() }
                                    list.filter { entry -> name(entry).lowercase().let { n -> words.all { it in n } } }
                                }
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(shown, key = { it.id }) { entry ->
                                        Text(
                                            name(entry),
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clickable { pick(widgetId, entry) }
                                                .padding(horizontal = 8.dp, vertical = 12.dp),
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

    private fun pick(widgetId: Int, entry: LibraryEntry) {
        WidgetConfigs.setEntryId(applicationContext, widgetId, entry.id)
        QuickLaunchWidgetProvider.requestUpdate(applicationContext, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private companion object {
        fun name(entry: LibraryEntry): String = GameNaming.displayName(entry.title)

        /** Playable games, one card per PC game as the Games grid groups them, by name. */
        fun playable(entries: List<LibraryEntry>): List<LibraryEntry> {
            val (pc, others) = entries.filter { !it.missing && !it.hidden }.partition { it.isPcOrEngineGame }
            return (LibraryGrouping.group(pc).map { it.displayEntry } + others).sortedBy { name(it).lowercase() }
        }
    }
}
