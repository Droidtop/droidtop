package dev.droidtop.shell.gamepad.hosted

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.Library
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.library.settings.UiModePrefs
import dev.droidtop.shell.gamepad.Corners
import dev.droidtop.shell.gamepad.Keycap
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuSectionLabel
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.QuickMenu
import dev.droidtop.shell.gamepad.QuickSection
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.PadPress
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.menuMove
import kotlinx.coroutines.launch
import dev.droidtop.shell.gamepad.ShellDialog
import dev.droidtop.library.itemName
import dev.droidtop.library.message
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.library.scraper.isPcOrEngineGame
import androidx.compose.ui.window.DialogProperties

/*
 * The Gaming shell's own pieces, handed to the modes that have no Gaming shell around them
 * (docs/SPEC.md 2b "Desktop chrome with a pad", Droidtop/tracker#350): a sheet that a pad and a finger
 * both drive, the Quick Menu, and a legend for the buttons. Desktop draws its chrome over a compositor
 * surface and has no left menu, no footer and no Compose focus tree the pad can walk, so what it shows
 * with a pad is built from the same MenuPanel, MenuRow, sliding focus ring and hint row Gaming's menus
 * are, not from a second set of widgets.
 */

/**
 * One row of a [HostedListSheet]. [section] is the heading it sits under: a row whose section differs
 * from the one before it starts a new group. [onToggle] is X on the pad (a pin), [onDetail] is Y on the
 * pad; a long press on a finger takes [onDetail], else [onToggle].
 */
class HostedRow(
    val key: String,
    val title: String,
    val onSelect: () -> Unit,
    val subtitle: String? = null,
    val value: String? = null,
    val section: String? = null,
    val leading: (@Composable () -> Unit)? = null,
    val onToggle: (() -> Unit)? = null,
    val onDetail: (() -> Unit)? = null,
)

/**
 * Where a [HostedListSheet]'s cursor stands, for a caller that takes the sheet away and brings it back (a
 * menu opened from a row is the only window shown, and closing it returns to the same row, Droidtop/tracker#371).
 */
class HostedCursor {
    var index: Int = 0
}

/**
 * The text a hardware keyboard's key press types, or null: only a press from a keyboard (not a pad), with no
 * Ctrl or Alt held, of a printable character other than a space (Space is X on the pad's table, 6e).
 */
private fun typedText(event: android.view.KeyEvent): String? {
    if (event.action != android.view.KeyEvent.ACTION_DOWN) return null
    if (!event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD) || event.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)) return null
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    val ch = event.unicodeChar
    if (ch <= 0 || (ch and android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) return null
    val c = ch.toChar()
    return if (c.isLetterOrDigit()) c.toString() else null
}

private sealed interface HostedItem {
    data class Header(val text: String) : HostedItem
    data class Entry(val index: Int, val row: HostedRow) : HostedItem
}

/** The rows with a heading before each new section, and the position of every row in that flat list. Pure. */
private fun flatten(rows: List<HostedRow>): Pair<List<HostedItem>, IntArray> {
    val items = ArrayList<HostedItem>(rows.size + 8)
    val at = IntArray(rows.size)
    var last: String? = null
    rows.forEachIndexed { i, row ->
        val section = row.section
        if (section != null && section != last) items.add(HostedItem.Header(section))
        last = section
        at[i] = items.size
        items.add(HostedItem.Entry(i, row))
    }
    return items to at
}

/**
 * Where Left or Right takes the cursor at [from] in a list whose rows are in [sections] (each row's section, in
 * order): the first row of the previous or next section ([forward]); at the first or last section it stays. Left
 * inside a section first goes to that section's own first row, as a shelf's Left does. Pure.
 */
internal fun sectionJump(sections: List<String?>, from: Int, forward: Boolean): Int {
    if (sections.isEmpty()) return 0
    val at = from.coerceIn(0, sections.size - 1)
    val starts = sections.indices.filter { it == 0 || sections[it] != sections[it - 1] }
    val own = starts.last { it <= at }
    return if (forward) {
        starts.firstOrNull { it > at } ?: at
    } else if (at > own) {
        own
    } else {
        starts.lastOrNull { it < own } ?: at
    }
}

