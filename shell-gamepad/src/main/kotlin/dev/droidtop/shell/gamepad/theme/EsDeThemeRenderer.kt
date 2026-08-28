package dev.droidtop.shell.gamepad.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.EsDeThemeView
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Renders a parsed [EsDeThemeView] as ONE coherent screen -- every
 * positioned element (background/video/info text/carousel/help/...)
 * composited together by real z-index, the theme itself driving the
 * layout. This replaces an earlier, real bug: [EsDeSystemListView] used
 * to be rendered separately, in droidtop's own hardcoded Column, ignoring
 * the theme's actual `pos`/`size`/`origin` for the carousel/grid/textlist
 * element entirely (DEcaffe positions it centered near the BOTTOM of the
 * screen; droidtop's own layout put system cards crowding the TOP,
 * nothing like the real theme). The primary list element is now just
 * another themed element type in this same dispatcher, positioned exactly
 * like every other one -- [items]/[firstItemFocus] are threaded through
 * only for that one case.
 */
@Composable
fun EsDeThemedView(
    view: EsDeThemeView,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier = Modifier,
    // Real per-system metadata (systemName/systemManufacturer/
    // systemReleaseYear/...) needs a theme parsed with THAT system's own
    // ${system.theme} substituted (see ThemeAssets.loadActiveTheme's own
    // doc comment) -- this bubbles the carousel/grid/textlist's own
    // focused-item index up to whoever owns [view] itself, so it can load
    // and pass down the right per-system-parsed EsDeThemeView. A no-op
    // default since most callers of this composable don't have a
    // per-system theme concept at all (e.g. anything that isn't the
    // "system" view).
    onFocusedIndexChanged: (Int) -> Unit = {},
    // The focused system's own real games (from the SAME LibraryEntry list
    // Games already shows elsewhere), feeding <gameselector>-driven
    // elements (the game-preview poster/mosaic/title in DEcaffe's real
    // "system" view) -- empty by default since most callers of this
    // composable (anything that isn't the "system" view) have no
    // gameselector elements to feed at all.
    focusedSystemEntries: List<LibraryEntry> = emptyList(),
    // Real, currently-relevant button hints for whatever screen [view] is
    // rendering (e.g. A/Select, Y/Info, L-R/Switch section) -- the theme's
    // own real <helpsystem> element (see EsDeTheme.kt's schema) only
    // supplies WHERE/HOW to draw them (pos/origin/colors/font/spacing);
    // WHICH actions are currently valid is app state this renderer has no
    // way to know on its own, same reason [items]/[firstItemFocus] are
    // threaded through for the list element rather than owned here.
    // Empty by default: most EsDeThemedView callers render a view with no
    // real helpsystem element at all.
    hints: List<kotlin.Pair<GamepadAction, String>> = emptyList(),
) {
    BoxWithConstraints(modifier = modifier) {
        val viewWidth = maxWidth
        val viewHeight = maxHeight
        // Selected ONCE per focused-system change (remember's key is the
        // entries list itself -- structurally stable across recompositions
        // for the same system, changes when focus moves to a different
        // one), not re-randomized every frame -- see GameSelector's own
        // doc comment for why that distinction matters for a real
        // "stable until you change platform" game-preview collage.
        val gameSelector = view.elements.values.firstOrNull { it.type == "gameselector" }
        val gameCount = gameSelector?.valueOrNull<EsDeThemeValue.UInt>("gameCount")?.value?.toInt() ?: 1
        val allowDuplicates = gameSelector?.valueOrNull<EsDeThemeValue.Bool>("allowDuplicates")?.value ?: true
        val gameSelection = remember(focusedSystemEntries) {
            GameSelector.select(focusedSystemEntries, gameCount, allowDuplicates)
        }
        view.elements.values
            // Real ES-DE `visible` property, applies to every element type
            // -- checked once here rather than duplicated in each
            // per-type renderer below.
            .filter { it.valueOrNull<EsDeThemeValue.Bool>("visible")?.value != false }
            .sortedBy { zIndexOf(it) }.forEach { element ->
            when (element.type) {
                "image" -> EsDeThemedImage(element, viewWidth, viewHeight, gameSelection)
                "text" -> EsDeThemedText(element, viewWidth, viewHeight, gameSelection)
                // Real, honest fallback: no video/GIF playback engine
                // wired up (real, separate work) -- shows the element's
                // own real default/poster PATH property, or the
                // gameselector-resolved artwork for its selected game when
                // gameselectorEntry is set, as a static image instead of
                // silently rendering nothing.
                "video", "animation" -> EsDeThemedFallbackImage(element, viewWidth, viewHeight, gameSelection)
                // Real, live-rendered -- ES-DE's own "clock" type has no
                // "metadata" property at all (confirmed against its real
                // schema), unlike "datetime".
                "clock" -> EsDeThemedClock(element, viewWidth, viewHeight)
                // Real, now-unblocked: LibraryEntry.releaseDate exists
                // (see that field's own doc comment -- real per-game
                // metadata, scraped via ScreenScraper/TheGamesDB), so a
                // theme's own real <datetime metadata="releasedate"> can
                // finally bind to real data instead of parsing-but-never-
                // rendering. Genuinely different from "clock": this reads
                // one static value once, not a live-ticking wall clock.
                "datetime" -> EsDeThemedDateTime(element, viewWidth, viewHeight, gameSelection)
                // Real, now-unblocked the same way: LibraryEntry.rating
                // exists (0.0-1.0, same real convention real ES-DE's own
                // MD_RATING uses).
                "rating" -> EsDeThemedRating(element, viewWidth, viewHeight, gameSelection)
                // The real fix described above: positioned/sized exactly
                // like any other themed element, using the SAME EsDeCarousel/
                // EsDeGrid/EsDeTextList composables that already read the
                // theme's own itemSize/colors -- only the outer placement
                // was ever wrong.
                "carousel", "grid", "textlist" -> EsDeThemedListElement(
                    element, items, firstItemFocus, viewWidth, viewHeight, onFocusedIndexChanged,
                )
                // Real, previously-dead theme data (see this file's own
                // history): EsDeTheme.kt's schema already parses a real
                // <helpsystem> block (pos/origin/textColor/iconColor/
                // fontPath/fontSize/entrySpacing/backgroundColor/opacity),
                // but nothing here ever rendered it -- droidtop's actual
                // button-hint bar was a fully hardcoded, unthemed
                // ButtonHintFooter instead. [hints] (see this composable's
                // own doc comment) is the only piece this renderer can't
                // get from the theme alone.
                "helpsystem" -> EsDeThemedHelpSystem(element, viewWidth, viewHeight, hints)
            }
        }
    }
}

