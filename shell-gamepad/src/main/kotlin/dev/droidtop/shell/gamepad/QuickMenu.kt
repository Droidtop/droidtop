package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.library.message
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import kotlinx.coroutines.launch

/**
 * The Quick Menu: press R2 anywhere in the Gaming shell (docs/
 * SPEC.md §4, quick-menu paradigm). A right-edge sheet in the Steam
 * Deck QAM family — the paradigm survey that picked it is in the SPEC:
 * the Deck's quick access menu (dedicated button, right sheet, vertical
 * tabs) is the strongest prior art for glanceable-while-playing, iiSU's
 * trigger menu is the same family on Android handhelds, and a
 * dedicated button here is R2, named by the R2 pill in the shell's
 * top-right corner. Hold-SELECT remains only as the fallback for pads
 * whose triggers are analog-only and never emit an R2 key event
 * (short-press SELECT keeps its existing meaning; chords were rejected
 * as undiscoverable). Start is NOT this menu's button: it opens the left
 * menu ([LeftMenu], owner 2026-10-01, Droidtop/tracker#258). The two
 * split the work -- the left menu is navigation, this one is quick
 * management -- and Start inside this sheet swaps to the left menu.
 *
 * ENTIRELY controller-driven, per direction: L1/R1 switch tabs, D-pad
 * moves, A opens, X dismisses, Y clears all, B closes. The System tab
 * is Android's quick-settings shape (status header, brightness and
 * volume sliders, a grid of large tiles -- see [QuickSettingsPanel]),
 * and it is still a VIEW of the settings catalog's own System group,
 * never a second quick-settings implementation with its own values: the
 * tiles carry the catalog's items and every press goes back to the
 * item's own write path.
 *
 * A third tab, Game, exists only while [runningEntry] is non-null --
 * while the most recent launch is still parked rather than explicitly
 * reclaimed (see [dev.droidtop.library.LaunchDisplay.parkedDisplayId]'s
 * own doc comment). The shell checks Android's package force-stop flag
 * off the main thread while the menu is open and clears the parked launch
 * when it is set. This cannot detect ordinary process death or a task
 * swipe; Android exposes no general task/process query to this app.
 * Before this the menu
 * showed the exact same Notifications/System pair whether or not a game
 * was running (Droidtop/tracker#82) -- no "you are in a game" surface
 * at all, unlike every console this mode is modeled on. When present,
 * Game opens first: the point of a distinct in-game menu is that it is
 * what greets you, not something you have to shoulder-cycle to find.
 * Its rows are resume and quit to library.
 *
 * A fourth tab, Plugins, exists only while a running plugin provides a
 * `ui.status_tile@1` or `ui.quick_tile@1` for this menu (docs/plugin-api.md
 * C2, C3, Droidtop/tracker#73): the same [MenuRow] shape, one row per tile.
 * What tiles exist is read from manifests when the sheet opens, their
 * state is asked once per opening and never while drawing, and a quick
 * tile's A press is the only other call. A status tile is read-only.
 *
 * WHERE the sheet sits follows the shape of the screen, because the
 * reason it is an edge sheet is that it must not cover the shell behind
 * it. On a landscape screen that edge is the right one, the Steam Deck
 * QAM shape, sized by what the tile grid needs. On a screen held
 * upright, a full-height right-edge sheet is the whole screen, so it
 * becomes a BOTTOM sheet instead: full width, sized by its content, the
 * shell still visible above it and the tabs within thumb reach rather
 * than at the far top corner. Same sheet, same tabs, same contents,
 * measured differently.
 *
 * A Compose [Dialog] on purpose: its window owns input while open, so
 * modality costs no key-event fencing in the shell underneath.
 */
