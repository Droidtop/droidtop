package dev.droidtop.shell.gamepad

import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.animateScrollBy
import dev.droidtop.ui.shared.Motion
import dev.droidtop.ui.shared.tabularFigures
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.isSpecified
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.ui.shared.LocalNoticePalette
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.input.PadPress
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound

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
 * The shell's one chip and button: a pill that is focusable for the pad
 * and clickable for touch. [on] is a filter or toggle in effect: it is
 * filled with the accent and carries a check, so which filters are on reads
 * without moving onto them (UI pass 2026-09-24, L3). [primary] is the one
 * action a row of chips leads with (Launch, Save). Focus is the window's
 * one ring, as on every other piece of chrome.
 *
 * The look is Steam's buttons in the theme's colours (docs/SPEC.md 7k): a
 * quiet chip at rest turns solid when selected ([MenuTokens.Selected] with
 * its own ink, Steam's inversion); a filled one keeps its fill and gains
 * the ring, a shadow that deepens when selected and a one-shot sheen as the cursor arrives. [large] is the page's Play: the
 * theme's launch colour, 48dp tall and 160dp wide at least, crisp corners
 * and a slower stripe. [tab] is a tab or view pill on a strip (Steam's
 * tabs): the label small, bold and uppercase, padded 6 by 16; the chosen
 * one ([on]) sits on a quiet plate instead of the accent, and the one under
 * the cursor turns solid, as every quiet chip does.
 *
 * This replaces three private copies (the detail screen's action chip,
 * the recent filter and the PC surface's filter chip), each of which drew
 * focus its own way.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ShellChip(
    label: String,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    primary: Boolean = false,
    // The one big button of a page (the PC game page's Play).
    large: Boolean = false,
    // A tab or view pill on a strip (the PC Games view strip, a page's tabs).
    tab: Boolean = false,
    // A primary action that cannot be pressed right now is still drawn,
    // faded, so the page says what it would do and why not (design
    // language: "a disabled button that says why").
    enabled: Boolean = true,
    // Non-null from a screen that moves ONE cursor of its own through its
    // one `onPad` handler (the PC Games tab and page, docs/SPEC.md 7i): the
    // chip then takes no focus and no key of its own and draws the ring
    // when the caller says it is the selected one. Null is the chip
    // driving itself through Compose focus, as every other caller uses it.
    selected: Boolean? = null,
    // The chip's own menu on a long press, the one L2 opens on it (Droidtop/tracker#621); null for none.
    onLongClick: (() -> Unit)? = null,
    // False where the strip draws the chosen tab's plate itself, gliding between tabs ([ViewStrip]).
    plate: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (large) Corners.Crisp else Corners.Pill
    val press = if (onLongClick != null) Modifier.padRow(onClick = onClick, onLongClick = onLongClick) else Modifier.padRow(onClick = onClick)
    // A chosen tab is a place on the strip, not a filter in effect: it keeps the quiet look.
    val filled = (on && !tab) || primary
    val isSelected = selected ?: focused
    val ring = isSelected && PadModality.showsFocus
    val labelColor = when {
        !enabled -> MenuTokens.OnSurfaceDisabled
        // The launch fill is held to contrast against the text ink (GamingThemeMapping).
        large && filled -> MenuTokens.OnSurface
        filled || ring -> MenuTokens.OnSelected
        else -> MenuTokens.OnSurface
    }
    Text(
        when {
            tab -> label.labelCase()
            on -> "\u2713 $label"
            else -> label
        },
        color = labelColor,
        style = when {
            large -> MaterialTheme.typography.titleMedium
            tab -> TypeRole.tabLabel
            else -> MaterialTheme.typography.labelLarge
        },
        fontWeight = if (large || tab) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        // A chip held to a width (the strip's filter pill) shortens its own text, never wraps.
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .then(
                if (selected == null) {
                    Modifier
                        // Ahead of the focus targets, not after them: see [GameCard].
                        .onPad { press ->
                            if (press.action == GamepadAction.A) {
                                onClick()
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .then(press)
                } else {
                    // The caller's cursor is the selection; a tap is still the press ([padRow]).
                    press
                },
            )
            .then(
                if (filled) {
                    val fill = when {
                        !enabled -> MenuTokens.LaunchDisabled
                        large -> if (ring) MenuTokens.LaunchFocused else MenuTokens.Launch
                        ring -> MenuTokens.Selected
                        else -> MenuTokens.Accent
                    }
                    Modifier
                        .primaryLift(ring && enabled, shape)
                        .background(fill, shape)
                        .shine(ring && enabled, play = large)
                        .focusRing(ring, shape)
                } else {
                    Modifier.selectionFrame(
                        isSelected,
                        shape,
                        rest = when {
                            !tab -> MenuTokens.Surface
                            on && plate -> MenuTokens.SurfaceSelected
                            else -> Color.Transparent
                        },
                        selectedFill = MenuTokens.Selected,
                    )
                },
            )
            .focusMarquee(isSelected)
            .then(if (large) Modifier.heightIn(min = 48.dp).widthIn(min = 160.dp) else Modifier)
            .padding(
                horizontal = if (large) 24.dp else 16.dp,
                vertical = when {
                    large -> 14.dp
                    tab -> 6.dp
                    else -> 8.dp
                },
            ),
    )
}

/**
 * The primary action's lift: a black shadow that deepens while the cursor
 * is on it ([Elevation]), read in the layer phase only.
 */
private fun Modifier.primaryLift(selected: Boolean, shape: Shape): Modifier = composed {
    val p = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) Motion.lift<Float>() else Motion.release<Float>(),
        label = "primary lift",
    )
    graphicsLayer {
        val rest = Elevation.PrimaryRest.toPx()
        shadowElevation = rest + (Elevation.PrimaryFocused.toPx() - rest) * p.value
        this.shape = shape
        clip = false
    }
}

/** A screen-level menu header: name first, explanation second, both quiet. */
@Composable
internal fun MenuHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, iconFile: String? = null) {
    // The same gutter the list below it uses. A fixed 48dp here put the
    // header a third of a phone's width in from rows indented 16dp.
    Column(modifier.padding(horizontal = LocalShellWindow.current.edgePadding).padding(top = 18.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A plugin panel's own icon before its name (tracker#515).
            if (iconFile != null) {
                CatalogGlyph(null, iconFile, MenuTokens.OnSurface, Modifier.padding(end = Space.Sm).size(28.dp))
            }
            Text(
                title,
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
    // In a pane (the Quick Menu's tabs) a label sits on a hairline, closer to its rows and a step smaller
    // (DroidDeck's compact SettingsGroup, Droidtop/tracker#363): the pane holds more rows.
    if (LocalShellWindow.current.inPane) {
        Column(modifier.padding(top = 10.dp, bottom = 4.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(MenuTokens.SectionLabel.copy(alpha = 0.3f)))
            Text(
                text.labelCase(),
                color = MenuTokens.SectionLabel,
                style = TypeRole.sectionLabel.copy(fontSize = 11.sp),
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }
        return
    }
    Text(
        text.labelCase(),
        color = MenuTokens.SectionLabel,
        style = TypeRole.sectionLabel,
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
    // A picture in place of [icon] (a store's cached mark, Droidtop/tracker#503).
    iconFile: String? = null,
    onClick: (() -> Unit)? = null,
    // Long-press is the touch route to Y on a row (the same convention
    // the shell's cards use for their detail).
    onLongClick: (() -> Unit)? = null,
    // A supporting line wraps in full by default (docs/SPEC.md "Text in rows
    // and tiles"); a caller only ever passes a limit for a surface that is
    // not a settings row.
    subtitleLines: Int = Int.MAX_VALUE,
    // True for a row in a SCROLLING list (the Settings catalog): the row is
    // at least [uniformRowHeight] tall, one title line and its summary lines
    // plus padding, so single-line rows (nearly all) share one height and a
    // page shows five or six of them, and a title that wraps to its second
    // line grows the row rather than being cut (Droidtop/tracker#664). What
    // does not fit two lines is ellipsized (the full text is on the focus
    // line and in the Info sheet).
    uniformHeight: Boolean = false,
    // How many summary lines a [uniformHeight] row makes room for: two
    // where a page shows summaries, none for a settings row, whose
    // explanation is on the focus line (docs/SPEC.md 7k, "Explanations").
    uniformSummaryLines: Int = 2,
    // The row's least height when it is not [uniformHeight]; null is [MenuTokens.RowMinHeight]. Still at least one
    // touch target where fingers are the input.
    minHeight: Dp? = null,
    // A toggle drawn as a switch in the value column (on/off), instead of
    // a value text. Null: not a toggle.
    switchOn: Boolean? = null,
    // A slider's position (0..1), drawn as a short track before its value.
    // Null: not a slider.
    sliderFraction: Float? = null,
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
    // A job's progress (CatalogItem.progress): a thin bar under the title, the title then one line so
    // the row keeps its height. Null: not a job.
    progress: Float? = null,
    // How that job's bar moves (CatalogItem.progressLook, Droidtop/tracker#643); a finished job's row, which has no
    // bar, draws its check or cross beside the title.
    progressLook: dev.droidtop.library.settings.JobLook? = dev.droidtop.library.settings.JobLook.of(progress),
    // A job counted in parts (CatalogItem.progressParts): its bar in that many segments (Droidtop/tracker#738).
    progressParts: Int = 0,
    // One status glyph beside the title (CatalogItem.statusMark, Droidtop/tracker#644): a plugin that needs you.
    statusMark: dev.droidtop.library.settings.StatusMark? = null,
    // A leading picture the caller draws (an app's own icon in the task manager's list); [accent] and
    // [icon] win when given, since a row carries one leading mark at most.
    leading: (@Composable () -> Unit)? = null,
    // A short tag on the row beside its title (CatalogItem.chip, one of RowTag's words): "Unofficial". A fact, drawn on
    // the row, not a tooltip.
    chip: String? = null,
    // The value drawn as a state chip (ChoiceItem.toned): in [valueTone]'s colours, or neutral when it has none
    // (Droidtop/tracker#519).
    valueChip: Boolean = false,
    valueTone: NoticeTone? = null,
    // The row has Info (a sentence beyond its title): a 20dp glyph says so, and a screen reader hears
    // "More info available" (docs/SPEC.md 7k, "Explanations").
    info: Boolean = false,
    // A tap on the info glyph: Info, never the row's own action. Without it the glyph was part of the row, so a tap
    // meant to read about an armed confirm row confirmed it (rig, build 1908, trusting a plugin origin).
    onInfo: (() -> Unit)? = null,
) {
    val window = LocalShellWindow.current
    // Real bug this fixes (owner, 2026-09-27): every menu built from
    // MenuPanel/MenuRow (Quick Menu, Settings, and PcGameMenu's L2 game
    // options) drives its own virtual cursor -- `selected` here, not real
    // Compose focus (see e.g. PcGameMenu's own onKey/focusIndex) -- so
    // Up/Down moving that cursor past the visible viewport changed which
    // row was selected without ever scrolling MenuPanel's own
    // verticalScroll Column to show it. A menu longer than one screenful
    // (PcGameMenu's Runs with/Play/Engine/source links/Manage install/
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
                    Modifier.heightIn(min = maxOf(uniformRowHeight(uniformSummaryLines), if (window.touchFirst) window.pressTarget else 0.dp))
                } else {
                    Modifier.heightIn(min = maxOf(minHeight ?: MenuTokens.RowMinHeight, if (window.touchFirst) window.pressTarget else 0.dp))
                },
            )
            .clip(MenuTokens.RowShape)
            .selectionFrame(selected, MenuTokens.RowShape, row = true)
            // Touch works on every row, always -- the shell is
            // gamepad-first, never gamepad-only.
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.padRow(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> Modifier.padRow(onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = window.rowVerticalPadding),
    ) {
        if (accent != null) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(32.dp)
                    .clip(Corners.Icon)
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
        } else if (icon != null || iconFile != null) {
            CatalogGlyph(
                icon,
                iconFile,
                tint = if (selected) MenuTokens.OnSurface else MenuTokens.OnSurfaceMuted,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        } else if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = if (danger) MenuTokens.NoticeDanger else MenuTokens.OnSurface,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (progress != null || chip != null) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (progress == null && progressLook != null) {
                    Spacer(Modifier.width(Space.Sm))
                    dev.droidtop.ui.shared.JobMark(progressLook, shellJobColors())
                }
                if (statusMark != null) {
                    Spacer(Modifier.width(Space.Sm))
                    dev.droidtop.ui.shared.StatusGlyph(statusMark, MenuTokens.Accent)
                }
                if (chip != null) {
                    Spacer(Modifier.width(Space.Sm))
                    // Capped, so a tag never takes the title's room (Droidtop/tracker#677); the words are RowTag's.
                    StatusChip(chip, ink = MenuTokens.OnSurface, fill = MenuTokens.SurfaceSelected, modifier = Modifier.widthIn(max = ROW_TAG_MAX_WIDTH))
                }
            }
            if (progress != null) {
                Row(Modifier.padding(top = Space.Sm), verticalAlignment = Alignment.CenterVertically) {
                    ShellProgressBar(progress, Modifier.weight(1f), progressLook ?: dev.droidtop.library.settings.JobLook.of(progress)!!, parts = progressParts)
                    // The percent only where the cursor is, in figures that keep their width (Droidtop/tracker#643:
                    // "Percent only on focus, in tabular figures").
                    if (selected && progress >= 0f && progressLook == dev.droidtop.library.settings.JobLook.ACTIVE) {
                        Spacer(Modifier.width(Space.Sm))
                        Text(
                            "${(progress * 100).toInt()}%",
                            color = MenuTokens.OnSurfaceMuted,
                            style = MaterialTheme.typography.labelSmall.tabularFigures(),
                            maxLines = 1,
                        )
                    }
                }
            }
            // Wraps in full: rows grow with their text, so nothing is cut
            // (owner, tracker#154). The Info sheet still shows the row whole.
            subtitle?.takeIf { !uniformHeight || uniformSummaryLines > 0 }?.let {
                Text(
                    it,
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (uniformHeight) uniformSummaryLines else subtitleLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (sliderFraction != null) {
            Spacer(Modifier.width(16.dp))
            ShellSlider(sliderFraction, selected, Modifier.width(120.dp))
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
                    modifier = Modifier.valueShare().then(if (columnWidth != null) Modifier.width(columnWidth + window.pressTarget * 2) else Modifier),
                ) {
                    AdjustArrow("‹") { onAdjust(-1) }
                    if (valueChip) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = if (columnWidth != null) Modifier.weight(1f) else Modifier,
                        ) { ValueChip(value, valueTone) }
                    } else {
                        Text(
                            value,
                            color = valueColor,
                            style = TypeRole.value,
                            textAlign = if (columnWidth != null) TextAlign.Center else TextAlign.Unspecified,
                            maxLines = valueLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (columnWidth != null) {
                                Modifier.weight(1f)
                            } else {
                                Modifier.widthIn(max = MenuTokens.ValueColumnMaxWidth)
                            },
                        )
                    }
                    AdjustArrow("›") { onAdjust(+1) }
                }
            } else if (valueChip) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.valueShare().then(
                        if (columnWidth != null) {
                            Modifier.widthIn(min = columnWidth, max = MenuTokens.ValueColumnMaxWidth)
                        } else {
                            Modifier.widthIn(min = MenuTokens.ValueColumnMinWidth, max = MenuTokens.ValueColumnMaxWidth)
                        },
                    ),
                ) {
                    if (selected && adjustable) Text("‹ ", color = valueColor, style = TypeRole.value)
                    ValueChip(value, valueTone)
                    if (selected && adjustable) Text(" ›", color = valueColor, style = TypeRole.value)
                }
            } else {
                Text(
                    if (selected && adjustable) "‹ $value ›" else value,
                    color = valueColor,
                    style = TypeRole.value,
                    textAlign = TextAlign.End,
                    maxLines = valueLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.valueShare().then(
                        if (columnWidth != null) {
                            // The shared width is a minimum, not a box: a value measured narrower than it draws
                            // (the rig wrapped "Review" as "Revie/w", Droidtop/tracker#480) still gets the room it needs.
                            Modifier.widthIn(min = columnWidth, max = MenuTokens.ValueColumnMaxWidth)
                        } else {
                            Modifier.widthIn(min = MenuTokens.ValueColumnMinWidth, max = MenuTokens.ValueColumnMaxWidth)
                        },
                    ),
                )
            }
        }
        if (switchOn != null) {
            Spacer(Modifier.width(16.dp))
            // In the shared value column, so switches line up with the
            // values of the rows around them.
            Box(
                modifier = Modifier.valueShare().then(LocalValueColumnWidth.current?.let { Modifier.width(it) } ?: Modifier),
                contentAlignment = Alignment.CenterEnd,
            ) { ShellSwitch(switchOn) }
        }
        if (info) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.Info,
                contentDescription = "More info available",
                tint = MenuTokens.OnSurfaceMuted,
                modifier = if (onInfo != null) {
                    Modifier
                        .clip(Corners.Pill)
                        .padRow(onClick = onInfo)
                        .size(if (window.touchFirst) window.pressTarget else 20.dp)
                        .wrapContentSize()
                        .size(20.dp)
                } else {
                    Modifier.size(20.dp)
                },
            )
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Text("›", color = MenuTokens.Placeholder, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * A row's value column never takes more than [MenuTokens.ValueMaxShare] of the width left to it. The shared value
 * column is up to [MenuTokens.ValueColumnMaxWidth] wide, and the Quick Menu's pane is about as wide as that, so a
 * long value pushed the title to zero width and an info row read as a bare value (rig, build 1715, Quick Menu >
 * Plugins > Sample, Droidtop/tracker#316). Capping the outer constraint leaves the inner width modifiers to settle
 * inside it, so it must come FIRST in the chain: after a fixed width it capped that width instead of the row's, and
 * a touch row's arrows (two 48 dp targets) left its value one letter wide ("A", "B" on a plugin's Permissions
 * screen at 1920x1080, rig, build 1768).
 */
private fun Modifier.valueShare(): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * MenuTokens.ValueMaxShare).toInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, cap), maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The shell's one on/off switch, in Settings' rows and on the Quick Menu's tiles alike (DroidDeck's
 * ToggleSwitch, ui/SettingsWidgets.kt at 9310d19, in the theme's roles): a 52 by 30 track that fills
 * with the affirmative colour when on, its knob sliding to that end in the ink that reads on that fill
 * ([MenuTokens.OnAffirmative]). The colour answers at once; the
 * knob glides ([Motion.FocusMs]) and is moved in the layer phase, so a flip recomposes nothing.
 */
