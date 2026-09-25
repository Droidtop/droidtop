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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.input.InputSeats
import dev.droidtop.input.PointerTransform
import dev.droidtop.library.Library
import dev.droidtop.library.LaunchResult
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.runtime.DisplayOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * "Desktop style" shell: a taskbar + start menu wrapped around the primary
 * container's compositor output, which :host-bridge presents onto a real
 * Android [Surface] (see [HostBridge.presentOutput]).
 *
 * [hostBridge] and [primaryOutput] are nullable on purpose:
 * dev.droidtop.app.DesktopSessionService does the orchestration (backend
 * selection, container create/start, HostBridge connect) and MainActivity
 * passes its live values through, but the session can genuinely be
 * absent — not started yet, still booting, or failed. Passing null renders this shell's
 * real chrome — taskbar, start menu, launching library entries all work
 * right now — with an honest "no desktop session" placeholder standing in
 * for the live output, rather than faking a connection that doesn't exist.
 * The surface is an input surface as well as an output one: it drives a
 * [dev.droidtop.input.DesktopInputRouter] over a single
 * [dev.droidtop.input.InputSeat], which is what makes the presented
 * desktop clickable rather than a video feed. See that class for the
 * per-source decisions; the coordinate transform is built in
 * `surfaceChanged` below, where the surface's real size is known.
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
    onOpenTerminal: (() -> Unit)? = null,
    loadLinuxApps: (suspend () -> List<ContainerApp>)? = null,
    onLaunchLinuxApp: ((ContainerApp) -> Unit)? = null,
    launchFailure: String? = null,
    onDismissLaunchFailure: () -> Unit = {},
    /** A Start-menu launch that was refused: shown in the same banner as [launchFailure]. */
    onLaunchFailure: (String) -> Unit = {},
) {
    var startMenuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesktopViewport(hostBridge, primaryOutput, sessionMessage)

        Taskbar(
            startMenuOpen = startMenuOpen,
            onToggleStartMenu = { startMenuOpen = !startMenuOpen },
            // Absent rather than disabled when there is no live session:
            // a Terminal button that cannot open a terminal is a lie about
            // what the desktop can do right now.
            onOpenTerminal = onOpenTerminal,
        )

        launchFailure?.let { message ->
            LaunchFailureBanner(message = message, onDismiss = onDismissLaunchFailure)
        }

        if (startMenuOpen) {
            StartMenu(
                library = library,
                loadLinuxApps = loadLinuxApps,
                onLaunchLinuxApp = onLaunchLinuxApp,
                onLaunchFailure = onLaunchFailure,
                onDismiss = { startMenuOpen = false },
            )
        }
    }
}

/**
 * A program that never appeared (or exited badly) has to say why. The
 * text comes from where the failure is understood --
 * [dev.droidtop.runtime.ContainerTerminal.failureMessage] tells "the
 * package isn't in this container" apart from anything else,
 * [dev.droidtop.runtime.ContainerApplications.launch] quotes the program's
 * own last output -- rather than being invented here.
 */
@Composable
private fun BoxScope.LaunchFailureBanner(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 64.dp)
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onDismiss)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "A program didn't run",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Tap to dismiss",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Mirrors dev.droidtop.app.DesktopSessionState without :shell-desktop depending on :app. */
sealed interface DesktopSessionMessage {
    data object Idle : DesktopSessionMessage
    /** [detail]: the latest line the booting container reported (a first boot installs the desktop), if any. */
    data class Connecting(val detail: String? = null) : DesktopSessionMessage
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

        // One seat per live HostBridge, and one router driving it. Both are
        // keyed on hostBridge so a session that goes away and comes back
        // does not leave the old connection's held buttons behind.
        //
        // The seat comes from InputSeats rather than being constructed
        // here, because this surface is no longer the only thing driving
        // it: the second-screen trackpad and keyboard (docs/SPEC.md 6c)
        // reach the same container, and two seats over one bridge would
        // break the single normalized seat :input-seat exists to be.
        val seat = remember(hostBridge) { InputSeats.of(hostBridge) }
        val router = remember(hostBridge) { DesktopInputRouter().also { it.seat = seat } }

        // The router is inert with no seat, so tearing the session down is
        // just clearing it -- and clearing it releases whatever the
        // container still thinks is held.
        DisposableEffect(router) {
            onDispose { router.seat = null }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    router.attachTo(this)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            presentFailed = !hostBridge.presentOutput(primaryOutput, holder.surface)
                        }

