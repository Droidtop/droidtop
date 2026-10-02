package dev.droidtop.shell.gamepad

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import kotlin.math.roundToInt

/**
 * The Gaming shell's header: section tabs, the L1/R1 glyphs that step
 * them, the L2 context-menu and R2 Quick Menu indicators (docs/SPEC.md
 * 7k, "Header and footer are one frame").
 *
 * Where there is room the tabs sit in the exact middle of the bar, with
 * the status readout in an equal slot on the left and the R2 indicator in
 * an equal slot on the right, so the bar is balanced about the screen's
 * centre. R2 stays at the top right, near the button it names (owner,
 * 2026-09-29). Where there is not room (a phone, or the four tabs of the
 * desktop-mode set) the tabs take the width and scroll, the readout
 * shrinks to a clock and two glyphs, and the row keeps the selected tab
 * whole in view: it scrolls by as little as it takes, so a label is
 * never clipped mid-word (Droidtop/tracker#165). L2 stays at the left end,
 * while the status readout sits before R2 at the right end.
 *
 * Nothing in it takes D-pad focus (the top bar is reached by touch, L1/R1
 * or Page Up/Page Down only; Droidtop/tracker#1).
 */
@Composable
internal fun SectionTabBar(
    current: GamingSection,
    onSelect: (GamingSection) -> Unit,
    onQuickMenu: () -> Unit,
    contextMenuEnabled: Boolean,
    onContextMenu: () -> Unit,
    sections: List<GamingSection> = GamingSection.entries,
) {
    val window = LocalShellWindow.current
    val tabStyle = MaterialTheme.typography.titleMedium
    val textMeasurer = rememberTextMeasurer()
    // L1/R1 only mean something with a pad; on a touch phone with none
    // attached they are noise in the bar, so they are not drawn there.
    val shoulders = sections.size > 1 && (!window.touchFirst || window.padPresent)
    // Measure the actual labels with the live theme font and text scale.
    // A per-tab estimate can say the centered layout fits while its last
    // selected label is already outside the screen at Largest text.
    val density = LocalDensity.current
    val tabWidths = remember(sections, tabStyle, density.fontScale) {
        sections.sumOf { section ->
            textMeasurer.measure(section.displayName(), tabStyle, maxLines = 1, softWrap = false).size.width
        }
    }
    // Equal side slots keep the tabs centred. Choose that layout only if
    // both slots remain large enough after reserving the measured tabs.
    val slotDp = (window.widthDp - 2 * window.edgePadding.value -
        with(density) { tabWidths.toDp().value } - sections.size * 28f -
        (sections.size - 1) * window.tabGap.value -
        (if (shoulders) 2 * MenuTokens.ShoulderEstimateDp else 0)) / 2f
    val centred = slotDp >= MenuTokens.StatusSlotMinDp

    // Where each tab sits inside the scrolling row, in content pixels, as
    // the row lays out: the selected tab is kept whole in view on top of
    // that (docs/SPEC.md 7k).
    val scrollState = rememberScrollState()
    val tabBounds = remember { FloatArray(sections.size * 2) }
    val tabRevision = remember { mutableIntStateOf(0) }
    val rowX = remember { mutableIntStateOf(0) }
    val rowW = remember { mutableIntStateOf(0) }

    val tabs: @Composable () -> Unit = {
        sections.forEachIndexed { index, entrySection ->
            SectionTab(
                label = entrySection.displayName(),
                isCurrent = entrySection == current,
                style = tabStyle,
                onClick = { onSelect(entrySection) },
                modifier = if (centred) Modifier else Modifier.onGloballyPositioned {
                    val b = it.boundsInRoot()
                    tabBounds[index * 2] = b.left - rowX.value + scrollState.value
                    tabBounds[index * 2 + 1] = b.width
                    tabRevision.value++
                },
            )
        }
    }
    val quickMenu: @Composable () -> Unit = { QuickMenuIndicator(showLabel = !window.compact, onClick = onQuickMenu) }
    val contextMenu: @Composable () -> Unit = { ContextMenuIndicator(contextMenuEnabled, !window.compact, onContextMenu) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = window.frameBarHeight)
            .frameEdge(atTop = false)
            .padding(horizontal = window.edgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (centred) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                contextMenu()
            }
            if (shoulders) ShoulderGlyph("L1", badge = true, modifier = Modifier.padding(end = 6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(window.tabGap),
                verticalAlignment = Alignment.CenterVertically,
            ) { tabs() }
            if (shoulders) ShoulderGlyph("R1", badge = true, modifier = Modifier.padding(start = 6.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusCluster(showBatteryPercent = true, onClick = onQuickMenu)
                    quickMenu()
                }
            }
        } else {
            contextMenu()
            if (shoulders) ShoulderGlyph("L1", badge = true, modifier = Modifier.padding(end = 6.dp))
            // The tabs scroll and the Quick Menu control stays pinned beside
            // them. On a phone the names do not fit across 411dp, and a plain
            // Row silently pushes the last one off the edge, which on the
            // desktop-mode tab set is the tab a user cannot otherwise reach
            // without a pad. Same rule as the hint bar and the PC filter
            // chips: a row that can outgrow the width scrolls rather than
            // clipping -- and the selected tab is the one the bar must
            // never leave clipped: the row scrolls it whole into view, by
            // as little as it takes (docs/SPEC.md 7k).
            Row(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned {
                        rowX.value = it.positionInRoot().x.toInt()
                        rowW.value = it.size.width
                    }
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(window.tabGap),
                verticalAlignment = Alignment.CenterVertically,
            ) { tabs() }
            if (shoulders) ShoulderGlyph("R1", badge = true, modifier = Modifier.padding(start = 6.dp))
            Box(Modifier.padding(start = 8.dp)) {
                StatusCluster(showBatteryPercent = false, onClick = onQuickMenu)
            }
            Box(Modifier.padding(start = 12.dp)) { quickMenu() }
            LaunchedEffect(current, tabRevision.value, rowX.value, rowW.value) {
                val i = sections.indexOf(current)
                if (i < 0) return@LaunchedEffect
                val x = tabBounds[i * 2]
                val w = tabBounds[i * 2 + 1]
                val view = rowW.value.toFloat()
                if (w <= 0f || view <= 0f) return@LaunchedEffect
                val offset = scrollState.value
                if (x >= offset && x + w <= offset + view) return@LaunchedEffect
                // Held L1/R1 steps the selection faster than an animation
                // can follow, so the row lands the tab at once, the same
                // rule the lists' scroll-follow keeps (ScrollFollow.kt).
                val delta = when {
                    x < offset -> x - offset
                    w >= view -> (x + w / 2f) - (offset + view / 2f)
                    else -> x + w - (offset + view)
                }
                scrollState.scrollBy(delta)
            }
        }
    }
}

