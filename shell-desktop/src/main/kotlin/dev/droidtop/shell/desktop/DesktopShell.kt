package dev.droidtop.shell.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.hostbridge.Toplevel
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.input.InputSeats
import dev.droidtop.input.PointerTransform
import dev.droidtop.library.Library
import dev.droidtop.library.LaunchResult
import dev.droidtop.library.LibraryKinds
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.runtime.DesktopLaunchRequests
import dev.droidtop.runtime.DesktopScale
import dev.droidtop.runtime.DisplayOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.integrations.PluginHub
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.pluginhost.PluginModes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.PcGameStandalone
import dev.droidtop.shell.gamepad.pc.PcLaunchOfferSheet
import android.widget.Toast
import dev.droidtop.library.TaskbarPin
import dev.droidtop.library.TaskbarPins
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.runtime.tasks.CloseOutcome
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.TaskPolicy
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.AppIcon
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.hosted.HostedArt
import dev.droidtop.shell.gamepad.query.LauncherSearchApp
import dev.droidtop.shell.gamepad.query.LauncherSearchScreen
import dev.droidtop.library.StartMenuSections
import dev.droidtop.shell.gamepad.hosted.HostedListSheet
import dev.droidtop.shell.gamepad.hosted.HostedRow
import dev.droidtop.shell.gamepad.hosted.PadLegend
import dev.droidtop.shell.gamepad.hosted.QuickMenuStandalone
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.pc.rememberPcLaunch

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
    /**
     * The compositor the session is running (DesktopSessionState.Connected's
     * copy of the provisioning plan's compositorCommand, null with no session).
     * sway ignores zwlr_foreign_toplevel_handle_v1's set_minimized/
     * unset_minimized, so under it the taskbar hides its minimize affordances
     * rather than offering an action that does nothing (Droidtop/tracker#145);
     * labwc implements the requests. The Wayland protocol has no capability
     * query, so the session says which compositor it started instead.
     */
    compositorCommand: String? = null,
    sessionMessage: DesktopSessionMessage = DesktopSessionMessage.Idle,
    onOpenTerminal: (() -> Unit)? = null,
    loadLinuxApps: (suspend () -> List<ContainerApp>)? = null,
    onLaunchLinuxApp: ((ContainerApp) -> Unit)? = null,
    launchFailure: String? = null,
    onDismissLaunchFailure: () -> Unit = {},
    /** A Start-menu launch that was refused: shown in the same banner as [launchFailure]. */
    onLaunchFailure: (String) -> Unit = {},
    /** Starts the desktop session: the button on the not-started and failed screens. */
    onStartSession: () -> Unit = {},
    /** Opens Desktop setup: the button on the not-set-up and failed screens (Droidtop/tracker#370). */
    onOpenSetup: () -> Unit = {},
) {
    var startMenuOpen by remember { mutableStateOf(false) }
    // The Quick Menu is the tray's, and the windows sheet the taskbar's window list, each a sheet a pad
    // drives (docs/SPEC.md 2b "Desktop chrome with a pad", Droidtop/tracker#350).
    var quickMenuOpen by remember { mutableStateOf(false) }
    var windowsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    // The pad's buttons that belong to droidtop's chrome rather than to the desktop arrive from the activity.
    LaunchedEffect(Unit) {
        DesktopPadRoutes.requests.collect { route ->
            when (route) {
                DesktopPadRoutes.Route.START_MENU -> startMenuOpen = true
                DesktopPadRoutes.Route.QUICK_MENU -> quickMenuOpen = true
                DesktopPadRoutes.Route.WINDOWS -> windowsOpen = true
                DesktopPadRoutes.Route.SEARCH -> {
                    searchText = ""
                    searchOpen = true
                }
            }
        }
    }
    // One listener on the bridge feeds the taskbar and the windows sheet alike.
    val toplevels = rememberToplevels(hostBridge)
    // What Android is running that droidtop started (a game or an app launched from here is a fullscreen
    // Activity over the desktop, not a window the compositor knows), polled only while Desktop shows.
    val androidApps = rememberAndroidApps()
    // A library entry launches the way it launches everywhere (Library.launch, SPEC 2b). A PC or
    // engine game first takes Gaming's primary-action rule (a store game that is not installed offers
    // the install) and has Gaming's page and menu, opened from the Start menu row's menu
    // (Droidtop/tracker#349). Both live here, not in the Start menu, which closes as they open.
    val context = LocalContext.current
    var pageId by remember { mutableStateOf<String?>(null) }
    val openDownloads: () -> Unit = { context.startActivity(Place.openIntent(context, Place.DOWNLOADS)) }
    val launchById: (String) -> Unit = { id ->
        // In the library's scope: closing the menu, which the same tap does, must not cancel it.
        library.launchInBackground(id) { result ->
            if (result is LaunchResult.Refused) onLaunchFailure(result.reason)
        }
    }
    val launchEntry: (LibraryEntry) -> Unit = { entry -> launchById(entry.id) }
    val pcLaunch = rememberPcLaunch(onLaunch = launchEntry, onOpenDownloads = openDownloads)
    val playEntry: (LibraryEntry) -> Unit = { entry -> if (entry.isPcOrEngineGame) pcLaunch.launch(entry) else launchEntry(entry) }

    // The container's own applications (docs/SPEC.md 2a), read when a session is live and again every time
    // the Start menu opens, since what is installed in the container changes whenever the user installs
    // something in it. They live here, not in the menu, because a pinned one on the taskbar needs them to start.
    var linuxApps by remember { mutableStateOf<List<ContainerApp>>(emptyList()) }
    var linuxAppsError by remember { mutableStateOf<String?>(null) }
    val loadApps by rememberUpdatedState(loadLinuxApps)
    val launchLinuxApp by rememberUpdatedState(onLaunchLinuxApp)
    val sessionLive = loadLinuxApps != null
    LaunchedEffect(sessionLive, startMenuOpen) {
        val load = loadApps
        if (load == null) {
            linuxApps = emptyList()
            linuxAppsError = null
            return@LaunchedEffect
        }
        if (!startMenuOpen && linuxApps.isNotEmpty()) return@LaunchedEffect
        try {
            linuxApps = withContext(Dispatchers.IO) { load() }
            linuxAppsError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            linuxAppsError = t.message ?: t.toString()
        }
    }

    // The taskbar's pins (docs/SPEC.md 2b): a Linux app or a library entry, started the way the Start menu starts it.
    val pins = rememberPins()
    val openPin: (TaskbarPin) -> Unit = { pin ->
        val entryId = TaskbarPins.entryIdOf(pin.key)
        if (entryId == null) {
            val app = linuxApps.firstOrNull { TaskbarPins.linuxKey(it.id) == pin.key }
            val launch = launchLinuxApp
            if (app != null && launch != null) {
                launch(app)
            } else {
                Toast.makeText(context, "${pin.title} runs on the desktop, which is not running.", Toast.LENGTH_LONG).show()
            }
        } else {
            val entry = library.backgroundScanState(START_MENU_KINDS).value?.firstOrNull { it.id == entryId }
            if (entry != null) playEntry(entry) else launchById(entryId)
        }
    }

    // A game the container's own menus asked for (docs/SPEC.md 2a, the launch helper): played exactly
    // as a Start menu tap plays it. An id the published list does not hold (yet) launches by id.
    val currentPlay by rememberUpdatedState(playEntry)
    LaunchedEffect(library) {
        DesktopLaunchRequests.requests.collect { id ->
            val entry = library.backgroundScanState(LibraryKinds.GAMES).value?.firstOrNull { it.id == id }
            if (entry != null) currentPlay(entry) else launchById(id)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesktopViewport(hostBridge, primaryOutput, sessionMessage, onStartSession, onOpenSetup)

        Taskbar(
            hostBridge = hostBridge,
            toplevels = toplevels,
            androidApps = androidApps,
            pins = pins.list,
            onOpenPin = openPin,
            onUnpin = { pins.toggle(it) },
            compositorCommand = compositorCommand,
            onOpenStartMenu = { startMenuOpen = true },
            onOpenQuickMenu = { quickMenuOpen = true },
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
                sessionLive = sessionLive,
                linuxApps = linuxApps,
                linuxAppsError = linuxAppsError,
                pins = pins,
                onLaunchLinuxApp = onLaunchLinuxApp,
                onPlay = playEntry,
                onOpenPage = { entry -> pageId = entry.id },
                onOpenTerminal = onOpenTerminal,
                onSearch = { text ->
                    searchText = text
                    searchOpen = true
                },
                onDismiss = { startMenuOpen = false },
            )
        }
        if (searchOpen) {
            DesktopSearch(
                library = library,
                linuxApps = linuxApps,
                initialText = searchText,
                onLaunchLinuxApp = onLaunchLinuxApp,
                onPlay = playEntry,
                onDismiss = { searchOpen = false },
            )
        }
        if (quickMenuOpen) {
            QuickMenuStandalone(
                library = library,
                onOpenStartMenu = {
                    quickMenuOpen = false
                    startMenuOpen = true
                },
                onDismiss = { quickMenuOpen = false },
            )
        }
        if (windowsOpen) {
            WindowsSheet(
                hostBridge = hostBridge,
                toplevels = toplevels,
                androidApps = androidApps,
                compositorCommand = compositorCommand,
                onDismiss = { windowsOpen = false },
            )
        }

        pageId?.let { id ->
            PcGameStandalone(
                library = library,
                entryId = id,
                onLaunch = launchEntry,
                onOpenDownloads = openDownloads,
                onClose = { pageId = null },
            )
        }
        PcLaunchOfferSheet(pcLaunch)
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
    /** No desktop image has been chosen, so there is no session to start until Desktop setup runs. */
    data object NeedsSetup : DesktopSessionMessage
}

@Composable
private fun BoxScope.DesktopViewport(
    hostBridge: HostBridge?,
    primaryOutput: DisplayOutput?,
    sessionMessage: DesktopSessionMessage,
    onStartSession: () -> Unit,
    onOpenSetup: () -> Unit,
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
                            val metrics = context.resources.displayMetrics
                            val scale = DesktopScale.resolve(DesktopPrefs.scale(context), metrics.xdpi, metrics.densityDpi)
                            val sized = hostBridge.setOutputSize(width, height, scale)
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
                    // The failure may be the setup itself (an image the catalog no longer has), so the
                    // way to fix it sits beside the retry rather than in the sentence (Droidtop/tracker#370).
                    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onStartSession) { Text("Try again") }
                        androidx.compose.material3.OutlinedButton(onClick = onOpenSetup) { Text("Desktop setup") }
                    }
                }
                is DesktopSessionMessage.NeedsSetup -> {
                    // Nothing to start yet: the one next step is choosing the Linux system the
                    // desktop runs, so the page offers exactly that (Droidtop/tracker#370).
                    Text("Set up the desktop", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose the Linux system it runs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onOpenSetup, modifier = Modifier.padding(top = 16.dp)) { Text("Desktop setup") }
                }
                is DesktopSessionMessage.Idle -> {
                    // The one thing to do here is start it, so the screen
                    // offers exactly that (rig, dq-desk2-02: the taskbar's
                    // "Start" is the Start menu, not the session).
                    Text("The desktop is not running", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "It was stopped. Start it to see your desktop here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onStartSession, modifier = Modifier.padding(top = 16.dp)) { Text("Start the desktop") }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.Taskbar(
    hostBridge: HostBridge?,
    toplevels: List<Toplevel>,
    androidApps: List<RunningApp>,
    pins: List<TaskbarPin>,
    onOpenPin: (TaskbarPin) -> Unit,
    onUnpin: (TaskbarPin) -> Unit,
    compositorCommand: String?,
    onOpenStartMenu: () -> Unit,
    onOpenQuickMenu: () -> Unit,
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
    val atTop = DesktopPrefs.taskbarAtTop(context)
    // With a pad in hand the bar names the four buttons that reach droidtop's chrome (the activity
    // answers them, see DesktopPadRoutes), on a strip of its own so the bar keeps its width for the
    // window list on a 768 dp console.
    val padPresent = currentShellWindow().padPresent
    val legend: @Composable () -> Unit = {
        if (padPresent) {
            PadLegend(
                items = listOf(
                    GamepadAction.START to "Start menu",
                    GamepadAction.R2 to "Quick menu",
                    GamepadAction.L to "Windows",
                    GamepadAction.R to "Search",
                ),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }

    Column(modifier = Modifier.align(if (atTop) Alignment.TopStart else Alignment.BottomStart).fillMaxWidth()) {
        if (!atTop) legend()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskbarButton(onClick = onOpenStartMenu) {
                Text("Start")
            }
            Spacer(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
            TaskbarPinChips(pins, onOpenPin, onUnpin)
            TaskbarWindowList(hostBridge, toplevels, androidApps, compositorCommand, modifier = Modifier.weight(1f))
            if (onOpenTerminal != null) {
                TaskbarButton(onClick = onOpenTerminal) {
                    Text("Terminal")
                }
            }
            TaskbarButton(onClick = { openContainers(context) }) {
                Text("Containers")
            }
            // The mode switcher, by name: Desktop's only other route to the
            // Android home or Gaming was a long-press of Back (SPEC 2c).
            TaskbarButton(onClick = { openModes(context) }) {
                Text("Modes")
            }
            TaskbarButton(onClick = { openSettings(context) }) {
                Text("Settings")
            }
            PluginTaskbarItems()
            ClipboardNotice()
            SystemTray(onClick = onOpenQuickMenu)
            Text(
                clockText,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (atTop) legend()
    }
}

/**
 * A taskbar button with the narrow padding a bar needs. The stock Button's
 * 24dp of content padding and 8dp of margin per side left the window list
 * (the weighted slot beside them) zero width on a 1080p tablet-class display,
 * so no window row could show even with windows open (Droidtop/tracker#94).
 */
@Composable
private fun TaskbarButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 3.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) { content() }
}

/**
 * The taskbar's real cross-container task manager (docs/SPEC.md's Desktop
 * shell section, Droidtop/tracker#94): one row per toplevel host-bridge
 * reports over wlr-foreign-toplevel-management. Tap activates it (raises +
 * focuses); tapping the already-activated one minimizes it instead, so a
 * single action both switches windows and gets one out of the way, the same
 * "click its taskbar button again" convention as a desktop OS. Long-press
 * opens a small menu for Restore/Minimize and Close -- the destructive one
 * deliberately not on a plain tap.
 *
 * Both minimize affordances exist only where they work. sway ignores the
 * protocol's set_minimized and unset_minimized requests outright, so under
 * it they were dead UI: the second tap and the menu's Minimize promised
 * something that never happened (rig, fix2-t5.png in verify-2026-09-29,
 * Droidtop/tracker#145). labwc implements the request (its wlr-foreign.c
 * handle_request_minimize calls view_minimize), and there is no
 * protocol-level capability query to ask with, so the one fact the shell
 * keys on is [compositorCommand]: "sway" drops the second-tap minimize and
 * hides the menu's Restore/Minimize row; anything else keeps both.
 *
 * Empty (no row shown at all, not even a placeholder) when [hostBridge] is
 * null or the compositor never advertised the protocol: an empty window
 * list and "there is no session"/"the protocol isn't there" look the same
 * to a user with nothing open, and this row isn't where either gets
 * explained.
 *
 * "Move to another output" (an earlier draft of docs/SPEC.md's Desktop
 * section) is NOT built here: wlr-foreign-toplevel-management-unstable-v1
 * has no such request (only activate/set_minimized/unset_minimized/close/
 * set_fullscreen/set_rectangle -- checked against the protocol XML), and
 * host-bridge's own output handling is still single-output-only (see
 * wayland_client.cpp's WaylandGlobals comment). Real "move to output" needs
 * real multi-output support first; SPEC.md is corrected alongside this
 * change to stop claiming it as already built.
 */
@Composable
private fun TaskbarWindowList(
    hostBridge: HostBridge?,
    toplevels: List<Toplevel>,
    androidApps: List<RunningApp>,
    compositorCommand: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Sway's dead set_minimized (see the comment above) makes minimize a
    // compositor-dependent affordance, not a universal one.
    val minimizeSupported = compositorCommand != "sway"

    if (toplevels.isEmpty() && androidApps.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }

    LazyRow(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        items(toplevels, key = { it.id }) { toplevel ->
            TaskbarWindowRow(
                toplevel = toplevel,
                showMinimize = minimizeSupported,
                onTap = {
                    if (toplevel.activated) {
                        if (minimizeSupported) hostBridge?.setToplevelMinimized(toplevel.id, true)
                    } else {
                        if (toplevel.minimized) hostBridge?.setToplevelMinimized(toplevel.id, false)
                        hostBridge?.activateToplevel(toplevel.id)
                    }
                },
                onClose = { hostBridge?.closeToplevel(toplevel.id) },
            )
        }
        // The Android apps and games started from here, after the container's windows (Droidtop/tracker#352).
        items(androidApps, key = { "app:" + it.packageName + "/" + it.displayId }) { app ->
            TaskbarAppRow(
                app = app,
                onOpen = { openAndroidApp(context, app) },
                onClose = { scope.launch { closeAndroidApp(context, app) } },
            )
        }
    }
}

/**
 * The container's windows as the bridge reports them, kept current by the bridge's one change listener
 * (it has room for just one, so the taskbar and the windows sheet share this list). Empty with no bridge.
 */
@Composable
private fun rememberToplevels(hostBridge: HostBridge?): List<Toplevel> {
    var toplevels by remember(hostBridge) { mutableStateOf(hostBridge?.toplevels() ?: emptyList()) }
    DisposableEffect(hostBridge) {
        val mainHandler = Handler(Looper.getMainLooper())
        hostBridge?.toplevelsChangedListener = {
            // Fires from a native worker thread (see HostBridge.kt).
            mainHandler.post { toplevels = hostBridge.toplevels() }
        }
        onDispose { hostBridge?.toplevelsChangedListener = null }
    }
    return toplevels
}

/**
 * Desktop's search (docs/SPEC.md 12a "One search", Droidtop/tracker#351): the same dialog the PC library, the
 * console lists and Standard's drawer open, with this surface's local rows: the library's entries (the
 * scan the Start menu observes, only while the search is open) and the container's own desktop entries as the
 * apps. A Linux app starts in the session, a game takes the one launch rule, a download source's result opens
 * its detail. Nothing here loads the launcher's model (Desktop does not have one).
 */
@Composable
private fun DesktopSearch(
    library: Library,
    linuxApps: List<ContainerApp>,
    initialText: String,
    onLaunchLinuxApp: ((ContainerApp) -> Unit)?,
    onPlay: (LibraryEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val games by library.backgroundScanState(START_MENU_KINDS).collectAsState()
    LaunchedEffect(library) { library.scanInBackground(START_MENU_KINDS) }
    val apps by rememberUpdatedState(linuxApps)
    val launch by rememberUpdatedState(onLaunchLinuxApp)
    LauncherSearchScreen(
        initialText = initialText,
        games = games,
        findApps = { text ->
            apps.filter { StartMenuSections.appMatches(it.name, it.genericName, text) }.map { app ->
                LauncherSearchApp(key = "linux:" + app.id, title = app.name, icon = null, open = { launch?.invoke(app) })
            }
        },
        onPlay = onPlay,
        onDismiss = onDismiss,
    )
}

/**
 * The Android apps and games droidtop started and that are still running ([TaskManager.snapshot], the one
 * task list the Quick Menu, the companion and Standard's home read too), kept current while Desktop is
 * showing and only then. A game launched from the Start menu is a fullscreen Activity over the desktop,
 * which the compositor's window list cannot show, so without this the bar listed neither it nor any
 * Android app (Droidtop/tracker#352). Without Shizuku the list holds just the apps droidtop opened itself.
 */
@Composable
private fun rememberAndroidApps(): List<RunningApp> {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TaskManager.watch(context) }
    val snapshot by TaskManager.snapshot.collectAsState()
    return remember(snapshot) { snapshot?.apps.orEmpty() }
}

private fun openAndroidApp(context: Context, app: RunningApp) {
    TaskActions.bringTo(context, app.packageName, app.displayId)?.let {
        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
    }
}

/** Closes through the task manager's strongest path and says so only when that did not end it. */
private suspend fun closeAndroidApp(context: Context, app: RunningApp) {
    val outcome = TaskManager.close(context, app.packageName)
    if (outcome !is CloseOutcome.Closed) Toast.makeText(context, outcome.text, Toast.LENGTH_LONG).show()
}

/**
 * The taskbar's window list as a sheet a pad drives (Droidtop/tracker#350): A raises a window (restoring it
 * first when it is minimized) or opens an Android app, X closes it, and the rest of the rules are the taskbar's own, including
 * that sway cannot minimize.
 */
@Composable
private fun WindowsSheet(
    hostBridge: HostBridge?,
    toplevels: List<Toplevel>,
    androidApps: List<RunningApp>,
    compositorCommand: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember(toplevels, androidApps) {
        toplevels.map { toplevel ->
            HostedRow(
                key = "window:" + toplevel.id,
                title = toplevel.title.ifBlank { toplevel.appId.ifBlank { "(untitled window)" } },
                subtitle = when {
                    toplevel.minimized -> "Minimized"
                    toplevel.activated -> "In front"
                    else -> null
                },
                onSelect = {
                    if (toplevel.minimized && compositorCommand != "sway") hostBridge?.setToplevelMinimized(toplevel.id, false)
                    hostBridge?.activateToplevel(toplevel.id)
                    onDismiss()
                },
                onToggle = { hostBridge?.closeToplevel(toplevel.id) },
            )
        } + androidApps.map { app ->
            HostedRow(
                key = "app:" + app.packageName + "/" + app.displayId,
                title = app.label,
                subtitle = "Android app, " + TaskPolicy.displayLabel(app.displayId),
                onSelect = {
                    openAndroidApp(context, app)
                    onDismiss()
                },
                onToggle = { scope.launch { closeAndroidApp(context, app) } },
            )
        }
    }
    HostedListSheet(
        title = "Windows",
        rows = rows,
        onClose = onDismiss,
        emptyText = "Nothing is open on the desktop.",
        selectLabel = "Bring to front",
        toggleLabel = "Close window",
    )
}

@Composable
private fun TaskbarWindowRow(toplevel: Toplevel, showMinimize: Boolean, onTap: () -> Unit, onClose: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Text(
            toplevel.title.ifBlank { toplevel.appId.ifBlank { "(untitled window)" } },
            color = if (toplevel.activated) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (toplevel.activated) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 180.dp)
                .combinedClickable(onClick = onTap, onLongClick = { menuOpen = true })
                .background(
                    if (toplevel.minimized) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (showMinimize) {
                DropdownMenuItem(
                    text = { Text(if (toplevel.minimized) "Restore" else "Minimize") },
                    onClick = { menuOpen = false; onTap() },
                )
            }
            DropdownMenuItem(
                text = { Text("Close") },
                onClick = { menuOpen = false; onClose() },
            )
        }
    }
}

/**
 * What is pinned to the taskbar (docs/SPEC.md 2b, Droidtop/tracker#348): each pin as its picture, when it has
 * one, and its name, ahead of the window list. A tap starts it, a long press offers Unpin. The pins sit in
 * a row of their own that is capped, so a long list of them cannot take the window list's room.
 */
@Composable
private fun TaskbarPinChips(pins: List<TaskbarPin>, onOpen: (TaskbarPin) -> Unit, onUnpin: (TaskbarPin) -> Unit) {
    if (pins.isEmpty()) return
    LazyRow(modifier = Modifier.widthIn(max = 260.dp), verticalAlignment = Alignment.CenterVertically) {
        items(pins, key = { it.key }) { pin ->
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .widthIn(max = 140.dp)
                        .combinedClickable(onClick = { onOpen(pin) }, onLongClick = { menuOpen = true })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    pin.art?.let { HostedArt(it, size = 24.dp) }
                    Text(
                        pin.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Unpin") },
                        onClick = { menuOpen = false; onUnpin(pin) },
                    )
                }
            }
        }
    }
}