@Composable
internal fun ShellSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val knob = animateFloatAsState(if (on) 1f else 0f, Motion.tw(Motion.FocusMs, easing = Motion.Glide), label = "switch knob")
    Box(
        modifier = modifier
            .size(width = SwitchWidth, height = SwitchHeight)
            .clip(Corners.Pill)
            .background(if (on) MenuTokens.Affirmative else MenuTokens.Placeholder)
            .padding(SwitchInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .graphicsLayer { translationX = knob.value * (SwitchWidth - SwitchHeight).toPx() }
                .size(SwitchHeight - SwitchInset * 2)
                .clip(Corners.Pill)
                .background(if (on) MenuTokens.OnAffirmative else MenuTokens.OnSurface),
        )
    }
}

private val SwitchWidth = 52.dp
private val SwitchHeight = 30.dp
private val SwitchInset = 4.dp

/**
 * The shell's one slider, in Settings' rows and the Quick Menu alike (DroidDeck's ValueSlider drawing,
 * ui/SettingsWidgets.kt at 9310d19, in the theme's roles): a thin track, filled to [fraction] in the
 * accent while [selected] and in the value colour otherwise, with a round thumb at the fill's end.
 * Drawn in one pass; [modifier] gives it its width.
 */
@Composable
internal fun ShellSlider(fraction: Float, selected: Boolean, modifier: Modifier = Modifier) {
    val track = MenuTokens.Placeholder
    val fill = if (selected) MenuTokens.Accent else MenuTokens.Value
    androidx.compose.foundation.Canvas(modifier.height(SliderHeight)) {
        val thumb = SliderThumb.toPx()
        val y = size.height / 2f
        val start = androidx.compose.ui.geometry.Offset(thumb, y)
        val end = androidx.compose.ui.geometry.Offset(size.width - thumb, y)
        val at = androidx.compose.ui.geometry.Offset(start.x + (end.x - start.x) * fraction.coerceIn(0f, 1f), y)
        val line = SliderTrack.toPx()
        drawLine(track, start, end, line, androidx.compose.ui.graphics.StrokeCap.Round)
        if (fraction > 0f) drawLine(fill, start, at, line, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(fill, thumb, at)
    }
}

/**
 * The shell's one progress bar ([ProgressLook]): the shared job bar ([dev.droidtop.ui.shared.JobProgress],
 * Droidtop/tracker#643) in Gaming's roles, a thin track in the scrim role filled in the accent, Steam's black track
 * and blue fill. The fill glides between reports; a negative [fraction] (a job under way whose size is not known yet)
 * is the checking shimmer. [modifier] gives it its width. A download's row, a capsule being installed and Home's
 * download cards all draw it.
 */
@Composable
internal fun ShellProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    look: dev.droidtop.library.settings.JobLook = dev.droidtop.library.settings.JobLook.of(fraction)!!,
    parts: Int = 0,
) {
    dev.droidtop.ui.shared.JobProgress(fraction, look, shellJobColors(), modifier, height = ProgressLook.Height, parts = parts)
}

