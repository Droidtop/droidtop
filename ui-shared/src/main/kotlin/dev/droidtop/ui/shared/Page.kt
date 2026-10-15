package dev.droidtop.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidtop.library.settings.ButtonBarItem
import dev.droidtop.library.settings.ButtonRole
import dev.droidtop.library.settings.CatalogChip
import dev.droidtop.library.settings.PageButton
import dev.droidtop.library.settings.PageHeader

/*
 * The page pieces every surface draws alike (docs/SPEC.md 7k, "Page anatomy"; Droidtop/tracker#485 F2a):
 * the header, the button bar, the empty/loading/error block and the focus line. Gaming, the companion and
 * Standard (through a ComposeView) draw these; none of them draws its own. Colours come from the
 * surface's MaterialTheme (Gaming's is the active theme's palette), the selection from the caller, because
 * each surface has its own cursor (Gaming's sliding ring, touch on the companion).
 */

/** The colours a page's buttons are drawn in; the default is the surface's MaterialTheme. */
@Immutable
class PageColors(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val danger: Color,
    val onDanger: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
)

@Composable
fun defaultPageColors(): PageColors {
    val c = MaterialTheme.colorScheme
    return PageColors(
        primary = c.primary,
        onPrimary = c.onPrimary,
        secondary = c.surfaceVariant,
        onSecondary = c.onSurface,
        danger = c.error,
        onDanger = c.onError,
        outline = c.outline,
        text = c.onSurface,
        muted = c.onSurfaceVariant,
    )
}