@Composable
private fun EsDeThemedListElement(
    element: EsDeThemeElement,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    viewWidth: Dp,
    viewHeight: Dp,
    onFocusedIndexChanged: (Int) -> Unit,
) {
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    EsDeSystemListView(
        element = element,
        items = items,
        firstItemFocus = firstItemFocus,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY).size(width = width, height = height),
        onFocusedIndexChanged = onFocusedIndexChanged,
    )
}

@Composable
private fun EsDeThemedImage(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)

    // Real gradient-band rendering (DEcaffe's own leftband/rightband
    // elements: a thin vertical divider fading from `color` to
    // `colorEnd`) -- previously fell through to the plain path-image
    // branch below, which only ever applies ONE static tint color via
    // ColorFilter.tint, producing a solid flat-colored bar instead of a
    // real fade. `path` is deliberately ignored here: real ES-DE's own
    // technique tints a plain filler image with the gradient, which for
    // this renderer is simplest to reproduce as a plain gradient-filled
    // box at the same real pos/size -- visually equivalent, no image
    // decode needed.
    val gradientType = element.valueOrNull<EsDeThemeValue.Str>("gradientType")?.value
    val startColor = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    if (gradientType != null && startColor != null) {
        val endColor = element.valueOrNull<EsDeThemeValue.Color>("colorEnd")?.let { colorOf(it) } ?: startColor.copy(alpha = 0f)
        val brush = if (gradientType == "horizontal") {
            Brush.horizontalGradient(listOf(startColor, endColor))
        } else {
            Brush.verticalGradient(listOf(startColor, endColor))
        }
        Box(
            modifier = Modifier
                .absoluteOffset(x = offsetX, y = offsetY)
                .size(width = width, height = height)
                .graphicsLayer { alpha = opacity }
                .background(brush),
        )
        return
    }

    // Real gameselector-driven artwork: an element with `gameselectorEntry`
    // (DEcaffe's own game1..game9 mosaic tiles) has no static `path` of
    // its own at all -- its real image comes from whichever game
    // GameSelector picked for that slot, using that game's OWN already-
    // resolved artwork (LibraryEntry.artworkUri, the same real per-game
    // media EsDeArtwork.resolve found at scan time). This uses droidtop's
    // default miximage/cover/screenshot/... priority rather than THIS
    // element's own real `imageType` ordering (e.g. "screenshot,cover,
    // titlescreen") -- a real, honest simplification: re-deriving the
    // exact gamesRoot/system/romBaseName EsDeArtwork.resolve's imageTypes
    // overload needs from a LibraryEntry alone isn't reliably possible
    // for every provider today, so this reuses the artwork already
    // resolved once at scan time instead of re-resolving per element.
    val gameselectorEntry = element.valueOrNull<EsDeThemeValue.UInt>("gameselectorEntry")?.value?.toInt()
    val path = if (gameselectorEntry != null) {
        gameSelection.getOrNull(gameselectorEntry)?.artworkUri
    } else {
        element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved
    } ?: return
    val tint = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    // Real properties (ImageComponent's own opacity/cornerRadius), already
    // parsed but previously unread -- opacity in particular matters a lot
    // for real themes that fade decorative art in/out.
    val cornerRadiusFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("cornerRadius")?.value ?: 0f
    // Real ImageComponent.cpp: cornerRadius scales against screen WIDTH,
    // not height -- confirmed against real ES-DE source (glm::clamp(...) *
    // mRenderer->getScreenWidth()), the one axis-exception also found for
    // help-bar entrySpacing (see EsDeThemedHelpSystem's own comment).
    val cornerRadius = (cornerRadiusFraction * viewWidth.value).dp
    // Real properties, previously not applied at all -- see this file's
    // own EsDeThemedImage doc comment for the real, confirmed carousel
    // outline/fade misalignment this caused (one real source image meant
    // to be mirrored twice around the carousel, both instances rendering
    // in the same orientation without these). Rotation applies around the
    // element's own real `origin` point (same anchor `pos` already uses),
    // not Compose's center-of-box default.
    val originFraction = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f)
    val rotation = element.valueOrNull<EsDeThemeValue.FloatValue>("rotation")?.value ?: 0f
    val flipHorizontal = element.valueOrNull<EsDeThemeValue.Bool>("flipHorizontal")?.value ?: false
    val flipVertical = element.valueOrNull<EsDeThemeValue.Bool>("flipVertical")?.value ?: false
    // Real cropSize property (game1..game9's own mosaic tiles all declare
    // one): real ES-DE crops the source image to a specific sub-rectangle
    // before display. This renderer doesn't decode the source image's own
    // intrinsic size, so an exact sub-rectangle crop isn't implemented --
    // ContentScale.Crop (fill the given box, cropping equally from the
    // overflowing dimension) is an honest approximation, not a precise
    // match, same "no intrinsic-size decode" limitation sizeOf's own doc
    // comment already notes for maxSize.
    val hasCropSize = element.valueOrNull<EsDeThemeValue.Pair>("cropSize") != null
    AsyncImage(
        model = path,
        contentDescription = null,
        colorFilter = tint?.let { ColorFilter.tint(it) },
        alpha = opacity,
        contentScale = if (hasCropSize) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height)
            .graphicsLayer {
                transformOrigin = TransformOrigin(originFraction.x, originFraction.y)
                rotationZ = rotation
                scaleX = if (flipHorizontal) -1f else 1f
                scaleY = if (flipVertical) -1f else 1f
            }
            .let { if (cornerRadius > 0.dp) it.clip(RoundedCornerShape(cornerRadius)) else it },
    )
}