/**
 * The shell's one loading indicator (Droidtop/tracker#671): [ShellProgressBar]'s checking shimmer, for something
 * under way with no measure (a list being built, a source being asked). Every waiting place draws this, never a
 * stock spinner; [modifier] may give another width ([ProgressLook.InlineLoadingWidth] beside a line of text).
 */
@Composable
internal fun ShellLoading(modifier: Modifier = Modifier.width(ProgressLook.LoadingWidth)) {
    ShellProgressBar(-1f, modifier)
}

/** Gaming's colours for a job's bar and mark. */
@Composable
internal fun shellJobColors(): dev.droidtop.ui.shared.JobProgressColors {
    return dev.droidtop.ui.shared.JobProgressColors(
        track = MenuTokens.Scrim,
        fill = MenuTokens.Accent,
        done = MenuTokens.Affirmative,
        failed = MenuTokens.NoticeDanger,
    )
}

/**
 * The shell's one status chip (DroidDeck's Chip, ui/FrontEndWidgets.kt at 9310d19): a fact in
 * small capitals on a pill of [fill] with a hairline of its own [ink], so it reads over art and
 * over a page alike. A capsule's corner marks and a place's header facts are both this chip.
 */
/** A setting's value as a state chip: [tone]'s plate and text, or the neutral chip when it has none. */
@Composable
private fun ValueChip(text: String, tone: NoticeTone?) {
    val palette = LocalNoticePalette.current
    if (tone == null) {
        StatusChip(text, ink = MenuTokens.OnSurface, fill = MenuTokens.SurfaceSelected)
    } else {
        StatusChip(text, ink = palette.on(tone), fill = palette.plate(tone))
    }
}

