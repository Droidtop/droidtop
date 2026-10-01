package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.input.PadPress
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad

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
 *
 * Drawn only while a pad or keyboard is driving ([PadModality], docs/
 * SPEC.md 6e): on touch the selection is still there -- a tap moves it --
 * but a ring on a row nobody is pressing reads as "the default".
 */
fun Modifier.selectionFrame(
    selected: Boolean,
    shape: Shape,
    rest: Color = MenuTokens.Surface,
    restOutline: Color = Color.Transparent,
): Modifier {
    val shown = selected && PadModality.showsFocus
    return this
        .background(if (shown) MenuTokens.SurfaceSelected else rest, shape)
        // Never a 0.dp border: Compose draws 0.dp (Dp.Hairline) as a 1px line,
        // so "no ring" is a transparent colour, not a zero width.
        .border(
            width = if (shown) MenuTokens.FocusRingWidth else 1.dp,
            color = if (shown) MenuTokens.Accent else restOutline,
            shape = shape,
        )
}

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
    // The one big button of a page (the PC game page's Play).
    large: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val filled = on || primary
    Text(
        if (on) "\u2713 $label" else label,
        color = if (filled) MenuTokens.OnSelected else MenuTokens.OnSurface,
        style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
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
                    val ring = focused && PadModality.showsFocus
                    Modifier
                        .background(if (ring) MenuTokens.Selected else MenuTokens.Accent, shape)
                        .border(MenuTokens.FocusRingWidth, if (ring) MenuTokens.Accent else Color.Transparent, shape)
                } else {
                    Modifier.selectionFrame(focused, shape)
                },
            )
            .padding(horizontal = if (large) 28.dp else 16.dp, vertical = if (large) 14.dp else 8.dp),
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
    // A category glyph (docs/SPEC.md 7k) -- rows that OPEN something carry
    // one, so the list scans by shape the way a polished console settings
    // list does, rather than every row reading identically (settings
    // polish pass, 2026-09-25).
    icon: CatalogIcon? = null,
    onClick: (() -> Unit)? = null,
    // Long-press is the touch route to Y on a row (the same convention
    // the shell's cards use for their detail).
    onLongClick: (() -> Unit)? = null,
    // A supporting line wraps in full by default (docs/SPEC.md "Text in rows
    // and tiles"); a caller only ever passes a limit for a surface that is
    // not a settings row.
    subtitleLines: Int = Int.MAX_VALUE,
    // True for a row in a SCROLLING list that must scroll evenly (the
    // Settings catalog): the row is exactly [uniformRowHeight] tall, a
    // two-line title and a two-line summary fit, and what does not fit is
    // ellipsized (the full text is in the screen's detail strip and the
    // Info sheet). Never grows.
    uniformHeight: Boolean = false,
    // An [adjustable] row is stepped with Left/Right on the pad. A touch
    // screen has no Left/Right, so on one the two arrows this row
    // already draws become the two targets that call this -- without it
    // a slider in Settings has no touch route at all, in either
    // direction, and a cycling choice only has a forwards one.
    onAdjust: ((Int) -> Unit)? = null,
    // True from a caller that already keeps the selected row in view
    // itself (docs/SPEC.md "Settings scrolling polish", 2026-09-28): the
    // Settings catalog's own LazyColumn and its Choice picker both run a
    // `LazyListState.keepInView` on every selection change, edge-aware
    // and non-animated-jump (see keepInView's own doc comment). Before
    // this flag, EVERY row still also armed its own BringIntoViewRequester
    // below, so a single Up/Down press fired two independent scroll
    // animations against the same LazyListState at once -- the real
    // cause of "settings scrolling isn't smooth" (owner, tracker#2):
    // `dumpsys gfxinfo` showed the jank, two competing scrolls is why.
    // Left false (the original always-on behaviour) for every menu built
    // on a plain `verticalScroll` Column with no scroll-keeping of its
    // own (Quick Menu, PcGameMenu, GamelistOptionsMenu and friends),
    // which still need MenuRow to scroll itself into view.
    ownScrollKeeping: Boolean = false,
) {
    val window = LocalShellWindow.current
    // Real bug this fixes (owner, 2026-09-27): every menu built from
    // MenuPanel/MenuRow (Quick Menu, Settings, and PcGameMenu's L2 game
    // options) drives its own virtual cursor -- `selected` here, not real
    // Compose focus (see e.g. PcGameMenu's own onKey/focusIndex) -- so
    // Up/Down moving that cursor past the visible viewport changed which
    // row was selected without ever scrolling MenuPanel's own
    // verticalScroll Column to show it. A menu longer than one screenful
    // (PcGameMenu's Runs with/Play/Engine/F95zone thread/Manage install/
    // Saves/Controls/Engine settings/ProtonDB/Lutris import/same-game
    // merge/versions/PC setup list, reported "inaccessible")
    // silently stopped responding to Down the moment the selection walked
    // off the bottom edge. BringIntoViewRequester is the real fix, once,
    // here, rather than in every menu that uses this row: any scrollable
    // ancestor (MenuPanel's Column) is asked to scroll this row into view
    // exactly when it becomes the selected one -- UNLESS the caller
    // already owns that job ([ownScrollKeeping]), where a second,
    // independent scroll would only fight the first one.
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, ownScrollKeeping) {
        if (selected && !ownScrollKeeping) bringIntoViewRequester.bringIntoView()
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (ownScrollKeeping) Modifier else Modifier.bringIntoViewRequester(bringIntoViewRequester))
            // The one height rule: a row is at least RowMinHeight (and at
            // least one touch target where fingers are the input) and GROWS
            // with its text, never a fixed height.
            .then(
                if (uniformHeight) {
                    Modifier.height(uniformRowHeight())
                } else {
                    Modifier.heightIn(min = maxOf(MenuTokens.RowMinHeight, if (window.touchFirst) window.minTouchTarget else 0.dp))
                },
            )
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
            .padding(horizontal = 16.dp, vertical = MenuTokens.RowVerticalPadding),
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
        } else if (icon != null) {
            Icon(
                icon.glyph(),
                contentDescription = null,
                tint = if (selected) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (danger) MenuTokens.Danger else MenuTokens.OnSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Wraps in full: rows grow with their text, so nothing is cut
            // (owner, tracker#154). The Info sheet still shows the row whole.
            subtitle?.let {
                Text(
                    it,
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (uniformHeight) 2 else subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            // One shared column across the screen's rows (content-sized to
            // the widest value, so the arrows line up), else per-row.
            val columnWidth = LocalValueColumnWidth.current
            val valueLines = if (uniformHeight) MenuTokens.UniformValueMaxLines else MenuTokens.ValueMaxLines
            val valueColor = when {
                placeholder -> MenuTokens.Placeholder
                selected -> MenuTokens.OnSurface
                else -> MenuTokens.Value
            }
            if (adjustable && onAdjust != null && window.touchFirst) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (columnWidth != null) Modifier.width(columnWidth + window.minTouchTarget * 2) else Modifier,
                ) {
                    AdjustArrow("‹") { onAdjust(-1) }
                    Text(
                        value,
                        color = valueColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = if (columnWidth != null) TextAlign.Center else TextAlign.Unspecified,
                        maxLines = valueLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (columnWidth != null) {
                            Modifier.weight(1f)
                        } else {
                            Modifier.widthIn(max = MenuTokens.ValueColumnMaxWidth)
                        },
                    )
                    AdjustArrow("›") { onAdjust(+1) }
                }
            } else {
                Text(
                    if (selected && adjustable) "‹ $value ›" else value,
                    color = valueColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = valueLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (columnWidth != null) {
                        Modifier.width(columnWidth)
                    } else {
                        Modifier.widthIn(min = MenuTokens.ValueColumnMinWidth, max = MenuTokens.ValueColumnMaxWidth)
                    },
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
 * A modal menu panel with focus and the pad handled ONCE, here.
 *
 * A Compose Dialog silently drops key events unless something inside it
 * actually holds focus -- a real bug that shipped in this shell before
 * this existed (the gamelist options overlay ignored every D-pad press
 * on a real device). Surfaces built on this cannot reintroduce it.
 *
 * The panel takes the pad through the input pipeline (docs/SPEC.md 6e):
 * the dialog it sits in gets the pipeline's front ([GatePadInThisDialog]),
 * and [onPad] gets each press once, on the press, with a held direction
 * coming round at the chrome cadence -- so every menu built on this can be
 * held down to run through it, which none could while they acted on the
 * key's release. In the PREVIEW pass, so a row a tap gave focus to cannot
 * take the press first. The system back key is the dialog's own dismiss.
 */
@Composable
internal fun MenuPanel(
    modifier: Modifier = Modifier,
    focusLabel: String = "Menu",
    onPad: (PadPress) -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    GatePadInThisDialog()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, focusLabel) }
    Column(
        modifier = modifier
            .clip(MenuTokens.OverlayShape)
            .background(MenuTokens.OverlaySurface)
            .focusRequester(focus)
            .focusable()
            .onPad(preview = true, handler = onPad)
            // A panel whose content can outgrow the screen must scroll:
            // the jump-to-letter list reaches 27 rows on a library that
            // spans the alphabet, which is taller than the display.
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
        content = content,
    )
}

/**
 * Up or Down on a menu's cursor: ES-DE's menus stop at both ends
 * ([menuStep]), and a move that happens plays ES-DE's own scroll sound.
 */
internal fun menuMove(index: Int, count: Int, press: PadPress): Int {
    val next = menuStep(index, count, if (press.action == GamepadAction.UP) -1 else 1)
    if (next != index) dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds.play("scroll")
    return next
}

/**
 * The one scrolling-text rule for a label that must stay on one line (a
 * grid tile, a carousel card, a tab label): while [active] (the focused
 * item) the whole text scrolls, so nothing is unreadable forever
 * (docs/SPEC.md "Text in rows and tiles"). Pair with maxLines = 1.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.focusMarquee(active: Boolean): Modifier =
    if (active) basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 600) else this

/**
 * The width of the value column every row of one list shares, so values
 * and their arrows align down the screen (content-sized to the widest
 * value, not a share of the window). Null: each row sizes its own.
 */
internal val LocalValueColumnWidth = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.unit.Dp?> { null }

/**
 * The one height of a [uniformHeight] row: a two-line title plus a
 * two-line summary plus the row's padding, derived from the CURRENT type
 * scale (sp through the font scale the Text size setting drives), so it
 * grows with the setting but never varies per row.
 */
@Composable
internal fun uniformRowHeight(): androidx.compose.ui.unit.Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val title = MaterialTheme.typography.bodyLarge
    val summary = MaterialTheme.typography.bodySmall
    return with(density) {
        fun line(style: androidx.compose.ui.text.TextStyle) =
            (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toDp()
        maxOf(MenuTokens.RowMinHeight, line(title) * 2 + line(summary) * 2 + MenuTokens.RowVerticalPadding * 2)
    }
}
