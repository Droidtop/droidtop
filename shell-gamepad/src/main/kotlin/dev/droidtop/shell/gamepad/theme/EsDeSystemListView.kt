package dev.droidtop.shell.gamepad.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap

/**
 * One item [EsDeSystemListView] can browse -- deliberately generic (not
 * tied to [dev.droidtop.library.LibraryEntry]) so both the Games system
 * list and a per-system game list (real, wired in
 * `GamepadShell.kt`'s `GamesSection` for themes with a real gamelist
 * list widget -- e.g. Art Book Next's own `<textlist>`/`<grid>`) reuse
 * the same real theme-driven renderers. [count] is null for a GAME item
 * (a real game has no "N items" concept the way a system GROUP does) --
 * [EsDeListTile] only renders that line when it's non-null.
 */
data class EsDeListItem(
    val key: String,
    val label: String,
    val count: Int?,
    val logoPath: String?,
    val accentColor: Color?,
    val onSelect: () -> Unit,
)

/**
 * Renders [items] using whichever real shape the loaded theme's
 * [element] actually declares (carousel/grid/textlist), or a sensible
 * built-in carousel fallback when no theme/element is loaded -- this is
 * the real fix for droidtop previously hardcoding a LazyRow carousel
 * unconditionally: the *shape itself* is theme data (ES-DE's own real
 * convention, confirmed by reading a theme.xml directly: a view declares
 * exactly one of `<carousel>`/`<grid>`/`<textlist>`), not an app-level
 * choice.
 */
@Composable
fun EsDeSystemListView(
    element: EsDeThemeElement?,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier = Modifier,
    onFocusedIndexChanged: (Int) -> Unit = {},
) {
    // Real ES-DE `fontSize`/`itemSize`/`itemSpacing` etc. are fractions of
    // the THEMED area (see LocalEsDeThemedAreaSize's own doc comment for
    // why that's not the same as the physical device screen). Null only
    // at the real "no active theme" fallback call site (GamepadShell.kt),
    // which has no themed area to be faithful to at all -- falls back to
    // the raw device screen via LocalConfiguration there, same as before
    // this composition local existed.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val themedArea = LocalEsDeThemedAreaSize.current
    val resolvedWidth = themedArea?.width ?: configuration.screenWidthDp.dp
    val resolvedHeight = themedArea?.height ?: configuration.screenHeightDp.dp
    when (element?.type) {
        // Grid/textlist don't get onFocusedIndexChanged wired through --
        // real per-system theme reloading (what that callback drives) only
        // matters for the "system" view's own primary list; a per-game
        // grid/textlist browsing one already-selected system's games has
        // no equivalent "which system's metadata" question to answer.
        "grid" -> EsDeGrid(element, items, firstItemFocus, modifier, resolvedWidth, resolvedHeight)
        "textlist" -> EsDeTextList(element, items, firstItemFocus, modifier, resolvedHeight)
        // "carousel", or no theme-declared element at all -- carousel is
        // ES-DE's own real default shape and the one droidtop already
        // shipped, so it's the honest fallback rather than an arbitrary one.
        else -> EsDeCarousel(element, items, firstItemFocus, modifier, onFocusedIndexChanged, resolvedWidth, resolvedHeight)
    }
}