/**
 * The page header: a 64dp picture (the thing's own art through [image], else [glyph]), the title, the
 * byline and the fact chips. Not focusable; marked as a heading for a screen reader.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PageHeaderView(
    header: PageHeader,
    modifier: Modifier = Modifier,
    image: (@Composable () -> Unit)? = null,
    glyph: ImageVector? = null,
    colors: PageColors = defaultPageColors(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .semantics(mergeDescendants = true) { heading() }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (image != null || glyph != null) {
            Box(Modifier.size(PageMetrics.HeaderArt), contentAlignment = Alignment.Center) {
                if (image != null) {
                    image()
                } else if (glyph != null) {
                    Icon(glyph, contentDescription = null, tint = colors.text, modifier = Modifier.size(40.dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(header.title, color = colors.text, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            header.byline?.let { Text(it, color = colors.muted, fontSize = 14.sp, lineHeight = 18.sp) }
            if (header.chips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    header.chips.forEach { FactChip(it, colors) }
                }
            }
        }
    }
}

@Composable
private fun FactChip(chip: CatalogChip, colors: PageColors) {
    Text(
        chip.label,
        color = colors.text,
        fontSize = 14.sp,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = 24.dp)
            .border(1.dp, colors.outline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/**
 * The page's button bar: up to three buttons, then "More" when the page has more. Each button is at least
 * 48dp tall and 120dp wide (the primary 160dp), and its label is never cut: a long label wraps and the bar
 * grows, and at large text sizes the buttons stack onto further lines. A danger button sits after a rule.
 * [selected] is the slot under the surface's own cursor (null when the cursor is elsewhere or a finger
 * drives); [ring] is that surface's focus ring for one button. [onPress] gets the slot index
 * ([ButtonBarItem.slots]; the last is More when there is one).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ButtonBarView(
    bar: ButtonBarItem,
    selected: Int?,
    onPress: (Int) -> Unit,
    modifier: Modifier = Modifier,
    ring: @Composable (Boolean) -> Modifier = { Modifier },
    colors: PageColors = defaultPageColors(),
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(PageMetrics.ButtonGap),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bar.slots.forEachIndexed { index, button ->
            if (button?.role == ButtonRole.DANGER) {
                // Destructive actions sit after a rule, never beside the page's job.
                Box(Modifier.padding(vertical = 8.dp).width(1.dp).height(32.dp).background(colors.outline))
            }
            PageButtonView(
                label = button?.shownLabel ?: ButtonBarItem.MORE_LABEL,
                role = button?.role ?: ButtonRole.SECONDARY,
                enabled = button?.enabled ?: true,
                icon = if (button == null) Icons.Filled.MoreHoriz else null,
                isSelected = selected == index,
                ring = ring(selected == index),
                colors = colors,
                onClick = { onPress(index) },
            )
        }
    }
}

/** One page button; disabled, its label stays in full ink and only the plate dims. */
@Composable
fun PageButtonView(
    label: String,
    role: ButtonRole,
    enabled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    ring: Modifier = Modifier,
    colors: PageColors = defaultPageColors(),
) {
    val (fill, ink) = when (role) {
        ButtonRole.PRIMARY -> colors.primary to colors.onPrimary
        ButtonRole.SECONDARY -> colors.secondary to colors.onSecondary
        ButtonRole.DANGER -> colors.danger to colors.onDanger
    }
    val shape = RoundedCornerShape(PageMetrics.ButtonCorner)
    Row(
        modifier = modifier
            .then(ring)
            .heightIn(min = PageMetrics.ButtonHeight)
            .widthIn(min = if (role == ButtonRole.PRIMARY) PageMetrics.PrimaryMinWidth else PageMetrics.ButtonMinWidth)
            .background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.45f), shape)
            .then(if (role == ButtonRole.SECONDARY) Modifier.border(1.dp, colors.outline, shape) else Modifier)
            .semantics {
                this.role = Role.Button
                this.selected = isSelected
                contentDescription = label
                if (!enabled) disabled()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        // Full ink even when disabled: the label is the reason, and a reason must be readable.
        Text(label, color = ink, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** The kinds of [StateBlock] (docs/SPEC.md 7k, "Empty, loading and error"). */
enum class StateKind { LOADING, EMPTY, ERROR }

/**
 * A page's empty, loading or error state: a 48dp glyph, a title of at most 32 characters, one body line,
 * and a next action (an empty state always has one). Loading never shows a bare title or an endless
 * spinner: the caller passes [slowLine] after two seconds, naming what is slow.
 */
@Composable
fun StateBlock(
    kind: StateKind,
    title: String,
    modifier: Modifier = Modifier,
    glyph: ImageVector? = null,
    body: String? = null,
    slowLine: String? = null,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    selected: Int? = null,
    ring: @Composable (Boolean) -> Modifier = { Modifier },
    colors: PageColors = defaultPageColors(),
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (glyph != null) Icon(glyph, contentDescription = null, tint = colors.muted, modifier = Modifier.size(48.dp))
        Text(
            title,
            color = colors.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        body?.let { Text(it, color = colors.muted, fontSize = 14.sp, textAlign = TextAlign.Center) }
        if (kind == StateKind.LOADING) slowLine?.let { Text(it, color = colors.muted, fontSize = 14.sp, textAlign = TextAlign.Center) }
        if (primary != null || secondary != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(PageMetrics.ButtonGap)) {
                primary?.let { (label, run) ->
                    PageButtonView(label, ButtonRole.PRIMARY, enabled = true, isSelected = selected == 0, onClick = run, ring = ring(selected == 0), colors = colors)
                }
                secondary?.let { (label, run) ->
                    PageButtonView(label, ButtonRole.SECONDARY, enabled = true, isSelected = selected == 1, onClick = run, ring = ring(selected == 1), colors = colors)
                }
            }
        }
    }
}

/**
 * The focus line (docs/SPEC.md 7k, "Page anatomy"): the focused item's Info sentence on a catalog page,
 * or the facts on a library page, in 16sp over one to three lines. It never cuts silently: past three
 * lines it ends "… Y Info", so the person knows where the rest is. It is not a live region: a screen
 * reader hears the focused item's own description instead.
 */
@Composable
fun FocusLine(text: String, modifier: Modifier = Modifier, colors: PageColors = defaultPageColors()) {
    var cut by remember(text) { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PageMetrics.FocusLineMin)
            .focusProperties { canFocus = false }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text,
            color = colors.text,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            maxLines = FOCUS_LINE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
            modifier = Modifier.weight(1f, fill = false),
        )
        if (cut) Text(" Y Info", color = colors.muted, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1)
    }
}

/** The page's shared measurements (docs/SPEC.md 7k, "Page anatomy"); every height is a minimum that grows with text size. */
object PageMetrics {
    val HeaderArt = 64.dp
    val ButtonHeight = 48.dp
    val ButtonMinWidth = 120.dp
    val PrimaryMinWidth = 160.dp
    val ButtonGap = 12.dp
    val ButtonCorner = 6.dp
    val FocusLineMin = 32.dp
}

const val FOCUS_LINE_MAX_LINES = 3