@Composable
internal fun StatusChip(text: String, ink: Color, fill: Color, modifier: Modifier = Modifier) {
    Text(
        text.labelCase(),
        color = ink,
        style = TypeRole.eyebrow,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(fill, Corners.Pill)
            .border(1.dp, ink.copy(alpha = 0.3f), Corners.Pill)
            .padding(horizontal = Space.Sm, vertical = Space.Hair),
    )
}

private val SliderHeight = 20.dp
private val SliderTrack = 4.dp
private val SliderThumb = 7.dp

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
            .size(LocalShellWindow.current.pressTarget)
            .clip(Corners.Pill)
            .padRow(onClick = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
    }
}

/** What the footer says while a [MenuPanel] is open and has no hints of its own to give. */
private val PANEL_HINTS = listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, "Back"))

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
 *
 * The panel is a layer on the footer's hint bar ([DeclareLayerHints]): while
 * it is open the bar shows [hints], not the screen's underneath. A panel
 * that draws a hint row of its own passes an empty list so the two never show.
 *
 * The look and motion are Steam's modal panel (docs/SPEC.md 7k, "Sheets"):
 * [title] in the heading role at the top, the page behind dimmed to the
 * theme's scrim strength (its window's own dim, so nothing is painted
 * outside the panel), the panel gliding in from a touch smaller and
 * transparent over [Motion.PanelInMs], and, while rows remain below the
 * fold, its last [MoreFadeDp] fading out over a down arrow (DroidDeck's
 * AnchoredMenu, ui/SettingsWidgets.kt at 9310d19), so a long sheet says it
 * goes on. The fade is a layer only while there is more to scroll to.
 */