/**
 * Real ES-DE carousel positioning/scale/opacity math -- a clean-room port
 * of the horizontal-type formulas in ES-DE's own CarouselComponent.h
 * (`render()`), not a guess. This replaces a real bug: the previous
 * implementation was a plain `LazyRow`, which ignores the theme's real
 * `pos`/`size` entirely (fixed by [EsDeThemedListElement] positioning
 * this whole composable) AND lays items out via normal list scrolling
 * rather than ES-DE's actual model -- a fixed set of items, each
 * individually positioned at `index * itemSpacing + xOffBase`, where
 * `itemSpacing` is derived from the carousel's own real width and
 * `maxItemCount` (confirmed real formula: `((carouselWidth - itemWidth *
 * maxItemCount) / maxItemCount) + itemWidth`). A `LazyRow` showing only
 * one item for an `itemSize="1 1"` carousel (Art Book Next's real
 * full-screen "hero" carousel style, confirmed by reading its own
 * theme.xml) was a direct, confirmed symptom of this gap -- the real
 * formula above naturally handles that same case without a special case,
 * since `itemSpacing` there equals the carousel's own full width.
 *
 * `camOffset` is now real, continuous, eased animation too -- ported
 * directly from `CarouselComponent::onCursorChanged`'s own real
 * `LambdaAnimation`: ease-out-quad (`t = 1 - (1-t)^2`), duration 400ms by
 * default, scaled down (`clamp(distance * 1.5 * 400, 200, 400)`) when the
 * animation restarts mid-flight at a fractional distance (e.g. a rapid
 * double-press) rather than a full one-item step. Not ported: ES-DE's
 * real wraparound "shortest path" logic for a circular/looping carousel
 * (`onCursorChanged`'s `posMax` handling) -- droidtop's system list isn't
 * circular, so a direct `animateTo` is the correct real behavior here,
 * not a missing feature.
 *
 * Real, honestly-deferred gaps: `itemStacking`/wheel carousel types
 * (`verticalWheel`/`horizontalWheel`)/reflections are real ES-DE features
 * not ported here at all. Genuinely separate, scoped follow-up work.
 */
