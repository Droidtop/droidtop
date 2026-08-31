package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The user-populatable widgets/info surface for the display the Handheld
 * shell is NOT on (docs/SPEC.md §4, companion surface — directed): real
 * Android app widgets via [AppWidgetHost] (the same mechanism every
 * launcher uses — music controls and the like), composited ABOVE
 * droidtop's own focused-game/info backdrop ([CompanionContent]).
 * Floating/resizable apps reach this display through the launcher-wide
 * launch-display targeting, not through this Activity.
 *
 * Widget picking uses the system's own `ACTION_APPWIDGET_PICK` flow (the
 * picker handles bind permission + the widget's own configure Activity),
 * and bound widget ids persist in [CompanionWidgetPrefs] so the layout
 * survives restarts.
 */
class CompanionActivity : AppCompatActivity() {
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private var widgetIds by mutableStateOf<List<Int>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        widgetIds = CompanionWidgetPrefs.widgetIds(this)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                val entry by CompanionState.focusedEntry.collectAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    // droidtop's info stays the BACKGROUND layer; user
                    // widgets composite above it (per direction).
                    CompanionContent(entry)
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        widgetIds.forEach { widgetId ->
                            val info = widgetManager.getAppWidgetInfo(widgetId)
                            if (info != null) {
                                // minHeight is real PIXELS (AppWidgetProviderInfo),
                                // converted properly rather than reinterpreted as dp.
                                val widgetHeight = with(density) { maxOf(info.minHeight, 200).toDp() }
                                AndroidView(
                                    factory = { context ->
                                        widgetHost.createView(context.applicationContext, widgetId, info).apply {
                                            setAppWidget(widgetId, info)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(widgetHeight)
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { pickWidget() }) { Text("Add widget") }
                            if (widgetIds.isNotEmpty()) {
                                TextButton(onClick = { removeLastWidget() }) {
                                    Text("Remove widget", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (widgetIds.isEmpty()) {
                        Text(
                            "Add widgets — music controls, calendars, anything installed.",
                            color = Color.DarkGray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        )
                    }
                }
            }
        }
    }

    private fun pickWidget() {
        val widgetId = widgetHost.allocateAppWidgetId()
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            REQUEST_PICK_WIDGET,
        )
    }

    private fun removeLastWidget() {
        widgetIds.lastOrNull()?.let { widgetId ->
            widgetHost.deleteAppWidgetId(widgetId)
            widgetIds = widgetIds.dropLast(1)
            CompanionWidgetPrefs.setWidgetIds(this, widgetIds)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_WIDGET && requestCode != REQUEST_CONFIGURE_WIDGET) return
        val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (resultCode != RESULT_OK || widgetId == -1) {
            if (widgetId != -1) widgetHost.deleteAppWidgetId(widgetId)
            return
        }
        if (requestCode == REQUEST_PICK_WIDGET) {
            // A picked widget may need its own configuration Activity
            // before it's usable — the standard host flow.
            val info = widgetManager.getAppWidgetInfo(widgetId)
            if (info?.configure != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                    REQUEST_CONFIGURE_WIDGET,
                )
                return
            }
        }
        widgetIds = widgetIds + widgetId
        CompanionWidgetPrefs.setWidgetIds(this, widgetIds)
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
        visible = true
    }

    override fun onStop() {
        visible = false
        widgetHost.stopListening()
        super.onStop()
    }

    companion object {
        private const val WIDGET_HOST_ID = 0xD801
        private const val REQUEST_PICK_WIDGET = 71
        private const val REQUEST_CONFIGURE_WIDGET = 72

        /**
         * Whether a companion instance is currently started/visible — read
         * by MainActivity's role orchestration so a display reinit knows
         * to (re)assert this surface (confirmed live: the built-in screen
         * stayed on whatever app was open there — Android Settings —
         * because nothing ever re-asserted the companion after the shell
         * relocated).
         */
        @Volatile
        var visible: Boolean = false
            private set
    }
}

/** Persisted companion widget layout — same shared-prefs convention as every other settings concern. */
object CompanionWidgetPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_WIDGET_IDS = "droidtop_companion_widget_ids"

    fun widgetIds(context: android.content.Context): List<Int> =
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_WIDGET_IDS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()

    fun setWidgetIds(context: android.content.Context, ids: List<Int>) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_WIDGET_IDS, ids.joinToString(",")).apply()
    }
}