/**
 * Real ES-DE `TextComponent` behavior, ported properly -- the previous
 * version only read `text`/`color`/`pos`, ignoring every other property
 * the parser already extracts correctly (`fontSize`, `horizontalAlignment`,
 * `verticalAlignment`, `letterCase`, `backgroundColor`, `opacity`,
 * `lineSpacing`). That gap mattered far more than it looks: `text`
 * elements are how a theme renders every plain label (headers, info-panel
 * fields, ...), and rendering them at Compose's default ~14sp regardless
 * of the theme's own real `fontSize` (a fraction of screen height, same
 * convention already ported for textlist rows) made a lot of real theme
 * content either invisibly small or wildly mis-scaled relative to the
 * rest of the screen -- a real, likely explanation for content that
 * "looked wrong" without an obvious crash or missing element.
 *
 * `size`, when the theme declares one, becomes both a real wrap width
 * (`fillMaxWidth` + `TextAlign`) and a real box for `verticalAlignment`
 * to position within (`Box`'s own `contentAlignment`) -- matching real
 * ES-DE's own "mSize.y acts as a bounding box the text centers/aligns
 * within" behavior (`TextComponent::onTextUpdated`). An element with no
 * `size` at all (common for a single short label) keeps the old
 * point-anchored `pos`-only placement, since there's no real box to
 * align within.
 */
