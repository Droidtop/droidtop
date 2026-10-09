package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.message
import dev.droidtop.pluginhost.JobsSummary
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.runtime.systemstatus.GameMode
import dev.droidtop.runtime.systemstatus.GameModeControl
import dev.droidtop.runtime.systemstatus.OverlayLevel
import dev.droidtop.runtime.systemstatus.PerformanceMonitor
import dev.droidtop.runtime.systemstatus.PerformanceOverlay
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import kotlinx.coroutines.launch

/**
 * The Quick Menu: press R2 anywhere in the Gaming shell (docs/SPEC.md
 * "Quick Menu: a branching panel"). The right-hand panel of the shell's
 * two menus: quick management lives here, and destinations live in the
 * left menu (Start). A right-edge sheet in the Steam Deck QAM family
 * (the paradigm survey is in the SPEC), a bottom sheet on a screen held
 * upright, and since 2026-10-02 a branching panel: an ICON RAIL of
 * sections ([QuickSection]) beside the section's own content, instead of
 * one flat tab row. Hold-SELECT remains the fallback for pads whose
 * triggers are analog-only and never emit an R2 key event. Start is NOT
 * this menu's button: it opens the left menu ([LeftMenu]), and Start inside
 * this sheet swaps to it.
 *
 * Sections: Game (only while a game runs), Running apps (the task
 * manager), Notifications, System, Performance, Audio, Display, Downloads
 * and jobs, and Plugins (only while a running plugin has a panel or
 * tiles here). Which
 * are shown, where the menu opens and how L1/R1 step the rail are pure
 * rules in [QuickTiles] (QuickTilesTest). Get games is deliberately not a
 * section: it is a contextual action on the pages that need it.
 *
 * ENTIRELY controller-driven, per direction: L1/R1 step the rail, D-pad
 * moves inside the section, A acts, X and Y are the section's own
 * actions, B closes -- and fully reachable by touch: the rail icons tap,
 * the hint row dispatches the real presses it names. The rail is not a
 * focus target (the pad's focus stays in the section); it carries a dot
 * where something is waiting (notifications, running jobs).
 *
 * The System, Audio and Display sections are quick-settings tile grids
 * and each is still a VIEW of the settings catalog's own groups
 * ([QuickSettingsPanel]), never a second implementation: the tiles carry
 * the catalog's items and every press goes back to the item's own write
 * path. Downloads and jobs is the same jobs screen Settings opens
 * ([PluginJobsScreen]), hosted in the sheet.
 *
 * The Game section exists only while [runningEntry] is non-null -- while
 * the most recent launch is still parked rather than explicitly reclaimed
 * (see [dev.droidtop.library.LaunchDisplay.parkedDisplayId]'s own doc
 * comment). The shell checks Android's package force-stop flag off the
 * main thread while the menu is open and clears the parked launch when it
 * is set. This cannot detect ordinary process death or a task swipe;
 * Android exposes no general task/process query to this app. When
 * present, Game opens first: the point of a distinct in-game section is
 * that it is what greets you, not something you have to cycle to find
 * (Droidtop/tracker#82). Its rows are resume, restart and quit to library.
 *
 * The Plugins section is Decky Loader's plugin list (docs/plugin-api.md
 * C17, Droidtop/tracker#316): one row per plugin, A opens that plugin's
 * panel (its tiles, its own `ui.panel` view and the way to its settings),
 * B comes back to the list, and the last row leads to the Plugins place.
 * It is the catalog navigator over [dev.droidtop.library.integrations.PluginPanels],
 * so a panel is drawn like every other droidtop list and the plugin draws
 * nothing. Which plugins appear is read from manifests when the sheet
 * opens; a panel asks its plugin when it is opened, never while drawing.
 *
 * WHERE the sheet sits follows the shape of the screen, because the
 * reason it is an edge sheet is that it must not cover the shell behind
 * it. On a landscape screen that edge is the right one, with the rail down
 * its left side, sized by what the tile grid needs. On a screen held
 * upright, a full-height right-edge sheet is the whole screen, so it
 * becomes a BOTTOM sheet instead: full width, sized by its content, the
 * rail across its top within thumb reach. Same sheet, same sections, same
 * contents, measured differently.
 *
 * A Compose [Dialog] on purpose: its window owns input while open, so
 * modality costs no key-event fencing in the shell underneath.
 */