@Composable
internal fun MenuPanel(
    modifier: Modifier = Modifier,
    focusLabel: String = "Menu",
    hints: List<HintBinding> = PANEL_HINTS,
    title: String? = null,
    onPad: (PadPress) -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    GatePadInThisDialog()
    DeclareLayerHints(hints)
    ModalScrim()
    // Every modal shows and hides with its own cue (Steam's modal sounds, docs/SPEC.md "Interface sounds").
    androidx.compose.runtime.DisposableEffect(Unit) {
        EsDeNavigationSounds.play(UiSound.MODAL_SHOW)
        onDispose { EsDeNavigationSounds.play(UiSound.MODAL_HIDE) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, focusLabel) }
    val shown = remember { androidx.compose.animation.core.Animatable(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.panelIn()) }
    val scroll = androidx.compose.foundation.rememberScrollState()
    // A D-pad press past the rows scrolls the panel by about two rows (#381).
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scrollStepPx = with(androidx.compose.ui.platform.LocalDensity.current) { PANEL_SCROLL_STEP.toPx() }
    // A dialog window does not bound its content to the screen: without
    // an explicit cap a long menu is centred and clipped at both edges
    // (title and last row cut, Droidtop/tracker#295) and its scroll never
    // starts. The cap is the screen less the edge margin each side.
    val window = LocalShellWindow.current
    // A panel is a window of its own (a Dialog), so it hosts its own sliding ring.
    FocusGlideHost(
        modifier
            .heightIn(max = maxOf(120.dp, window.heightDp.dp - window.edgePadding * 2))
            .graphicsLayer {
                val p = shown.value
                alpha = p
                scaleX = PANEL_OPEN_SCALE + (1f - PANEL_OPEN_SCALE) * p
                scaleY = scaleX
            }
            .clip(MenuTokens.OverlayShape)
            .background(MenuTokens.OverlaySurface),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .focusRequester(focus)
                    .focusable()
                    .onPad(preview = true) { press ->
                        MenuRowEnd.atEdge = false
                        val handled = onPad(press)
                        val direction = panelScrollDirection(press.action, MenuRowEnd.atEdge, scroll.canScrollForward, scroll.canScrollBackward)
                        if (direction != 0) {
                            scope.launch { scroll.animateScrollBy(direction * scrollStepPx) }
                            true
                        } else {
                            handled
                        }
                    }
                    .fadeWhileMoreBelow(scroll)
                    // A panel whose content can outgrow the screen must scroll:
                    // the jump-to-letter list reaches 27 rows on a library that
                    // spans the alphabet, which is taller than the display.
                    .verticalScroll(scroll)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
            ) {
                title?.let { MenuPanelTitle(it) }
                content()
            }
            if (scroll.canScrollForward) MoreBelowArrow(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        }
    }
}

