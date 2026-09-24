package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap

/*
 * The Gaming shell's shared menu language, in one place. Its colours
 * and measures are [MenuTokens], in DesignTokens.kt with the rest of the
 * design system (docs/SPEC.md 7k); the row anatomy is here.
 *
 * Every menu-ish surface in this shell (settings catalogs, the gamelist
 * options overlay, the Quick Menu, the metadata and collection editors,
 * the display chooser) used to pick its own greys, its own row padding
 * and its own idea of what "selected" looks like -- 43 hand-written
 * colors across five files, which is exactly why the menus read as
 * unfinished next to the themed views. These are the tokens and the row
 * anatomy all of them share now.
 *
 * The rules the anatomy encodes, so surfaces stop re-deciding them:
 * - Every row sits on a faint card. SELECTION BRIGHTENS THAT CARD; it
 *   never materialises a slab under text that was previously floating.
 * - Titles are one line, subtitles at most two, so rows keep a uniform
 *   height and a list scans as a column instead of a ragged stack.
 * - A value and a chevron are different things: a chevron means "this
 *   opens", a value means "this is set to". Nothing renders a chevron
 *   in a value's place.
 * - Unset reads as a dim placeholder, never as loud as a real value.
 */

/**
 * List padding shared by every full-screen menu list, as a LazyColumn's
 * own `contentPadding` -- with the room the hint bar takes at the bottom.
 *
 * A padding MODIFIER on a lazy list shrinks its viewport, so its last row
 * ends exactly where the hint bar begins and the bar draws over it (rig:
 * "the bottom row is clipped under the hint bar with no fade"). As
 * CONTENT padding the same space scrolls with the list and the last row
 * comes clear.
 */
internal val MenuListContentPadding: PaddingValues
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = PaddingValues(
        start = LocalShellWindow.current.edgePadding,
        end = LocalShellWindow.current.edgePadding,
        top = 12.dp,
        bottom = MenuTokens.HintBarRoom,
    )

/**
 * The shell's ONE selection idiom: an accent ring over a raised fill.
 *
 * Every focusable piece of chrome draws selection through this -- menu
 * rows, chips, tabs, buttons, cards, Quick Menu tiles -- so "what am I
 * on" has one answer across the shell. Settings rows used to show focus
 * only as a slightly lighter card (about #2e on #121212), which the UI
 * pass of 2026-09-24 (M1) found hard to see at arm's length on a 5.5"
 * screen, while the cards and the Quick Menu already drew the ring.
 *
 * [rest] is the fill while not selected; [restOutline] is an optional
 * hairline kept while not selected (the cards keep [MenuTokens.CardOutline]).
 */
internal fun Modifier.selectionFrame(
    selected: Boolean,
    shape: Shape,
    rest: Color = MenuTokens.Surface,
    restOutline: Color = Color.Transparent,
): Modifier = this
    .background(if (selected) MenuTokens.SurfaceSelected else rest, shape)
    .border(
        width = when {
            selected -> MenuTokens.FocusRingWidth
            restOutline != Color.Transparent -> 1.dp
            else -> 0.dp
        },
        color = if (selected) MenuTokens.Accent else restOutline,
        shape = shape,
    )

/**
 * The shell's one chip: a pill that is focusable for the pad and
 * clickable for touch. [on] is a filter or toggle in effect: it is filled
 * with the accent and carries a check, so which filters are on reads
 * without moving onto them (UI pass 2026-09-24, L3). [primary] is the one
 * action a row of chips leads with (Launch, Save). Focus is the
 * [selectionFrame] ring, as on every other piece of chrome.
 *
 * This replaces three private copies (the detail screen's action chip,
 * the recent filter and the PC surface's filter chip), each of which drew
 * focus its own way.
 */