@Composable
private fun EsDeThemedText(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    // Real gameselector-bound title text (DEcaffe's own `text name="game"`,
    // `metadata=name`): previously fell through the plain `?: return`
    // below every single time, since a metadata-bound element has no
    // static `text` property of its own at all -- silently rendering
    // nothing despite being a real, positioned "text" element, not one of
    // the deferred badges/rating/gamelistinfo types. Real ES-DE convention
    // when `gameselectorEntry` is omitted on a metadata-bound element:
    // implicitly entry 0 of whichever gameselector is in scope (matches
    // DEcaffe's own pairing of this element with `screen2`'s explicit
    // `gameselectorEntry=0` poster -- same featured game, paired caption).
    // Real, extended beyond just "name" now that LibraryEntry actually
    // models the rest of real ES-DE's own MetaData fields (description/
    // developer/publisher/genre/players -- see that field's own doc
    // comment): every metadata-bound `text` element real ES-DE themes use
    // for these keys now resolves to real scraped data instead of always
    // falling through to `defaultValue`.
    val metadata = element.valueOrNull<EsDeThemeValue.Str>("metadata")?.value
    val gameselectorEntry = element.valueOrNull<EsDeThemeValue.UInt>("gameselectorEntry")?.value?.toInt() ?: 0
    val selectedGame = gameSelection.getOrNull(gameselectorEntry)
    val metadataText = when (metadata) {
        "name" -> selectedGame?.title
        "desc" -> selectedGame?.description
        "developer" -> selectedGame?.developer
        "publisher" -> selectedGame?.publisher
        "genre" -> selectedGame?.genre
        "players" -> selectedGame?.players
        else -> null
    }
    val resolvedText = if (metadata != null) {
        metadataText ?: element.valueOrNull<EsDeThemeValue.Str>("defaultValue")?.value
    } else {
        element.valueOrNull<EsDeThemeValue.Str>("text")?.value
    } ?: return
    // Real ES-DE convention: ":space:" renders as blank (reserves the
    // element's own position/size, shows no visible text) rather than the
    // literal string.
    val rawText = if (resolvedText == ":space:") "" else resolvedText
    val uppercase = element.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"
    val text = if (uppercase) rawText.uppercase() else rawText

    val hasSize = element.valueOrNull<EsDeThemeValue.Pair>("size") != null
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(
        element, viewWidth, viewHeight,
        if (hasSize) width else 0.dp, if (hasSize) height else 0.dp,
    )

    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    val backgroundColor = element.valueOrNull<EsDeThemeValue.Color>("backgroundColor")?.let { colorOf(it) }
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)

    // Real ES-DE default text size convention (same as textlist rows):
    // fontSize is a fraction of screen height.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val fontSizeDp = (fontSizeFraction * viewHeight.value).dp
    val fontSizeSp = with(LocalDensity.current) { fontSizeDp.toSp() }
    val lineSpacing = (element.valueOrNull<EsDeThemeValue.FloatValue>("lineSpacing")?.value ?: 1.5f).coerceIn(0.5f, 3f)

    val textAlign = when (element.valueOrNull<EsDeThemeValue.Str>("horizontalAlignment")?.value) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.End
        else -> TextAlign.Start
    }
    val boxAlignment = when (element.valueOrNull<EsDeThemeValue.Str>("verticalAlignment")?.value) {
        "center" -> Alignment.CenterStart
        "bottom" -> Alignment.BottomStart
        else -> Alignment.TopStart
    }

    Box(
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .let { if (hasSize) it.size(width = width, height = height) else it }
            .let { if (backgroundColor != null) it.background(backgroundColor.copy(alpha = backgroundColor.alpha * opacity)) else it },
        contentAlignment = boxAlignment,
    ) {
        Text(
            text = text,
            color = color.copy(alpha = color.alpha * opacity),
            fontSize = fontSizeSp,
            lineHeight = fontSizeSp * lineSpacing,
            textAlign = textAlign,
            modifier = if (hasSize) Modifier.fillMaxWidth() else Modifier,
        )
    }
}

