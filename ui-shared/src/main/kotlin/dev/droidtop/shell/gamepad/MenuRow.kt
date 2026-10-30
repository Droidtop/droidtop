package dev.droidtop.shell.gamepad

import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidtop.ui.shared.tabularFigures
import androidx.compose.ui.unit.isSpecified
import dev.droidtop.library.settings.CatalogIcon
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.ui.shared.LocalNoticePalette

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
val MenuListContentPadding: PaddingValues
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = PaddingValues(
        start = LocalShellWindow.current.edgePadding,
        end = LocalShellWindow.current.edgePadding,
        top = 12.dp,
        bottom = MenuTokens.HintBarRoom,
    )

/** A screen-level menu header: name first, explanation second, both quiet. */
@Composable
fun MenuHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier, iconFile: String? = null) {
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
fun MenuSectionLabel(text: String, modifier: Modifier = Modifier) {
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
fun MenuRow(
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
fun StatusChip(text: String, ink: Color, fill: Color, modifier: Modifier = Modifier) {
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

/** The widest a row's tag ([MenuRow]'s chip) may draw: room for "Anti-features" at the label size, never more. */
private val ROW_TAG_MAX_WIDTH = 120.dp

/**
 * The one scrolling-text rule for a label that must stay on one line (a
 * grid tile, a carousel card, a tab label): while [active] (the focused
 * item) the whole text scrolls, so nothing is unreadable forever
 * (docs/SPEC.md "Text in rows and tiles"). Pair with maxLines = 1.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.focusMarquee(active: Boolean): Modifier =
    if (active) basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 600) else this

/**
 * The width of the value column every row of one list shares, so values
 * and their arrows align down the screen (content-sized to the widest
 * value, not a share of the window). Null: each row sizes its own.
 */
val LocalValueColumnWidth = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.unit.Dp?> { null }

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
