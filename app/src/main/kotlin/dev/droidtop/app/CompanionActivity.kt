package dev.droidtop.app

import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.appwidget.AppWidgetHost
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * The user-populatable widgets/info surface for the display the Gaming
 * shell is NOT on (docs/SPEC.md §4, companion surface — directed): real
 * Android app widgets via [AppWidgetHost] (the same mechanism every
 * launcher uses — music controls and the like), composited ABOVE
 * droidtop's own focused-game/info backdrop ([CompanionContent]).
 * Floating/resizable apps reach this display through the launcher-wide
 * launch-display targeting, not through this Activity.
 *
 * Widget picking is [CompanionWidgetPickActivity] (the system's own
 * `ACTION_APPWIDGET_PICK` flow), the same for every companion host, and bound
 * widget ids persist in [CompanionWidgetPrefs] so the layout survives restarts.
 */
class CompanionActivity : AppCompatActivity() {
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_DISMISS) {
            finish()
            return
        }
        window.addFlags(dev.droidtop.display.SecondScreenWindowFlags.touchOnly())
        // Never the only thing a lone screen shows (tracker#182).
        dev.droidtop.display.CompanionSurfaceLifetime.bind(this, secondaryOnly = false)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = CompanionWidgets.host(this)
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                // Touch only: the D-pad never reaches these controls (dispatchKeyEvent below). Turned inside
                // the window when the display cannot take the orientation asked for (Lock to landscape, #213).
                dev.droidtop.display.DisplayOrientationContent {
                    Box(Modifier.fillMaxSize()) {
                        // The same tab host every other second-screen host draws: with the
                        // shell relocated to the addon, THIS activity is what the remaining panel
                        // shows, and in Desktop mode its default tab is the input surface
                        // (trackpad + keyboard), with Home, Tasks, Performance and System beside it.
                        val mode = dev.droidtop.display.SecondaryDisplayContent.currentMode(this@CompanionActivity)
                        CompanionTabs(mode) {
                            val entry = settledFocusedEntry()
                            CompanionSurface(entry = entry, widgetManager = widgetManager, widgetHost = widgetHost)
                        }
                    }
                }
            }
        }
    }

    // singleTask (AndroidManifest.xml) resolves a repeat launch from
    // MainActivity's role orchestration against this ONE instance rather
    // than stacking a new one on top -- the fix for the display-0
    // ghosting rig bug (dumpsys window's transient surface=[0,0][0,0] on
    // both windows during relocation). This carries no per-launch extras
    // today, but every other singleTask Activity in droidtop
    // (MainActivity) overrides onNewIntent for the same reason: Android's
    // documented behavior for re-launching an already-top Activity is to
    // bring it forward with its ORIGINAL Intent still in effect, silently
    // dropping whatever the new Intent carried.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_DISMISS) finish()
    }

    override fun onResume() {
        super.onResume()
        dev.droidtop.display.CompanionCover.resumed(displayIdCompat())
    }

    // Touch-only, except while Android has made it the top activity: then keys reach this
    // display and it must have a window for them, or the system reports droidtop as not
    // responding (TouchOnlySurfaceFocus; console, build 1386).
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        dev.droidtop.display.TouchOnlySurfaceFocus.onTopResumedChanged(this, isTopResumedActivity, displayIdCompat())
    }

    // Touch only (Droidtop/tracker#186): a pad key that still reaches this window goes to the shell
    // or nowhere, never to the companion's own controls.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        CompanionConfirm.cancelByKey(event) ||
            dev.droidtop.display.TouchOnlySurfaceFocus.consumesKey(event) || super.dispatchKeyEvent(event)

    override fun onPause() {
        // Paused with something in front of it (an app launched onto this screen,
        // the widget picker): the orchestration leaves that app alone (tracker#265).
        dev.droidtop.display.CompanionCover.paused(displayIdCompat(), isFinishing)
        super.onPause()
    }

    private fun displayIdCompat(): Int? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.displayId
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId
        }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
        visible = true
    }

    override fun onStop() {
        visible = false
        widgetHost.stopListening()
        // Covered by the shell on its own screen (Main screen set to the built-in one moves the shell
        // over it): the companion has no role there, and left stopped under the shell it is what that
        // screen shows whenever the shell goes (tracker#163, #182). An app covering it is different: the
        // shell is then on the other screen, and the companion waits for that app (tracker#265).
        val shellDisplay = ForegroundShell.current()?.takeIf { !it.isFinishing }?.let { shell ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                shell.display?.displayId
            } else {
                @Suppress("DEPRECATION")
                shell.windowManager.defaultDisplay?.displayId
            }
        }
        if (!isFinishing && shellDisplay != null && shellDisplay == displayIdCompat()) {
            android.util.Log.i("droidtop.SecondScreen", "Companion covered by the shell on display $shellDisplay: finishing")
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        // A finished companion covers nothing; a stale cover would keep the next one from starting.
        if (isFinishing) dev.droidtop.display.CompanionCover.retired(displayIdCompat())
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISMISS = "dev.droidtop.app.action.DISMISS_COMPANION"

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

/**
 * Persisted companion widget layout -- same shared-prefs convention as every other settings concern -- and observable
 * ([ids]), so every companion host shows a widget added or removed from any of them. [load] reads the file: call it
 * off the main thread.
 */
object CompanionWidgetPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_WIDGET_IDS = "droidtop_companion_widget_ids"

    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<Int>>(emptyList())
    val ids: kotlinx.coroutines.flow.StateFlow<List<Int>> = state

    fun load(context: android.content.Context) {
        state.value = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_WIDGET_IDS, null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
    }

    fun add(context: android.content.Context, id: Int) = store(context, state.value + id)

    /** Takes the last widget off Home and releases its id. */
    fun removeLast(context: android.content.Context) {
        val last = state.value.lastOrNull() ?: return
        runCatching { CompanionWidgets.host(context).deleteAppWidgetId(last) }
        store(context, state.value.dropLast(1))
    }

    private fun store(context: android.content.Context, ids: List<Int>) {
        state.value = ids
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
    val battery = rememberBattery()
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
            // Mic muted, Mic in use, Camera in use: each its own words, read by TalkBack (VPN is in the line above).
            rememberStatusIndicators(vpn = false).forEach { indicator ->
                androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))
                Text(indicator, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            // A plugin that records (windowcast or another recorder) says so: "Recording 1:05".
            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 3.dp))
            CompanionRecordingIndicator()
            // Time to empty or full, once the battery broadcasts give enough to say (about a minute).
            battery.second?.let { estimate ->
                androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))
                Text(estimate.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CompanionLowBatteryLine(battery.first)
        DisplayFallbackNotice()
    }
}

/**
 * The add-on can come up in a low safe mode and stay there until it is
 * power-cycled (docs/SPEC.md section 4); Android reports it as a normal
 * display, so this says so on the glanceable screen, with what to do.
 */
@androidx.compose.runtime.Composable
private fun DisplayFallbackNotice() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val outputs by androidx.compose.runtime.remember {
        dev.droidtop.runtime.DisplayOutputRepository(context.applicationContext).observe()
    }.collectAsState(initial = emptyList())
    val degraded = outputs.firstOrNull {
        it.kind == dev.droidtop.runtime.DisplayOutputKind.SECOND_SCREEN && it.isInFallbackMode
    } ?: return
    Text(
        displayFallbackMessage(degraded),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
}

internal fun displayFallbackMessage(output: dev.droidtop.runtime.DisplayOutput): String =
    "The second screen is running at ${output.modeSummary()}. " +
        "Turn it off and on again to get its full resolution back."

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
