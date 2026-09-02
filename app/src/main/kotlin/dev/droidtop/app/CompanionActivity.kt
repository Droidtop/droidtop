package dev.droidtop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
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
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

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
        widgetHost = CompanionWidgets.host(this)
        widgetIds = CompanionWidgetPrefs.widgetIds(this)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                // The same mode+role selection every other second-screen
                // host applies (SecondScreenPresentation, :display's
                // SecondaryDisplayActivity): with the shell relocated to
                // the addon, THIS activity is what the remaining panel
                // shows, and in Desktop mode that panel is the input
                // surface (trackpad + keyboard) by default, not widgets.
                val mode = dev.droidtop.display.SecondaryDisplayContent.currentMode(this)
                if (SecondScreenInputPrefs.role(this, mode) == SecondScreenInputPrefs.Role.INPUT) {
                    SecondScreenInputSurface(mode)
                    return@DroidtopTheme
                }
                val entry by CompanionState.focusedEntry.collectAsState()
                CompanionSurface(
                    entry = entry,
                    widgetIds = widgetIds,
                    widgetManager = widgetManager,
                    widgetHost = widgetHost,
                ) {
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
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
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


/**
 * The companion's own chrome over the shared [dev.droidtop.runtime.systemstatus.SystemStatus]
 * core -- data shared, chrome per surface, same split the settings
 * catalogs use. Clock + network + battery readout, with the honest
 * controls: volume (directly controllable), brightness (behind the
 * WRITE_SETTINGS grant, surfaced as a grant action until given), and
 * the system's own internet panel for Wi-Fi -- programmatic toggling
 * left app reach in API 29, and opening the real control beats faking
 * one.
 */
@androidx.compose.runtime.Composable
internal fun CompanionNotifications() {
    val items by dev.droidtop.runtime.systemstatus.NotificationsStore.items.collectAsState()
    if (items.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        items.take(4).forEach { item ->
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = item.contentIntent != null) {
                            runCatching { item.contentIntent?.send() }
                        },
                ) {
                    Text(
                        listOfNotNull(item.appLabel, item.title).joinToString(": "),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                    // Local val, not the property: cross-module
                    // properties don't smart-cast.
                    val body = item.text
                    if (!body.isNullOrBlank()) {
                        Text(body, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
                if (item.clearable) {
                    TextButton(onClick = {
                        dev.droidtop.runtime.systemstatus.NotificationsStore.controller?.dismiss(item.key)
                    }) { Text("Dismiss", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (items.size > 4) {
            Text("+" + (items.size - 4) + " more in the Quick Menu", color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@androidx.compose.runtime.Composable
internal fun CompanionSystemBar() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status by androidx.compose.runtime.remember {
        dev.droidtop.runtime.systemstatus.SystemStatus.flow(context)
    }.collectAsState(initial = dev.droidtop.runtime.systemstatus.SystemStatus.snapshot(context))
    var clock by androidx.compose.runtime.remember {
        mutableStateOf(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date()))
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            clock = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())
            kotlinx.coroutines.delay(30_000)
        }
    }
    var controlsOpen by androidx.compose.runtime.remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(clock, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 8.dp))
            Text(
                statusLine(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(onClick = { controlsOpen = !controlsOpen }) {
                Text(if (controlsOpen) "Hide controls" else "Controls")
            }
        }
        if (controlsOpen) {
            SystemControlsRow()
        }
    }
}

internal fun statusLine(status: dev.droidtop.runtime.systemstatus.SystemStatusSnapshot): String {
    val network = when (status.network) {
        dev.droidtop.runtime.systemstatus.NetworkKind.WIFI ->
            "Wi-Fi" + (status.wifiLevel?.let { "  " + "\u2582\u2584\u2586\u2588".take(it.coerceIn(0, 4)) } ?: "")
        dev.droidtop.runtime.systemstatus.NetworkKind.ETHERNET -> "Ethernet"
        dev.droidtop.runtime.systemstatus.NetworkKind.CELLULAR -> "Mobile data"
        dev.droidtop.runtime.systemstatus.NetworkKind.NONE -> "Offline"
    }
    // Validation is the first-class fact: connected-without-internet is
    // the captive-portal state a handheld must SAY, not hide behind a
    // healthy-looking Wi-Fi glyph.
    val noInternet = if (status.network != dev.droidtop.runtime.systemstatus.NetworkKind.NONE && !status.validated) {
        "(no internet)"
    } else ""
    val vpn = if (status.vpnActive) "VPN" else ""
    val battery = status.batteryPercent?.let { "$it%" + if (status.charging) " \u26A1" else "" } ?: ""
    return listOf(network, noInternet, vpn, battery).filter { it.isNotEmpty() }.joinToString("   ")
}

@androidx.compose.runtime.Composable
private fun SystemControlsRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controls = dev.droidtop.runtime.systemstatus.SystemControls
    var volume by androidx.compose.runtime.remember {
        mutableStateOf(controls.volume(context).toFloat())
    }
    val volumeMax = androidx.compose.runtime.remember { controls.volumeRange(context).last.toFloat() }
    var brightness by androidx.compose.runtime.remember {
        mutableStateOf((controls.brightness(context) ?: 128).toFloat())
    }
    val canBrightness = androidx.compose.runtime.remember { controls.canWriteBrightness(context) }

    Column(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Slider(
                value = volume,
                onValueChange = { volume = it; controls.setVolume(context, it.toInt()) },
                valueRange = 0f..volumeMax,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
        }
        if (canBrightness) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Brightness", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Slider(
                    value = brightness,
                    onValueChange = { brightness = it; controls.setBrightness(context, it.toInt()) },
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
            }
        }
        androidx.compose.foundation.layout.Row {
            run {
                var dnd by androidx.compose.runtime.remember { mutableStateOf(controls.dndEnabled(context)) }
                TextButton(onClick = {
                    if (controls.hasDndAccess(context)) {
                        dnd = !dnd; controls.setDnd(context, dnd)
                    } else {
                        context.startActivity(controls.dndGrantIntent())
                    }
                }) { Text(if (dnd) "DND on" else "DND off") }
            }
            TextButton(onClick = { context.startActivity(controls.internetPanelIntent()) }) { Text("Network") }
            TextButton(onClick = { context.startActivity(controls.bluetoothSettingsIntent()) }) { Text("Bluetooth") }
            if (!canBrightness) {
                TextButton(onClick = { context.startActivity(controls.brightnessGrantIntent(context)) }) {
                    Text("Allow brightness control")
                }
            }
            TextButton(onClick = { context.startActivity(controls.allSettingsIntent()) }) { Text("All settings") }
        }
    }
}
