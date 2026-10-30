package dev.droidtop.shell.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
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
import androidx.compose.ui.layout.onSizeChanged
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
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.integrations.PluginHub
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.library.settings.DesktopPrefs
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.GamePageStandalone
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
    // Another window in front: a container with a keyboard layout of its own types in it (SPEC 3d "Keyboard").
    val focusedWindow = toplevels.firstOrNull { it.activated }?.id
    LaunchedEffect(focusedWindow) { dev.droidtop.runtime.DesktopFocus.changed() }
    FollowTextFields(hostBridge)
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
        // The bar takes its height from the desktop rather than covering it (Droidtop/tracker#682): the viewport is
        // laid out in the rest, so the compositor's output is exactly what shows and no window's edge is under the bar.
        val density = androidx.compose.ui.platform.LocalDensity.current
        var barHeightPx by remember { mutableStateOf(0) }
        val barHeight = with(density) { barHeightPx.toDp() }
        // Desktop mode's settings, read off the main thread and followed while the shell shows (Droidtop/tracker#764).
        val shellContext = LocalContext.current
        val prefs by remember(shellContext) { DesktopPrefs.changes(shellContext).flowOn(Dispatchers.IO) }
            .collectAsState(initial = DesktopPrefs.Values())
        val barAtTop = prefs.taskbarAtTop
        // The seat's input method, held or left to a container's own as the setting says (Droidtop/tracker#809).
        LaunchedEffect(hostBridge, prefs.containerInputMethod) {
            withContext(Dispatchers.IO) { hostBridge?.setInputMethodTaken(!prefs.containerInputMethod) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (barAtTop) barHeight else 0.dp, bottom = if (barAtTop) 0.dp else barHeight),
        ) {
            DesktopViewport(hostBridge, primaryOutput, sessionMessage, onStartSession, onOpenSetup, prefs)
        }

        Taskbar(
            atTop = barAtTop,
            onHeight = { barHeightPx = it },
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
            GamePageStandalone(
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
    // The search's rows hold a bitmap, not a picture to load: the icons are decoded once, by the image loader (off
    // the main thread, PNG or SVG alike), while the search is open (Droidtop/tracker#689; rig 2026-10-29: SVG
    // icons had none here).
    val context = androidx.compose.ui.platform.LocalContext.current
    val icons by produceState(emptyMap<String, android.graphics.Bitmap>(), linuxApps) {
        val loader = coil3.SingletonImageLoader.get(context)
        value = linuxApps.mapNotNull { app ->
            val file = app.iconFile ?: return@mapNotNull null
            val request = coil3.request.ImageRequest.Builder(context).data(java.io.File(file)).size(SEARCH_ICON_PX).build()
            (loader.execute(request) as? coil3.request.SuccessResult)?.image?.toBitmap()?.let { app.id to it }
        }.toMap()
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
    val ownScreens by dev.droidtop.runtime.tasks.OwnScreens.open.collectAsState()
    val rows = remember(toplevels, androidApps, ownScreens, hidden) {
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
        } + ownScreens.map { screen ->
            HostedRow(
                key = "own:" + screen.taskId,
                title = screen.title,
                subtitle = "droidtop, " + TaskPolicy.displayLabel(screen.displayId),
                onSelect = {
                    openOwnScreen(context, screen)
                    onDismiss()
                },
                onToggle = { dev.droidtop.runtime.tasks.OwnScreens.close(context, screen) },
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

/** The size the search's Linux app icons are decoded at. */
private const val SEARCH_ICON_PX = 96

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
 * `.desktop` (foot's windows are `foot`, from `foot.desktop`), which is how the bar finds a window's icon. Matched
 * without case: some toolkits capitalise the id their program gives.
 */
private fun windowIcons(linuxApps: List<ContainerApp>): Map<String, String> =
    linuxApps.mapNotNull { app -> linuxIconUri(app)?.let { app.id.removeSuffix(".desktop").lowercase() to it } }.toMap()