@Composable
internal fun ShellChip(
    label: String,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val filled = on || primary
    Text(
        if (on) "\u2713 $label" else label,
        color = if (filled) MenuTokens.OnSelected else MenuTokens.OnSurface,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = modifier
            // Ahead of the focus targets, not after them: see [GameCard].
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .then(
                if (filled) {
                    Modifier
                        .background(if (focused) MenuTokens.Selected else MenuTokens.Accent, shape)
                        .border(if (focused) MenuTokens.FocusRingWidth else 0.dp, MenuTokens.Accent, shape)
                } else {
                    Modifier.selectionFrame(focused, shape)
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A screen-level menu header: name first, explanation second, both quiet. */
@Composable
internal fun MenuHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    // The same gutter the list below it uses. A fixed 48dp here put the
    // header a third of a phone's width in from rows indented 16dp.
    Column(modifier.padding(horizontal = LocalShellWindow.current.edgePadding).padding(top = 18.dp, bottom = 2.dp)) {
        Text(
            title,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.let {
            Text(
                it,
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A group label: a real section marker, not another grey title competing with the rows. */
@Composable
internal fun MenuSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = MenuTokens.SectionLabel,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = TextUnit(1.2f, TextUnitType.Sp),
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
    )
}

/**
 * The one row anatomy. [value] is what the setting is set to; [chevron]
 * says the row opens something; [accent] paints a leading rail for rows
 * that carry an identity color (a system's own, say).
 */
@Composable
internal fun MenuRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    placeholder: Boolean = false,
    adjustable: Boolean = false,
    chevron: Boolean = false,
    selected: Boolean = false,
    danger: Boolean = false,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    // Long-press is the touch route to Y on a row (the same convention
    // the shell's cards use for their detail).
    onLongClick: (() -> Unit)? = null,
    // A row that is a status read-out rather than a setting (an action's
    // multi-line result) may take more lines; a setting's row takes one.
    subtitleLines: Int = 1,
    // An [adjustable] row is stepped with Left/Right on the pad. A touch
    // screen has no Left/Right, so on one the two arrows this row
    // already draws become the two targets that call this -- without it
    // a slider in Settings has no touch route at all, in either
    // direction, and a cycling choice only has a forwards one.
    onAdjust: ((Int) -> Unit)? = null,
) {
    val window = LocalShellWindow.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // The one height rule, plus a touch target where fingers are
            // the input: a 56dp row is uniform everywhere, and on a
            // touch-first window it is at least one touch target tall.
            .heightIn(min = if (window.touchFirst) window.minTouchTarget else MenuTokens.RowMinHeight)
            .clip(MenuTokens.RowShape)
            .selectionFrame(selected, MenuTokens.RowShape)
            // Touch works on every row, always -- the shell is
            // gamepad-first, never gamepad-only.
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) MenuTokens.Danger else MenuTokens.OnSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // One line, so every row with a subtitle is the same height
            // (UI pass 2026-09-24, M14: rows of 84, 93 and more px down one
            // screen). The whole sentence is on the row's Info sheet.
            subtitle?.let {
                Text(
                    it,
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            val valueColor = when {
                placeholder -> MenuTokens.Placeholder
                selected -> MenuTokens.OnSurface
                else -> MenuTokens.Value
            }
            if (adjustable && onAdjust != null && window.touchFirst) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AdjustArrow("‹") { onAdjust(-1) }
                    Text(
                        value,
                        color = valueColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AdjustArrow("›") { onAdjust(+1) }
                }
            } else {
                Text(
                    if (selected && adjustable) "‹ $value ›" else value,
                    color = valueColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(min = MenuTokens.ValueColumnMinWidth),
                )
            }
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = MenuTokens.Placeholder, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * One side of a touch-adjustable row's value: the arrow the row already
 * draws, given a real 48dp target around it. It steps the value through
 * the same [adjustCatalogItem] path Left/Right does -- the arrow is a
 * second way in, never a second definition.
 */
@Composable
private fun AdjustArrow(glyph: String, onPress: () -> Unit) {
    Box(
        modifier = Modifier
            .size(LocalShellWindow.current.minTouchTarget)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
    }
}

/** The button-hint line every menu ends with, so controls are never a guess. */
@Composable
internal fun MenuHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MenuTokens.OnSurfaceMuted,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(top = 10.dp),
    )
}

/**
 * A modal menu panel with focus handled ONCE, here.
 *
 * A Compose Dialog silently drops key events unless something inside it
 * actually holds focus -- a real bug that shipped in this shell before
 * this existed (the gamelist options overlay ignored every D-pad press
 * on a real device). Surfaces built on this cannot reintroduce it.
 */
@Composable
internal fun MenuPanel(
    modifier: Modifier = Modifier,
    focusLabel: String = "Menu",
    onKey: (KeyEvent) -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, focusLabel) }
    Column(
        modifier = modifier
            .clip(MenuTokens.OverlayShape)
            .background(MenuTokens.OverlaySurface)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent(onKey)
            // A panel whose content can outgrow the screen must scroll:
            // the jump-to-letter list reaches 27 rows on a library that
            // spans the alphabet, which is taller than the display.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
        content = content,
    )
}