/**
 * Real fallback for `video`/`animation` elements: their own `default`/
 * `defaultImage`/`path` PATH property when present, shown as a plain
 * static image -- or, for a gameselector-driven element (DEcaffe's own
 * `screen2`, the large game-preview poster, which has NO static path
 * property of its own at all), the selected game's own already-resolved
 * artwork, same real per-game-image approach as [EsDeThemedImage]'s own
 * gameselectorEntry handling (see that function's doc comment for the
 * same "default priority order, not this element's own imageType"
 * simplification). Real ES-DE plays these as actual video/GIF content;
 * this pass doesn't build a media-playback engine, so a static poster is
 * the honest alternative to rendering nothing at all.
 */
@Composable
private fun EsDeThemedFallbackImage(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val gameselectorEntry = element.valueOrNull<EsDeThemeValue.UInt>("gameselectorEntry")?.value?.toInt()
    val path = if (gameselectorEntry != null) {
        gameSelection.getOrNull(gameselectorEntry)?.artworkUri
    } else {
        element.valueOrNull<EsDeThemeValue.Path>("default")?.resolved
            ?: element.valueOrNull<EsDeThemeValue.Path>("defaultImage")?.resolved
            ?: element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved
    } ?: return
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val tint = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // "video" real property is imageCornerRadius; "animation" real property is cornerRadius -- different keys, same real concept.
    val cornerRadiusFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("imageCornerRadius")?.value
        ?: element.valueOrNull<EsDeThemeValue.FloatValue>("cornerRadius")?.value ?: 0f
    // Real ImageComponent.cpp: cornerRadius scales against screen WIDTH,
    // not height -- confirmed against real ES-DE source (glm::clamp(...) *
    // mRenderer->getScreenWidth()), the one axis-exception also found for
    // help-bar entrySpacing (see EsDeThemedHelpSystem's own comment).
    val cornerRadius = (cornerRadiusFraction * viewWidth.value).dp
    // Same real cropSize approximation as EsDeThemedImage -- see that
    // function's own doc comment.
    val hasCropSize = element.valueOrNull<EsDeThemeValue.Pair>("cropSize") != null
    AsyncImage(
        model = path,
        contentDescription = null,
        colorFilter = tint?.let { ColorFilter.tint(it) },
        alpha = opacity,
        contentScale = if (hasCropSize) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height)
            .let { if (cornerRadius > 0.dp) it.clip(RoundedCornerShape(cornerRadius)) else it },
    )
}

/**
 * Real, live-updating `clock` rendering -- current wall-clock time,
 * formatted via the element's own real `format` property when present
 * (ES-DE's own strftime-style format string; falls back to a plain
 * default). Ticks every second via a real Compose LaunchedEffect/delay
 * loop, not a one-shot render. See [EsDeThemedDateTime] for the
 * genuinely different, metadata-bound (not live) `datetime` element.
 */
