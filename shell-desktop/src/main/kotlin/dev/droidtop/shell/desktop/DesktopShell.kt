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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.DesktopWindows
import dev.droidtop.hostbridge.HiddenWindow
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.hostbridge.SwayWindow
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.integrations.PluginHub
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.PcGameStandalone
import dev.droidtop.shell.gamepad.pc.PcLaunchOfferSheet
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
import dev.droidtop.shell.gamepad.ActionNote
import dev.droidtop.shell.gamepad.ActionRow
import dev.droidtop.shell.gamepad.ActionSheet
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.StatusCluster
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
     * The session's window actions: raise, minimize and restore for whichever
     * compositor it started (Droidtop/tracker#145, #391). Null with no session.
     */
    windows: DesktopWindows? = null,
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
    /** Start > System's Restart desktop, Stop desktop and Lock screen (Droidtop/tracker#687); null leaves the row out. */
    onRestartSession: (() -> Unit)? = null,
    onStopSession: (() -> Unit)? = null,
    onLockScreen: (() -> Unit)? = null,
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
    LaunchedEffect(windows, toplevels) { windows?.forget(toplevels) }
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
            when (result) {
                is LaunchResult.Refused -> onLaunchFailure(result.reason)
                // Desktop's own rows take the install offer first (pcLaunch, below); one that reaches here is said.
                is LaunchResult.NeedsInstall -> onLaunchFailure("${result.title} isn't installed yet: open its page to install it")
                LaunchResult.Launched -> Unit
            }
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
                dev.droidtop.library.settings.Notices.flash("${pin.title} runs on the desktop, which is not running.", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context)
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
    // The desktop's launcher key (Super+D in sway) opens this Start menu, the desktop's one launcher (#683).
    LaunchedEffect(Unit) {
        DesktopLaunchRequests.startMenuRequests.collect { startMenuOpen = true }
    }

    // Desktop's chrome wears the Gaming theme (Droidtop/tracker#555 D4): the bar, its sheets and the Quick Menu
    // read the same palette Gaming does, through MaterialTheme and MenuTokens alike, instead of stock Material.
    dev.droidtop.shell.gamepad.GamingTheme {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DesktopViewport(hostBridge, primaryOutput, sessionMessage, onStartSession, onOpenSetup)

        Taskbar(
            hostBridge = hostBridge,
            toplevels = toplevels,
            androidApps = androidApps,
            // A Linux pin made before its app had an icon takes the icon it has now (Droidtop/tracker#689).
            pins = remember(pins.list, linuxApps) { withLinuxIcons(pins.list, linuxApps) },
            windowIcons = remember(linuxApps) { windowIcons(linuxApps) },
            onOpenPin = openPin,
            onUnpin = { pins.toggle(it) },
            windows = windows,
            onOpenStartMenu = { startMenuOpen = true },
            onOpenQuickMenu = { quickMenuOpen = true },
        )

        launchFailure?.let { message ->
            LaunchFailureNotice(message = message, onDismiss = onDismissLaunchFailure)
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
                onRestartDesktop = onRestartSession,
                onStopDesktop = onStopSession,
                onLockScreen = onLockScreen,
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
                windows = windows,
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
}

/**
 * A program that never appeared (or exited badly) has to say why, as a caution notice at the top of the desktop
 * (docs/SPEC.md 7k, "Notices"). The text comes from where the failure is understood --
 * [dev.droidtop.runtime.ContainerTerminal.failureMessage] tells "the package isn't in this container" apart from
 * anything else, [dev.droidtop.runtime.ContainerApplications.launch] quotes the program's own last output -- rather
 * than being invented here. Dismiss clears the failure it reports.
 */
@Composable
private fun BoxScope.LaunchFailureNotice(message: String, onDismiss: () -> Unit) {
    dev.droidtop.ui.shared.NoticeCard(
        notice = dev.droidtop.library.settings.Notice(
            tone = dev.droidtop.library.settings.NoticeTone.Caution,
            title = "A program didn't run",
            body = message.take(dev.droidtop.library.settings.Notice.BODY_MAX),
            details = message,
            action = dev.droidtop.library.settings.NoticeAction("Dismiss") { onDismiss() },
        ),
        placement = dev.droidtop.library.settings.NoticePlacement.Page,
        onAction = onDismiss,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
            .widthIn(max = 640.dp),
    )
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
        // The pad's chords (docs/SPEC.md 6b "Pad chords", Droidtop/tracker#383): the bundled layout, read off the main
        // thread; until it is in, the pad behaves as it always has.
        val context = LocalContext.current
        LaunchedEffect(router) {
            router.chords = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.resources.openRawResource(dev.droidtop.input.R.raw.desktop_pad_chords).bufferedReader().use {
                        dev.droidtop.mapping.ControllerLayout.parse(it.readText())
                    }
                }.onFailure { android.util.Log.w("DesktopShell", "The pad chord layout did not load", it) }.getOrNull()
            }
        }

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
                            // Below full resolution the output is asked for fewer pixels than the view
                            // has and SurfaceFlinger scales the buffer up (Droidtop/tracker#426); the
                            // scale shrinks with it so the layout keeps its size.
                            val factor = DesktopScale.resolutionFactor(DesktopPrefs.resolution(context))
                            val (outW, outH) = DesktopScale.outputSize(width, height, factor)
                            val sized = hostBridge.setOutputSize(outW, outH, DesktopScale.outputScale(scale, factor))
                            router.transform = PointerTransform(
                                viewWidth = width,
                                viewHeight = height,
                                outputWidth = if (sized) outW else primaryOutput.widthPx,
                                outputHeight = if (sized) outH else primaryOutput.heightPx,
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
                Text(stringResource(R.string.desktop_output_failed), color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.desktop_output_failed_hint),
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
                    Text(stringResource(R.string.desktop_starting), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    // What is going on and how long it takes, nothing about root or the machinery (Droidtop/tracker#693):
                    // droidtop never asks for root itself, and the line under it is the boot's own progress.
                    Text(
                        stringResource(R.string.desktop_starting_detail),
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
                    Text(stringResource(R.string.desktop_start_failed), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
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
                        Button(onClick = onStartSession) { Text(stringResource(R.string.desktop_try_again)) }
                        androidx.compose.material3.OutlinedButton(onClick = onOpenSetup) { Text(stringResource(R.string.desktop_setup)) }
                    }
                }
                is DesktopSessionMessage.NeedsSetup -> {
                    // Nothing to start yet: the one next step is choosing the Linux system the
                    // desktop runs, so the page offers exactly that (Droidtop/tracker#370).
                    Text(stringResource(R.string.desktop_set_up), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose the Linux system it runs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onOpenSetup, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.desktop_setup)) }
                }
                is DesktopSessionMessage.Idle -> {
                    // The one thing to do here is start it, so the screen
                    // offers exactly that (rig, dq-desk2-02: the taskbar's
                    // "Start" is the Start menu, not the session).
                    Text(stringResource(R.string.desktop_not_running), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "It was stopped. Start it to see your desktop here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onStartSession, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.desktop_start)) }
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
    windows: DesktopWindows?,
    onOpenStartMenu: () -> Unit,
    windowIcons: Map<String, String> = emptyMap(),
    onOpenQuickMenu: () -> Unit,
) {
    val context = LocalContext.current
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
                .background(dev.droidtop.shell.gamepad.MenuTokens.Ground),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskbarButton(onClick = onOpenStartMenu) {
                Text(stringResource(R.string.desktop_start_menu))
            }
            Spacer(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.outline))
            TaskbarPinChips(pins, onOpenPin, onUnpin)
            TaskbarWindowList(hostBridge, toplevels, androidApps, windows, windowIcons, modifier = Modifier.weight(1f))
            // What only Android can do stays on droidtop's bar (Droidtop/tracker#555 D4): Terminal, Containers
            // and Settings are Start's System rows, and the clock is the panel's (Waybar's custom/clock, or
            // swaybar's status line), so the screen has one. The mode switcher stays, by name: Desktop's only
            // other route to the Android home or Gaming is a long-press of Back (SPEC 2c).
            TaskbarButton(onClick = { openModes(context) }) {
                Text(stringResource(R.string.desktop_modes))
            }
            PluginTaskbarItems()
            CompanionTaskbarItem()
            ClipboardNotice()
            SystemTray(onClick = onOpenQuickMenu)
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
    // A flat chip in the Gaming palette, not Material's filled pill (Droidtop/tracker#555 D4).
    androidx.compose.material3.Surface(
        onClick = onClick,
        // 48 dp, the touch target every one of droidtop's controls keeps (DESIGN-LANGUAGE, Droidtop/tracker#690).
        modifier = Modifier.padding(horizontal = 3.dp).heightIn(min = 48.dp),
        shape = dev.droidtop.shell.gamepad.MenuTokens.RowShape,
        color = dev.droidtop.shell.gamepad.MenuTokens.Card,
        contentColor = dev.droidtop.shell.gamepad.MenuTokens.OnSurface,
    ) {
        Box(modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * The taskbar's real cross-container task manager (docs/SPEC.md's Desktop
 * shell section, Droidtop/tracker#94): one row per toplevel host-bridge
 * reports over wlr-foreign-toplevel-management. Tap activates it (raises +
 * focuses, restoring it first when minimized); tapping the already-activated
 * one minimizes it instead, so a single action both switches windows and
 * gets one out of the way, the same "click its taskbar button again"
 * convention as a desktop OS. Long-press opens a small menu for
 * Restore/Minimize and Close -- the destructive one deliberately not on a
 * plain tap.
 *
 * Minimize and restore go through [DesktopWindows], which knows the
 * compositor: labwc honours the protocol's set_minimized, sway ignores it
 * (rig, fix2-t5.png in verify-2026-09-29, Droidtop/tracker#145) and gets its
 * scratchpad over IPC instead (Droidtop/tracker#391). A minimize the
 * compositor could not be asked for says so in a toast.
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
    windows: DesktopWindows?,
    windowIcons: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hidden by rememberHiddenWindows(windows)
    val placed by remember(windows) { windows?.placed ?: MutableStateFlow(emptyMap<Long, SwayWindow>()) }.collectAsState()

    if (toplevels.isEmpty() && androidApps.isEmpty()) {
        Spacer(modifier = modifier)
        return
    }

    LazyRow(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        items(toplevels, key = { it.id }) { toplevel ->
            val minimized = windows?.isMinimized(toplevel, hidden) ?: toplevel.minimized
            TaskbarWindowRow(
                toplevel = toplevel,
                icon = windowIcons[toplevel.appId],
                minimized = minimized,
                onTap = {
                    scope.launch {
                        if (toplevel.activated && !minimized) minimizeWindow(context, windows, toplevel) else windows?.raise(toplevel)
                    }
                },
                onMinimize = {
                    scope.launch { if (minimized) windows?.raise(toplevel) else minimizeWindow(context, windows, toplevel) }
                },
                onClose = { hostBridge?.closeToplevel(toplevel.id) },
                placed = toplevel.id in placed,
                onPlace = windows?.takeIf { it.canPlace }?.let { w ->
                    { placement: DesktopWindows.Placement ->
                        scope.launch {
                            if (!w.place(toplevel, placement)) {
                                dev.droidtop.library.settings.Notices.flash("Couldn't move this window", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context)
                            }
                        }
                        Unit
                    }
                },
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

/** [windows]' minimized-by-droidtop set as state; empty with no session. */
@Composable
private fun rememberHiddenWindows(windows: DesktopWindows?) =
    remember(windows) { windows?.hidden ?: MutableStateFlow(emptyMap<Long, HiddenWindow>()) }.collectAsState()

/** Minimizes [toplevel], and says so when the compositor could not be asked. */
private suspend fun minimizeWindow(context: Context, windows: DesktopWindows?, toplevel: Toplevel) {
    if (windows?.minimize(toplevel) != true) {
        dev.droidtop.library.settings.Notices.flash("Couldn't minimize this window", tone = dev.droidtop.library.settings.NoticeTone.Caution, context = context)
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
    // The search's rows hold a bitmap, not a picture to load: the PNG icons are decoded once, off the main
    // thread, while the search is open (Droidtop/tracker#689). An SVG icon is the image loader's alone and has
    // none here.
    val icons by produceState(emptyMap<String, android.graphics.Bitmap>(), linuxApps) {
        value = withContext(Dispatchers.IO) {
            linuxApps.mapNotNull { app ->
                app.iconFile?.takeIf { it.endsWith(".png") }
                    ?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
                    ?.let { app.id to it }
            }.toMap()
        }
    }
    LauncherSearchScreen(
        initialText = initialText,
        games = games,
        findApps = { text ->
            apps.filter { StartMenuSections.appMatches(it.name, it.genericName, text) }.map { app ->
                LauncherSearchApp(key = "linux:" + app.id, title = app.name, icon = icons[app.id], open = { launch?.invoke(app) })
            }
        },
        // Desktop has no game page; the Start menu's search plays the game.
        onOpen = onPlay,
        // Desktop's own settings are searched with the rest.
        settingsRoot = remember { dev.droidtop.library.settings.SettingsScreenRegistry.get("desktop_settings") },
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
        dev.droidtop.library.settings.Notices.flash(it, context = context)
    }
}

/** Closes through the task manager's strongest path and says so only when that did not end it. */
private suspend fun closeAndroidApp(context: Context, app: RunningApp) {
    val outcome = TaskManager.close(context, app.packageName)
    if (outcome !is CloseOutcome.Closed) dev.droidtop.library.settings.Notices.flash(outcome.text, context = context)
}

/**
 * The taskbar's window list as a sheet a pad drives (Droidtop/tracker#350): A raises a window (restoring it
 * first when it is minimized) or opens an Android app, X closes it, and the rest of the rules are the taskbar's own.
 */
@Composable
private fun WindowsSheet(
    hostBridge: HostBridge?,
    toplevels: List<Toplevel>,
    androidApps: List<RunningApp>,
    windows: DesktopWindows?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hidden by rememberHiddenWindows(windows)
    val rows = remember(toplevels, androidApps, hidden) {
        toplevels.map { toplevel ->
            HostedRow(
                key = "window:" + toplevel.id,
                title = toplevel.title.ifBlank { toplevel.appId.ifBlank { "(untitled window)" } },
                subtitle = when {
                    windows?.isMinimized(toplevel, hidden) ?: toplevel.minimized -> "Minimized"
                    toplevel.activated -> "In front"
                    else -> null
                },
                onSelect = {
                    // Dismissed after the raise: the sheet's scope ends with it.
                    scope.launch {
                        windows?.raise(toplevel)
                        onDismiss()
                    }
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
private fun TaskbarWindowRow(
    toplevel: Toplevel,
    icon: String?,
    minimized: Boolean,
    onTap: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    placed: Boolean = false,
    onPlace: ((DesktopWindows.Placement) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        // The app's icon ahead of the title, so windows of different programs tell apart at a glance (#689).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 180.dp)
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onTap, onLongClick = { menuOpen = true })
                .background(
                    if (minimized) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                )
                .padding(horizontal = 10.dp),
        ) {
            if (icon != null) HostedArt(icon, modifier = Modifier.padding(end = 6.dp), size = 20.dp)
            Text(
                windowTitle(toplevel),
                color = if (toplevel.activated) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (toplevel.activated) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (menuOpen) {
            val maximize = stringResource(R.string.desktop_maximize)
            val snapLeft = stringResource(R.string.desktop_snap_left)
            val snapRight = stringResource(R.string.desktop_snap_right)
            val restoreSize = stringResource(R.string.desktop_restore_size)
            val close = stringResource(R.string.desktop_close)
            // The one sheet of choices, which a pad drives too (docs/SPEC.md 7k, Droidtop/tracker#690).
            val rows = buildList {
                add(ActionRow(if (minimized) "Restore" else "Minimize", onSelect = { menuOpen = false; onMinimize() }))
                // A title bar's buttons and an edge drag, which sway's windows do not have (Droidtop/tracker#686).
                if (onPlace != null && !minimized) {
                    add(ActionRow(maximize, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.MAXIMIZE) }))
                    add(ActionRow(snapLeft, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.SNAP_LEFT) }))
                    add(ActionRow(snapRight, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.SNAP_RIGHT) }))
                    if (placed) add(ActionRow(restoreSize, onSelect = { menuOpen = false; onPlace(DesktopWindows.Placement.RESTORE) }))
                }
                add(ActionRow(close, onSelect = { menuOpen = false; onClose() }))
            }
            ActionSheet(title = windowTitle(toplevel), rows = rows, onDismiss = { menuOpen = false })
        }
    }
}

private fun windowTitle(toplevel: Toplevel): String = toplevel.title.ifBlank { toplevel.appId.ifBlank { "(untitled window)" } }

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
                        .heightIn(min = 48.dp)
                        .combinedClickable(onClick = { onOpen(pin) }, onLongClick = { menuOpen = true })
                        .padding(horizontal = 8.dp),
                ) {
                    pin.art?.let { HostedArt(it, size = 24.dp) }
                    Text(
                        pin.title,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (menuOpen) {
                    val unpin = stringResource(R.string.desktop_unpin)
                    ActionSheet(
                        title = pin.title,
                        rows = listOf(ActionRow(unpin, onSelect = { menuOpen = false; onUnpin(pin) })),
                        onDismiss = { menuOpen = false },
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
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp),
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
        if (menuOpen) {
            val close = stringResource(R.string.desktop_close)
            ActionSheet(
                title = app.label,
                rows = listOf(ActionRow(close, onSelect = { menuOpen = false; onClose() })),
                onDismiss = { menuOpen = false },
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
        TaskbarButton(onClick = { open = !open }) {
            Text(stringResource(R.string.desktop_clipboard_on_focus))
        }
        if (open) {
            val title = stringResource(R.string.desktop_clipboard_on_focus)
            val switchKeyboard = stringResource(R.string.desktop_switch_keyboard)
            ActionSheet(
                title = title,
                rows = listOf(
                    ActionNote(dev.droidtop.hostbridge.ClipboardAccess.WHY_BLOCKED),
                    ActionRow(switchKeyboard, onSelect = { open = false; dev.droidtop.library.settings.Keyboards.showPicker(context) }),
                ),
                onDismiss = { open = false },
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
    TaskbarButton(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Gaming's status readout, the one component (Droidtop/tracker#691): the clock in Android's 12 or 24 hour
            // format, the network and battery glyphs and the battery's percent. The desktop's only clock, shown with
            // or without a session and under either compositor.
            StatusCluster(showBatteryPercent = true, onClick = onClick)
            if (status.vpnActive) {
                Text(stringResource(R.string.desktop_tray_vpn), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
            }
            if (waiting > 0) NotificationCount(waiting)
        }
    }
}

/** The notifications waiting, as a count on a badge. */
@Composable
private fun NotificationCount(count: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
            .padding(horizontal = 5.dp),
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
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

    /** The Desktop resolution setting as stored ([DesktopScale.RESOLUTION_KEY]); null when never set. */
    fun resolution(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(DesktopScale.RESOLUTION_KEY, null)
}

// The container manager in droidtop's screen host (CatalogScreenLink): an
// action, because :shell-desktop has no compile-time dependency on :app.
internal fun openContainers(context: Context) {
    context.startActivity(CatalogScreenLink.intent(context, CONTAINERS_SCREEN_ID))
}

/** `ContainersCatalog.SCREEN_ID` in :app, which this module cannot see. */
private const val CONTAINERS_SCREEN_ID = "containers"

/** `AppSettingsCatalogs.SCREEN_ROM_FOLDERS` in :app: the game folders, where an empty library is filled. */
internal const val GAME_FOLDERS_SCREEN_ID = "rom_folders"

internal fun openModes(context: Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "dev.droidtop.app.ModeSwitcherActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/**
 * Desktop's settings in droidtop's screen host, the chrome Containers and the places open in (Droidtop/tracker#692),
 * with Desktop's page first and the rest of Settings one row away at its end. It opened Launcher3's settings
 * activity, which drew the same catalog as stock Android preferences; that page stays Standard's.
 */
internal fun openSettings(context: Context) {
    context.startActivity(CatalogScreenLink.intent(context, DESKTOP_SETTINGS_SCREEN_ID))
}

/** `DroidtopWideSettings.SCREEN_DESKTOP` in :app, which this module cannot see. */
private const val DESKTOP_SETTINGS_SCREEN_ID = "desktop_settings"

/**
 * "Companion" on the taskbar while the device has one screen (docs/SPEC.md "The companion's tabs", One screen): the
 * companion's tabs as a tray panel over the taskbar. With a second screen the companion is there instead.
 */
@Composable
private fun CompanionTaskbarItem() {
    val context = LocalContext.current
    var offered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        offered = withContext(Dispatchers.IO) { dev.droidtop.runtime.CompanionSheet.offered(context) }
    }
    if (offered) {
        TaskbarButton(onClick = { dev.droidtop.runtime.CompanionSheet.open(context, true) }) { Text(stringResource(R.string.desktop_companion)) }
    }
}

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
        ) { Text(stringResource(R.string.desktop_plugins)) }
    }
}

/** A Linux app's cached icon ([ContainerApp.iconFile]) as the image loader takes it. */
internal fun linuxIconUri(app: ContainerApp): String? = app.iconFile?.let { "file://$it" }

/** [pins] with a Linux pin that has no picture given its app's icon, when the app has one now. */
private fun withLinuxIcons(pins: List<TaskbarPin>, linuxApps: List<ContainerApp>): List<TaskbarPin> =
    pins.map { pin ->
        if (pin.art != null) return@map pin
        val app = linuxApps.firstOrNull { TaskbarPins.linuxKey(it.id) == pin.key } ?: return@map pin
        linuxIconUri(app)?.let { pin.copy(art = it) } ?: pin
    }

/**
 * Each window's icon by its app id: a Wayland window's app id is its program's desktop file id without
 * `.desktop` (foot's windows are `foot`, from `foot.desktop`), which is how the bar finds a window's icon.
 */
private fun windowIcons(linuxApps: List<ContainerApp>): Map<String, String> =
    linuxApps.mapNotNull { app -> linuxIconUri(app)?.let { app.id.removeSuffix(".desktop") to it } }.toMap()