@Composable
internal fun QuickMenu(
    runningEntry: dev.droidtop.library.LibraryEntry?,
    library: dev.droidtop.library.Library,
    onResume: (dev.droidtop.library.LibraryEntry) -> Unit,
    onQuit: (entry: dev.droidtop.library.LibraryEntry, restart: Boolean) -> Unit,
    quitOutcome: dev.droidtop.library.QuitResult?,
    onOpenLeftMenu: () -> Unit,
    openPlace: (screenId: String) -> Boolean,
    /** Whether [openPlace] would open this place in the current mode (Kiosk and Kid hide the device-management places). */
    placeAvailable: (screenId: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val window = currentShellWindow()
    // The shared side-panel frame (SidePanel.kt): a right-edge panel, or a bottom sheet on a screen held upright.
    // Its close slides the panel out first, so every way out here (B, R2, the hint row, the scrim) goes through it.
    SidePanelFrame(
        edge = if (window.portrait) PanelEdge.BOTTOM else PanelEdge.RIGHT,
        onDismiss = onDismiss,
        // About a third of the screen, the left menu's width on the other side: a side panel, not a
        // screen. The tile grids drop to one column when that leaves them little room.
        panelWidth = { screen -> sidePanelWidth(screen, 0.34f, 300.dp, 440.dp) },
        // Steam's quick access panel floats: in from the edge, the panel radius and a hairline.
        floatMargin = if (window.portrait) 0.dp else Space.Md,
    ) { sheetWidth, close ->
        // Game only while a game is actually running (see this
        // function's own doc comment) -- computed once per sheet
        // opening, same as runningEntry itself is (GamepadShell only
        // re-resolves it when quickMenuOpen flips true).
        val context = androidx.compose.ui.platform.LocalContext.current
        // Read from manifests off the main thread; the section appears only when there is something to show.
        val hasPlugins by androidx.compose.runtime.produceState(false) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.library.integrations.PluginPanels.panelsFor(context, dev.droidtop.pluginhost.PluginModes.GAMING).isNotEmpty()
            }
        }
        val granted = remember { NotificationsStore.isGranted(context) }
        val gameRunning = runningEntry != null
        // The sections Kid and Kiosk allow come from ControlAccess, like the companion's tabs.
        val uiMode by dev.droidtop.library.settings.UiModeRefresh.mode.collectAsState()
        val sections = remember(gameRunning, hasPlugins, uiMode) {
            QuickTiles.visibleSections(gameRunning, hasPlugins, uiMode)
        }
        var section by remember { mutableStateOf(QuickTiles.initialSection(gameRunning, granted)) }
        LaunchedEffect(sections, section) {
            if (section !in sections) section = QuickTiles.initialSection(gameRunning, granted)
        }
        // What is waiting, for the rail's dots: read while the sheet is open, never polled.
        val notifications by NotificationsStore.items.collectAsState()
        val jobs by PluginJobsCenter.entries().collectAsState()
        val dots = buildSet {
            if (granted && notifications.isNotEmpty()) add(QuickSection.NOTIFICATIONS)
            if (JobsSummary.runningCount(jobs) > 0) add(QuickSection.DOWNLOADS)
        }

        // Preview: stepping the rail and closing win over the
        // section inside, which holds focus and takes its own
        // presses. R2 closes: the press that OPENED the sheet
        // belonged to the shell underneath, so its release never
        // acts here (docs/SPEC.md 6e) and only a fresh press
        // closes. A held Select arrives as R2 too, so holding it
        // again closes the sheet it opened. Start is the left
        // menu's button, so it swaps to that menu rather than
        // closing this one (Droidtop/tracker#258).
        val keys = Modifier.onPad(preview = true) { press ->
            when (press.action) {
                GamepadAction.R2 -> {
                    close(); true
                }
                GamepadAction.START -> {
                    onOpenLeftMenu(); true
                }
                GamepadAction.L, GamepadAction.R -> {
                    val step = if (press.action == GamepadAction.L) -1 else 1
                    val next = QuickTiles.stepSection(sections, section, step)
                    dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds.play(
                        if (next != section) dev.droidtop.shell.gamepad.theme.UiSound.TAB else dev.droidtop.shell.gamepad.theme.UiSound.BUMP,
                    )
                    section = next
                    true
                }
                else -> false
            }
        }
        val rail: @Composable () -> Unit = {
            QuickRail(sections, section, dots, vertical = !window.portrait) { section = it }
        }
        val pane: @Composable (Modifier) -> Unit = { paneModifier ->
            Column(modifier = paneModifier.padding(16.dp)) {
                // L1/R1 step the rail; the glyphs beside the section's
                // name say so instead of a "Switch tab" hint-bar pill
                // (owner, 2026-09-25).
                // The section's name in the heading role, as Steam's panels head
                // theirs (it was a small label, ui-compare pair 10).
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    ShoulderGlyph("L1", modifier = Modifier.padding(end = 8.dp))
                    Text(
                        section.label,
                        style = TypeRole.screenTitle,
                        color = MenuTokens.OnSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ShoulderGlyph("R1", modifier = Modifier.padding(start = 8.dp))
                }
                // Closing's touch route is the hint row's own "B Close"
                // pill, which dispatches a real B into this window.
                // key(): sections share one composable (the tile
                // grids), and a cursor must not carry from one to
                // the next.
                key(section) {
                    when (section) {
                        QuickSection.GAME -> runningEntry?.let {
                            GameTab(it, library, onResume, onQuit, quitOutcome, close)
                        }
                        QuickSection.APPS -> AppsTab(close)
                        QuickSection.NOTIFICATIONS -> NotificationsTab(close)
                        QuickSection.SYSTEM, QuickSection.AUDIO, QuickSection.DISPLAY ->
                            QuickSettingsPanel(section, sheetWidth.value.toInt() - RailWidthDp, openPlace, close)
                        QuickSection.PERFORMANCE -> PerformanceSection(close)
                        QuickSection.DOWNLOADS -> DownloadsSection(close)
                        QuickSection.PLUGINS -> PluginsTab(runningEntry, openPlace, placeAvailable, close)
                    }
                }
            }
        }
        if (window.portrait) {
            Column(Modifier.fillMaxWidth().then(keys)) {
                rail()
                pane(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize().then(keys)) {
                rail()
                pane(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The landscape rail's width: an icon target plus its padding. */
private const val RailWidthDp = 64

/**
 * The icon rail: one drawn glyph per section, the current one lit. A column down the sheet's left
 * edge, or a row across the top of a bottom sheet. It scrolls if the sections outnumber the room
 * and keeps the current one in view. Not a focus target: L1/R1 step it, and a tap selects. The
 * current section wears Steam's chosen-tab look (a quiet plate, the glyph in full ink, not the
 * accent: it is where you are, and the cursor is in the section), the others muted.
 */
@Composable
private fun QuickRail(
    sections: List<QuickSection>,
    selected: QuickSection,
    dots: Set<QuickSection>,
    vertical: Boolean,
    onSelect: (QuickSection) -> Unit,
) {
    val window = currentShellWindow()
    val target = window.minTouchTarget.coerceAtLeast(44.dp)
    val items: @Composable () -> Unit = {
        sections.forEach { s -> key(s) {
            val current = s == selected
            val requester = remember { BringIntoViewRequester() }
            LaunchedEffect(current) { if (current) requester.bringIntoView() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .bringIntoViewRequester(requester)
                    .size(target)
                    .clip(MenuTokens.RowShape)
                    .background(if (current) MenuTokens.SurfaceSelected else Color.Transparent, Corners.Crisp)
                    // Ahead of the clickable: a plain clickable is still a
                    // focus target, and the pad's focus belongs to the section.
                    .focusProperties { canFocus = false }
                    .clickable { onSelect(s) }
                    .semantics {
                        contentDescription = s.label
                        this.selected = current
                    },
            ) {
                QuickGlyphIcon(
                    glyph = s.glyph,
                    tint = if (current) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                    modifier = Modifier.size(22.dp),
                )
                if (s in dots && !current) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MenuTokens.Accent),
                    )
                }
            }
        } }
    }
    if (vertical) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(RailWidthDp.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { items() }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) { items() }
    }
}

/**
 * The Performance section: the overlay's level and the game's performance mode, then what a non-root app can read
 * about how the device is doing, as readouts (docs/SPEC.md, "Performance overlay").
 * It reads the shared sampler ([PerformanceMonitor], the one the companion's Performance tab reads
 * too), which takes a sample every two seconds only while some surface runs [PerformanceMonitor.watch]:
 * here that is this section's own composition, so with the sheet closed or another section showing,
 * nothing polls. Readings Android does not give an app are named as such, never drawn as a number.
 *
 * Two rows lead. "Overlay" cycles Off, FPS, Basic and Full on A (or Left and Right), and with no way to draw
 * over a game yet A opens the one grant that gives it instead. "Performance mode" is drawn only with a
 * running `priv.shell` provider and a game in front. Up and Down walk the rows, then scroll the readouts.
 */
@Composable
private fun PerformanceSection(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val history by PerformanceMonitor.history.collectAsState()
    val s = history.lastOrNull()
    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val touch = rememberGamepadTouch()

    val overlayLevel by PerformanceOverlay.level.collectAsState()
    val canDraw = remember { PerformanceOverlay.canDraw(context) }
    val hasShell = remember { PerformanceOverlay.hasShell() }
    val game = remember { dev.droidtop.runtime.tasks.LaunchLedger.last?.packageName }
    var mode by remember { mutableStateOf<GameMode?>(null) }
    var modeNote by remember { mutableStateOf<String?>(null) }
    val rowCount = if (hasShell && game != null) 2 else 1
    var focusIndex by remember { mutableStateOf(0) }

    val cycleOverlay = {
        if (!canDraw) {
            SettingsLaunch.start(
                context,
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            onDismiss()
        } else {
            PerformanceOverlay.setLevel(context, overlayLevel.next())
        }
    }
    val cycleMode = {
        val next = (mode ?: GameMode.STANDARD).next()
        val pkg = game
        if (pkg != null) {
            modeNote = "Setting..."
            scope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    GameModeControl.set(dev.droidtop.runtime.tasks.TaskManager.shell, next, pkg)
                }
                if (ok) mode = next
                modeNote = if (ok) "Set for this game" else "Android refused"
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(Unit) { PerformanceMonitor.watch(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPad { press ->
                when (press.action) {
                    GamepadAction.UP ->
                        if (scroll.value > 0) scope.launch { scroll.animateScrollTo((scroll.value - 240).coerceAtLeast(0)) }
                        else focusIndex = menuStep(focusIndex, rowCount, -1)
                    GamepadAction.DOWN ->
                        if (focusIndex < rowCount - 1) focusIndex = menuStep(focusIndex, rowCount, 1)
                        else scope.launch { scroll.animateScrollTo(scroll.value + 240) }
                    GamepadAction.A, GamepadAction.RIGHT -> if (focusIndex == 0) cycleOverlay() else cycleMode()
                    GamepadAction.LEFT -> if (focusIndex == 0 && canDraw) {
                        PerformanceOverlay.setLevel(context, OverlayLevel.values()[(overlayLevel.ordinal + OverlayLevel.values().size - 1) % OverlayLevel.values().size])
                    }
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MenuRow(
                title = "Overlay",
                value = if (canDraw) overlayLevel.label else "Needs display over apps",
                subtitle = if (overlayLevel != OverlayLevel.OFF && !hasShell) "FPS, CPU and GPU need the Shizuku plugin" else null,
                adjustable = canDraw,
                selected = focusIndex == 0,
                onClick = {
                    focusIndex = 0
                    touch(GamepadAction.A)
                },
            )
            if (hasShell && game != null) {
                MenuRow(
                    title = "Performance mode",
                    value = mode?.label ?: "Unchanged",
                    subtitle = modeNote,
                    adjustable = true,
                    selected = focusIndex == 1,
                    onClick = {
                        focusIndex = 1
                        touch(GamepadAction.A)
                    },
                )
            }
            if (s == null) {
                Text("Reading the device...", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            } else {
                val device = s.deviceCpuPercent
                val clock = s.cpuMhz?.let { "Fastest core $it MHz" }
                when {
                    device != null -> ReadoutRow("Processor", "$device% busy", device, clock)
                    else -> ReadoutRow(
                        "Processor",
                        s.cpuMhz?.let { "$it MHz fastest core" } ?: "Not readable by an app",
                        null,
                        s.ownCpuPercent?.let { "droidtop uses $it%" },
                    )
                }
                ReadoutRow(
                    "Memory",
                    "${s.memTotalMb - s.memAvailMb} of ${s.memTotalMb} MB",
                    s.memUsedPercent,
                    if (s.lowMemory) "Android reports low memory" else null,
                    alarm = s.lowMemory,
                )
                ReadoutRow(
                    "Battery",
                    (s.batteryPercent?.let { "$it%" } ?: "Unknown") + if (s.charging) ", charging" else "",
                    s.batteryPercent,
                    listOfNotNull(
                        s.batteryTempTenthC?.let { PerformanceMonitor.tempText(it) },
                        s.batteryMilliamps?.let { "$it mA" },
                    ).joinToString("   ").ifEmpty { null },
                )
                ReadoutRow(
                    "Heat",
                    PerformanceMonitor.thermalLabel(s.thermalStatus),
                    null,
                    null,
                    alarm = (s.thermalStatus ?: 0) >= 3,
                )
                ReadoutRow("GPU and frame rate", if (hasShell) "Use the overlay" else "Needs privilege", null, null)
            }
        }
        HintRow(
            bindings = listOf(HintBinding(GamepadAction.A, "Change"), HintBinding(GamepadAction.B, "Close")),
            background = Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One readout: a name, its value, an optional fill (0..100) and an optional supporting line. */
@Composable
private fun ReadoutRow(label: String, value: String, fill: Int?, detail: String?, alarm: Boolean = false) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MenuTokens.RowShape)
            .background(MenuTokens.Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                color = if (alarm) MenuTokens.Danger else MenuTokens.Value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (fill != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MenuTokens.SurfaceSelected),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill.coerceIn(0, 100) / 100f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (alarm) MenuTokens.Danger else MenuTokens.Accent),
                )
            }
        }
        if (detail != null) {
            Text(
                detail,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Downloads and jobs: the one jobs screen ([PluginJobsScreen], the same as Settings' "Downloads and
 * installs") hosted in the sheet through the catalog's own navigator, so a job reads the same here
 * as there and nothing is listed twice by two mechanisms.
 */
@Composable
private fun DownloadsSection(onDismiss: () -> Unit) {
    CatalogNavigator(root = remember { PluginJobsScreen.screen(headed = false) }, onExit = onDismiss)
}

@Composable
private fun NotificationsTab(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val granted = remember { NotificationsStore.isGranted(context) }
    // Android 13+ "restricted settings": then the step is App info's "Allow restricted settings" (SPEC 4c).
    val restricted by androidx.compose.runtime.produceState(initialValue = false, granted) {
        if (!granted) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { NotificationsStore.restrictedStepNeeded(context) }
        }
    }
    val grantStep = {
        if (restricted) {
            dev.droidtop.runtime.systemstatus.RestrictedSettings.openAppInfo(context)
        } else {
            NotificationsStore.openGrantScreen(context)
        }
    }
    val items by NotificationsStore.items.collectAsState()
    var focusIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    // Every action below is defined once, in the key handler. A tap
    // moves the cursor and sends the real press rather than repeating
    // any of it.
    val press = rememberGamepadTouch()

    // A held direction follows the cursor without animating each step.
    var heldStep by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(focusIndex, items.size) {
        if (items.isNotEmpty()) listState.keepInView(focusIndex.coerceIn(0, items.size - 1), animate = !heldStep)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // Every press is this tab's: the sheet above has already
            // taken the tabs and the close.
            .onPad { press ->
                val current = items.getOrNull(focusIndex)
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        heldStep = press.repeat
                        focusIndex = menuStep(focusIndex, items.size, if (press.action == GamepadAction.UP) -1 else 1)
                    }
                    GamepadAction.B -> onDismiss()
                    GamepadAction.A -> when {
                        !granted -> {
                            grantStep()
                            onDismiss()
                        }
                        current != null -> {
                            // Captured locally: contentIntent is a property
                            // from another module, so no smart cast.
                            val pending = current.contentIntent
                            if (pending != null) runCatching { pending.send() }
                            onDismiss()
                        }
                    }
                    GamepadAction.X -> if (current?.clearable == true) NotificationsStore.controller?.dismiss(current.key)
                    GamepadAction.Y -> if (items.any { it.clearable }) NotificationsStore.controller?.clearAll()
                    else -> Unit
                }
                true
            },
    ) {
        val showList = granted && items.isNotEmpty()
        when {
            !granted -> MenuRow(
                title = "Notification access",
                value = if (restricted) dev.droidtop.runtime.systemstatus.RestrictedSettings.TITLE else "Needs permission",
                selected = focusIndex == 0,
                onClick = {
                    grantStep()
                    onDismiss()
                },
            )
            items.isEmpty() -> Text(
                "No notifications.",
                style = MaterialTheme.typography.bodyMedium,
                color = MenuTokens.OnSurfaceMuted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> Unit
        }
        // The hint row is docked at the bottom of the sheet on every tab.
        // With no list to take the room it used to float directly under
        // "No notifications." while the System tab's sat at the bottom
        // (UI pass 2026-09-24, M12).
        if (!showList) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                // The hint bar's own room (MenuTokens.HintBarRoom).
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = MenuTokens.HintBarRoom),
            ) {
                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                    val focused = index == focusIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectionFrame(focused, MenuTokens.RowShape)
                            // Without this a notification could only be
                            // reached with a pad: the rows carried no
                            // touch route at all, in the one sheet a
                            // phone user opens most. After the
                            // background, so the press indication is
                            // drawn over it rather than under it.
                            .clickable {
                                focusIndex = index
                                press(GamepadAction.A)
                            }
                            .padding(10.dp),
                    ) {
                        Row {
                            Text(
                                item.appLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MenuTokens.OnSurfaceMuted,
                            )
                            Spacer(Modifier.weight(1f))
                            if (!item.clearable) {
                                Text(
                                    "ongoing",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MenuTokens.Placeholder,
                                )
                            }
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MenuTokens.OnSurface,
                        )
                        if (item.text.isNotBlank()) {
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MenuTokens.Value,
                            )
                        }
                    }
                }
            }
        }
        // The legend here named X (dismiss one) and Y (clear all),
        // neither of which had any touch route. As a hint bar the same
        // line IS the route, dispatching into this dialog's own window.
        // Built from gated bindings, like every other row: only what
        // dispatches right now is named -- Open and Dismiss need a
        // notification under the cursor, Dismiss and Clear all need one
        // that can be cleared, and an empty list offers none of them.
        HintRow(
            bindings = listOf(
                HintBinding(GamepadAction.A, "Grant access") { !granted },
                HintBinding(GamepadAction.A, "Open") { granted && items.getOrNull(focusIndex) != null },
                HintBinding(GamepadAction.X, "Dismiss") {
                    granted && items.getOrNull(focusIndex)?.clearable == true
                },
                HintBinding(GamepadAction.Y, "Clear all") { granted && items.any { it.clearable } },
                HintBinding(GamepadAction.B, "Close"),
            ),
            background = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}


/**
 * One row the Game section can show. Deliberately the same shape a plugin's
 * `ui.quick_tile@1` op (`state -> {label, value, on?, icon}` plus
 * `toggle`/`action`, docs/plugin-api.md C2) hands over -- [dangerAction]
 * is this list's `danger` styling, not a fourth quick-tile kind. Only
 * droidtop's own core rows are here: plugin tiles are in their plugin's
 * panel under the Plugins section ([PluginsTab]).
 */
private data class GameQuickTile(
    val title: String,
    val subtitle: String?,
    val dangerAction: Boolean = false,
    val action: () -> Unit,
)

/** Returns whether a destructive quit needs a second activation. */
internal fun quitNeedsConfirmation(armed: Boolean): Boolean = !armed

/**
 * The Plugins section (docs/plugin-api.md C17, Droidtop/tracker#316): Decky Loader's list and panels as one catalog
 * navigator. A on a plugin pushes its panel, B pops back to the list and from the list closes the sheet. The last
 * row leads out to the Plugins place instead of pushing that screen into the sheet (one screen, one home). A page a
 * quick tile's press answered with opens over the sheet, as before panels existed.
 */
@Composable
private fun PluginsTab(
    runningEntry: dev.droidtop.library.LibraryEntry?,
    openPlace: (screenId: String) -> Boolean,
    placeAvailable: (screenId: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var replyScreen by remember { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    val root = remember(runningEntry?.id) {
        val game = runningEntry?.let { entry ->
            dev.droidtop.pluginhost.ContextTarget(
                kind = if (entry.kind == dev.droidtop.library.LibraryEntryKind.NATIVE_ANDROID_APP) "app" else "game",
                id = entry.id,
                title = dev.droidtop.library.GameNaming.displayName(entry.title),
                systemId = entry.systemId,
                packageName = if (entry.kind == dev.droidtop.library.LibraryEntryKind.NATIVE_ANDROID_APP) entry.id else null,
            )
        }
        dev.droidtop.library.integrations.PluginPanels.quickMenuScreen(
            game = game,
            showManage = placeAvailable(PLACE_PLUGINS_SCREEN_ID),
            onReplyScreen = { replyScreen = it },
        )
    }
    CatalogNavigator(
        root = root,
        onExit = onDismiss,
        nativeActions = mapOf(dev.droidtop.library.integrations.PluginPanels.MANAGE_ROW_ID to { openPlace(PLACE_PLUGINS_SCREEN_ID); Unit }),
    )
    replyScreen?.let { screen ->
        val close = { replyScreen = null }
        androidx.compose.ui.window.Dialog(onDismissRequest = close) {
            GatePadInThisDialog()
            CatalogNavigator(root = screen, onExit = close)
        }
    }
}

/**
 * The Quick Menu's Game section (docs/SPEC.md, Droidtop/tracker#82): who is
 * running, and the actions a console's in-game overlay always offers --
 * resume, restart and quit to library. Same interaction model as
 * [NotificationsTab]: a virtual cursor over [MenuRow] tiles, A activates
 * the focused one, B/Back closes the whole sheet (there is nothing to
 * back OUT to within this section -- unlike Notifications' list, one level
 * is all there is).
 *
 * [onResume] is `GamepadShell`'s real `onLaunch`, the one entry point
 * every launch in the shell already goes through (console ROM, PC and
 * engine games alike) -- relaunching the SAME entry is what real ES-DE's
 * own planned Recents tab (docs/SPEC.md, "Recents (decided 2026-08-30)")
 * already decided "resume" means here, reused rather than invented a
 * second time. [onQuit] fires [dev.droidtop.library.Library.quit] and
 * reports its outcome through [quitOutcome], which this section shows in
 * the pressed row's subtitle: droidtop clears its own running-game state
 * only when that outcome is [dev.droidtop.library.QuitResult.Ended], so a
 * quit that left the emulator alive (the Android 13 case,
 * Droidtop/tracker#82) is shown honestly instead of being reported as a
 * success. Restart is quit with `restart = true`: the shell starts the
 * entry again only once the quit really ended, so a game that would not
 * end is never launched twice. Both ending rows need a second A press,
 * since unsaved progress may be lost. See
 * [dev.droidtop.library.LibraryProvider.quit]'s own doc comment.
 */
@Composable
private fun GameTab(
    entry: dev.droidtop.library.LibraryEntry,
    library: dev.droidtop.library.Library,
    onResume: (dev.droidtop.library.LibraryEntry) -> Unit,
    onQuit: (entry: dev.droidtop.library.LibraryEntry, restart: Boolean) -> Unit,
    quitOutcome: dev.droidtop.library.QuitResult?,
    onDismiss: () -> Unit,
) {
    var focusIndex by remember(entry.id) { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val press = rememberGamepadTouch()
    // The row waiting for its second press (Restart or Quit), and the one whose outcome the subtitle reports.
    var armed by remember(entry.id) { mutableStateOf<GameEnding?>(null) }
    var lastEnding by remember(entry.id) { mutableStateOf<GameEnding?>(null) }
    // The per-game emulator (Droidtop/tracker#248): the installed candidates for a console game's
    // system, loaded off the main thread, and the game's own stored choice (null follows the system).
    val scope = rememberCoroutineScope()
    val emulators = rememberSystemEmulators(entry)
    var emulatorChoice by remember(entry.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.id) { emulatorChoice = library.getMetadataForEditing(entry)?.altEmulator }

    val tiles = remember(entry.id, quitOutcome, armed, lastEnding, emulators, emulatorChoice) {
        fun ending(kind: GameEnding, title: String, idle: String?, restart: Boolean) = GameQuickTile(
            title = title,
            subtitle = when {
                armed == kind -> "Press A again"
                lastEnding == kind && quitOutcome != null -> quitOutcome.message
                else -> idle
            },
            dangerAction = true,
            action = {
                if (quitNeedsConfirmation(armed == kind)) {
                    armed = kind
                } else {
                    armed = null
                    lastEnding = kind
                    onQuit(entry, restart)
                }
            },
        )
        buildList {
            add(
                GameQuickTile(
                    title = "Resume",
                    subtitle = null,
                    action = { onResume(entry) },
                ),
            )
            // Only for a console game with something installed to choose from. A cycles Follow the
            // system, then each installed emulator; the launch reads it, so it applies from the next start.
            if (emulators != null && emulators.candidates.isNotEmpty()) {
                add(
                    GameQuickTile(
                        title = "Emulator",
                        subtitle = gameEmulatorSummary(emulators, emulatorChoice),
                        action = {
                            val ids = listOf<String?>(null) + emulators.candidates.map { it.id }
                            val own = dev.droidtop.library.consoles.EmulatorResolution
                                .matchGameChoice(emulators.candidates, emulatorChoice)?.id
                            val next = ids[(ids.indexOf(own).coerceAtLeast(0) + 1) % ids.size]
                            scope.launch {
                                val meta = library.getMetadataForEditing(entry)
                                    ?: dev.droidtop.library.consoles.GameMetadataEntity(id = entry.id)
                                if (library.saveMetadata(entry, meta.copy(altEmulator = next))) emulatorChoice = next
                            }
                        },
                    ),
                )
            }
            add(ending(GameEnding.RESTART, "Restart", null, restart = true))
        }
    }
    // The Stop pill in the header (DroidDeck's red Stop, Steam's destructive red): ending the game
    // without restarting it, with the same second press as the rows.
    val stop = GameQuickTile(title = "Kill", subtitle = null, dangerAction = true) {
        if (quitNeedsConfirmation(armed == GameEnding.KILL)) {
            armed = GameEnding.KILL
        } else {
            armed = null
            lastEnding = GameEnding.KILL
            onQuit(entry, false)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPad { press ->
                when (press.action) {
                    // The pill sits above the rows: Up from the first row reaches it, Down leaves it.
                    GamepadAction.UP -> {
                        armed = null
                        focusIndex = if (focusIndex <= 0) STOP_PILL else menuStep(focusIndex, tiles.size, -1)
                    }
                    GamepadAction.DOWN -> {
                        armed = null
                        focusIndex = if (focusIndex == STOP_PILL) 0 else menuStep(focusIndex, tiles.size, 1)
                    }
                    GamepadAction.B -> onDismiss()
                    GamepadAction.A -> if (focusIndex == STOP_PILL) stop.action() else tiles.getOrNull(focusIndex)?.action?.invoke()
                    else -> Unit
                }
                true
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StopPill(
                label = if (armed == GameEnding.KILL) "Press A again" else stop.title,
                selected = focusIndex == STOP_PILL,
                onClick = {
                    if (focusIndex != STOP_PILL) armed = null
                    focusIndex = STOP_PILL
                    press(GamepadAction.A)
                },
            )
        }
        if (lastEnding == GameEnding.KILL && quitOutcome != null) {
            Text(
                quitOutcome.message,
                style = MaterialTheme.typography.bodySmall,
                color = MenuTokens.OnSurfaceMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tiles.forEachIndexed { index, tile ->
                MenuRow(
                    title = tile.title,
                    subtitle = tile.subtitle,
                    danger = tile.dangerAction,
                    selected = index == focusIndex,
                    onClick = {
                        focusIndex = index
                        press(GamepadAction.A)
                    },
                )
            }
        }
        HintRow(
            bindings = listOf(
                HintBinding(GamepadAction.A, "Select"),
                HintBinding(GamepadAction.B, "Close"),
            ),
            background = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The two controls of the Game section that end the game, so each can wait for its own second press. */
private enum class GameEnding { RESTART, KILL }

/** The Game section's cursor on the header's Stop pill rather than on a row. */
private const val STOP_PILL = -1

/**
 * The Game section's Stop: a pill in the danger colour with the power glyph (DroidDeck's Stop pill,
 * ui/SessionOverlay.kt at 9310d19; Steam's destructive red is the theme's danger role). Under the
 * cursor it fills faintly with that red; the window's ring marks it like any control.
 */
@Composable
private fun StopPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .heightIn(min = 40.dp)
            .semantics { contentDescription = label }
            .selectionFrame(
                selected,
                Corners.Pill,
                rest = Color.Transparent,
                restOutline = MenuTokens.Danger.copy(alpha = 0.55f),
                selectedFill = MenuTokens.Danger.copy(alpha = 0.18f),
            )
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
    ) {
        QuickGlyphIcon(QuickGlyph.POWER, tint = MenuTokens.Danger, modifier = Modifier.size(18.dp))
        Text(label, style = TypeRole.button, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MenuTokens.Danger)
    }
}