@Composable
private fun EsDeThemedClock(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val format = element.valueOrNull<EsDeThemeValue.Str>("format")?.value ?: "%H:%M"
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val formatted = remember(now, format) {
        runCatching {
            SimpleDateFormat(strftimeToJavaPattern(format), Locale.getDefault()).format(now)
        }.getOrDefault("")
    }
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // Same real scaling axis as EsDeThemedText/textlist rows, but a
    // different real default: DateTimeComponent.cpp's own real default is
    // FONT_SIZE_SMALL (0.035), not TextComponent's FONT_SIZE_MEDIUM
    // (0.045) -- droidtop previously borrowed the wrong component's
    // default for this element type, confirmed against real ES-DE source.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.035f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * viewHeight.value).dp.toSp() }
    Text(
        text = formatted,
        color = color.copy(alpha = color.alpha * opacity),
        fontSize = fontSizeSp,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
    )
}

/**
 * Real, metadata-bound `datetime` rendering -- genuinely different from
 * [EsDeThemedClock]: reads one static real per-game date value (currently
 * only `releasedate`, real ES-DE's own "YYYYMMDDT000000" MD_DATE
 * convention -- see [LibraryEntry.releaseDate]'s own doc comment) and
 * formats it via the element's own real strftime-style `format` property
 * ONCE, not a live-ticking clock. Other real ES-DE datetime metadata keys
 * (`lastplayed`) aren't modeled on [LibraryEntry] yet -- a real, honest
 * gap, not silently pretended away.
 */
@Composable
private fun EsDeThemedDateTime(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val metadata = element.valueOrNull<EsDeThemeValue.Str>("metadata")?.value
    // Real ES-DE's own datetime schema has no gameselectorEntry property
    // (confirmed against EsDeTheme.kt's schema) -- implicit entry 0,
    // same real convention already used for text's own metadata binding.
    val rawDate = when (metadata) {
        "releasedate" -> gameSelection.getOrNull(0)?.releaseDate
        else -> null
    }
    val format = element.valueOrNull<EsDeThemeValue.Str>("format")?.value ?: "%Y-%m-%d"
    val formatted = remember(rawDate, format) {
        rawDate?.let {
            runCatching {
                val sourceFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                sourceFormat.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = sourceFormat.parse(it) ?: return@runCatching null
                SimpleDateFormat(strftimeToJavaPattern(format), Locale.getDefault()).format(parsed)
            }.getOrNull()
        }
    } ?: element.valueOrNull<EsDeThemeValue.Str>("defaultValue")?.value
    if (formatted.isNullOrBlank()) return

    val hasSize = element.valueOrNull<EsDeThemeValue.Pair>("size") != null
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(
        element, viewWidth, viewHeight,
        if (hasSize) width else 0.dp, if (hasSize) height else 0.dp,
    )
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // Same real DateTimeComponent default (0.035) as EsDeThemedClock.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.035f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * viewHeight.value).dp.toSp() }
    Text(
        text = formatted,
        color = color.copy(alpha = color.alpha * opacity),
        fontSize = fontSizeSp,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
    )
}

/**
 * Real `rating` rendering -- [LibraryEntry.rating] (0.0-1.0, the same
 * real convention real ES-DE's own scrapers already normalize to, see
 * that field's own doc comment) drives a real 5-star row. Real ES-DE's
 * own `RatingComponent` renders actual `filledPath`/`unfilledPath` star
 * images when a theme declares them; falls back to a plain unicode star
 * string otherwise -- an honest simplification for the common case, not a
 * full per-theme custom-star-shape renderer.
 */
