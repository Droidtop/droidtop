package dev.droidtop.shell.gamepad

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.droidtop.ui.shared.LocalNoticePalette
import dev.droidtop.ui.shared.glyph
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
 * Sections: Running apps (the task manager), Notifications, System, Performance, Audio, Display, Downloads
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
 * The running game has no section here: R2 is the global menu, and what
 * can be done with the game (Resume, Quit, Restart, Kill, its emulator and
 * the rest) is L2's [RunningGameSheet] (the owner, 2026-10-30, Droidtop/
 * tracker#740: "r2 is the global menu. L2 is everything we can do
 * contextually for this app/game/etc"). [runningEntry] is the running
 * game for the Plugins section's game panels.
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
    onOpenLeftMenu: () -> Unit,
    openPlace: (screenId: String) -> Boolean,
    /** Whether [openPlace] would open this place in the current mode (Kiosk and Kid hide the device-management places). */
    placeAvailable: (screenId: String) -> Boolean,
    onDismiss: () -> Unit,
    // The section to open on, when it is shown (a link to Updates opens Downloads, Droidtop/tracker#749); null is the usual one.
    startSection: QuickSection? = null,
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
        val context = androidx.compose.ui.platform.LocalContext.current
        // The Plugins section is the one way to plugins (Droidtop/tracker#644): it shows whenever a plugin is installed or
        // plugins can be managed from here, never only when one has a panel. Read off the main thread.
        val canManagePlugins = placeAvailable(PLACE_PLUGINS_SCREEN_ID)
        val hasPlugins by androidx.compose.runtime.produceState(canManagePlugins) {
            value = canManagePlugins || kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.pluginhost.PluginStore.installed(context).isNotEmpty()
            }
        }
        val granted = remember { NotificationsStore.isGranted(context) }
        // The sections Kid and Kiosk allow come from ControlAccess, like the companion's tabs.
        val uiMode by dev.droidtop.library.settings.UiModeRefresh.mode.collectAsState()
        // The person's order for the rail (Settings > Shell > Quick Menu order), read once off the main thread.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.library.settings.QuickMenuOrder.load(context.applicationContext)
            }
        }
        val order by dev.droidtop.library.settings.QuickMenuOrder.order.collectAsState()
        val hasSocial = placeAvailable(PLACE_SOCIAL_SCREEN_ID)
        val sections = remember(hasPlugins, hasSocial, uiMode, order) {
            QuickTiles.visibleSections(hasPlugins, hasSocial, uiMode, order)
        }
        var section by remember { mutableStateOf(startSection?.takeIf { it in sections } ?: QuickTiles.initialSection(granted)) }
        val sectionStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        LaunchedEffect(sections, section) {
            if (section !in sections) section = QuickTiles.initialSection(granted)
        }
        // What is waiting, for the rail's dots: read while the sheet is open, never polled.
        val notifications by NotificationsStore.items.collectAsState()
        val recentUnread by dev.droidtop.library.settings.Notices.unread.collectAsState()
        val jobs by PluginJobsCenter.entries().collectAsState()
        val unreadMessages by androidx.compose.runtime.produceState(dev.droidtop.library.social.SocialHub.unread()) {
            dev.droidtop.library.social.SocialHub.changes().collect { value = dev.droidtop.library.social.SocialHub.unread() }
        }
        val dots = buildSet {
            if (unreadMessages > 0) add(QuickSection.SOCIAL)
            if (granted && notifications.isNotEmpty()) add(QuickSection.NOTIFICATIONS)
            if (recentUnread > 0) add(QuickSection.NOTIFICATIONS)
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
        // The pane's width: the sheet less the rail beside it in landscape, the whole sheet under the rail in portrait.
        val paneWidthDp = sheetWidth.value.toInt() - if (window.portrait) 0 else RailWidthDp
        val pane: @Composable (Modifier) -> Unit = { paneModifier ->
            // Top and bottom only: the heading and the drawn sections take the sheet's 16dp side gutter themselves, and a
            // catalog section takes it from its own gutter, sized to the pane (Droidtop/tracker#649).
            // A pane under 600dp reads the compact ladder (Droidtop/tracker#739): the sheet is about 400dp on the
            // console's internal screen, where the full-screen roles wrapped a notification to four lines.
            NarrowPane(paneWidthDp) {
                Column(modifier = paneModifier.padding(vertical = 16.dp)) {
                    // L1/R1 step the rail; the glyphs beside the section's
                    // name say so instead of a "Switch tab" hint-bar pill
                    // (owner, 2026-09-25).
                    // The section's name in the heading role, as Steam's panels head
                    // theirs (it was a small label, ui-compare pair 10).
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
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
                    // One saved state per section: sections share one composable (the tile grids), so a cursor must
                    // not carry from one to the next, and each keeps its own cursor and scroll while the sheet is open,
                    // so stepping away with L1/R1 and back lands where the person was (DroidDeck's per-page focus
                    // and scroll memory, Droidtop/tracker#363).
                    // key() as well: several sections share one call site (the tile grids), and without a new group per
                    // section the shared one kept its composition across L1/R1, so its focus request never ran again
                    // and the focus was lost with the old section's rows (Droidtop/tracker#820).
                    key(section) { sectionStates.SaveableStateProvider(section.name) {
                        when (section) {
                            // Catalog pages (Social, Downloads, Plugins and every plugin's page) pad their rows by the
                            // window's content gutter. Under the window's own size that was 2.8 percent of the whole
                            // screen inside a sheet a third as wide, on top of the pane's 16dp: 36dp + 16dp a side at
                            // 1280dp, 54dp + 16dp at 1920dp (owner: "The plugin pages have margins that are ridiculously
                            // large", Droidtop/tracker#649). They now see the pane as their window, so the gutter is the
                            // scale's floor, 16dp, and it is the only one.
                            QuickSection.SOCIAL, QuickSection.DOWNLOADS, QuickSection.PLUGINS, QuickSection.PROFILE ->
                                CompositionLocalProvider(LocalShellWindow provides window.pane(paneWidthDp)) {
                                    when (section) {
                                        QuickSection.SOCIAL -> PlaceCatalogView(PLACE_SOCIAL_SCREEN_ID, onBack = close)
                                        QuickSection.DOWNLOADS -> DownloadsSection(close)
                                        // The person's own name and picture (Droidtop/tracker#586), a page of the sheet.
                                        QuickSection.PROFILE -> CatalogNavigator(
                                            root = remember { dev.droidtop.library.profile.ProfileStore.screen() },
                                            onExit = close,
                                        )
                                        else -> PluginsTab(runningEntry, placeAvailable, close)
                                    }
                                }
                            else -> Box(Modifier.padding(horizontal = 16.dp)) {
                                when (section) {
                                    QuickSection.APPS -> AppsTab(close)
                                    QuickSection.NOTIFICATIONS -> NotificationsTab(close)
                                    QuickSection.CONNECTIONS, QuickSection.SYSTEM, QuickSection.AUDIO, QuickSection.DISPLAY ->
                                        QuickSettingsPanel(section, sheetWidth.value.toInt() - RailWidthDp, openPlace, close)
                                    QuickSection.PERFORMANCE -> PerformanceSection(close)
                                    else -> Unit
                                }
                            }
                        }
                    } }
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
 * accent: it is where you are, and the cursor is in the section), the others muted. The one
 * [TabRail], unlabelled; the companion draws the same rail with labels.
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
    TabRail(
        tabs = sections.map { RailTab(key = it, label = it.label, glyph = it.glyph, dot = it in dots) },
        selected = selected,
        vertical = vertical,
        labelled = false,
        colors = RailColors(
            selected = MenuTokens.SurfaceSelected,
            ink = MenuTokens.OnSurface,
            muted = MenuTokens.OnSurfaceMuted,
            accent = MenuTokens.Accent,
            onAccent = MenuTokens.OnSurface,
        ),
        onSelect = { onSelect(it.key as QuickSection) },
        modifier = if (vertical) Modifier.width(RailWidthDp.dp) else Modifier,
        target = window.pressTarget,
    )
}

/** The Performance section's rows, in order (Droidtop/tracker#812). */
private enum class PerfRow { OVERLAY, CORNER, SIZE, MODE }

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
    val overlayCorner by PerformanceOverlay.corner.collectAsState()
    val overlaySize by PerformanceOverlay.size.collectAsState()
    val canDraw = remember { PerformanceOverlay.canDraw(context) }
    val hasShell = remember { PerformanceOverlay.hasShell() }
    // Performance mode is the one catalog item the companion's System > Power card and Game tab draw too
    // (GamingSettingsCatalog.performanceModeItem): built off the main thread, rebuilt after each press.
    var modeVersion by remember { mutableStateOf(0) }
    val modeItem by androidx.compose.runtime.produceState<dev.droidtop.library.settings.AsyncActionItem?>(null, modeVersion) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dev.droidtop.library.settings.GamingSettingsCatalog.performanceModeItem()
        }
    }
    var modeNote by remember { mutableStateOf<String?>(null) }
    // The section's rows in order: where the overlay sits and how big it is only while it shows (Droidtop/tracker#812).
    val rows = buildList {
        add(PerfRow.OVERLAY)
        if (canDraw && overlayLevel != OverlayLevel.OFF) {
            add(PerfRow.CORNER)
            add(PerfRow.SIZE)
        }
        if (modeItem != null) add(PerfRow.MODE)
    }
    val rowCount = rows.size
    var focusIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    // A restored cursor (one saved per section) stays on a row that still exists.
    LaunchedEffect(rowCount) { if (focusIndex >= rowCount) focusIndex = rowCount - 1 }

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
        modeItem?.let { item ->
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    item.run(context) { status -> modeNote = status }
                }
                modeNote = result
                modeVersion++
            }
        }
    }

    // Asked each frame until the window is attached: one early request was lost in the Quick Menu over a game,
    // and the first D-pad press only gave the section focus (Droidtop/tracker#820).
    LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester, "Quick Menu Performance") }
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
                    GamepadAction.A, GamepadAction.RIGHT -> when (rows.getOrNull(focusIndex)) {
                        PerfRow.OVERLAY -> cycleOverlay()
                        PerfRow.CORNER -> PerformanceOverlay.setCorner(context, overlayCorner.step(1))
                        PerfRow.SIZE -> PerformanceOverlay.setSize(context, overlaySize.step(1))
                        PerfRow.MODE -> cycleMode()
                        null -> Unit
                    }
                    GamepadAction.LEFT -> when (rows.getOrNull(focusIndex)) {
                        PerfRow.OVERLAY -> if (canDraw) {
                            PerformanceOverlay.setLevel(context, OverlayLevel.values()[(overlayLevel.ordinal + OverlayLevel.values().size - 1) % OverlayLevel.values().size])
                        }
                        PerfRow.CORNER -> PerformanceOverlay.setCorner(context, overlayCorner.step(-1))
                        PerfRow.SIZE -> PerformanceOverlay.setSize(context, overlaySize.step(-1))
                        else -> Unit
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
                subtitle = if (overlayLevel != OverlayLevel.OFF && !hasShell) "FPS, CPU and GPU need More controls" else null,
                adjustable = canDraw,
                selected = rows.getOrNull(focusIndex) == PerfRow.OVERLAY,
                onClick = {
                    focusIndex = rows.indexOf(PerfRow.OVERLAY)
                    touch(GamepadAction.A)
                },
            )
            if (PerfRow.CORNER in rows) {
                MenuRow(
                    title = "Overlay corner",
                    value = overlayCorner.label,
                    adjustable = true,
                    selected = rows.getOrNull(focusIndex) == PerfRow.CORNER,
                    onClick = {
                        focusIndex = rows.indexOf(PerfRow.CORNER)
                        touch(GamepadAction.A)
                    },
                )
                MenuRow(
                    title = "Overlay size",
                    value = overlaySize.label,
                    adjustable = true,
                    selected = rows.getOrNull(focusIndex) == PerfRow.SIZE,
                    onClick = {
                        focusIndex = rows.indexOf(PerfRow.SIZE)
                        touch(GamepadAction.A)
                    },
                )
            }
            modeItem?.let { item ->
                MenuRow(
                    title = item.title,
                    value = item.value,
                    subtitle = modeNote,
                    adjustable = true,
                    selected = rows.getOrNull(focusIndex) == PerfRow.MODE,
                    onClick = {
                        focusIndex = rows.indexOf(PerfRow.MODE)
                        touch(GamepadAction.A)
                    },
                )
            }
            if (s == null) {
                Text(stringResource(R.string.reading_device), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
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
                // No "GPU and frame rate" readout: it never had a reading here; the overlay above is where they show
                // (Droidtop/tracker#571 row 35).
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
                color = if (alarm) MenuTokens.NoticeDanger else MenuTokens.Value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (fill != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(6.dp)
                    .clip(Corners.Icon)
                    .background(MenuTokens.SurfaceSelected),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill.coerceIn(0, 100) / 100f)
                        .height(6.dp)
                        .clip(Corners.Icon)
                        .background(if (alarm) MenuTokens.NoticeDanger else MenuTokens.Accent),
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
 * Downloads: where updates are checked for and started, then the running and recent jobs (owner, 2026-10-29,
 * Droidtop/tracker#749: "actually initializing updates and stuff handled in the QAM"). The screen is `:app`'s
 * ([PluginJobsScreen.QUICK_MENU_ID], built by UpdatesPage with the jobs list's own rows), hosted through the catalog's
 * navigator; without it (a build with no `:app`) the jobs list alone.
 */
@Composable
private fun DownloadsSection(onDismiss: () -> Unit) {
    CatalogNavigator(
        root = remember {
            dev.droidtop.library.settings.SettingsScreenRegistry.get(PluginJobsScreen.QUICK_MENU_ID) ?: PluginJobsScreen.screen(headed = false)
        },
        onExit = onDismiss,
    )
}

/**
 * The Notifications section: droidtop's own Recent group first (docs/SPEC.md 7k, "Notices": every flash, with an
 * unread dot, Copy all on Select; it needs no notification access), then Android's notifications from
 * [NotificationsStore] once access is granted. One cursor runs over both.
 */
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
    val posted by NotificationsStore.items.collectAsState()
    // What can be cleared first, then what an app keeps up while it runs under one "Active" heading: whether a
    // notification can be cleared is a property of the group, not something to read on every row (Droidtop/tracker#674).
    val items = remember(posted) { posted.sortedBy { !it.clearable } }
    val activeStart = items.indexOfFirst { !it.clearable }
    val recent by dev.droidtop.library.settings.Notices.recent.collectAsState()
    // The dot says what was new when the tab opened; leaving the tab marks it read.
    val unreadAtOpen = remember { dev.droidtop.library.settings.Notices.unread.value }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { dev.droidtop.library.settings.Notices.markRead() } }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val copyAll = {
        if (recent.isNotEmpty()) {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(dev.droidtop.library.settings.Notices.recentAsText()))
            dev.droidtop.library.settings.Notices.flash("Copied")
        }
    }
    // The cursor runs over Recent, then the access row or Android's notifications.
    val systemRows = if (granted) items.size else 1
    val rowCount = recent.size + systemRows
    var focusIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    // Every action below is defined once, in the key handler. A tap
    // moves the cursor and sends the real press rather than repeating
    // any of it.
    val press = rememberGamepadTouch()

    // A held direction follows the cursor without animating each step.
    var heldStep by remember { mutableStateOf(false) }

    // Asked each frame until the window is attached: one early request was lost in the Quick Menu over a game,
    // and the first D-pad press only gave the section focus (Droidtop/tracker#820).
    LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester, "Quick Menu Notifications") }
    // List items: the Recent heading (when there is Recent), its rows, then the system rows.
    val headingOffset = if (recent.isNotEmpty()) 1 else 0
    LaunchedEffect(focusIndex, rowCount, activeStart) {
        if (rowCount > 0) {
            val row = focusIndex.coerceIn(0, rowCount - 1)
            // The Active heading sits in the list ahead of the first row it heads.
            val activeHeading = if (granted && activeStart >= 0 && row - recent.size >= activeStart) 1 else 0
            listState.keepInView(row + headingOffset + activeHeading, animate = !heldStep)
        }
    }
    val systemIndex = focusIndex - recent.size
    val current = if (granted) items.getOrNull(systemIndex) else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            // Every press is this tab's: the sheet above has already
            // taken the tabs and the close.
            .onPad { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        heldStep = press.repeat
                        focusIndex = menuStep(focusIndex, rowCount, if (press.action == GamepadAction.UP) -1 else 1)
                    }
                    GamepadAction.B -> onDismiss()
                    GamepadAction.A -> when {
                        systemIndex < 0 -> Unit
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
                    GamepadAction.SELECT -> copyAll()
                    GamepadAction.X -> if (current?.clearable == true) NotificationsStore.controller?.dismiss(current.key)
                    GamepadAction.Y -> if (granted && items.any { it.clearable }) NotificationsStore.controller?.clearAll()
                    else -> Unit
                }
                true
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            // The hint bar's own room (MenuTokens.HintBarRoom).
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = MenuTokens.HintBarRoom),
        ) {
            if (recent.isNotEmpty()) {
                item(key = "recent_heading") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        MenuSectionLabel("droidtop")
                        if (unreadAtOpen > 0) {
                            Box(
                                Modifier
                                    .padding(start = 6.dp)
                                    .size(8.dp)
                                    .background(MenuTokens.Accent, androidx.compose.foundation.shape.CircleShape)
                                    .semantics { contentDescription = "$unreadAtOpen new" },
                            )
                        }
                    }
                }
                itemsIndexed(recent, key = { index, entry -> "recent_${entry.atMillis}_$index" }) { index, entry ->
                    val focused = index == focusIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectionFrame(focused, MenuTokens.RowShape, row = true)
                            .padRow { focusIndex = index }
                            .padding(10.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            entry.tone.glyph(),
                            contentDescription = null,
                            tint = LocalNoticePalette.current.ink(entry.tone),
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(
                                listOfNotNull(entry.source, recentTime(entry.atMillis)).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MenuTokens.OnSurfaceMuted,
                            )
                            Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = MenuTokens.OnSurface)
                        }
                    }
                }
            }
            when {
                !granted -> item(key = "access") {
                    MenuRow(
                        title = "Notification access",
                        value = if (restricted) dev.droidtop.runtime.systemstatus.RestrictedSettings.TITLE else "Needs permission",
                        selected = systemIndex == 0,
                        onClick = {
                            focusIndex = recent.size
                            press(GamepadAction.A)
                        },
                    )
                }
                items.isEmpty() && recent.isEmpty() -> item(key = "empty") {
                    Text(
                        "No notifications.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MenuTokens.OnSurfaceMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                else -> items.forEachIndexed { index, note ->
                    if (index == activeStart) item(key = "active_heading") { MenuSectionLabel("Active") }
                    item(key = note.key) {
                        val focused = index == systemIndex
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectionFrame(focused, MenuTokens.RowShape, row = true)
                                // Without this a notification could only be
                                // reached with a pad: the rows carried no
                                // touch route at all, in the one sheet a
                                // phone user opens most. After the
                                // background, so the press indication is
                                // drawn over it rather than under it.
                                .padRow {
                                    focusIndex = recent.size + index
                                    press(GamepadAction.A)
                                }
                                .padding(10.dp),
                        ) {
                            Text(
                                note.appLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MenuTokens.OnSurfaceMuted,
                            )
                            Text(
                                note.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MenuTokens.OnSurface,
                            )
                            val body = notificationBody(note.text)
                            if (body.isNotBlank()) {
                                Text(
                                    body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MenuTokens.Value,
                                )
                            }
                        }
                    }
                }
            }
        }
        // The legend is the touch route too, dispatching into this dialog's
        // own window. Built from gated bindings, like every other row: only
        // what dispatches right now is named.
        HintRow(
            bindings = listOf(
                HintBinding(GamepadAction.A, "Grant access") { !granted && systemIndex == 0 },
                HintBinding(GamepadAction.A, "Open") { granted && current != null },
                HintBinding(GamepadAction.X, "Dismiss") { granted && current?.clearable == true },
                HintBinding(GamepadAction.Y, "Clear all") { granted && items.any { it.clearable } },
                HintBinding(GamepadAction.SELECT, "Copy all") { recent.isNotEmpty() },
                HintBinding(GamepadAction.B, "Close"),
            ),
            background = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** A Recent entry's time: the clock time today, else the date. */
private fun recentTime(atMillis: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = atMillis }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "d MMM HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(atMillis))
}