/**
 * A list sheet the pad drives the way it drives every Gaming menu (docs/SPEC.md 6e): Up and Down step the
 * cursor and stop at the ends, Left and Right jump between sections when the list has them ([sectionJump]; the
 * Start menu's Pinned, Recent and All, gap review 2026-10-17), A takes [HostedRow.onSelect], X [HostedRow.onToggle], Y [HostedRow.onDetail],
 * B closes, Start closes too (the button that opened it). It is a window of its own (a Dialog), so the
 * one input pipeline runs inside it; the rows are [MenuRow]s under the window's sliding focus ring; the
 * list is lazy and keeps the cursor on screen, so a library of hundreds costs what is on show.
 * Every row is also a tap target, and the hint row at the foot is the touch route to the same presses.
 *
 * The sheet does not close itself when a row is taken: the caller's [HostedRow.onSelect] decides (a
 * Start-menu launch closes it, a toggle leaves it open). [onExtraPad] sees the presses the sheet does not
 * use, for a caller's extra button; name it in [extraBindings] so the hint row promises it.
 */
@Composable
fun HostedListSheet(
    title: String,
    rows: List<HostedRow>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing here yet.",
    selectLabel: String = "Open",
    toggleLabel: String = "Pin",
    detailLabel: String = "Options",
    onExtraPad: (PadPress) -> Boolean = { false },
    extraBindings: List<HintBinding> = emptyList(),
    cursor: HostedCursor = remember { HostedCursor() },
    onTyped: ((String) -> Unit)? = null,
    // A shorter row than Gaming's [MenuTokens.RowMinHeight] for a dense list (Desktop's Start menu); null keeps it.
    rowMinHeight: androidx.compose.ui.unit.Dp? = null,
) {
    val window = currentShellWindow()
    val currentRows by rememberUpdatedState(rows)
    var selected by remember { mutableIntStateOf(cursor.index) }
    LaunchedEffect(selected) { cursor.index = selected }
    var repeating by remember { mutableStateOf(false) }
    LaunchedEffect(rows.size) { selected = selected.coerceIn(0, maxOf(0, rows.size - 1)) }
    val flat = remember(rows) { flatten(rows) }
    val listState = rememberLazyListState()
    LaunchedEffect(selected, flat) {
        if (rows.isEmpty()) return@LaunchedEffect
        val target = if (selected == 0) 0 else flat.second[selected.coerceIn(0, rows.size - 1)]
        listState.keepInView(target, animate = !repeating)
    }
    val hints = remember(selectLabel, toggleLabel, detailLabel, extraBindings) {
        listOf(
            HintBinding(GamepadAction.A, selectLabel) { currentRows.isNotEmpty() },
            HintBinding(GamepadAction.X, toggleLabel) { currentRows.getOrNull(selected)?.onToggle != null },
            HintBinding(GamepadAction.Y, detailLabel) { currentRows.getOrNull(selected)?.onDetail != null },
        ) + extraBindings + HintBinding(GamepadAction.B, "Close")
    }
    CompositionLocalProvider(LocalShellWindow provides window) {
        // The platform's default dialog width (440dp on the emulator rig) would cap the sheet below its own width.
        ShellDialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            GatePadInThisDialog()
            // Desktop is immersive (Droidtop/tracker#555 D4): a sheet that let its own window show the bars made the
            // desktop under it resize as it opened and closed, unlike the Quick Menu's frame, which already hid them.
            HideSystemBarsInThisDialog()
            MenuPanel(
                // Wide enough for A, X, Y and B on one line (rig emulator-5560, 2026-10-09: at 440dp "B Close"
                // ran off the edge); a narrower window wraps them (TouchHintBar).
                modifier = modifier.width(window.panelWidth(HOSTED_SHEET_WIDTH)).onPreviewKeyEvent { event ->
                    val typed = onTyped
                    val text = if (typed == null) null else typedText(event.nativeKeyEvent)
                    if (typed != null && text != null) {
                        typed(text)
                        true
                    } else {
                        false
                    }
                },
                focusLabel = title,
                title = title,
                // The sheet draws the hint row itself, inside the panel.
                hints = emptyList(),
                onPad = { press ->
                    repeating = press.repeat
                    when (press.action) {
                        GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, rows.size, press)
                        // Section jumps only where there are sections; otherwise the caller may use Left and Right.
                        GamepadAction.LEFT, GamepadAction.RIGHT ->
                            if (currentRows.mapTo(HashSet()) { it.section }.size > 1) {
                                selected = sectionJump(currentRows.map { it.section }, selected, forward = press.action == GamepadAction.RIGHT)
                            } else {
                                onExtraPad(press)
                            }
                        GamepadAction.A -> currentRows.getOrNull(selected)?.onSelect?.invoke()
                        GamepadAction.X -> currentRows.getOrNull(selected)?.onToggle?.invoke()
                        GamepadAction.Y -> currentRows.getOrNull(selected)?.onDetail?.invoke()
                        GamepadAction.B, GamepadAction.START -> onClose()
                        else -> onExtraPad(press)
                    }
                    true
                },
            ) {
                if (rows.isEmpty()) {
                    Text(emptyText, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = (window.heightDp * 0.6f).dp),
                        verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
                    ) {
                        items(
                            flat.first,
                            key = { item ->
                                when (item) {
                                    is HostedItem.Header -> "h:" + item.text
                                    is HostedItem.Entry -> "r:" + item.row.key
                                }
                            },
                        ) { item ->
                            when (item) {
                                is HostedItem.Header -> MenuSectionLabel(item.text)
                                is HostedItem.Entry -> MenuRow(
                                    title = item.row.title,
                                    subtitle = item.row.subtitle,
                                    value = item.row.value,
                                    selected = item.index == selected,
                                    onClick = {
                                        selected = item.index
                                        item.row.onSelect()
                                    },
                                    onLongClick = item.row.onDetail ?: item.row.onToggle,
                                    ownScrollKeeping = true,
                                    leading = item.row.leading,
                                    minHeight = rowMinHeight,
                                )
                            }
                        }
                    }
                }
                HintRow(
                    bindings = hints,
                    background = Color.Transparent,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** A square of artwork for a row's leading mark: a blank plate until the picture arrives, loaded by the image loader, never here. */
@Composable
fun HostedArt(uri: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(Corners.Crisp).background(MenuTokens.CardInset)) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The buttons a surface answers, named and not tappable: a legend for chrome whose presses arrive from
 * the activity rather than from a window of its own (Desktop's taskbar), where a tap on a real hint
 * pill would have nowhere to go. [labelColor] is the host's ink, since the host is not always dark.
 */
@Composable
fun PadLegend(
    items: List<Pair<GamepadAction, String>>,
    modifier: Modifier = Modifier,
    labelColor: Color = MenuTokens.OnSurfaceMuted,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (action, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Keycap(GamepadKeyMap.labelFor(action))
                Text(label, color = labelColor, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The Quick Menu outside Gaming (docs/SPEC.md 7j, Droidtop/tracker#350): Desktop's tray opens the same
 * sheet Gaming's R2 does, with its running apps, notifications, system, audio, display, performance,
 * downloads and plugin sections, instead of a second control panel of its own. No game is running in it
 * (a game launched from Desktop is an Android task, listed under Running apps); the places open in
 * droidtop's screen host.
 */
@Composable
fun QuickMenuStandalone(
    onOpenStartMenu: () -> Unit,
    onDismiss: () -> Unit,
    // The tab it opens on (OverlayQuickMenuRigTest opens each one); null for the menu's own choice.
    startSection: QuickSection? = null,
    // Power and mode's Search tile (Droidtop/tracker#621 M4), where the host has the one search (over a game); null
    // for none (Desktop's tray). Kiosk and Kid never get it, as in Gaming.
    onSearch: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uiMode by dev.droidtop.library.settings.UiModeRefresh.mode.collectAsState()
    val searchable = onSearch != null && dev.droidtop.shell.gamepad.menuSectionsFor(uiMode).any { it.managesDevice }
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        QuickMenu(
            runningEntry = null,
            onOpenLeftMenu = onOpenStartMenu,
            openPlace = { screenId ->
                context.startActivity(CatalogScreenLink.intent(context, screenId))
                true
            },
            placeAvailable = { screenId ->
                val place = Place.byScreenId(screenId)
                place == null || place in Place.reachable(UiModePrefs.get(context))
            },
            onDismiss = onDismiss,
            startSection = startSection,
            onSearch = onSearch?.takeIf { searchable },
        )
    }
}

/**
 * What Android's accessibility button opens (Droidtop/tracker#839; the owner: "We should probably add an accessibility
 * button that lets the user pick what menu to open"): the touch route to the menus the HOME chords open, for a person
 * with no pad. Four rows, each opening the same menu its chord does, not a copy: the Quick Menu (HOME + R2), the menu of
 * what is in front (HOME + L2; [contextTitle] names it), the Start menu (HOME + Start), and the mode switcher.
 */
@Composable
fun MenuPickerStandalone(
    contextTitle: String,
    onQuickMenu: () -> Unit,
    onContextMenu: () -> Unit,
    onStartMenu: () -> Unit,
    onSwitchMode: () -> Unit,
    onDismiss: () -> Unit,
) {
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        dev.droidtop.shell.gamepad.ActionSheet(
            title = "droidtop",
            rows = listOf(
                dev.droidtop.shell.gamepad.ActionRow("Quick Menu", "What R2 opens: running apps, notifications, settings", onSelect = onQuickMenu),
                dev.droidtop.shell.gamepad.ActionRow(contextTitle, "What L2 opens for what is on screen", onSelect = onContextMenu),
                dev.droidtop.shell.gamepad.ActionRow("Start menu", "What Start opens: droidtop's places", onSelect = onStartMenu),
                dev.droidtop.shell.gamepad.ActionRow("Switch mode", "Gaming, Mobile or Desktop", onSelect = onSwitchMode),
            ),
            onDismiss = onDismiss,
        )
    }
}

/** A hosted list sheet's width where the window allows: four hint pills fit on one line. */
private val HOSTED_SHEET_WIDTH = 520.dp

/**
 * The left menu's launcher content over a game (HOME + Start, docs/SPEC.md "In-game overlays: HOME chords",
 * Droidtop/tracker#740): the shell's own [dev.droidtop.shell.gamepad.LeftMenu], with the destinations the UI mode
 * allows, Resume first while a game runs and the cursor on it. A destination is opened by the host
 * ([onOpenSection], a Gaming section's name, and its place's screen id when it is a place), which leaves the game
 * for droidtop; R2 swaps to the Quick Menu, as it does in Gaming.
 */
@Composable
fun LeftMenuStandalone(
    // Whether Gaming is the mode in use: only then are Gaming's own sections offered, since they open Gaming; the places
    // (Downloads and the rest) open in every mode.
    gamingSections: Boolean,
    runningTitle: String?,
    onResume: () -> Unit,
    onOpenSection: (section: String, placeScreenId: String?) -> Unit,
    onOpenQuickMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Read from storage before anything is drawn: over a game the shell may never have loaded the live mode, and Kiosk
    // and Kid must not show the places for a frame.
    val context = LocalContext.current
    val stored by androidx.compose.runtime.produceState<dev.droidtop.library.settings.UiMode?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UiModePrefs.get(context) }
    }
    val uiMode = stored ?: return
    val all = dev.droidtop.shell.gamepad.menuSectionsFor(uiMode)
    val sections = if (gamingSections) all else all.filter { it.placeScreenId != null }
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        dev.droidtop.shell.gamepad.LeftMenu(
            entries = dev.droidtop.shell.gamepad.leftMenuEntries(sections),
            current = null,
            runningTitle = runningTitle,
            onResume = onResume,
            onSelect = { entry -> onOpenSection(entry.section.name, entry.section.placeScreenId) },
            onOpenQuickMenu = onOpenQuickMenu,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The running game's own overlay (HOME + L2, docs/SPEC.md "In-game overlays: HOME chords", Droidtop/tracker#740): its
 * own surface, not the Quick Menu (the owner, 2026-10-29: "You shouldn't be adding to tbe quick menu. The pause goes in
 * the overlay, which is supposed to be different"). It is the running game's sheet ([RunningGameSheet][dev.droidtop.shell.gamepad.RunningGameSheet]),
 * the same one L2 opens on the running game in Gaming: Resume closes the overlay (the game never left), Restart starts
 * the game again with [onLaunch] once it really ended, and Game options is what L2 opens on the game in Gaming
 * ([dev.droidtop.shell.gamepad.pc.PcGameMenu] with its page for a PC or engine game, the game menu with Gaming's detail
 * screen for a console game).
 */
@Composable
fun GameOptionsStandalone(
    library: Library,
    entry: dev.droidtop.library.LibraryEntry,
    onResume: () -> Unit,
    onLaunch: (dev.droidtop.library.LibraryEntry) -> Unit,
    onOpenDownloads: () -> Unit,
    onDismiss: () -> Unit,
) {
    var optionsOpen by remember(entry.id) { mutableStateOf(false) }
    CompositionLocalProvider(
        LocalShellWindow provides currentShellWindow(),
        dev.droidtop.shell.gamepad.LocalRunningGameHost provides remember(library) { dev.droidtop.shell.gamepad.RunningGameHost(library, onLaunch) },
    ) {
        if (optionsOpen) {
            dev.droidtop.shell.gamepad.GameOptionsOfRunningGame {
                RunningGameOptions(library, entry, onResume, onOpenDownloads, onClose = { optionsOpen = false })
            }
            return@CompositionLocalProvider
        }
        dev.droidtop.shell.gamepad.RunningGameSheet(
            entry = entry,
            onResume = onResume,
            onGameOptions = { optionsOpen = true },
            onDismiss = onDismiss,
            resumeDetail = "Back to the game; it never stopped",
        )
    }
}

/**
 * The running game's Game options: what L2 opens on [entry] in Gaming, drawn over the game or over Gaming on coming back
 * to it. [onClose] goes back to the running game's sheet.
 */
@Composable
internal fun RunningGameOptions(
    library: Library,
    entry: dev.droidtop.library.LibraryEntry,
    onResume: () -> Unit,
    onOpenDownloads: () -> Unit,
    onClose: () -> Unit,
) {
    var pageOpen by remember(entry.id) { mutableStateOf(false) }
    var detailOpen by remember(entry.id) { mutableStateOf(false) }
    GameOptions(library, entry, onResume, onOpenDownloads, onClose, pageOpen, { pageOpen = it }, detailOpen, { detailOpen = it })
}

@Composable
private fun GameOptions(
    library: Library,
    entry: dev.droidtop.library.LibraryEntry,
    onResume: () -> Unit,
    onOpenDownloads: () -> Unit,
    onClose: () -> Unit,
    pageOpen: Boolean,
    setPageOpen: (Boolean) -> Unit,
    detailOpen: Boolean,
    setDetailOpen: (Boolean) -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (entry.isPcOrEngineGame) {
        if (pageOpen) {
            dev.droidtop.shell.gamepad.pc.PcGameStandalone(
                library = library,
                entryId = entry.id,
                onLaunch = { onResume() },
                onOpenDownloads = onOpenDownloads,
                onClose = { setPageOpen(false) },
            )
        } else {
            dev.droidtop.shell.gamepad.pc.PcGameMenu(
                entry = entry,
                library = library,
                onLaunch = onResume,
                onClose = onClose,
                onOpenPage = { setPageOpen(true) },
                onOpenDownloads = onOpenDownloads,
            )
        }
        return
    }
    if (detailOpen) {
        ShellDialog(
            onDismissRequest = { setDetailOpen(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            GatePadInThisDialog()
            HideSystemBarsInThisDialog()
            Box(Modifier.fillMaxSize().groundBackground()) {
                dev.droidtop.shell.gamepad.EntryDetailScreen(
                    entry = entry,
                    library = library,
                    onLaunch = onResume,
                    onClose = { setDetailOpen(false) },
                )
            }
        }
        return
    }
    dev.droidtop.shell.gamepad.AppOptionsMenu(
        entry = entry,
        detailsLabel = "Game details",
        onDetails = { setDetailOpen(true) },
        onToggleFavorite = {
            // Back to the overlay once it is written: the scope ends with this menu.
            scope.launch {
                library.toggleFavorite(entry)
                onClose()
            }
        },
        onDismiss = onClose,
    )
}

/**
 * HOME + L2 over an app droidtop did not launch, Android's Settings included (docs/SPEC.md "In-game overlays: HOME
 * chords", Droidtop/tracker#740): that app's menu over it. Its name, then Resume (closes the overlay; the app never
 * left), Close the app on a second press (the task manager's one way, [dev.droidtop.runtime.tasks.TaskManager.close],
 * its outcome said on the row) and App info (Android's page for the app, [onAppInfo]).
 */
@Composable
fun AppInFrontStandalone(
    packageName: String,
    onResume: () -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val label by androidx.compose.runtime.produceState(packageName, packageName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
            }.getOrDefault(packageName)
        }
    }
    var asking by remember(packageName) { mutableStateOf(false) }
    var said by remember(packageName) { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        dev.droidtop.shell.gamepad.ActionSheet(
            title = label,
            rows = listOf(
                dev.droidtop.shell.gamepad.ActionRow("Resume", "Back to the app; it never stopped") { onResume() },
                dev.droidtop.shell.gamepad.ActionRow(
                    title = "Close the app",
                    detail = said ?: "Ends it the way the task manager does",
                    danger = true,
                ) { asking = true },
                dev.droidtop.shell.gamepad.ActionRow("App info", "Android's page for the app") { onAppInfo() },
            ),
            onDismiss = onDismiss,
        )
        if (asking) {
            dev.droidtop.shell.gamepad.ConfirmDialog(
                question = "Close $label?",
                action = "Close the app",
                onConfirm = {
                    asking = false
                    scope.launch {
                        val outcome = dev.droidtop.runtime.tasks.TaskManager.close(context, packageName)
                        said = outcome.text
                        if (outcome == dev.droidtop.runtime.tasks.CloseOutcome.Closed) onDismiss()
                    }
                },
                onCancel = { asking = false },
            )
        }
    }
}
