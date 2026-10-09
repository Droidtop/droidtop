package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One entry of a [TabRail]: what it is called, its glyph, and what is waiting behind it ([badge] a count, or
 * [dot] for "something new" without one).
 */
data class RailTab(
    val key: Any,
    val label: String,
    val glyph: QuickGlyph,
    val badge: Int = 0,
    val dot: Boolean = false,
) {
    /** What TalkBack says for it: the name and the count, never an icon alone. */
    val spoken: String get() = if (badge > 0) "$label, $badge new" else label
}

/** The rail's inks: the Quick Menu passes the shell's tokens, the companion its Material colours. */
data class RailColors(val selected: Color, val ink: Color, val muted: Color, val accent: Color, val onAccent: Color)

/**
 * The one tab rail (docs/SPEC.md "The companion's tabs" and "Quick Menu: a branching panel"): the Quick Menu's
 * section rail and the companion's bar and side rail are this, drawn two ways.
 * - [labelled] false: a glyph per entry in a square touch target, scrolling when they outnumber the room, the
 *   current one kept in view (the Quick Menu, which the pad steps with L1 and R1).
 * - [labelled] true: a glyph over a label of up to two lines, the entries sharing the whole length evenly; the
 *   caller decides what fits (the measured label sizes feed the companion's `slots`, see [labelExtent]), so nothing
 *   scrolls and no label is cut off.
 * Every entry has the tab role, its selected state and a spoken name with its count. It is never a focus target:
 * a tap selects.
 */
@Composable
fun TabRail(
    tabs: List<RailTab>,
    selected: Any?,
    vertical: Boolean,
    labelled: Boolean,
    colors: RailColors,
    onSelect: (RailTab) -> Unit,
    modifier: Modifier = Modifier,
    target: Dp = 48.dp,
) {
    val entry: @Composable (RailTab, Modifier) -> Unit = { tab, itemModifier ->
        val current = tab.key == selected
        val requester = remember { BringIntoViewRequester() }
        LaunchedEffect(current) { if (current && !labelled) requester.bringIntoView() }
        Box(
            contentAlignment = Alignment.Center,
            modifier = itemModifier
                .bringIntoViewRequester(requester)
                .sizeIn(minWidth = target, minHeight = target)
                .clip(RoundedCornerShape(10.dp))
                .background(if (current) colors.selected else Color.Transparent)
                // Ahead of the clickable: a plain clickable is still a focus target, and the pad never drives this.
                .focusProperties { canFocus = false }
                .clickable { onSelect(tab) }
                // One spoken element per entry: its name and count, the tab role, its state, and the tap.
                .clearAndSetSemantics {
                    contentDescription = tab.spoken
                    role = Role.Tab
                    this.selected = current
                    onClick { onSelect(tab); true }
                },
        ) {
            val ink = if (current) colors.ink else colors.muted
            if (labelled) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    QuickGlyphIcon(glyph = tab.glyph, tint = ink, modifier = Modifier.size(22.dp))
                    Text(
                        tab.label,
                        style = TextStyle(fontSize = LABEL_SIZE, lineHeight = LABEL_LINE, textAlign = TextAlign.Center),
                        color = ink,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                QuickGlyphIcon(glyph = tab.glyph, tint = ink, modifier = Modifier.size(22.dp))
            }
            if (!current && (tab.badge > 0 || tab.dot)) {
                Badge(tab.badge, colors, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
    }
    if (vertical) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .let { if (labelled) it else it.verticalScroll(rememberScrollState()) }
                .padding(vertical = if (labelled) 4.dp else 12.dp, horizontal = if (labelled) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tabs.forEach { tab ->
                key(tab.key) { entry(tab, if (labelled) Modifier.fillMaxWidth().weight(1f, fill = false) else Modifier) }
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .let { if (labelled) it else it.horizontalScroll(rememberScrollState()) }
                .padding(horizontal = if (labelled) 4.dp else 12.dp, vertical = if (labelled) 4.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                key(tab.key) { entry(tab, if (labelled) Modifier.weight(1f).heightIn(min = target) else Modifier) }
            }
        }
    }
}

/** A count in a small accent disc ("9+" past nine), or a plain dot when there is no count. */
@Composable
private fun Badge(count: Int, colors: RailColors, modifier: Modifier) {
    if (count <= 0) {
        Box(modifier.size(8.dp).clip(RoundedCornerShape(50)).background(colors.accent))
        return
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.sizeIn(minWidth = 18.dp, minHeight = 18.dp).clip(RoundedCornerShape(50)).background(colors.accent),
    ) {
        Text(
            if (count > 9) "9+" else count.toString(),
            style = TextStyle(fontSize = 11.sp),
            color = colors.onAccent,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** The labelled rail's label type: its size, which the companion measures labels with before laying out. */
val LABEL_SIZE = 12.sp
val LABEL_LINE = 15.sp

/** The width of a labelled entry around its label: the label's own width plus the entry's padding. */
fun labelExtent(labelWidth: Dp): Dp = labelWidth + 8.dp + 4.dp
