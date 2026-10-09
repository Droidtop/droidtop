package dev.droidtop.shell.gamepad.hosted

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
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
import androidx.compose.ui.window.Dialog
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
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.PadPress
import dev.droidtop.shell.gamepad.keepInView
import dev.droidtop.shell.gamepad.menuMove

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
 * A list sheet the pad drives the way it drives every Gaming menu (docs/SPEC.md 6e): Up and Down step the
 * cursor and stop at the ends, A takes [HostedRow.onSelect], X [HostedRow.onToggle], Y [HostedRow.onDetail],
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
        Dialog(onDismissRequest = onClose) {
            GatePadInThisDialog()
            MenuPanel(
                modifier = modifier.width(window.panelWidth(440.dp)).onPreviewKeyEvent { event ->
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
    library: Library,
    onOpenStartMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    CompositionLocalProvider(LocalShellWindow provides currentShellWindow()) {
        QuickMenu(
            runningEntry = null,
            library = library,
            onResume = {},
            onQuit = { _, _ -> },
            quitOutcome = null,
            onOpenLeftMenu = onOpenStartMenu,
            openPlace = { screenId ->
                context.startActivity(CatalogScreenLink.intent(context, screenId))
                true
            },
            placeAvailable = { screenId ->
                val place = Place.byScreenId(screenId)
                place == null || place in Place.visible(UiModePrefs.get(context))
            },
            onDismiss = onDismiss,
        )
    }
}