/** How far one D-pad press past the rows scrolls a panel. */
private val PANEL_SCROLL_STEP = 96.dp

/** How small a sheet starts as it glides in: 96 percent of its size. */
private const val PANEL_OPEN_SCALE = 0.96f

/** How tall the fade at the foot of a long sheet is, in dp. */
internal const val MoreFadeDp = 36

/** A sheet's own name at its top: one heading style for every sheet. */
@Composable
internal fun MenuPanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MenuTokens.OnSurface,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Dims everything behind the dialog this is drawn in to the theme's scrim
 * strength (Steam's modal scrim is 80 percent; the scrim role carries the
 * same): the window's own dim, so a panel that wraps its content paints
 * nothing outside itself. Outside a dialog it does nothing.
 */
@Composable
internal fun ModalScrim() {
    val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    val strength = MenuTokens.Scrim.alpha
    androidx.compose.runtime.SideEffect { dialogWindow?.setDimAmount(strength) }
}

/** The widest a row's tag ([MenuRow]'s chip) may draw: room for "Anti-features" at the label size, never more. */
private val ROW_TAG_MAX_WIDTH = 120.dp

/** One choice of a modal dialog ([DialogChoices]): its label, a short line under it (a date, what it will do), and whether it is one-way. */
internal class DialogChoice(val label: String, val detail: String? = null, val danger: Boolean = false)