/** An Android app or game in the bar: the app's icon and name; a tap opens it, a long press offers Close. */
@Composable
private fun TaskbarAppRow(app: RunningApp, onOpen: () -> Unit, onClose: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .widthIn(max = 180.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            AppIcon(app.packageName, size = 20.dp)
            Text(
                app.label,
                color = if (app.visible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (app.visible) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Close") },
                onClick = { menuOpen = false; onClose() },
            )
        }
    }
}

/**
 * Shown only while another keyboard than droidtop's own is active: copying in an Android app
 * then reaches the container only when droidtop's window regains focus (Android lets just the
 * focused app or the active keyboard read the clipboard), so a paste can lag a copy. The menu
 * says why, using the bridge's own wording, and opens the system keyboard switcher; it is the
 * only place the state shows and it never blocks anything (docs/SPEC.md 6a, 6d).
 */
@Composable
private fun ClipboardNotice() {
    val live by dev.droidtop.hostbridge.ClipboardBridge.androidReadsLive.collectAsState()
    if (live) return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.TextButton(onClick = { open = !open }) {
            Text("Clipboard: on focus", color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                dev.droidtop.hostbridge.ClipboardAccess.WHY_BLOCKED,
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 320.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            DropdownMenuItem(
                text = { Text("Switch keyboard…") },
                onClick = { open = false; dev.droidtop.library.settings.Keyboards.showPicker(context) },
            )
        }
    }
}

