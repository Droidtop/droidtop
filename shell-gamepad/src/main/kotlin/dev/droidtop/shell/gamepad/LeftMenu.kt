package dev.droidtop.shell.gamepad

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.ui.shared.Motion

/**
 * One place the left menu can take the user: a section, Home included, which is a section of its own since
 * 2026-10-23 (Droidtop/tracker#611; it was PC Games in another view).
 */
internal data class LeftMenuEntry(val section: GamingSection, val label: String) {
    val key: String get() = section.name

    /** Whether this row is where the user is: Updates also while its Get new games page is up. */
    fun isAt(current: GamingSection): Boolean = section == current.menuSection
}

/** The left menu's rows for the sections this UI mode allows (Kiosk and Kid hide Settings), Home first. */
internal fun leftMenuEntries(sections: List<GamingSection>): List<LeftMenuEntry> =
    sections.map { LeftMenuEntry(it, it.displayName()) }

/**
 * The row the cursor starts on: where the user is now (focus memory). A
 * destination the mode hides falls to the top rather than to nothing.
 * [rowsAbove] is how many rows sit above the destinations (the Resume row).
 */
internal fun leftMenuStartIndex(entries: List<LeftMenuEntry>, current: GamingSection, rowsAbove: Int = 0): Int =
    rowsAbove + entries.indexOfFirst { it.isAt(current) }.coerceAtLeast(0)

/** What a row of the left menu is, by its place in the list: the running game's Resume, Search, then the destinations. */
internal sealed interface LeftMenuRow {
    object Resume : LeftMenuRow
    object Search : LeftMenuRow
    class Destination(val index: Int) : LeftMenuRow
}

/** How many rows sit above the destinations: Resume while a game runs, and Search when the mode has one. */
internal fun leftMenuRowsAbove(running: Boolean, searchable: Boolean): Int = (if (running) 1 else 0) + (if (searchable) 1 else 0)

/** The row at [index] of the whole list (the rows above, then the destinations). */
internal fun leftMenuRowAt(index: Int, running: Boolean, searchable: Boolean): LeftMenuRow {
    var left = index
    if (running) {
        if (left == 0) return LeftMenuRow.Resume
        left--
    }
    if (searchable) {
        if (left == 0) return LeftMenuRow.Search
        left--
    }
    return LeftMenuRow.Destination(left)
}

/** What a destination row shows beside its name: a dot when something needs the person, a ring while jobs run. */
internal sealed interface Attention {
    data object Dot : Attention

    /** How far the running jobs have got together, 0 to 1; negative while none of them knows. */
    data class Ring(val fraction: Float) : Attention
}

/**
 * What each destination has waiting (DroidDeck's rail badge, ui/FrontEndRail.kt at 9310d19; Droidtop/tracker#643: "a
 * filling ring while active, a dot when something needs the person"): Downloads a dot while a job has failed or waits
 * for the person's pick ([needYou]), else a ring filling as its running jobs ([running], each one's fraction or
 * negative when it does not know) get on together. Social's unread dot is on the Quick Menu's rail, where Social is.
 * Read while the menu is open, never polled. Pure.
 */
internal fun leftMenuAttention(running: List<Float>, needYou: Int, updates: Int = 0): Map<GamingSection, Attention> = buildMap {
    // Updates' dot while anything has a newer version (Droidtop/tracker#792): the page says how many.
    if (updates > 0) put(GamingSection.UPDATES, Attention.Dot)
    when {
        needYou > 0 -> put(GamingSection.DOWNLOADS, Attention.Dot)
        running.isNotEmpty() -> {
            val known = running.filter { it >= 0f }
            put(GamingSection.DOWNLOADS, Attention.Ring(if (known.isEmpty()) -1f else known.average().toFloat()))
        }
    }
}