/**
 * A modal's choices, Steam's dialog list (its power dialog, docs/SPEC.md 7k "Dialogs"): flat rows on
 * one plate, groups split by a thin dark rule ([DialogLook.RuleHeight]), the safe choices first and
 * the way out (Cancel, OK, Decide later) last in a group of its own, except when every other choice
 * is one-way: then the way out is the safe choice and comes first. The row under the cursor is the
 * solid selected inversion, with the window's one ring. [selected] counts rows across the groups,
 * in order; a tap chooses ([onChoose]). One component for every dialog, so they read alike.
 */
@Composable
internal fun DialogChoices(groups: List<List<DialogChoice>>, selected: Int, onChoose: (Int) -> Unit) {
    val window = LocalShellWindow.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.Sm)
            .clip(Corners.Crisp)
            .background(MenuTokens.Surface),
    ) {
        var index = 0
        groups.filter { it.isNotEmpty() }.forEachIndexed { g, group ->
            if (g > 0) Box(Modifier.fillMaxWidth().height(DialogLook.RuleHeight).background(MenuTokens.Scrim))
            group.forEach { choice ->
                val row = index++
                val inverted = row == selected && PadModality.showsFocus
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A row is a button here, so it is at least as big as a finger on a screen without a pad.
                        .heightIn(min = if (window.touchFirst) window.pressTarget else DialogLook.RowMinHeight)
                        .selectionFrame(row == selected, Corners.Crisp, rest = Color.Transparent, selectedFill = MenuTokens.Selected)
                        .padRow { onChoose(row) }
                        .padding(horizontal = Space.Lg, vertical = Space.Sm),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        choice.label,
                        color = when {
                            inverted -> MenuTokens.OnSelected
                            choice.danger -> MenuTokens.NoticeDanger
                            else -> MenuTokens.OnSurface
                        },
                        style = TypeRole.body,
                    )
                    choice.detail?.let {
                        Text(it, color = if (inverted) MenuTokens.OnSelected else MenuTokens.OnSurfaceMuted, style = TypeRole.supporting)
                    }
                }
            }
        }
    }
}

/**
 * The foot of a scrolling column fading out while it can still scroll down,
 * so a cut row reads as "more below" rather than as the end. Drawn in the
 * draw phase; the column is composited offscreen only while the fade shows.
 */
internal fun Modifier.fadeWhileMoreBelow(scroll: androidx.compose.foundation.ScrollState): Modifier =
    this
        .graphicsLayer {
            compositingStrategy = if (scroll.canScrollForward) {
                androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            } else {
                androidx.compose.ui.graphics.CompositingStrategy.Auto
            }
        }
        .drawWithContent {
            drawContent()
            if (scroll.canScrollForward) {
                val fade = MoreFadeDp.dp.toPx().coerceAtMost(size.height / 3f)
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startY = size.height - fade,
                        endY = size.height,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - fade),
                    size = androidx.compose.ui.geometry.Size(size.width, fade),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
        }

