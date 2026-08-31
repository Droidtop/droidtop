package dev.droidtop.shell.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.runtime.DisplayOutput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Desktop style" shell: a taskbar + start menu wrapped around the primary
 * container's compositor output, which :host-bridge presents onto a real
 * Android [Surface] (see [HostBridge.presentOutput]).
 *
 * [hostBridge] and [primaryOutput] are nullable on purpose: as of this
 * writing, dev.droidtop.app.DesktopSessionService — the thing that's
 * actually supposed to create the primary container and connect a
 * [HostBridge] to its Wayland socket — is still a TODO stub (see its own
 * source). Passing null here (which :app does today) renders this shell's
 * real chrome — taskbar, start menu, launching library entries all work
 * right now — with an honest "no desktop session" placeholder standing in
 * for the live output, rather than faking a connection that doesn't exist.
 * Once DesktopSessionService is real, wiring a live HostBridge/DisplayOutput
 * through to this composable is the only change needed here.
 *
 * [sessionMessage] carries a human-readable description of the underlying
 * [dev.droidtop.app.DesktopSessionState] (Connecting/Failed/Idle) — this
 * composable previously showed the exact same "no desktop session" text for
 * all three, which is genuinely misleading: root-checking/container-startup
 * (Connecting) looks identical to a hard failure (Failed) looks identical to
 * never having started at all (Idle). A real bug surfaced by this gap: the
 * root-grant prompt DesktopSessionService triggers can land while the
 * screen is dimmed and be effectively invisible (confirmed via on-device
 * logcat), and with no in-app indication that a permission prompt is even
 * expected, a user has no way to know something needs their attention.
 */
@Composable
fun DesktopShell(
    library: Library,
    hostBridge: HostBridge?,
    primaryOutput: DisplayOutput?,
    sessionMessage: DesktopSessionMessage = DesktopSessionMessage.Idle,
) {
    var startMenuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesktopViewport(hostBridge, primaryOutput, sessionMessage)

        Taskbar(
            startMenuOpen = startMenuOpen,
            onToggleStartMenu = { startMenuOpen = !startMenuOpen },
        )

        if (startMenuOpen) {
            StartMenu(
                library = library,
                onDismiss = { startMenuOpen = false },
            )
        }
    }
}

/** Mirrors dev.droidtop.app.DesktopSessionState without :shell-desktop depending on :app. */
sealed interface DesktopSessionMessage {
    data object Idle : DesktopSessionMessage
    data object Connecting : DesktopSessionMessage
    data class Failed(val reason: String) : DesktopSessionMessage
}