/**
 * The left menu: press Start anywhere in the Gaming shell (docs/SPEC.md
 * 7j, "Gaming controls", Droidtop/tracker#258). It is where things LIVE
 * and how you get to them -- the destinations -- while the Quick Menu on
 * the right (R2) is quick management only.
 *
 * Steam's main menu, measured (docs/SPEC.md 7k, [SideMenu]): a 240dp panel
 * flush with the left edge, starting below the top and ending at the menu's
 * own hint row, over the page dimmed by the scrim role's own strength. When
 * a game is running, its Resume row leads (DroidDeck's Resume item,
 * ui/FrontEndRail.kt at 9310d19, with a live dot that pulses while motion
 * is on); Search is the next row, where the one search over the library, the apps, the download
 * sources and the settings opens (docs/SPEC.md 12a "One search", Droidtop/tracker#12); the
 * destinations follow, the one the person is on marked by a
 * short accent pill at the panel's edge and the ones with something waiting
 * by an accent dot.
 *
 * Its own layout, not the ES-DE theme's (no theme draws a menu), but
 * every colour and type style comes from the same tokens the theme feeds
 * the rest of the shell, so it follows a theme switch.
 *
 * Controller: Up/Down move, A goes there (or resumes), B or Start closes,
 * R2 swaps to the Quick Menu. Touch: tap a row, tap the dimmed page to
 * close. A Compose [Dialog] on purpose, like the Quick Menu: its window owns
 * input while it is open, so the shell underneath needs no fencing.
 */
@Composable
internal fun LeftMenu(
    entries: List<LeftMenuEntry>,
    // Where the person is; null over a game (the in-game overlay, Droidtop/tracker#740), where no row is "here" and
    // the cursor starts on Resume.
    current: GamingSection?,
    runningTitle: String?,
    onResume: () -> Unit,
    onSelect: (LeftMenuEntry) -> Unit,
    onOpenQuickMenu: () -> Unit,
    onDismiss: () -> Unit,
    // Opens the one search; null in a mode that has none (Kiosk and Kid), which then has no Search row.
    onSearch: (() -> Unit)? = null,
) {
    val window = currentShellWindow()
    val resumeRows = if (runningTitle != null) 1 else 0
    val running = runningTitle != null
    val searchable = onSearch != null
    val rowsAbove = leftMenuRowsAbove(running, searchable)
    val rowCount = rowsAbove + entries.size
    // What is waiting, for the dots: read while the menu is open, never polled.
    val jobs by PluginJobsCenter.entries().collectAsState()
    val updates by dev.droidtop.library.settings.UpdatesWaiting.count.collectAsState()
    val menuContext = androidx.compose.ui.platform.LocalContext.current
    // Counted again when the menu opens (off the main thread), so the dot is not left from before a check.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.droidtop.library.settings.UpdatesWaiting.refresh?.invoke(menuContext) }
    }
    val attention = leftMenuAttention(
        updates = updates,
        running = jobs.filter { !it.done && !it.paused }.map { if (it.percent >= 0) it.percent / 100f else -1f },
        needYou = jobs.count { job ->
            job.state == dev.droidtop.pluginhost.JobState.FAILED ||
                job.report?.let { it.count(dev.droidtop.pluginhost.Outcome.NEEDS_PICK) > 0 } == true
        },
    )
    // The frame's close slides the panel out first; every way out here goes through it.
    SidePanelFrame(
        edge = PanelEdge.LEFT,
        onDismiss = onDismiss,
        panelWidth = { screen -> if (window.portrait) screen * SideMenu.PortraitFraction else SideMenu.Width.coerceAtMost(screen) },
        scrim = MenuTokens.Veil,
        topInset = SideMenu.VerticalInset,
        // Steam draws the main menu's legend where the footer is, not inside the panel.
        footer = {
            HintRow(
                bindings = listOf(
                    HintBinding(GamepadAction.A, if (resumeRows > 0) "Select" else "Open"),
                    HintBinding(GamepadAction.R2, "Quick Menu"),
                    HintBinding(GamepadAction.B, "Close"),
                ),
                background = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) { _, close ->
        // A tap moves the cursor and sends the real press, so the one key
        // handler below is the only place that says what a row does.
        val press = rememberGamepadTouch()
        // Keyed on where the person is: the cursor starts on the row the "you are here" pill marks, even when the
        // place changes while the menu is composed (Droidtop/tracker#493: the pill on Apps, the cursor on Plugins).
        var focusIndex by remember(current, rowsAbove) { mutableIntStateOf(current?.let { leftMenuStartIndex(entries, it, rowsAbove) } ?: 0) }
        var heldStep by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { requestFocusWhenAttached(focusRequester, "Left menu") }
        LaunchedEffect(focusIndex, rowCount) {
            if (rowCount > 0) listState.keepInView(focusIndex.coerceIn(0, rowCount - 1), animate = !heldStep)
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
                            val next = menuStep(focusIndex, rowCount, if (p.action == GamepadAction.UP) -1 else 1)
                            moveCue(next != focusIndex, p.repeat)
                            focusIndex = next
                        }
                        GamepadAction.A -> when (val row = leftMenuRowAt(focusIndex, running, searchable)) {
                            LeftMenuRow.Resume -> onResume()
                            LeftMenuRow.Search -> onSearch?.invoke()
                            is LeftMenuRow.Destination -> entries.getOrNull(row.index)?.let(onSelect)
                        }
                        // X plays, so on the running game's card it resumes it; elsewhere it does nothing (proposal
                        // 6.D, Droidtop/tracker#644 slice 4).
                        GamepadAction.X -> if (leftMenuRowAt(focusIndex, running, searchable) == LeftMenuRow.Resume) onResume()
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
                contentPadding = PaddingValues(vertical = Space.Lg),
            ) {
                if (runningTitle != null) {
                    item(key = "resume") {
                        ResumeRow(
                            title = runningTitle,
                            selected = focusIndex == 0,
                            onClick = {
                                focusIndex = 0
                                press(GamepadAction.A)
                            },
                            onHover = { focusIndex = 0 },
                        )
                        Spacer(Modifier.height(Space.Sm))
                    }
                }
                if (searchable) {
                    item(key = "search") {
                        SearchRow(
                            selected = focusIndex == resumeRows,
                            onClick = {
                                focusIndex = resumeRows
                                press(GamepadAction.A)
                            },
                            onHover = { focusIndex = resumeRows },
                        )
                        Spacer(Modifier.height(Space.Sm))
                    }
                }
                itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                    SideMenuRow(
                        entry = entry,
                        isCurrent = current != null && entry.isAt(current),
                        waiting = attention[entry.section],
                        selected = index + rowsAbove == focusIndex,
                        onClick = {
                            focusIndex = index + rowsAbove
                            press(GamepadAction.A)
                        },
                        onHover = { focusIndex = index + rowsAbove },
                    )
                }
            }
        }
    }
}

