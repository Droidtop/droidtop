package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds

/**
 * One place the left menu can take the user. Home is the PC Games section
 * showing its shelves ([home]); the "PC Games" row is the same section
 * showing the library grid (docs/SPEC.md 7i, "Home art").
 */
internal data class LeftMenuEntry(val section: GamingSection, val label: String, val home: Boolean = false) {
    val key: String get() = if (home) "home" else section.name

    /** Whether this row is where the user is: [atHome] tells Home from the PC Games grid. */
    fun isAt(current: GamingSection, atHome: Boolean): Boolean =
        section == current && (section != GamingSection.PC_GAMES || home == atHome)
}

/** The left menu's rows for the sections this UI mode allows (Kiosk and Kid hide Settings), Home first. */
internal fun leftMenuEntries(sections: List<GamingSection>): List<LeftMenuEntry> = buildList {
    if (GamingSection.PC_GAMES in sections) add(LeftMenuEntry(GamingSection.PC_GAMES, "Home", home = true))
    sections.forEach { add(LeftMenuEntry(it, it.displayName())) }
}

/**
 * The row the cursor starts on: where the user is now (focus memory). A
 * destination the mode hides falls to the top rather than to nothing.
 */
internal fun leftMenuStartIndex(entries: List<LeftMenuEntry>, current: GamingSection, atHome: Boolean = false): Int =
    entries.indexOfFirst { it.isAt(current, atHome) }.coerceAtLeast(0)

// A side-menu row is this tall (a touch window raises it to one touch target).
private val LEFT_MENU_ROW_HEIGHT = 48.dp

/**
 * The left menu: press Start anywhere in the Gaming shell (docs/SPEC.md
 * 7j, "Gaming controls", Droidtop/tracker#258). It is where things LIVE
 * and how you get to them -- the destinations -- while the Quick Menu on
 * the right (R2) is quick management only. A full-height left-edge side
 * menu (icon and label rows) over a dimmed page, opening on the destination
 * the user is on.
 *
 * Its own layout, not the ES-DE theme's (no theme draws a menu), but
 * every colour and type style comes from the same tokens the theme feeds
 * the rest of the shell, so it follows a theme switch.
 *
 * Controller: Up/Down move, A goes there, B or Start closes, R2 swaps to
 * the Quick Menu. Touch: tap a row, tap the dimmed page to close. A
 * Compose [Dialog] on purpose, like the Quick Menu: its window owns input
 * while it is open, so the shell underneath needs no fencing.
 */
@Composable
internal fun LeftMenu(
    entries: List<LeftMenuEntry>,
    current: GamingSection,
    atHome: Boolean,
    onSelect: (LeftMenuEntry) -> Unit,
    onOpenQuickMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    val window = currentShellWindow()
    // The frame's close slides the panel out first; every way out here goes through it.
    SidePanelFrame(
        edge = PanelEdge.LEFT,
        onDismiss = onDismiss,
        panelWidth = { screen -> if (window.portrait) screen * 0.72f else sidePanelWidth(screen, 0.28f, 224.dp, 360.dp) },
    ) { _, close ->
        // A tap moves the cursor and sends the real press, so the one key
        // handler below is the only place that says what a row does.
        val press = rememberGamepadTouch()
        var focusIndex by remember { mutableIntStateOf(leftMenuStartIndex(entries, current, atHome)) }
        var heldStep by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester, "Left menu") }
        LaunchedEffect(focusIndex, entries.size) {
            if (entries.isNotEmpty()) listState.keepInView(focusIndex.coerceIn(0, entries.size - 1), animate = !heldStep)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Preview, like the Quick Menu's sheet: the panel takes
                // its own presses before the focused column inside it.
                .onPad(preview = true) { p ->
                    when (p.action) {
                        GamepadAction.UP, GamepadAction.DOWN -> {
                            heldStep = p.repeat
                            val next = menuStep(focusIndex, entries.size, if (p.action == GamepadAction.UP) -1 else 1)
                            if (next != focusIndex) EsDeNavigationSounds.play("scroll")
                            focusIndex = next
                        }
                        GamepadAction.A -> entries.getOrNull(focusIndex)?.let(onSelect)
                        GamepadAction.B, GamepadAction.START -> close()
                        GamepadAction.R2 -> onOpenQuickMenu()
                        else -> return@onPad false
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.Hair),
                contentPadding = PaddingValues(vertical = Space.Lg),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                    SideMenuRow(
                        entry = entry,
                        isCurrent = entry.isAt(current, atHome),
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
                    HintBinding(GamepadAction.A, "Open"),
                    HintBinding(GamepadAction.R2, "Quick Menu"),
                    HintBinding(GamepadAction.B, "Close"),
                ),
                background = Color.Transparent,
            )
        }
    }
}

/**
 * One side-menu row: an icon and a label, flat to the panel's edges. The
 * destination the user is on carries an accent bar and a filled row; the
 * cursor is the shell's one selection frame, so both read together when the
 * menu opens on the current destination (docs/SPEC.md 7j, "Gaming controls").
 */
@Composable
private fun SideMenuRow(entry: LeftMenuEntry, isCurrent: Boolean, selected: Boolean, onClick: () -> Unit) {
    val window = currentShellWindow()
    val height: Dp = maxOf(LEFT_MENU_ROW_HEIGHT, if (window.touchFirst) window.minTouchTarget else 0.dp)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .selectionFrame(selected, RectangleShape, rest = if (isCurrent) MenuTokens.Surface else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        if (isCurrent) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .height(28.dp)
                    .background(MenuTokens.Accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Icon(
                entry.glyph(),
                contentDescription = null,
                tint = if (selected || isCurrent) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                entry.label,
                color = MenuTokens.OnSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