@Composable
internal fun QuickMenu(
    runningEntry: dev.droidtop.library.LibraryEntry?,
    onResume: (dev.droidtop.library.LibraryEntry) -> Unit,
    onQuit: (dev.droidtop.library.LibraryEntry) -> Unit,
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
        // Read from manifests off the main thread; the tab appears only when there is something to show.
        val pluginTiles by androidx.compose.runtime.produceState(emptyList<dev.droidtop.library.integrations.PluginTiles.Tile>()) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.library.integrations.PluginTiles.tilesFor(context)
            }
        }
        val granted = remember { NotificationsStore.isGranted(context) }
        val visibleTabs = remember(runningEntry != null, pluginTiles.isNotEmpty()) {
            QuickTab.entries.filter { (it != QuickTab.GAME || runningEntry != null) && (it != QuickTab.PLUGINS || pluginTiles.isNotEmpty()) }
        }
        var tab by remember {
            mutableStateOf(
                when {
                    runningEntry != null -> QuickTab.GAME
                    !granted -> QuickTab.SYSTEM
                    else -> QuickTab.NOTIFICATIONS
                }
            )
        }
        LaunchedEffect(visibleTabs, tab) {
            if (tab !in visibleTabs) {
                tab = if (!granted) QuickTab.SYSTEM else QuickTab.NOTIFICATIONS
            }
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
                    // Preview: tab switching and closing win over the tab
                    // inside, which holds focus and takes its own presses.
                    // R2 closes: the press that OPENED the sheet belonged to
                    // the shell underneath, so its release never acts here
                    // (docs/SPEC.md 6e) and only a fresh press closes. A held
                    // Select arrives as R2 too, so holding it again closes the
                    // sheet it opened. Start is the left menu's button, so it
                    // swaps to that menu rather than closing this one.
                    .onPad(preview = true) { press ->
                        when (press.action) {
                            GamepadAction.R2 -> {
                                onDismiss(); true
                            }
                            GamepadAction.START -> {
                                onOpenLeftMenu(); true
                            }
                            GamepadAction.L, GamepadAction.R -> {
                                val i = visibleTabs.indexOf(tab)
                                val step = if (press.action == GamepadAction.L) -1 else 1
                                tab = visibleTabs[(i + step + visibleTabs.size) % visibleTabs.size]
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
                Column(
                    modifier = Modifier
                        .then(if (window.portrait) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // L1/R1 switches tabs; the glyphs beside the row say
                        // so instead of a "Switch tab" hint-bar pill (owner,
                        // 2026-09-25: "Can remove the next/previous section
                        // pills").
                        ShoulderGlyph("L1", modifier = Modifier.padding(end = 6.dp))
                        visibleTabs.forEach { t ->
                            Text(
                                t.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = if (t == tab) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                                // The tabs were nameplates: L1/R1 switched
                                // them and a tap did nothing, so on a phone
                                // the System tab was unreachable. The
                                // current one carries the raised fill the
                                // shell's section tabs use; the focus ring
                                // stays on the one thing the pad is on.
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (t == tab) MenuTokens.SurfaceSelected else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { tab = t }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        ShoulderGlyph("R1", modifier = Modifier.padding(start = 2.dp))
                    }
                    Spacer(Modifier.padding(4.dp))
                    // Closing's touch route is the hint row's own "B Close"
                    // pill, which dispatches a real B into this window; a
                    // separate "Close" text in the corner was a second
                    // control for the same press (UI pass 2026-09-24, M12).
                    // L1/R1 switches tabs; the ShoulderGlyph pair above the
                    // tab row names that now, not a hint-bar pill.
                    when (tab) {
                        QuickTab.GAME -> runningEntry?.let {
                            GameTab(it, onResume, onQuit, quitOutcome, onDismiss)
                        }
                        QuickTab.NOTIFICATIONS -> NotificationsTab(onDismiss)
                        QuickTab.SYSTEM -> QuickSettingsPanel(sheetWidth.value.toInt(), onDismiss)
                        QuickTab.PLUGINS -> PluginTilesTab(pluginTiles, onDismiss)
                    }
                }
            }
        }
    }
}

private enum class QuickTab(val label: String) {
    // Listed first: see QuickMenu's own doc comment on why Game opens
    // before Notifications when it's shown at all.
    GAME("Game"),
    NOTIFICATIONS("Notifications"),
    SYSTEM("System"),
    PLUGINS("Plugins"),
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
 * One row this tab can show. Deliberately the same shape a plugin's
 * `ui.quick_tile@1` op (`state -> {label, value, on?, icon}` plus
 * `toggle`/`action`, docs/plugin-api.md C2) hands over -- [dangerAction]
 * is this list's `danger` styling, not a fourth quick-tile kind. Only
 * droidtop's own two core rows are here: plugin tiles have the Plugins
 * tab ([PluginTilesTab]).
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
 * The Quick Menu's Game tab (docs/SPEC.md, Droidtop/tracker#82): who is
 * running, and the two actions a console's in-game overlay always
 * offers -- resume and quit to library. Same interaction model as
 * [NotificationsTab]: a virtual cursor over [MenuRow] tiles, A activates
 * the focused one, B/Back closes the whole sheet (there is nothing to
 * back OUT to within this tab -- unlike Notifications' list, one level
 * is all there is).
 *
 * [onResume] is `GamepadShell`'s real `onLaunch`, the one entry point
 * every launch in the shell already goes through (console ROM, PC and
 * engine games alike) -- relaunching the SAME entry is what real ES-DE's
 * own planned Recents tab (docs/SPEC.md, "Recents (decided 2026-08-30)")
 * already decided "resume" means here, reused rather than invented a
 * second time. [onQuit] fires [dev.droidtop.library.Library.quit] and
 * reports its outcome through [quitOutcome], which this tab shows in the
 * quit row's subtitle: droidtop clears its own running-game state only
 * when that outcome is [QuitResult.Ended], so a quit that left the
 * emulator alive (the Android 13 case, Droidtop/tracker#82) is shown
 * honestly instead of being reported as a success. See
 * [dev.droidtop.library.LibraryProvider.quit]'s own doc comment.
 */
/**
 * The Plugins tab: one row per status or quick tile a running plugin provides. The state is
 * refreshed once when the tab opens (droidtop decides when, the plugin never runs its own loop),
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

@Composable
private fun GameTab(
    entry: dev.droidtop.library.LibraryEntry,
    onResume: (dev.droidtop.library.LibraryEntry) -> Unit,
    onQuit: (dev.droidtop.library.LibraryEntry) -> Unit,
    quitOutcome: dev.droidtop.library.QuitResult?,
    onDismiss: () -> Unit,
) {
    var focusIndex by remember(entry.id) { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val press = rememberGamepadTouch()
    var quitConfirmArmed by remember(entry.id) { mutableStateOf(false) }

    val tiles = remember(entry.id, quitOutcome, quitConfirmArmed) {
        listOf(
            GameQuickTile(
                title = "Resume",
                subtitle = "Back to ${entry.title}",
                action = { onResume(entry) },
            ),
            GameQuickTile(
                title = "Kill",
                subtitle = quitOutcome?.message ?: if (quitConfirmArmed) {
                    "Press A again to end ${entry.title}; unsaved progress may be lost"
                } else {
                    "End ${entry.title}"
                },
                dangerAction = true,
                action = {
                    if (quitNeedsConfirmation(quitConfirmArmed)) {
                        quitConfirmArmed = true
                    } else {
                        quitConfirmArmed = false
                        onQuit(entry)
                    }
                },
            ),
        )
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
                            quitConfirmArmed = false
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
            modifier = Modifier.weight(1f),
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