/**
 * A side-menu row's focus: the one row focus every menu has ([rowFocus], Droidtop/tracker#644: the accent at 22 percent
 * and a 4 dp accent bar at the leading edge, no ring, no flash, nothing growing), and [current]'s "you are here" pill on
 * that edge while the cursor is elsewhere. A pointer over the row moves the cursor to it.
 */
private fun Modifier.sideMenuFocus(selected: Boolean, onClick: () -> Unit, onHover: () -> Unit, current: Boolean = false): Modifier = composed {
    val shown = selected && PadModality.showsFocus
    // A pointer over a row moves the cursor to it: pointer and focus are one selection (docs/SPEC.md 7j).
    val source = remember { MutableInteractionSource() }
    val hovered = rememberUpdatedState(onHover)
    LaunchedEffect(source) {
        source.interactions.collect { if (it is HoverInteraction.Enter) hovered.value() }
    }
    Modifier
        .fillMaxWidth()
        .heightIn(min = sideMenuRowHeight())
        .hoverable(source)
        .rowFocus(shown, current = current)
        // No indication of its own: the cursor above is the one selection. The default one drew a grey plate on the
        // row under the pointer or with Android's focus while the cursor sat on another (Droidtop/tracker#639).
        .padRow(silent = true, interactionSource = source, onClick = onClick)
}

/** A side-menu row's height: Steam's 48dp, and at least one touch target where fingers are the input. */
@Composable
private fun sideMenuRowHeight(): Dp {
    val window = currentShellWindow()
    return maxOf(SideMenu.RowHeight, if (window.touchFirst) window.pressTarget else 0.dp)
}

/**
 * One destination row: an icon and a label, flat to the panel's edges. The
 * destination the user is on carries a short accent pill at the panel's edge
 * (Steam's "you are here"); [waiting] adds an accent dot or a job ring after the label.
 * The cursor and the mark are [sideMenuFocus], and the menu opens with the cursor on the current row.
 */