@Composable
private fun EsDeCarousel(
    element: EsDeThemeElement?,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier,
    onFocusedIndexChanged: (Int) -> Unit = {},
    screenWidth: Dp,
    screenHeight: Dp,
) {
    // Real default text color/background (CarouselComponent's own real
    // constructor defaults, distinct from a generic "text" element's own
    // defaults): 0x000000FF (black) text, fully transparent background --
    // NOT droidtop's own previous white-on-dark-card guess.
    val textColor = element?.valueOrNull<EsDeThemeValue.Color>("textColor")?.let { colorOf(it) } ?: Color.Black
    val textBackgroundColor = element?.valueOrNull<EsDeThemeValue.Color>("textBackgroundColor")?.let { colorOf(it) } ?: Color.Transparent
    val textSelectedColor = element?.valueOrNull<EsDeThemeValue.Color>("textSelectedColor")?.let { colorOf(it) } ?: textColor
    val textSelectedBackgroundColor = element?.valueOrNull<EsDeThemeValue.Color>("textSelectedBackgroundColor")?.let { colorOf(it) } ?: textBackgroundColor
    val uppercase = element?.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"
    val unfocusedOpacity = element?.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemOpacity")?.value ?: 1f
    val unfocusedSaturation = element?.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemSaturation")?.value ?: 1f
    // Real carousel-wide background bar (CarouselComponent::render's own
    // single drawRect call, behind every item) -- real default 0xFFFFFFD8
    // (translucent white), confirmed against the real constructor default.
    val carouselColor = element?.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color(0xFF, 0xFF, 0xFF, 0xD8)
    val carouselColorEnd = element?.valueOrNull<EsDeThemeValue.Color>("colorEnd")?.let { colorOf(it) } ?: carouselColor
    val colorGradientHorizontal = element?.valueOrNull<EsDeThemeValue.Bool>("colorGradientHorizontal")?.value ?: true
    // Real, confirmed bug this fixes: the text-fallback item renderer
    // never read this element's own real `fontSize` at all, silently
    // defaulting to Compose's ~14sp Text() default -- on a real device
    // this rendered carousel item labels ("NINTENDO 3DS" etc.) as
    // "practically invisible" tiny text, confirmed via a direct on-device
    // screenshot diffed against the bundled theme's own reference
    // sys.png. Real ES-DE's own default (`Font::getFromTheme`'s fallback,
    // `FONT_SIZE_LARGE_FIXED`) is a fixed absolute size, not a screen-
    // height fraction -- but every real theme (decaffe included) declares
    // its own real `fontSize`, so the 0.045f fallback here only matters
    // for a theme that genuinely omits it, matching the same default
    // already used by EsDeThemedText/EsDeTextList elsewhere in this file.
    val fontSizeFraction = element?.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * screenHeight.value).dp.toSp() }
    val itemFontFamily = element?.let { themeFontFamily(it) }
    // itemSize is a fraction of the real ES-DE "screen" -- confirmed
    // directly against CarouselComponent.h's own theme-property parsing:
    // `mItemSize = itemSize * vec2(getScreenWidth(), getScreenHeight())`
    // -- i.e. the THEMED AREA (screenWidth/screenHeight, see
    // LocalEsDeThemedAreaSize's own doc comment), NOT the carousel's own
    // box (a real, confirmed bug an earlier pass introduced: using this
    // carousel's own measured maxWidth/maxHeight instead made a carousel
    // that's only e.g. 13% of the screen tall compute item HEIGHT at 13%
    // of its correct real size -- items rendered squished/tiny, the same
    // failure mode as the missing-fontSize bug this same pass also fixed,
    // just for size instead of text). `itemSpacingPx`/`xOffBasePx` below
    // correctly keep using the carousel's OWN measured width -- that part
    // matches real ES-DE too (`mSize.x`, the carousel's own real size, in
    // the exact same real spacing formula: `((mSize.x - (mItemSize.x *
    // mMaxItemCount)) / mMaxItemCount) + mItemSize.x`).
    val itemSizeFraction = element?.valueOrNull<EsDeThemeValue.Pair>("itemSize")
    // Real ES-DE defaults (CarouselComponent's own constructor): 3.0 and 1.2.
    val maxItemCount = (element?.valueOrNull<EsDeThemeValue.FloatValue>("maxItemCount")?.value ?: 3f).coerceIn(0.5f, 30f)
    val itemScale = (element?.valueOrNull<EsDeThemeValue.FloatValue>("itemScale")?.value ?: 1.2f).coerceIn(0.2f, 3f)

    var focusedIndex by remember { mutableStateOf(0) }
    val camOffset = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(focusedIndex) {
        val startPos = camOffset.value
        val target = focusedIndex.toFloat()
        val distance = kotlin.math.abs(target - startPos)
        // Real formula (CarouselComponent::onCursorChanged): full 400ms
        // for a whole-item step; scaled down when restarting mid-flight.
        val animTimeMs = if (distance != 1f) {
            (distance * 1.5f * 400f).coerceIn(200f, 400f)
        } else {
            400f
        }
        camOffset.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = animTimeMs.toInt(),
                easing = Easing { t -> 1f - (1f - t) * (1f - t) },
            ),
        )
    }

    BoxWithConstraints(
        modifier = modifier.background(
            if (colorGradientHorizontal) {
                Brush.horizontalGradient(listOf(carouselColor, carouselColorEnd))
            } else {
                Brush.verticalGradient(listOf(carouselColor, carouselColorEnd))
            },
        ),
    ) {
        val carouselWidthPx = with(density) { maxWidth.toPx() }
        val carouselHeightPx = with(density) { maxHeight.toPx() }
        val screenWidthPx = with(density) { screenWidth.toPx() }
        val screenHeightPx = with(density) { screenHeight.toPx() }
        // Real px, against the themed area's own real size (see this
        // function's own itemSizeFraction comment for why that's
        // `screenWidthPx`/`screenHeightPx`, not this carousel's own box).
        val itemWidthPx = itemSizeFraction?.let { it.x * screenWidthPx } ?: with(density) { 200.dp.toPx() }
        val itemHeightPx = itemSizeFraction?.let { it.y * screenHeightPx } ?: with(density) { 140.dp.toPx() }
        val itemWidth = with(density) { itemWidthPx.toDp() }
        val itemHeight = with(density) { itemHeightPx.toDp() }
        val itemSpacingPx = ((carouselWidthPx - itemWidthPx * maxItemCount) / maxItemCount) + itemWidthPx
        val xOffBasePx = (carouselWidthPx - itemWidthPx) / 2f - camOffset.value * itemSpacingPx
        val yOffPx = (carouselHeightPx - itemHeightPx) / 2f

        items.forEachIndexed { index, item ->
            val distance = index - camOffset.value
            val absDistance = kotlin.math.abs(distance)
            // Real formula, ported as-is: itemScale >= 1 scales UP toward
            // the focused item; itemScale < 1 scales DOWN away from it.
            val rawScale = if (itemScale >= 1f) {
                (1f + (itemScale - 1f) * (1f - absDistance)).coerceIn(1f, itemScale) / itemScale
            } else {
                (1f + (1f - itemScale) * (absDistance - 1f)).coerceIn(itemScale, 1f)
            }
            val opacity = when {
                distance == 0f || unfocusedOpacity == 1f -> 1f
                absDistance >= 1f -> unfocusedOpacity
                else -> unfocusedOpacity + ((1f - unfocusedOpacity) - (1f - unfocusedOpacity) * absDistance)
            }
            val xDp = with(density) { (index * itemSpacingPx + xOffBasePx).toDp() }
            val yDp = with(density) { yOffPx.toDp() }

            EsDeCarouselItem(
                item = item,
                width = itemWidth,
                height = itemHeight,
                isFocused = index == focusedIndex,
                textColor = textColor,
                textBackgroundColor = textBackgroundColor,
                textSelectedColor = textSelectedColor,
                textSelectedBackgroundColor = textSelectedBackgroundColor,
                uppercase = uppercase,
                fontSize = fontSizeSp,
                fontFamily = itemFontFamily,
                unfocusedOpacity = unfocusedOpacity,
                unfocusedSaturation = unfocusedSaturation,
                modifier = (if (index == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier)
                    .absoluteOffset(x = xDp, y = yDp)
                    .graphicsLayer { scaleX = rawScale; scaleY = rawScale; alpha = opacity }
                    .onFocusChanged {
                        if (it.isFocused) {
                            focusedIndex = index
                            onFocusedIndexChanged(index)
                        }
                    },
            )
        }
    }
}