/**
 * The Plugins section (docs/plugin-api.md C17, Droidtop/tracker#316): Decky Loader's list and panels as one catalog
 * navigator. A on a plugin pushes its panel, B pops back to the list and from the list closes the sheet. The last
 * row, Manage plugins, pushes the Plugins screen here too: Gaming's plugins live in this section, not in the
 * left menu (docs/SPEC.md 4, Droidtop/tracker#586). A page a quick tile's press answered with opens over the sheet,
 * as before panels existed.
 */
@Composable
private fun PluginsTab(
    runningEntry: dev.droidtop.library.LibraryEntry?,
    placeAvailable: (screenId: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var replyScreen by remember { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    val root = remember(runningEntry?.id) {
        val game = runningEntry?.let { dev.droidtop.library.integrations.PluginContextActions.targetOf(it) }
        dev.droidtop.library.integrations.PluginPanels.quickMenuScreen(
            game = game,
            showManage = placeAvailable(PLACE_PLUGINS_SCREEN_ID),
            onReplyScreen = { replyScreen = it },
        )
    }
    CatalogNavigator(root = root, onExit = onDismiss)
    replyScreen?.let { screen ->
        val close = { replyScreen = null }
        // The one sheet with the ground painted (CatalogSheet): a bare Dialog let the screen behind show through (rig, 0.2.0-dev.2070).
        CatalogSheet(root = screen, onExit = close)
    }
}

/**
 * A notification's text as the Quick Menu shows it: without the app's own sentences telling a finger what to do
 * ("Tap to turn off wireless debugging"), which are wrong advice on a pad, where A opens it and the footer says so
 * (Droidtop/tracker#674). The rest of the text is the app's, unchanged. Pure, for the tests.
 */
internal fun notificationBody(text: String): String =
    text.split(Regex("(?<=[.!?])\\s+"))
        .filterNot { TOUCH_INSTRUCTION.containsMatchIn(it.trim()) }
        .joinToString(" ")
        .trim()

private val TOUCH_INSTRUCTION = Regex("^(tap|touch|click)\\b", RegexOption.IGNORE_CASE)