@Composable
private fun SideMenuRow(entry: LeftMenuEntry, isCurrent: Boolean, waiting: Attention?, selected: Boolean, onClick: () -> Unit, onHover: () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.sideMenuFocus(selected, onClick, onHover, current = isCurrent)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SideMenu.RowPaddingStart, end = SideMenu.RowPaddingEnd),
        ) {
            Icon(
                entry.glyph(),
                contentDescription = null,
                tint = if (selected || isCurrent) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                modifier = Modifier.size(SideMenu.IconSize),
            )
            Spacer(Modifier.width(SideMenu.IconGap))
            // Two lines, so a long name ("Downloads and Updates") is said whole, never cut (Droidtop/tracker#639); the row
            // grows past its 48dp for it.
            Text(
                entry.label,
                color = MenuTokens.OnSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                style = TypeRole.rowTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when (waiting) {
                null -> Unit
                Attention.Dot -> {
                    Spacer(Modifier.width(Space.Sm))
                    Box(Modifier.size(SideMenu.AttentionDot).background(MenuTokens.Accent, Corners.Pill))
                }
                is Attention.Ring -> {
                    Spacer(Modifier.width(Space.Sm))
                    dev.droidtop.ui.shared.JobRing(
                        waiting.fraction,
                        dev.droidtop.library.settings.JobLook.of(waiting.fraction)!!,
                        shellJobColors(),
                    )
                }
            }
        }
    }
}

/** The row that opens the one search: the same shape as a destination, without the "you are here" mark. */
@Composable
private fun SearchRow(selected: Boolean, onClick: () -> Unit, onHover: () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.sideMenuFocus(selected, onClick, onHover)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SideMenu.RowPaddingStart, end = SideMenu.RowPaddingEnd),
        ) {
            Icon(
                dev.droidtop.library.settings.CatalogIcon.SEARCH.glyph(),
                contentDescription = null,
                tint = if (selected) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                modifier = Modifier.size(SideMenu.IconSize),
            )
            Spacer(Modifier.width(SideMenu.IconGap))
            Text(
                "Search",
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.Medium,
                style = TypeRole.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The running game, one press from the menu (DroidDeck's Resume item,
 * ui/FrontEndRail.kt at 9310d19, reworked to the menu's row and the shell's
 * tokens): a live dot in the affirmative colour, whose ring pulses outwards
 * while motion is on and is absent when it is off, then "Resume" over the
 * game's name. A resumes it through the same launch path the Quick Menu's
 * Game section uses.
 */
@Composable
private fun ResumeRow(title: String, selected: Boolean, onClick: () -> Unit, onHover: () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.sideMenuFocus(selected, onClick, onHover)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SideMenu.RowPaddingStart, end = SideMenu.RowPaddingEnd, top = Space.Xs, bottom = Space.Xs),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SideMenu.IconSize)) {
                LiveDot()
            }
            Spacer(Modifier.width(SideMenu.IconGap))
            Column(verticalArrangement = Arrangement.spacedBy(Space.Hair)) {
                Text(stringResource(R.string.resume), color = MenuTokens.OnSurface, style = TypeRole.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(title, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The live dot, and while motion is on a ring that grows out of it and fades, once per [Motion.LivePulseMs]. */
@Composable
private fun LiveDot() {
    val colour = MenuTokens.Affirmative
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SideMenu.LiveRing)) {
        if (Motion.enabled) {
            val pulse by rememberInfiniteTransition(label = "live").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(Motion.LivePulseMs, easing = Motion.Glide), RepeatMode.Restart),
                label = "live ring",
            )
            Box(
                Modifier
                    .size(SideMenu.LiveRing)
                    .drawBehind {
                        val p = pulse
                        val radius = size.minDimension / 2f * (SideMenu.LiveDot / SideMenu.LiveRing + (SideMenu.LiveRingGrowth - SideMenu.LiveDot / SideMenu.LiveRing) * p)
                        drawCircle(colour.copy(alpha = colour.alpha * (1f - p)), radius = radius, style = Stroke(MenuTokens.FocusRingWidth.toPx()))
                    },
            )
        }
        Box(Modifier.size(SideMenu.LiveDot).background(colour, Corners.Pill))
    }
}