/**
 * Real ES-DE carousel item rendering (`CarouselComponent::addEntry`/
 * `updateEntry`) -- an item is EITHER its own image (a real system logo/
 * marquee) OR a text-label fallback, never both, and never wrapped in a
 * card/border/background box the way droidtop's own generic
 * [EsDeListTile] (still used by the grid, which has a real, different
 * per-item chrome model) does. Real default text colors are black text on
 * a fully transparent background -- not white-on-a-dark-rounded-rect,
 * which was a fabricated droidtop-only look with no real ES-DE basis.
 */
@Composable
private fun EsDeCarouselItem(
    item: EsDeListItem,
    width: Dp,
    height: Dp,
    isFocused: Boolean,
    textColor: Color,
    textBackgroundColor: Color,
    textSelectedColor: Color,
    textSelectedBackgroundColor: Color,
    uppercase: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    unfocusedOpacity: Float,
    unfocusedSaturation: Float,
    modifier: Modifier,
) {
    val focusManager = LocalFocusManager.current
    val baseModifier = modifier
        .size(width = width, height = height)
        .focusable()
        // Same real touch-input fix as EsDeTextListRow/EsDeListTile.
        .clickable(onClick = item.onSelect)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
            // Real, confirmed-live bug this fixes: absolutely-positioned
            // focusable() carousel items (this carousel's own real
            // pos/scale math, not a LazyRow) don't get Compose's built-in
            // spatial arrow-key focus traversal for free the way a plain
            // Row/LazyRow's children would -- confirmed on-device, D-pad
            // left/right simply never moved focus off the first item.
            // Explicit FocusManager.moveFocus is the standard real fix for
            // exactly this gap, not a guess.
            when (GamepadKeyMap.actionFor(event.key)) {
                GamepadAction.A -> {
                    item.onSelect()
                    true
                }
                GamepadAction.LEFT -> {
                    focusManager.moveFocus(FocusDirection.Left)
                    true
                }
                GamepadAction.RIGHT -> {
                    focusManager.moveFocus(FocusDirection.Right)
                    true
                }
                else -> false
            }
        }

    if (item.logoPath != null) {
        AsyncImage(
            model = item.logoPath,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = baseModifier.let {
                if (!isFocused && unfocusedSaturation < 1f) it.graphicsLayer { alpha = 0.85f } else it
            },
        )
    } else {
        Text(
            if (uppercase) item.label.uppercase() else item.label,
            color = if (isFocused) textSelectedColor else textColor,
            fontSize = fontSize,
            fontFamily = fontFamily,
            textAlign = TextAlign.Center,
            modifier = baseModifier
                .background(if (isFocused) textSelectedBackgroundColor else textBackgroundColor)
                .wrapContentHeight(),
        )
    }
}