@Composable
private fun ContextMenuIndicator(enabled: Boolean, showLabel: Boolean, onClick: () -> Unit) {
    val window = LocalShellWindow.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
            .focusProperties { canFocus = false }
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.42f),
    ) {
        Text(
            "L2",
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .border(1.dp, MenuTokens.HintPillOutline, RoundedCornerShape(50))
                .padding(horizontal = 9.dp)
                .opticallyCentred(MenuTokens.TabPillHeight, MaterialTheme.typography.labelLarge.fontSize),
        )
        if (showLabel) Text("Options", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * One section tab. The selected one is a pill of fixed height with its
 * label centred on the capitals' middle, so the pill is not bottom-heavy
 * (owner's first tester, 2026-09-29, Droidtop/tracker#157). A touch-first
 * window wraps the pill in an invisible 48dp tap target; the pill itself
 * is the same height either way.
 */
@Composable
private fun SectionTab(
    label: String,
    isCurrent: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = LocalShellWindow.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
            // Deliberately NOT a directional-search target (owner,
            // 2026-09-27: "the D-pad must NEVER be able to reach the top
            // bar"; Droidtop/tracker#1). `Modifier.clickable` chains its own
            // internal `.focusable()`, which is exactly the node Compose's
            // default 2D focus search picks up from the first row of a
            // list; `canFocus = false` ahead of it in the same chain makes
            // the node unfocusable while leaving the tap intact. L1/R1 and
            // Page Up/Page Down remain the only pad route.
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (isCurrent) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
            style = style,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .then(if (isCurrent) Modifier.background(MenuTokens.SurfaceSelected) else Modifier)
                .padding(horizontal = 14.dp)
                .opticallyCentred(MenuTokens.TabPillHeight, style.fontSize),
        )
    }
}

/**
 * On-screen indicator for the Quick Menu button: the bordered pill names
 * the physical button, the label names what it opens. Tapping it opens the
 * menu too, the same touch-parity rule as the tabs. Its place (top right,
 * near the R2 button) is deliberate and does not move.
 */
@Composable
private fun QuickMenuIndicator(showLabel: Boolean, onClick: () -> Unit) {
    val window = LocalShellWindow.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
            // Same reasoning and same fix as the tabs: `.clickable()` alone
            // is a directional-search target, and this sits in the top bar
            // the D-pad must never reach.
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
    ) {
        Text(
            "R2",
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .border(1.dp, MenuTokens.HintPillOutline, RoundedCornerShape(50))
                .padding(horizontal = 9.dp)
                .opticallyCentred(MenuTokens.TabPillHeight, MaterialTheme.typography.labelLarge.fontSize),
        )
        if (showLabel) {
            Text("Quick Menu", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Centres a single line of text on the middle of its capitals inside a box
 * [height] tall. Text's own line box is ascent to descent, so centring the
 * box leaves the letters riding high with the descender's empty room below
 * them; that is the "bottom-heavy" pill. The first baseline is read from
 * the measured text and the capitals' middle (about 0.36 em above it) is
 * put on the box's middle. No allocation outside layout.
 */
internal fun Modifier.opticallyCentred(height: Dp, fontSize: TextUnit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val boxHeight = height.roundToPx()
    val baseline = placeable[FirstBaseline]
    val y = if (baseline == AlignmentLine.Unspecified) {
        (boxHeight - placeable.height) / 2
    } else {
        val em = (if (fontSize.isSp) fontSize else 16.sp).toPx()
        (boxHeight / 2f - (baseline - CAP_MIDDLE_EM * em)).roundToInt()
    }
    layout(placeable.width, boxHeight) { placeable.place(0, y) }
}

private const val CAP_MIDDLE_EM = 0.36f

/**
 * The hairline that ends a frame bar on the side facing the content: the
 * header's bottom edge, the footer's top edge. The only shared drawing the
 * two bars have beyond their height and gutter, so they read as one frame
 * (docs/SPEC.md 7k) while the footer keeps its own plate.
 */
internal fun Modifier.frameEdge(atTop: Boolean): Modifier = drawBehind {
    val y = if (atTop) 0.5f else size.height - 0.5f
    drawLine(MenuTokens.FrameHairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
}