/**
 * The ends of a scrolling row fading out on the side it can still scroll to, so a tab or chip cut at the edge reads
 * as "more this way" (Droidtop/tracker#661): a row of tabs never ends mid-letter with nothing to say so. Drawn in the
 * draw phase; the row is composited offscreen only while a fade shows.
 */
internal fun Modifier.fadeWhileMoreSideways(state: androidx.compose.foundation.lazy.LazyListState): Modifier =
    this
        .graphicsLayer {
            compositingStrategy = if (state.canScrollForward || state.canScrollBackward) {
                androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            } else {
                androidx.compose.ui.graphics.CompositingStrategy.Auto
            }
        }
        .drawWithContent {
            drawContent()
            val fade = MoreFadeDp.dp.toPx().coerceAtMost(size.width / 4f)
            if (state.canScrollForward) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                    size = androidx.compose.ui.geometry.Size(fade, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            if (state.canScrollBackward) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    size = androidx.compose.ui.geometry.Size(fade, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
        }

/** The small down chevron under a sheet that goes on below the fold. */
@Composable
private fun MoreBelowArrow(modifier: Modifier = Modifier) {
    val ink = MenuTokens.OnSurfaceMuted
    androidx.compose.foundation.Canvas(modifier.size(20.dp)) {
        val w = 2.dp.toPx()
        val cx = size.width / 2f
        val y0 = size.height * 0.35f
        val y1 = size.height * 0.65f
        drawLine(ink, androidx.compose.ui.geometry.Offset(cx - size.width * 0.3f, y0), androidx.compose.ui.geometry.Offset(cx, y1), w, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(ink, androidx.compose.ui.geometry.Offset(cx + size.width * 0.3f, y0), androidx.compose.ui.geometry.Offset(cx, y1), w, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/**
 * Up or Down on a menu's cursor: ES-DE's menus stop at both ends
 * ([menuStep]); a move that happens plays the move cue, and a fresh press
 * at an end that goes nowhere plays the bump cue ([moveCue]).
 */
internal fun menuMove(index: Int, count: Int, press: PadPress): Int {
    val next = menuStep(index, count, if (press.action == GamepadAction.UP) -1 else 1)
    moveCue(next != index, press.repeat)
    // The panel this menu is in scrolls on a press that went nowhere (MenuPanel, Droidtop/tracker#381).
    MenuRowEnd.atEdge = next == index
    return next
}

/**
 * Whether the last cursor step ([menuMove]) went nowhere: the rows ended. [MenuPanel] reads it after its menu's
 * handler, to scroll content past the rows (an action's result) with the D-pad. Main thread only, like the pad.
 */
internal object MenuRowEnd {
    var atEdge: Boolean = false
}

/**
 * Which way a panel scrolls for a press (Droidtop/tracker#381): +1 down, -1 up, 0 not at all. Only Up or Down that
 * went nowhere among the rows ([atEdge]) scrolls, and only where there is more that way, so a menu whose rows all
 * fit keeps its bump and a long result below the rows is reached with the D-pad, not only a touch swipe.
 */
internal fun panelScrollDirection(action: GamepadAction, atEdge: Boolean, canForward: Boolean, canBackward: Boolean): Int = when {
    !atEdge -> 0
    action == GamepadAction.DOWN && canForward -> 1
    action == GamepadAction.UP && canBackward -> -1
    else -> 0
}

/**
 * The one sound rule for a cursor step (docs/SPEC.md "Interface sounds"): [moved], the move cue;
 * not moved, the bump cue (Steam's "a press that went nowhere"), but only for a fresh press, so a
 * direction held against an end does not keep bumping.
 */
internal fun moveCue(moved: Boolean, repeat: Boolean) {
    when {
        moved -> EsDeNavigationSounds.play(UiSound.MOVE)
        !repeat -> EsDeNavigationSounds.play(UiSound.BUMP)
    }
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
 * The least height of a [uniformHeight] row: one title line plus
 * [summaryLines] lines of summary plus the row's padding, derived from the
 * CURRENT type scale (sp through the font scale the Text size setting
 * drives), so it grows with the setting. It used to make room for two title
 * lines on every row, which left single-line rows twice their height and
 * three rows to a page (Droidtop/tracker#664); a wrapped title now grows its
 * own row.
 */
@Composable
internal fun uniformRowHeight(summaryLines: Int = 2): androidx.compose.ui.unit.Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val padding = LocalShellWindow.current.rowVerticalPadding
    val title = MaterialTheme.typography.bodyLarge
    val summary = MaterialTheme.typography.bodySmall
    return with(density) {
        fun line(style: androidx.compose.ui.text.TextStyle) =
            (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toDp()
        maxOf(MenuTokens.ListRowMinHeight, line(title) + line(summary) * summaryLines + padding * 2)
    }
}