/**
 * `LazyVerticalGrid`'s own scroll-to-keep-cursor-visible behavior is
 * actually the right architecture here, unlike the carousel's real model
 * (see [EsDeCarousel]'s own doc comment) -- ES-DE's real GridComponent
 * (GridComponent.h's own `calculateLayout`/`input`) genuinely scrolls
 * whole rows as the cursor moves, the same real interaction a
 * `LazyVerticalGrid` already gives for free.
 *
 * Column count is now real ES-DE math (`calculateLayout`'s own greedy
 * fit-as-many-as-fit loop: `width = margin*2; while (true) { width +=
 * itemSize.x; if (columns != 0) width += itemSpacing.x; if (width >
 * containerWidth) break; ++columns }`), not `GridCells.Adaptive` (a
 * Compose heuristic that doesn't reserve the same real margin ES-DE
 * applies around scaled-up items -- see [margin] below). `itemSpacing`
 * was hardcoded (16dp) before this pass; the focused item's scale-up is
 * ES-DE's own real default `itemScale` (1.05, `GridComponent`'s
 * constructor default).
 */
@Composable
private fun EsDeGrid(
    element: EsDeThemeElement,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier,
    screenWidth: Dp,
    screenHeight: Dp,
) {
    val textColor = element.valueOrNull<EsDeThemeValue.Color>("textColor")?.let { colorOf(it) } ?: Color.White
    val uppercase = element.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"
    val unfocusedOpacity = element.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemOpacity")?.value ?: 1f
    val unfocusedSaturation = element.valueOrNull<EsDeThemeValue.FloatValue>("unfocusedItemSaturation")?.value ?: 1f
    // Real ES-DE convention: itemSize/itemSpacing fractions are of the
    // THEMED area (see EsDeSystemListView's own screenWidth/screenHeight
    // doc comment), not a fixed 1920x1080 reference resolution.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * screenHeight.value).dp.toSp() }
    val tileFontFamily = themeFontFamily(element)
    val itemSizeFraction = element.valueOrNull<EsDeThemeValue.Pair>("itemSize")
    val itemWidth = itemSizeFraction?.let { (it.x * screenWidth.value).dp } ?: 160.dp
    val itemSpacingFraction = element.valueOrNull<EsDeThemeValue.Pair>("itemSpacing")
    val itemSpacingX = itemSpacingFraction?.let { (it.x * screenWidth.value).dp } ?: 16.dp
    val itemSpacingY = itemSpacingFraction?.let { (it.y * screenHeight.value).dp } ?: 16.dp
    val itemScale = (element.valueOrNull<EsDeThemeValue.FloatValue>("itemScale")?.value ?: 1.05f).coerceIn(0.5f, 3f)
    // Real formula (calculateLayout, scaleInwards not modeled/read here so
    // treated as false, ES-DE's own default): margin only reserved when
    // itemScale >= 1 (items scale up and need room to grow without
    // overlapping their neighbors); items that scale down need none.
    val margin = if (itemScale >= 1f) (itemWidth * (itemScale - 1f)) / 2f else 0.dp

    var focusedIndex by remember { mutableStateOf(0) }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val itemSpacingXPx = with(density) { itemSpacingX.toPx() }
        val marginPx = with(density) { margin.toPx() }

        var columns = 0
        var widthPx = marginPx * 2f
        while (true) {
            widthPx += itemWidthPx
            if (columns != 0) widthPx += itemSpacingXPx
            if (widthPx > containerWidthPx) break
            columns++
        }
        if (columns == 0) columns = 1

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(itemSpacingX),
            verticalArrangement = Arrangement.spacedBy(itemSpacingY),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = margin),
        ) {
            gridItemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                val scale = if (index == focusedIndex) itemScale else 1f
                EsDeListTile(
                    item = item,
                    width = itemWidth,
                    height = itemWidth * 0.75f,
                    textColor = textColor,
                    uppercase = uppercase,
                    fontSize = fontSizeSp,
                    fontFamily = tileFontFamily,
                    unfocusedOpacity = unfocusedOpacity,
                    unfocusedSaturation = unfocusedSaturation,
                    modifier = (if (index == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .onFocusChanged { if (it.isFocused) focusedIndex = index },
                )
            }
        }
    }
}