/**
 * The desktop's system tray, over the shared
 * [dev.droidtop.runtime.systemstatus.SystemStatus] core: the readout sits in the bar and the Quick Menu
 * is behind it. Volume, brightness, Do Not Disturb, the network and Bluetooth panels and the notification
 * list are that menu's sections, the same ones Gaming's R2 shows, so the tray keeps no control panel of
 * its own; the readout also counts the notifications waiting (Droidtop/tracker#352) (docs/SPEC.md 2b "Desktop chrome with a pad", Droidtop/tracker#350). Data shared, chrome per
 * surface, the same split the settings catalogs use.
 */
@Composable
private fun SystemTray(onClick: () -> Unit) {
    val context = LocalContext.current
    val status by remember { dev.droidtop.runtime.systemstatus.SystemStatus.flow(context) }
        .collectAsState(initial = dev.droidtop.runtime.systemstatus.SystemStatus.snapshot(context))
    // What is waiting, so the tray says so without opening the Quick Menu, whose first section it is once
    // notification access is granted. Ongoing notifications (a foreground service's) are not waiting for anyone.
    val waiting = NotificationsStore.items.collectAsState().value.count { it.clearable }
    androidx.compose.material3.TextButton(onClick = onClick) {
        val network = when (status.network) {
            dev.droidtop.runtime.systemstatus.NetworkKind.WIFI ->
                "◤" + (status.wifiLevel?.let { " $it/4" } ?: "")
            dev.droidtop.runtime.systemstatus.NetworkKind.ETHERNET -> "ETH"
            dev.droidtop.runtime.systemstatus.NetworkKind.CELLULAR -> "LTE"
            dev.droidtop.runtime.systemstatus.NetworkKind.NONE -> "✕"
        }
        val noInternet = if (
            status.network != dev.droidtop.runtime.systemstatus.NetworkKind.NONE && !status.validated
        ) " !" else ""
        val vpn = if (status.vpnActive) "  VPN" else ""
        val battery = status.batteryPercent?.let { "  $it%" + if (status.charging) "⚡" else "" } ?: ""
        // "!" = connected without validated internet (captive portal); the Quick Menu's System
        // section opens the system sheet where signing in happens.
        val notifications = if (waiting > 0) "  $waiting new" else ""
        Text(network + noInternet + vpn + battery + notifications, color = MaterialTheme.colorScheme.onSurface)
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

    /** The Desktop scale setting as stored ([DesktopScale.KEY]); null when never set. */
    fun scale(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(DesktopScale.KEY, null)
}

// The container manager in droidtop's screen host (CatalogScreenLink): an
// action, because :shell-desktop has no compile-time dependency on :app.
internal fun openContainers(context: Context) {
    context.startActivity(CatalogScreenLink.intent(context, CONTAINERS_SCREEN_ID))
}

/** `ContainersCatalog.SCREEN_ID` in :app, which this module cannot see. */
private const val CONTAINERS_SCREEN_ID = "containers"

internal fun openModes(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "dev.droidtop.app.ModeSwitcherActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

internal fun openSettings(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
        putExtra(":settings:fragment", "app.murinelauncher.settings.SettingsDesktopFragment")
        // Settings has its own task (SPEC 2c): open this page fresh.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(intent)
}

private fun formatClock(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

/** How often the taskbar checks again whether any plugin has a panel for Desktop; also when the bar is first drawn. */
private const val PLUGINS_RECHECK_MS = 60_000L

/**
 * "Plugins" on the taskbar (docs/plugin-api.md 1.9): Desktop's way to every plugin's panel, the same list Gaming's
 * Quick Menu shows, drawn in droidtop's plugin window. Absent while no plugin has a panel for Desktop, so the bar shows
 * nothing that does nothing. The check reads manifests and grants only, never a plugin.
 */
@Composable
private fun PluginTaskbarItems() {
    val context = LocalContext.current
    var hasPanels by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            hasPanels = withContext(Dispatchers.IO) { PluginPanels.panelsFor(context, PluginModes.DESKTOP).isNotEmpty() }
            delay(PLUGINS_RECHECK_MS)
        }
    }
    if (hasPanels) {
        TaskbarButton(
            onClick = {
                PluginHub.open(
                    context,
                    PluginPanels.quickMenuScreen(
                        game = null,
                        showManage = true,
                        onReplyScreen = { screen -> PluginHub.open(context, screen) },
                        surface = PluginModes.Surfaces.DESKTOP_TASKBAR,
                    ),
                )
            },
        ) { Text("Plugins") }
    }
}
