package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.library.social.SocialHub
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds

/**
 * One place the left menu can take the user. Home is the PC Games section
 * showing recent activity across every library ([home]); the "PC Games" row
 * is the same section showing the PC library's own Overview shelves and its
 * grid views (docs/SPEC.md 7i, "Home art").
 */
internal data class LeftMenuEntry(val section: GamingSection, val label: String, val home: Boolean = false) {
    val key: String get() = if (home) "home" else section.name

    /** Whether this row is where the user is: [atHome] tells Home from PC Games. */
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
 * [rowsAbove] is how many rows sit above the destinations (the Resume row).
 */
internal fun leftMenuStartIndex(entries: List<LeftMenuEntry>, current: GamingSection, atHome: Boolean = false, rowsAbove: Int = 0): Int =
    rowsAbove + entries.indexOfFirst { it.isAt(current, atHome) }.coerceAtLeast(0)

/**
 * The destinations with something waiting, for the menu's attention dots
 * (DroidDeck's rail badge, ui/FrontEndRail.kt at 9310d19): Downloads while a
 * job is running, Social while a message is unread. Read while the menu is
 * open, never polled. Pure.
 */
internal fun leftMenuAttention(runningJobs: Int, unreadMessages: Int): Set<GamingSection> = buildSet {
    if (runningJobs > 0) add(GamingSection.DOWNLOADS)
    if (unreadMessages > 0) add(GamingSection.SOCIAL)
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
 * is on); the destinations follow, the one the person is on marked by a
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
    current: GamingSection,
    atHome: Boolean,
    runningTitle: String?,
    onResume: () -> Unit,
    onSelect: (LeftMenuEntry) -> Unit,
    onOpenQuickMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    val window = currentShellWindow()
    val resumeRows = if (runningTitle != null) 1 else 0
    val rowCount = resumeRows + entries.size
    // What is waiting, for the dots: read while the menu is open, never polled.
    val jobs by PluginJobsCenter.entries().collectAsState()
    val unread by produceState(SocialHub.unread()) { SocialHub.changes().collect { value = SocialHub.unread() } }
    val attention = leftMenuAttention(jobs.count { !it.done && !it.paused }, unread)
    // The frame's close slides the panel out first; every way out here goes through it.
    SidePanelFrame(
        edge = PanelEdge.LEFT,
        onDismiss = onDismiss,
        panelWidth = { screen -> if (window.portrait) screen * SideMenu.PortraitFraction else SideMenu.Width.coerceAtMost(screen) },
        scrim = MenuTokens.Scrim,
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
        var focusIndex by remember { mutableIntStateOf(leftMenuStartIndex(entries, current, atHome, resumeRows)) }
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
                            if (next != focusIndex) EsDeNavigationSounds.play("scroll")
                            focusIndex = next
                        }
                        GamepadAction.A -> if (focusIndex < resumeRows) onResume() else entries.getOrNull(focusIndex - resumeRows)?.let(onSelect)
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
                        )
                        Spacer(Modifier.height(Space.Sm))
                    }
                }
                itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                    SideMenuRow(
                        entry = entry,
                        isCurrent = entry.isAt(current, atHome),
                        waiting = entry.section in attention && !entry.home,
                        selected = index + resumeRows == focusIndex,
                        onClick = {
                            focusIndex = index + resumeRows
                            press(GamepadAction.A)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Steam's side-menu row focus (docs/SPEC.md 7k): while the pad drives and the
 * cursor is on the row, its plate flashes the text ink at [SideMenu.FlashFrom]
 * and settles to [SideMenu.FlashTo] ([Motion.FlashMs]), and [grow] eases its
 * content to [SideMenu.FocusScale]. The colour is drawn and the growth applied
 * in the draw and layer phases, so the animation recomposes nothing. The
 * window's one sliding ring marks the row too, as it marks every selection.
 */
private fun Modifier.sideMenuFocus(selected: Boolean, onClick: () -> Unit): Modifier = composed {
    val shown = selected && PadModality.showsFocus
    val flash = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            flash.snapTo(SideMenu.FlashFrom)
            flash.animateTo(SideMenu.FlashTo, Motion.flash())
        } else {
            flash.snapTo(0f)
        }
    }
    val ink = MenuTokens.OnSurface
    Modifier
        .fillMaxWidth()
        .heightIn(min = sideMenuRowHeight())
        .drawBehind {
            val alpha = flash.value
            if (alpha > 0f) drawRect(ink.copy(alpha = alpha))
        }
        .focusRing(shown, RectangleShape)
        .clickable(onClick = onClick)
}

/** The content of a focused row grows rightwards from near its left edge; the plate stays put. */
private fun Modifier.sideMenuGrow(selected: Boolean): Modifier = composed {
    val grow by animateFloatAsState(if (selected && PadModality.showsFocus) SideMenu.FocusScale else 1f, Motion.focus(), label = "side menu grow")
    Modifier.graphicsLayer {
        scaleX = grow
        scaleY = grow
        transformOrigin = TransformOrigin(SideMenu.FocusPivotX, 0.5f)
    }
}

/** A side-menu row's height: Steam's 48dp, and at least one touch target where fingers are the input. */
@Composable
private fun sideMenuRowHeight(): Dp {
    val window = currentShellWindow()
    return maxOf(SideMenu.RowHeight, if (window.touchFirst) window.minTouchTarget else 0.dp)
}

/**
 * One destination row: an icon and a label, flat to the panel's edges. The
 * destination the user is on carries a short accent pill at the panel's edge
 * (Steam's "you are here"); [waiting] adds an accent dot after the label.
 * The cursor is [sideMenuFocus], and the menu opens with it on the current row.
 */
@Composable
private fun SideMenuRow(entry: LeftMenuEntry, isCurrent: Boolean, waiting: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.sideMenuFocus(selected, onClick)) {
        if (isCurrent) CurrentMark()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .sideMenuGrow(selected)
                .padding(start = SideMenu.RowPaddingStart, end = SideMenu.RowPaddingEnd),
        ) {
            Icon(
                entry.glyph(),
                contentDescription = null,
                tint = if (selected || isCurrent) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                modifier = Modifier.size(SideMenu.IconSize),
            )
            Spacer(Modifier.width(SideMenu.IconGap))
            Text(
                entry.label,
                color = MenuTokens.OnSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                style = TypeRole.rowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (waiting) {
                Spacer(Modifier.width(Space.Sm))
                Box(Modifier.size(SideMenu.AttentionDot).background(MenuTokens.Accent, Corners.Pill))
            }
        }
    }
}

/** "You are here": a short accent pill on the panel's own edge. */
@Composable
private fun BoxScope.CurrentMark() {
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .size(width = SideMenu.CurrentMarkWidth, height = SideMenu.CurrentMarkHeight)
            .background(MenuTokens.Accent, Corners.Pill),
    )
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
private fun ResumeRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.sideMenuFocus(selected, onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .sideMenuGrow(selected)
                .padding(start = SideMenu.RowPaddingStart, end = SideMenu.RowPaddingEnd, top = Space.Xs, bottom = Space.Xs),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SideMenu.IconSize)) {
                LiveDot()
            }
            Spacer(Modifier.width(SideMenu.IconGap))
            Column(verticalArrangement = Arrangement.spacedBy(Space.Hair)) {
                Text("Resume", color = MenuTokens.OnSurface, style = TypeRole.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