@Composable
private fun EsDeThemedRating(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp, gameSelection: List<LibraryEntry>) {
    val rating = gameSelection.getOrNull(0)?.rating ?: return
    val hideIfZero = element.valueOrNull<EsDeThemeValue.Bool>("hideIfZero")?.value ?: false
    if (hideIfZero && rating <= 0f) return
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val filledPath = element.valueOrNull<EsDeThemeValue.Path>("filledPath")?.resolved
    val unfilledPath = element.valueOrNull<EsDeThemeValue.Path>("unfilledPath")?.resolved
    val filledStars = kotlin.math.round(rating * 5).toInt().coerceIn(0, 5)

    if (filledPath != null && unfilledPath != null) {
        Row(modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY).graphicsLayer { alpha = opacity }) {
            repeat(5) { i ->
                AsyncImage(
                    model = if (i < filledStars) filledPath else unfilledPath,
                    contentDescription = null,
                    modifier = Modifier.size(height),
                )
            }
        }
    } else {
        val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
        Text(
            text = "★".repeat(filledStars) + "☆".repeat(5 - filledStars),
            color = color.copy(alpha = color.alpha * opacity),
            fontSize = with(LocalDensity.current) { height.toSp() },
            modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
        )
    }
}

/**
 * Real, theme-styled button-hint bar -- reads the theme's own real
 * `<helpsystem>` pos/origin/textColor/iconColor/fontSize/entrySpacing/
 * backgroundColor/opacity (see EsDeTheme.kt's schema), applied to
 * whichever [hints] the caller says are currently valid for this screen.
 * `fontPath`/`customButtonIcon` (real per-theme font/icon assets) aren't
 * applied yet -- real fonts/glyph icons are separate, later work; the
 * button pill + label shape below matches droidtop's own pre-existing
 * (now themed instead of hardcoded-black-and-white) button-hint look.
 */
@Composable
private fun EsDeThemedHelpSystem(
    element: EsDeThemeElement,
    viewWidth: Dp,
    viewHeight: Dp,
    hints: List<kotlin.Pair<GamepadAction, String>>,
) {
    if (hints.isEmpty()) return
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    // Real HelpComponent.cpp defaults (0x777777FF, gray) for BOTH colors --
    // droidtop previously guessed White/Black, confirmed wrong against real
    // ES-DE source: a theme that sets only one of textColor/iconColor would
    // have paired it with an arbitrary, wrong color for the other.
    val defaultHelpColor = Color(0xFF777777)
    val textColor = element.valueOrNull<EsDeThemeValue.Color>("textColor")?.let { colorOf(it) } ?: defaultHelpColor
    val iconColor = element.valueOrNull<EsDeThemeValue.Color>("iconColor")?.let { colorOf(it) } ?: defaultHelpColor
    val backgroundColor = element.valueOrNull<EsDeThemeValue.Color>("backgroundColor")?.let { colorOf(it) }
    val opacity = (element.valueOrNull<EsDeThemeValue.FloatValue>("opacity")?.value ?: 1f).coerceIn(0f, 1f)
    // Real default: HelpComponent constructs with FONT_SIZE_SMALL (0.035),
    // not 0.025 -- confirmed against real ES-DE source (Font.h/HelpComponent.h).
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.035f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * viewHeight.value).dp.toSp() }
    // Real ES-DE HelpComponent.cpp: entrySpacing is a fraction of screen
    // WIDTH, not height (droidtop previously used height, matching every
    // other element's own real height-based convention, but help-bar
    // spacing is confirmed real-source to be the one exception) -- real
    // default is 0.00833, not 0.02 (droidtop's own earlier guess was
    // ~2.4x too large).
    val entrySpacing = (element.valueOrNull<EsDeThemeValue.FloatValue>("entrySpacing")?.value ?: 0.00833f) * viewWidth.value

    Row(
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .let { if (backgroundColor != null) it.background(backgroundColor.copy(alpha = backgroundColor.alpha * opacity)) else it }
            .graphicsLayer { alpha = opacity },
        horizontalArrangement = Arrangement.spacedBy(entrySpacing.dp),
    ) {
        hints.forEach { (action, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    GamepadKeyMap.labelFor(action),
                    color = iconColor,
                    fontSize = fontSizeSp,
                    modifier = Modifier
                        .background(textColor, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Text(label, color = textColor, fontSize = fontSizeSp)
            }
        }
    }
}

/**
 * `origin` is ES-DE's real anchor-point convention: a 0-1 fraction of the
 * element's OWN size that `pos` refers to, not always the top-left corner
 * (DEcaffe uses `origin="0.5 0.5"` -- center-anchoring -- throughout).
 * Defaults to "0 0" (top-left), matching ES-DE's own default when an
 * element omits the property.
 */