                        // The only place the view's real size is known:
                        // width/height here are the surface's, which is not
                        // the panel size (the taskbar takes 48dp). The
                        // compositor is asked to make its output exactly
                        // this size, so a frame lands 1:1 on the view rather
                        // than being stretched, and the input transform is
                        // rebuilt with it. Both on every change, so a
                        // rotation or a lapdock resize cannot leave a stale
                        // size behind. Until the compositor has applied the
                        // new size the frame is scaled to fit, which the
                        // STRETCH transform already accounts for (it only
                        // hands the compositor ratios).
                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            val sized = hostBridge.setOutputSize(width, height)
                            router.transform = PointerTransform(
                                viewWidth = width,
                                viewHeight = height,
                                outputWidth = if (sized) width else primaryOutput.widthPx,
                                outputHeight = if (sized) height else primaryOutput.heightPx,
                            )
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            router.transform = null
                            router.releaseHeldInput()
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
                        "The first start downloads a Linux system and installs the desktop " +
                            "into it, which takes several minutes. On a rooted device, grant " +
                            "root access if droidtop asks for it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    sessionMessage.detail?.let { detail ->
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
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
private fun BoxScope.Taskbar(
    startMenuOpen: Boolean,
    onToggleStartMenu: () -> Unit,
    onOpenTerminal: (() -> Unit)?,
) {
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
        if (onOpenTerminal != null) {
            Button(onClick = onOpenTerminal, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Terminal")
            }
        }
        Button(onClick = { openContainers(context) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Containers")
        }
        // The mode switcher, by name: Desktop's only other route to the
        // Android home or Gaming was a long-press of Back (SPEC 2c).
        Button(onClick = { openModes(context) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Modes")
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
            val noInternet = if (
                status.network != dev.droidtop.runtime.systemstatus.NetworkKind.NONE && !status.validated
            ) " !" else ""
            val vpn = if (status.vpnActive) "  VPN" else ""
            val battery = status.batteryPercent?.let { "  $it%" + if (status.charging) "\u26A1" else "" } ?: ""
            // "!" = connected without validated internet (captive
            // portal); the popover's Network entry opens the system
            // sheet where signing in happens.
            Text(network + noInternet + vpn + battery, color = MaterialTheme.colorScheme.onSurface)
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
 * Reads the mode-specific preference set that :app's Desktop mode
 * settings catalog (DroidtopWideSettings) writes. No
 * compile-time dependency on :shell-default -- see :shell-gamepad's
 * equivalent GamingPrefs for the same reasoning -- so this reads the
 * shared [LAUNCHER_PREFS_FILE_NAME] SharedPreferences file instead.
 */
private object DesktopPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
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

private fun openModes(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "dev.droidtop.shell.standard.ModeSwitcherActivity")
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
private fun BoxScope.StartMenu(
    library: Library,
    loadLinuxApps: (suspend () -> List<ContainerApp>)?,
    onLaunchLinuxApp: ((ContainerApp) -> Unit)?,
    onLaunchFailure: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // The library as its index holds it (docs/SPEC.md 7g): shown at once
    // from the saved index, walking only what the index does not cover
    // (the app list), in the library's own scope rather than this menu's.
    val entries by library.backgroundScanState(START_MENU_KINDS).collectAsState()
    var linuxApps by remember { mutableStateOf<List<ContainerApp>>(emptyList()) }
    var linuxAppsError by remember { mutableStateOf<String?>(null) }
    val taskbarAtTop = DesktopPrefs.taskbarAtTop(context)

    LaunchedEffect(library) {
        library.scanInBackground(START_MENU_KINDS)
    }
    // Read again every time the menu opens: what is installed in the
    // container changes whenever the user installs something in it.
    LaunchedEffect(loadLinuxApps) {
        val load = loadLinuxApps ?: return@LaunchedEffect
        try {
            linuxApps = withContext(Dispatchers.IO) { load() }
            linuxAppsError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            linuxAppsError = t.message ?: t.toString()
        }
    }

    Box(
        modifier = Modifier
            .align(if (taskbarAtTop) Alignment.TopStart else Alignment.BottomStart)
            .padding(top = if (taskbarAtTop) 48.dp else 0.dp, bottom = if (taskbarAtTop) 0.dp else 48.dp)
            .width(320.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val currentEntries = entries
        LazyColumn(modifier = Modifier.padding(8.dp)) {
            // The primary container's own applications first: they are
            // what the desktop runs. Launched into the session, so their
            // windows appear on the desktop behind this menu.
            if (loadLinuxApps != null) {
                item(key = "linux-apps-header") { StartMenuHeader("Linux apps") }
                linuxAppsError?.let { message ->
                    item(key = "linux-apps-error") {
                        Text(
                            "Couldn't read the installed apps: $message",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                items(linuxApps, key = { "linux:" + it.id }) { app ->
                    // The app's own name, and under it what kind of program
                    // it is when the entry says (foot: "Terminal").
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLaunchLinuxApp?.invoke(app)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                    ) {
                        Text(app.name, color = MaterialTheme.colorScheme.onSurface)
                        app.genericName?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item(key = "library-header") { StartMenuHeader("Library") }
            }
            when {
                currentEntries == null -> item(key = "library-loading") {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(8.dp))
                }
                currentEntries.isEmpty() -> item(key = "library-empty") {
                    Text(
                        "Nothing in the library yet.",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                else -> items(currentEntries, key = { it.id }) { entry ->
                    Text(
                        entry.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .clickable {
                                // In the library's scope: closing the menu,
                                // which this same tap does, must not cancel it.
                                library.launchInBackground(entry.id) { result ->
                                    if (result is LaunchResult.Refused) onLaunchFailure(result.reason)
                                }
                                onDismiss()
                            },
                    )
                }
            }
        }
    }
}

/** Everything the library holds: the Start menu is the Desktop's one list of it. */
private val START_MENU_KINDS: Set<LibraryEntryKind> = LibraryEntryKind.entries.toSet()

@Composable
private fun StartMenuHeader(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}