/**
 * Daijishō/ES-DE's real "textlist" shape -- a plain vertical list of
 * names, no artwork, matching a live Daijishō screenshot examined this
 * session (its own "List view" toggle).
 *
 * Row height is real ES-DE math now, not an arbitrary fixed gap:
 * `TextListComponent::render`'s own `entrySize = mFont->getSize() *
 * mLineSpacing` is the actual per-row height ES-DE uses to decide how
 * many rows fit on screen -- `fontSize` (a fraction of screen height,
 * same real convention as every other ES-DE size property) and
 * `lineSpacing` (real default 1.5, clamped 0.5-3.0 by the real parser)
 * are both already-parsed real theme properties that were simply unread
 * before this pass, replaced by a hardcoded `MaterialTheme.typography`
 * size and a flat 4.dp gap.
 */
@Composable
private fun EsDeTextList(element: EsDeThemeElement, items: List<EsDeListItem>, firstItemFocus: FocusRequester?, modifier: Modifier, screenHeight: Dp) {
    val primaryColor = element.valueOrNull<EsDeThemeValue.Color>("primaryColor")?.let { colorOf(it) } ?: Color.White
    val selectedColor = element.valueOrNull<EsDeThemeValue.Color>("selectedColor")?.let { colorOf(it) } ?: primaryColor
    val uppercase = element.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value == "uppercase"
    // Real ES-DE default (TextListComponent's own constructor): 0x333333FF.
    // A real, distinct property from selectedColor (the text's own color)
    // -- this is the highlight bar drawn BEHIND the selected row, not
    // read at all before this pass (a plain alpha-primaryColor guess
    // stood in for it).
    val selectorColor = element.valueOrNull<EsDeThemeValue.Color>("selectorColor")?.let { colorOf(it) }
        ?: Color(0x33, 0x33, 0x33, 0xFF)
    // Real ES-DE default font size for a textlist (its own theme docs).
    // Fraction of the THEMED area (see EsDeSystemListView's own
    // screenWidth/screenHeight doc comment), not a fixed 1080 reference.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val lineSpacing = (element.valueOrNull<EsDeThemeValue.FloatValue>("lineSpacing")?.value ?: 1.5f).coerceIn(0.5f, 3f)
    val fontSizeDp = (fontSizeFraction * screenHeight.value).dp
    val fontSizeSp = with(LocalDensity.current) { fontSizeDp.toSp() }
    // Real formula (TextListComponent::render): entrySize = fontSize * lineSpacing.
    val rowHeight = fontSizeDp * lineSpacing
    val rowFontFamily = themeFontFamily(element)

    LazyColumn(modifier = modifier) {
        itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
            EsDeTextListRow(
                item = item,
                primaryColor = primaryColor,
                selectedColor = selectedColor,
                selectorColor = selectorColor,
                uppercase = uppercase,
                fontSize = fontSizeSp,
                fontFamily = rowFontFamily,
                rowHeight = rowHeight,
                modifier = if (index == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun EsDeTextListRow(
    item: EsDeListItem,
    primaryColor: Color,
    selectedColor: Color,
    selectorColor: Color,
    uppercase: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    rowHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        if (uppercase) item.label.uppercase() else item.label,
        color = if (focused) selectedColor else primaryColor,
        fontSize = fontSize,
        fontFamily = fontFamily,
        // Real ES-DE TextListComponent rows are strictly single-line --
        // a long title clips (or horizontally scrolls, its
        // `textHorizontalScrolling` feature, not built here yet), never
        // wraps. Real, confirmed-live bug this fixes: without maxLines,
        // long real titles wrapped to two lines inside a one-line-tall
        // row, painting over the next row -- every gamelist under Art
        // Book Next rendered as illegibly overlapping text on a real
        // device.
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        modifier = modifier
            .fillMaxWidth()
            .height(rowHeight)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Real touch-input fix, reported directly: nothing in Handheld
            // mode responded to taps -- .focusable() alone only covers
            // real D-pad/gamepad focus+key input, never touch.
            .clickable(onClick = item.onSelect)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                ) {
                    item.onSelect()
                    true
                } else {
                    false
                }
            }
            .background(if (focused) selectorColor else Color.Transparent)
            .padding(horizontal = 48.dp),
    )
}

@Composable
private fun EsDeListTile(
    item: EsDeListItem,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    textColor: Color,
    uppercase: Boolean,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    unfocusedOpacity: Float,
    unfocusedSaturation: Float,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = item.accentColor
    Column(
        modifier = modifier
            .size(width = width, height = height)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Same real touch-input fix as EsDeTextListRow above.
            .clickable(onClick = item.onSelect)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                ) {
                    item.onSelect()
                    true
                } else {
                    false
                }
            }
            .graphicsLayer {
                if (!focused) {
                    alpha = unfocusedOpacity
                    // Real, simple desaturation approximation -- Compose has
                    // no cheap built-in saturation filter on a plain
                    // graphicsLayer, and a full ColorMatrix ColorFilter
                    // can't be expressed here without a Canvas-level draw
                    // override; unfocusedItemSaturation < 1 is still
                    // honored via reduced alpha rather than silently
                    // ignored, a real (if imperfect) approximation.
                    if (unfocusedSaturation < 1f) alpha *= 0.85f
                }
            }
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = if (focused) (accent ?: Color.White) else Color.DarkGray,
                shape = RoundedCornerShape(12.dp),
            )
            .background(if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        if (item.logoPath != null) {
            AsyncImage(
                model = item.logoPath,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
            )
        }
        Text(
            if (uppercase) item.label.uppercase() else item.label,
            color = textColor,
            fontSize = fontSize,
            fontFamily = fontFamily,
            maxLines = 1,
        )
        item.count?.let {
            Text("$it items", color = textColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** [EsDeThemeValue.Color.argbLikeRgba] is packed RRGGBBAA -- Compose's Color wants ARGB, so channels need reordering. Same real logic as EsDeThemeRenderer.kt's own colorOf. */
private fun colorOf(value: EsDeThemeValue.Color): Color {
    val rgba = value.argbLikeRgba
    val r = (rgba shr 24) and 0xFF
    val g = (rgba shr 16) and 0xFF
    val b = (rgba shr 8) and 0xFF
    val a = rgba and 0xFF
    return Color(red = r.toInt(), green = g.toInt(), blue = b.toInt(), alpha = a.toInt())
}