/**
 * ES-DE's real `datetime`/`clock` `format` property uses C strftime-style
 * `%`-specifiers (its own renderer calls through to strftime under the
 * hood), not Java's SimpleDateFormat letters -- feeding one straight into
 * SimpleDateFormat produced real garbage (e.g. a real theme's "%H:%M"-style
 * default rendered as literal "%2026", since SimpleDateFormat treats '%' as
 * a literal character and 'Y'/'H'/etc. as its OWN unrelated pattern
 * letters). Covers the common specifiers ES-DE's own themes actually use;
 * anything else passes through as a SimpleDateFormat literal (quoted), the
 * same honest-fallback approach used elsewhere in this renderer.
 */
private val STRFTIME_TO_JAVA = listOf(
    "%Y" to "yyyy", "%y" to "yy",
    "%m" to "MM", "%d" to "dd",
    "%H" to "HH", "%I" to "hh",
    "%M" to "mm", "%S" to "ss",
    "%p" to "a",
    "%A" to "EEEE", "%a" to "EEE",
    "%B" to "MMMM", "%b" to "MMM",
    "%%" to "%",
)

private fun strftimeToJavaPattern(format: String): String {
    val sb = StringBuilder()
    var i = 0
    outer@ while (i < format.length) {
        for ((strftime, java) in STRFTIME_TO_JAVA) {
            if (format.startsWith(strftime, i)) {
                sb.append(java)
                i += strftime.length
                continue@outer
            }
        }
        val c = format[i]
        if (c.isLetter()) sb.append('\'').append(c).append('\'') else sb.append(c)
        i++
    }
    return sb.toString()
}

private fun positionOf(
    element: EsDeThemeElement,
    viewWidth: Dp,
    viewHeight: Dp,
    width: Dp = 0.dp,
    height: Dp = 0.dp,
): kotlin.Pair<Dp, Dp> {
    val pos = element.valueOrNull<EsDeThemeValue.Pair>("pos") ?: EsDeThemeValue.Pair(0f, 0f)
    val origin = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f)
    val x = viewWidth * pos.x - width * origin.x
    val y = viewHeight * pos.y - height * origin.y
    return x to y
}

/**
 * Real, distinct ES-DE properties (confirmed against ImageComponent.cpp's
 * own setResize/setMaxSize): "size" stretches to an exact size, "maxSize"
 * scales down to fit WITHIN bounds preserving aspect ratio -- a real
 * theme can use only one (Art Book Next's own system-logo element has no
 * "size" at all, only "maxSize"). "size" wins if both are present,
 * matching ES-DE's own real precedence (its applyTheme sets one or the
 * other, "size" checked first). The maxSize case doesn't yet apply real
 * aspect-preserving containment at this layer -- AsyncImage's own
 * ContentScale.Fit (already applied at the call site) does that visually
 * within whatever box these bounds define, an honest approximation since
 * this parser doesn't decode the target image's real intrinsic size.
 */
private fun sizeOf(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp): kotlin.Pair<Dp, Dp> {
    val size = element.valueOrNull<EsDeThemeValue.Pair>("size")
        ?: element.valueOrNull<EsDeThemeValue.Pair>("maxSize")
        ?: EsDeThemeValue.Pair(0.2f, 0.2f)
    return viewWidth * size.x to viewHeight * size.y
}

private fun zIndexOf(element: EsDeThemeElement): Float =
    element.valueOrNull<EsDeThemeValue.FloatValue>("zIndex")?.value ?: 0f

/** [EsDeThemeValue.Color.argbLikeRgba] is packed RRGGBBAA (same layout as ES-DE's own getHexColor) -- Compose's Color wants ARGB, so the channels need reordering, not just a straight reinterpret. */
private fun colorOf(value: EsDeThemeValue.Color): Color {
    val rgba = value.argbLikeRgba
    val r = (rgba shr 24) and 0xFF
    val g = (rgba shr 16) and 0xFF
    val b = (rgba shr 8) and 0xFF
    val a = rgba and 0xFF
    return Color(red = r.toInt(), green = g.toInt(), blue = b.toInt(), alpha = a.toInt())
}
