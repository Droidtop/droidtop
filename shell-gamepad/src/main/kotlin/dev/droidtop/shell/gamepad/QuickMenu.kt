package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.library.message
import dev.droidtop.pluginhost.JobsSummary
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.runtime.systemstatus.PerformanceMonitor
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
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
 * and jobs, and Plugins (only while a running plugin offers tiles). Which
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
 * The Plugins section: one [MenuRow] per `ui.status_tile@1` or
 * `ui.quick_tile@1` a running plugin provides (docs/plugin-api.md C2, C3,
 * Droidtop/tracker#73). What tiles exist is read from manifests when the
 * sheet opens, their state is asked once per opening and never while
 * drawing, and a quick tile's A press is the only other call. A status
 * tile is read-only.
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
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The sheet is a window of its own: the same front of the input
        // pipeline as the shell's (docs/SPEC.md 6e), so the stick, the
        // repeat cadence and a held Select behave here as they do there.
        GatePadInThisDialog()
        HideSystemBarsInThisDialog()
        // Game only while a game is actually running (see this
        // function's own doc comment) -- computed once per sheet
        // opening, same as runningEntry itself is (GamepadShell only
        // re-resolves it when quickMenuOpen flips true).
        val context = androidx.compose.ui.platform.LocalContext.current
        // Read from manifests off the main thread; the section appears only when there is something to show.
        val pluginTiles by androidx.compose.runtime.produceState(emptyList<dev.droidtop.library.integrations.PluginTiles.Tile>()) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.library.integrations.PluginTiles.tilesFor(context)
            }
        }
        val granted = remember { NotificationsStore.isGranted(context) }
        val gameRunning = runningEntry != null
        val sections = remember(gameRunning, pluginTiles.isNotEmpty()) {
            QuickTiles.visibleSections(gameRunning, pluginTiles.isNotEmpty())
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

        val window = currentShellWindow()
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Wide enough for a real tile grid (two columns always, three
            // when the screen has room), capped so the sheet stays a
            // sheet -- the shell behind it must remain visible, which is
            // the whole point of a quick menu over a settings screen.
            val sheetWidth = if (window.portrait) {
                maxWidth
            } else {
                (maxWidth * 0.62f).coerceIn(480.dp, 760.dp).coerceAtMost(maxWidth)
            }
            Surface(
                modifier = Modifier
                    .then(
                        if (window.portrait) {
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxHeight * 0.72f)
                        } else {
                            Modifier.fillMaxHeight().width(sheetWidth)
                        },
                    )
                    .align(if (window.portrait) Alignment.BottomCenter else Alignment.CenterEnd)
                    // Preview: stepping the rail and closing win over the
                    // section inside, which holds focus and takes its own
                    // presses. R2 closes: the press that OPENED the sheet
                    // belonged to the shell underneath, so its release never
                    // acts here (docs/SPEC.md 6e) and only a fresh press
                    // closes. A held Select arrives as R2 too, so holding it
                    // again closes the sheet it opened. Start is the left
                    // menu's button, so it swaps to that menu rather than
                    // closing this one (Droidtop/tracker#258).
                    .onPad(preview = true) { press ->
                        when (press.action) {
                            GamepadAction.R2 -> {
                                onDismiss(); true
                            }
                            GamepadAction.START -> {
                                onOpenLeftMenu(); true
                            }
                            GamepadAction.L, GamepadAction.R -> {
                                val step = if (press.action == GamepadAction.L) -1 else 1
                                section = QuickTiles.stepSection(sections, section, step)
                                true
                            }
                            else -> false
                        }
                    },
                // The shell's own overlay surface, not the platform's
                // colour scheme. Every token this sheet's contents draw
                // with (MenuTokens: white label text, a 5%-white row
                // fill) is defined against THIS surface; painting the
                // sheet with MaterialTheme.colorScheme.surface meant a
                // device in a light colour state got a white panel with
                // white-on-white tile labels, the System tab's own
                // contents rendered illegible by a background the rest
                // of the shell never uses (emulator rig, 2026-09-10).
                color = MenuTokens.OverlaySurface,
                tonalElevation = 0.dp,
            ) {
                val rail: @Composable () -> Unit = {
                    QuickRail(sections, section, dots, vertical = !window.portrait) { section = it }
                }
                val pane: @Composable (Modifier) -> Unit = { paneModifier ->
                    Column(modifier = paneModifier.padding(16.dp)) {
                        // L1/R1 step the rail; the glyphs beside the section's
                        // name say so instead of a "Switch tab" hint-bar pill
                        // (owner, 2026-09-25).
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            ShoulderGlyph("L1", modifier = Modifier.padding(end = 8.dp))
                            Text(
                                section.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = MenuTokens.OnSurface,
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
                                    GameTab(it, library, onResume, onQuit, quitOutcome, onDismiss)
                                }
                                QuickSection.APPS -> AppsTab(onDismiss)
                                QuickSection.NOTIFICATIONS -> NotificationsTab(onDismiss)
                                QuickSection.SYSTEM, QuickSection.AUDIO, QuickSection.DISPLAY ->
                                    QuickSettingsPanel(section, sheetWidth.value.toInt() - RailWidthDp, onDismiss)
                                QuickSection.PERFORMANCE -> PerformanceSection(onDismiss)
                                QuickSection.DOWNLOADS -> DownloadsSection(onDismiss)
                                QuickSection.PLUGINS -> PluginTilesTab(pluginTiles, onDismiss)
                            }
                        }
                    }
                }
                if (window.portrait) {
                    Column(Modifier.fillMaxWidth()) {
                        rail()
                        pane(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        rail()
                        pane(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** The landscape rail's width: an icon target plus its padding. */
private const val RailWidthDp = 64

/**
 * The icon rail: one drawn glyph per section, the current one lit. A column down the sheet's left
 * edge, or a row across the top of a bottom sheet. It scrolls if the sections outnumber the room
 * and keeps the current one in view. Not a focus target: L1/R1 step it, and a tap selects.
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
                    .background(if (current) MenuTokens.SurfaceSelected else Color.Transparent)
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
                    tint = if (current) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
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
 * The Performance section: what a non-root app can read about how the device is doing, as readouts.
 * It reads the shared sampler ([PerformanceMonitor], the one the companion's Performance tab reads
 * too), which takes a sample every two seconds only while some surface runs [PerformanceMonitor.watch]:
 * here that is this section's own composition, so with the sheet closed or another section showing,
 * nothing polls. Readings Android does not give an app are named as such, never drawn as a number.
 */
@Composable
private fun PerformanceSection(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val history by PerformanceMonitor.history.collectAsState()
    val s = history.lastOrNull()
    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(Unit) { PerformanceMonitor.watch(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPad { press ->
                when (press.action) {
                    GamepadAction.UP -> scope.launch { scroll.animateScrollTo((scroll.value - 240).coerceAtLeast(0)) }
                    GamepadAction.DOWN -> scope.launch { scroll.animateScrollTo(scroll.value + 240) }
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
                        "Android hides whole-device load from apps." + (s.ownCpuPercent?.let { " droidtop itself uses $it%." } ?: ""),
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
                ReadoutRow("GPU and frame rate", "Needs privilege", null, "Android gives an app neither; a privilege helper plugin could.")
            }
        }
        HintRow(
            bindings = listOf(HintBinding(GamepadAction.B, "Close")),
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
                            context.startActivity(NotificationsStore.grantIntent())
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
                subtitle = "One-time grant on the system screen this opens; afterwards notifications appear here",
                value = "Needs permission",
                selected = focusIndex == 0,
                onClick = {
                    context.startActivity(NotificationsStore.grantIntent())
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
 * droidtop's own core rows are here: plugin tiles have the Plugins
 * section ([PluginTilesTab]).
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
 * The Plugins section: one row per status or quick tile a running plugin provides. The state is
 * refreshed once when the section opens (droidtop decides when, the plugin never runs its own loop),
 * a tile that does not answer in time keeps its last value, and only a quick tile answers to A.
 */
@Composable
private fun PluginTilesTab(
    tiles: List<dev.droidtop.library.integrations.PluginTiles.Tile>,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var focusIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val press = rememberGamepadTouch()
    var states by remember(tiles) {
        mutableStateOf<Map<String, dev.droidtop.pluginhost.TileState?>>(tiles.associate { it.key to dev.droidtop.library.integrations.PluginTiles.cached(it) })
    }
    var message by remember { mutableStateOf<String?>(null) }
    var tileScreen by remember { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }

    suspend fun refresh() {
        states = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dev.droidtop.library.integrations.PluginTiles.refresh(context, tiles)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(tiles) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPad { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN ->
                        focusIndex = menuStep(focusIndex, tiles.size, if (press.action == GamepadAction.UP) -1 else 1)
                    GamepadAction.B -> onDismiss()
                    GamepadAction.A -> {
                        val tile = tiles.getOrNull(focusIndex)
                        if (tile != null && tile.quick) {
                            scope.launch {
                                val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    dev.droidtop.library.integrations.PluginTiles.press(context, tile, states[tile.key])
                                }
                                message = outcome?.message
                                tileScreen = outcome?.screen
                                refresh()
                            }
                        }
                    }
                    else -> Unit
                }
                true
            },
    ) {
        // Scrolls, so a selected row past the sheet's bottom is brought into
        // view by MenuRow itself: a plain Column left those rows unreachable
        // on screen (tracker#152).
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tiles.forEachIndexed { index, tile ->
                val state = states[tile.key]
                MenuRow(
                    title = state?.label ?: tile.fallbackLabel,
                    subtitle = if (index == focusIndex) message ?: tile.pluginLabel else tile.pluginLabel,
                    value = when (state?.on) {
                        true -> "On"
                        false -> "Off"
                        null -> state?.value
                    },
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
                HintBinding(GamepadAction.A, "Use") { tiles.getOrNull(focusIndex)?.quick == true },
                HintBinding(GamepadAction.B, "Close"),
            ),
            background = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    tileScreen?.let { screen ->
        val close = { tileScreen = null }
        androidx.compose.ui.window.Dialog(onDismissRequest = close) {
            dev.droidtop.shell.gamepad.CatalogNavigator(root = screen, onExit = close)
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
        fun ending(kind: GameEnding, title: String, idle: String, restart: Boolean) = GameQuickTile(
            title = title,
            subtitle = when {
                armed == kind -> "Press A again to end ${entry.title}; unsaved progress may be lost"
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
                    subtitle = "Back to ${entry.title}",
                    action = { onResume(entry) },
                ),
            )
            // Only for a console game with something installed to choose from. A cycles Follow the
            // system, then each installed emulator; the launch reads it, so it applies from the next start.
            if (emulators != null && emulators.candidates.isNotEmpty()) {
                add(
                    GameQuickTile(
                        title = "Emulator",
                        subtitle = gameEmulatorSummary(emulators, emulatorChoice) + ". A cycles; applies from the next start.",
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
            add(ending(GameEnding.RESTART, "Restart", "End ${entry.title} and start it again", restart = true))
            add(ending(GameEnding.KILL, "Kill", "End ${entry.title}", restart = false))
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
                    GamepadAction.UP, GamepadAction.DOWN ->
                        {
                            armed = null
                            focusIndex = menuStep(focusIndex, tiles.size, if (press.action == GamepadAction.UP) -1 else 1)
                        }
                    GamepadAction.B -> onDismiss()
                    GamepadAction.A -> tiles.getOrNull(focusIndex)?.action?.invoke()
                    else -> Unit
                }
                true
            },
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.titleMedium,
            color = MenuTokens.OnSurface,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 12.dp),
        )
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

/** The two rows of the Game section that end the game, so each can wait for its own second press. */
private enum class GameEnding { RESTART, KILL }