@Composable
private fun BoxScope.DesktopViewport(
    hostBridge: HostBridge?,
    primaryOutput: DisplayOutput?,
    sessionMessage: DesktopSessionMessage,
) {
    if (hostBridge != null && primaryOutput != null) {
        var presentFailed by remember { mutableStateOf(false) }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            presentFailed = !hostBridge.presentOutput(primaryOutput, holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            hostBridge.stopPresenting()
                        }
                    })
                }
            },
        )

        if (presentFailed) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't present the desktop output.", color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Check that the primary container's compositor is running.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (sessionMessage) {
                is DesktopSessionMessage.Connecting -> {
                    Text("Starting the desktop session…", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "If a permission prompt appears (root access is required to run " +
                            "the container), grant it to continue.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is DesktopSessionMessage.Failed -> {
                    Text("Desktop session failed to start", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        sessionMessage.reason,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                is DesktopSessionMessage.Idle -> {
                    Text("Desktop session not started", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "The primary container (Wine/Linux desktop) isn't running yet — " +
                            "start it to see your desktop here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.Taskbar(startMenuOpen: Boolean, onToggleStartMenu: () -> Unit) {
    val context = LocalContext.current
    var clockText by remember { mutableStateOf(formatClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = formatClock()
            delay(30_000)
        }
    }

    Row(
        modifier = Modifier
            .align(if (DesktopPrefs.taskbarAtTop(context)) Alignment.TopStart else Alignment.BottomStart)
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onToggleStartMenu, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(if (startMenuOpen) "Close" else "Start")
        }
        Spacer(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = { openContainers(context) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Containers")
        }
        Button(onClick = { openSettings(context) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Settings")
        }
        SystemTray()
        Text(clockText, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * The desktop's system tray, over the shared
 * [dev.droidtop.runtime.systemstatus.SystemStatus] core -- data shared,
 * chrome per surface, the same split the settings catalogs use (per
 * direction: wifi status and system controls in every mode except
 * Standard, which has Android's own status bar). Readout in the bar;
 * the honest controls in a popover: volume directly, brightness behind
 * the WRITE_SETTINGS grant, and the system's own internet panel for
 * Wi-Fi -- programmatic toggling left app reach in API 29, and opening
 * the real control beats faking one.
 */
@Composable
private fun SystemTray() {
    val context = LocalContext.current
    val status by remember { dev.droidtop.runtime.systemstatus.SystemStatus.flow(context) }
        .collectAsState(initial = dev.droidtop.runtime.systemstatus.SystemStatus.snapshot(context))
    var open by remember { mutableStateOf(false) }
    val controls = dev.droidtop.runtime.systemstatus.SystemControls

    Box {
        androidx.compose.material3.TextButton(onClick = { open = !open }) {
            val network = when (status.network) {
                dev.droidtop.runtime.systemstatus.NetworkKind.WIFI ->
                    "\u25E4" + (status.wifiLevel?.let { " $it/4" } ?: "")
                dev.droidtop.runtime.systemstatus.NetworkKind.ETHERNET -> "ETH"
                dev.droidtop.runtime.systemstatus.NetworkKind.CELLULAR -> "LTE"
                dev.droidtop.runtime.systemstatus.NetworkKind.NONE -> "\u2715"
            }
            val battery = status.batteryPercent?.let { "  $it%" + if (status.charging) "\u26A1" else "" } ?: ""
            Text(network + battery, color = MaterialTheme.colorScheme.onSurface)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var volume by remember { mutableStateOf(controls.volume(context).toFloat()) }
            val volumeMax = remember { controls.volumeRange(context).last.toFloat() }
            Text("Volume", modifier = Modifier.padding(horizontal = 16.dp))
            androidx.compose.material3.Slider(
                value = volume,
                onValueChange = { volume = it; controls.setVolume(context, it.toInt()) },
                valueRange = 0f..volumeMax,
                modifier = Modifier.padding(horizontal = 16.dp).width(220.dp),
            )
            if (controls.canWriteBrightness(context)) {
                var brightness by remember { mutableStateOf((controls.brightness(context) ?: 128).toFloat()) }
                Text("Brightness", modifier = Modifier.padding(horizontal = 16.dp))
                androidx.compose.material3.Slider(
                    value = brightness,
                    onValueChange = { brightness = it; controls.setBrightness(context, it.toInt()) },
                    valueRange = 0f..255f,
                    modifier = Modifier.padding(horizontal = 16.dp).width(220.dp),
                )
            } else {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Allow brightness control\u2026") },
                    onClick = { open = false; context.startActivity(controls.brightnessGrantIntent(context)) },
                )
            }
            run {
                var dnd by remember { mutableStateOf(controls.dndEnabled(context)) }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (dnd) "Do Not Disturb: on" else "Do Not Disturb: off") },
                    onClick = {
                        if (controls.hasDndAccess(context)) {
                            dnd = !dnd
                            controls.setDnd(context, dnd)
                        } else {
                            open = false
                            context.startActivity(controls.dndGrantIntent())
                        }
                    },
                )
            }
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Network\u2026") },
                onClick = { open = false; context.startActivity(controls.internetPanelIntent()) },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Bluetooth\u2026") },
                onClick = { open = false; context.startActivity(controls.bluetoothSettingsIntent()) },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("All Android settings\u2026") },
                onClick = { open = false; context.startActivity(controls.allSettingsIntent()) },
            )
        }
    }
}

/**
 * Reads the mode-specific preference set from :shell-default's real
 * settings screen (SettingsDesktopFragment / murine_prefs_desktop.xml). No
 * compile-time dependency on :shell-default -- see :shell-gamepad's
 * equivalent HandheldPrefs for the same reasoning -- so this reads the
 * shared "com.android.launcher3.prefs" SharedPreferences file by its
 * literal name instead.
 */
private object DesktopPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_TASKBAR_TOP = "pref_desktop_taskbar_top"

    fun taskbarAtTop(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TASKBAR_TOP, false)
}

// Action string, not a component class: :shell-desktop has no compile-time
// dependency on :app (where ContainersActivity lives) -- same decoupling as
// openSettings' component-name launch below. setPackage keeps it internal.
private fun openContainers(context: Context) {
    val intent = Intent("dev.droidtop.app.action.CONTAINERS").apply {
        setPackage(context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun openSettings(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
        putExtra(":settings:fragment", "app.murinelauncher.settings.SettingsDesktopFragment")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun formatClock(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

@Composable
private fun BoxScope.StartMenu(library: Library, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val scope = rememberCoroutineScope()
    val taskbarAtTop = DesktopPrefs.taskbarAtTop(context)

    LaunchedEffect(library) {
        entries = library.scanAll()
    }

    Box(
        modifier = Modifier
            .align(if (taskbarAtTop) Alignment.TopStart else Alignment.BottomStart)
            .padding(top = if (taskbarAtTop) 48.dp else 0.dp, bottom = if (taskbarAtTop) 0.dp else 48.dp)
            .width(320.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val currentEntries = entries
        when {
            currentEntries == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
            currentEntries.isEmpty() -> Text(
                "Nothing in the library yet.",
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )

            else -> LazyColumn(modifier = Modifier.padding(8.dp)) {
                items(currentEntries, key = { it.id }) { entry ->
                    Text(
                        entry.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .clickable {
                                scope.launch { library.launch(entry) }
                                onDismiss()
                            },
                    )
                }
            }
        }
    }
}
