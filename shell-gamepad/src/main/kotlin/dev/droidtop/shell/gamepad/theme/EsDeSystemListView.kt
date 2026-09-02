package dev.droidtop.shell.gamepad.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
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
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.GameMediaLocator
import dev.droidtop.library.theme.EsDeCarouselPlacement
import dev.droidtop.library.theme.EsDeImageTypes
import dev.droidtop.library.theme.EsDeCarouselType
import dev.droidtop.library.theme.EsDeGridConfig
import dev.droidtop.library.theme.EsDeGridLayout
import dev.droidtop.library.theme.EsDeLetterCase
import dev.droidtop.library.theme.EsDePrimaryAlignment
import dev.droidtop.library.theme.EsDeSelectorLayer
import dev.droidtop.library.theme.EsDeTextListConfig
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.applyTo
import dev.droidtop.library.theme.esDeCarouselConfig
import dev.droidtop.library.theme.esDeGridConfig
import dev.droidtop.library.theme.esDeGridItemCenter
import dev.droidtop.library.theme.esDeGridScrollRow
import dev.droidtop.library.theme.esDeIndicatorPrefix
import dev.droidtop.library.theme.esDeTextListConfig
import dev.droidtop.library.theme.layoutEsDeCarousel
import dev.droidtop.library.theme.layoutEsDeGrid
import dev.droidtop.library.theme.layoutEsDeTextList
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap

/**
 * One item [EsDeSystemListView] can browse -- deliberately generic (not
 * tied to [dev.droidtop.library.LibraryEntry]) so both the Games system
 * list and a per-system game list (real, wired in
 * `GamepadShell.kt`'s `GamesSection` for themes with a real gamelist
 * list widget -- e.g. Art Book Next's own `<textlist>`/`<grid>`) reuse
 * the same real theme-driven renderers. Deliberately carries no per-item
 * chrome of its own -- no count line, no accent color -- because a real
 * ES-DE list widget draws an item as its own artwork (or its name as
 * text) plus whatever background/selector layers the THEME asked for,
 * and anything droidtop adds on top of that occupies a surface the theme
 * was meant to own.
 */
data class EsDeListItem(
    val key: String,
    val label: String,
    val logoPath: String?,
    val onSelect: () -> Unit,
    // Real ES-DE textlist `indicators`: a favorite gets a leading marker
    // before its name (GamelistBase.cpp:916-926). Always false for a
    // system-list item -- real ES-DE has no favorite concept for systems
    // either.
    val favorite: Boolean = false,
    // Real `TextListEntryType::SECONDARY`, which is what selects the
    // `secondaryColor`/`selectedSecondaryColor`/
    // `selectedSecondaryBackgroundColor` half of a textlist's real color
    // scheme. In real ES-DE exactly one thing is secondary: a FOLDER
    // entry in a gamelist (GamelistBase.cpp:955-958; system-view entries
    // are unconditionally PRIMARY, SystemView.cpp:888). droidtop's ROM
    // scan is flat and produces no folder entries at all yet, so nothing
    // sets this today -- the same standing gap the `folder` badge slot
    // documents. It is modeled rather than omitted because the color
    // rule that reads it is real, and half-implementing that rule would
    // silently paint folders in the wrong color the day folders land.
    val isSecondary: Boolean = false,
    // Where this item's game keeps its scraped media, so a carousel or
    // grid that declared an `<imageType>` can resolve THAT type instead of
    // always showing [logoPath]. Null for a system-list item (a system has
    // no per-game media) and for any game with no ES-DE media layout.
    // See dev.droidtop.library.GameMediaLocator.
    val mediaLocator: GameMediaLocator? = null,
)

/**
 * The image a `carousel`/`grid` entry should draw, honouring the
 * element's own `<imageType>`.
 *
 * Real ES-DE, ported from GridComponent.h:490-522 (CarouselComponent.h:
 * 548-580 is the same code): walk the declared types in the THEME's
 * order, take the first that has a file, and stop; `none` breaks the loop
 * immediately, which is the theme explicitly asking for the game's name
 * as text instead of an image.
 *
 * Two deliberate points where this is not a literal port:
 *
 *  * When nothing resolves, real ES-DE falls back to the element's own
 *    `defaultImage` and then to text. droidtop falls back to [logoPath]
 *    -- the entry's single pre-resolved artwork -- FIRST. This is a
 *    knowing divergence, not an accident: droidtop's own scraper writes
 *    covers and little else, so a strict port would blank out a fully
 *    scraped library the moment a theme asked for a marquee. `none` is
 *    exempt, because there the theme did not fail to find media, it asked
 *    for text.
 *  * `defaultImage` itself is not implemented on these two elements yet
 *    (it is not part of this change), so the chain ends at [logoPath].
 */
internal fun EsDeListItem.esDePrimaryImage(imageTypes: List<String>): String? {
    if (imageTypes.isEmpty()) return logoPath
    val untilNone = imageTypes.takeWhile { it != "none" }
    val resolved = mediaLocator?.let { EsDeArtwork.resolveImageTypes(it, untilNone) }
    if (resolved != null) return resolved
    // `none` reached without a match: the theme asked for text.
    return if (untilNone.size != imageTypes.size) null else logoPath
}

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
    // Real `imageType` on the two primary elements that accept it
    // (`carousel` and `grid`; `textlist` has no image at all, and real
    // ES-DE's schema does not give it the property either). Resolved once
    // here, at the single dispatch point, rather than inside both widgets
    // -- one mechanism, and the per-item filesystem lookups are memoised
    // for as long as this list is composed rather than repeated per frame.
    //
    // Real ES-DE gates this on being a GAMELIST view (`mGamelistView` in
    // CarouselComponent.h:1367 / GridComponent.h:988) -- a SYSTEM carousel
    // shows system logos, which are theme art, not scraped game media. The
    // gate here is the same fact expressed through the data: a system-list
    // item carries no [EsDeListItem.mediaLocator], so nothing resolves and
    // its logo is kept.
    val imageTypes = remember(element) {
        if (element?.type == "carousel" || element?.type == "grid") {
            EsDeImageTypes.forPrimaryElement(element.valueOrNull<EsDeThemeValue.Str>("imageType")?.value)
        } else {
            emptyList()
        }
    }
    val typedItems = remember(items, imageTypes) {
        if (imageTypes.isEmpty()) items
        else items.map { item -> item.copy(logoPath = item.esDePrimaryImage(imageTypes)) }
    }
    when (element?.type) {
        // The grid doesn't get onFocusedIndexChanged wired through -- it
        // still delegates its cursor to LazyVerticalGrid's own focus
        // traversal and has no single cursor of its own to report.
        "grid" -> EsDeGrid(element, typedItems, firstItemFocus, modifier, resolvedWidth, resolvedHeight)
        // textlist DOES get onFocusedIndexChanged now: it owns its own
        // cursor (real TextListComponent architecture, see EsDeTextList),
        // so a theme whose system view is a textlist rather than a
        // carousel gets the same real per-system theme reloading.
        "textlist" -> EsDeTextList(
            element, items, firstItemFocus, modifier, onFocusedIndexChanged, resolvedWidth, resolvedHeight,
        )
        // "carousel", or no theme-declared element at all -- carousel is
        // ES-DE's own real default shape and the one droidtop already
        // shipped, so it's the honest fallback rather than an arbitrary one.
        else -> EsDeCarousel(element, typedItems, firstItemFocus, modifier, onFocusedIndexChanged, resolvedWidth, resolvedHeight)
    }
}

/**
 * Real ES-DE carousel rendering. All FOUR real carousel types --
 * `horizontal`, `vertical`, `horizontalWheel`, `verticalWheel` -- now
 * render with their own real geometry; before this pass droidtop
 * implemented only `horizontal` and silently drew every wheel- or
 * vertical-typed theme with horizontal geometry.
 *
 * The geometry itself lives in [layoutEsDeCarousel]
 * (runtime-common's own `EsDeCarouselLayout.kt`), a literal, unit-tested
 * port of real `CarouselComponent<T>::render()`; this composable is only
 * the drawing half -- it asks where each item goes and paints it. That
 * split is deliberate: the maths is the part that can be verified
 * against the real source without a device, and it is where the
 * previously-unimplemented properties live (`type`, `itemStacking`,
 * `itemsBeforeCenter`/`itemsAfterCenter`, `itemRotation`/
 * `itemRotationOrigin`/`itemAxisHorizontal`/`itemAxisRotation`,
 * `itemHorizontalAlignment`/`itemVerticalAlignment`,
 * `wheelHorizontalAlignment`/`wheelVerticalAlignment`,
 * `horizontalOffset`/`verticalOffset`, `itemLinearScale`/
 * `itemLinearSpacing`, `selectedItemOffset`, `itemDiagonalOffset`).
 *
 * `camOffset` is real, continuous, eased animation -- ported directly
 * from `CarouselComponent::onCursorChanged`'s own real
 * `LambdaAnimation`: ease-out-quad (`t = 1 - (1-t)^2`), duration 400ms by
 * default, scaled down (`clamp(distance * 1.5 * 400, 200, 400)`) when the
 * animation restarts mid-flight at a fractional distance rather than a
 * full one-item step. `itemTransitions="instant"` skips the animation
 * entirely, which is exactly what real ES-DE's own
 * `mInstantItemTransitions` does. Not ported: ES-DE's real wraparound
 * "shortest path" logic for a looping carousel (`onCursorChanged`'s
 * `posMax` handling) -- droidtop's list isn't circular in its animation,
 * so a direct `animateTo` is the correct real behavior here.
 *
 * `imageType` IS now honored (the library-core data-model change it was
 * blocked on landed as `LibraryEntry.mediaLocator`) -- see
 * [esDePrimaryImage], which is where the real per-entry resolution and
 * the real two-entry cap live.
 *
 * Honestly still unimplemented here, and NOT faked:
 * `imageColorEnd`/`imageGradientType`/
 * `imageSelectedColorEnd`/`imageSelectedGradientType` (a POSITIONAL
 * gradient applied as a color shift over an image, which a Compose
 * `ColorFilter` cannot express -- it needs a shader), `textRelativeScale`/
 * `textBackgroundCornerRadius`, and the four `textHorizontalScroll*`
 * properties. `imageInterpolation` IS now honored (see
 * esDeFilterQuality).
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
    val letterCase = esDeLetterCaseOf(element?.valueOrNull<EsDeThemeValue.Str>("letterCase")?.value)
    // Real `imageInterpolation` (CarouselComponent.h's own applyTheme,
    // the same two "nearest"/"linear" literals as every other element's
    // `interpolation`) -- see esDeFilterQuality for the one honest
    // divergence from real ES-DE's magnify-only filter flag. Four of the
    // ten themes measured for this pass set it on their carousel, all of
    // them to keep pixel-art system logos crisp.
    val imageFilterQuality = element?.let { esDeFilterQuality(it, "imageInterpolation") } ?: FilterQuality.Low
    // Real carousel-wide background bar (CarouselComponent::render's own
    // single drawRect call, behind every item) -- real default 0xFFFFFFD8
    // (translucent white), confirmed against the real constructor default.
    val carouselColor = element?.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color(0xFF, 0xFF, 0xFF, 0xD8)
    val carouselColorEnd = element?.valueOrNull<EsDeThemeValue.Color>("colorEnd")?.let { colorOf(it) } ?: carouselColor
    // Real `gradientType` (CarouselComponent.h:1414-1429), which is what
    // the schema actually calls this -- the previous code read a
    // `colorGradientHorizontal` property that exists nowhere in real
    // ES-DE's schema, so a theme could never influence it at all.
    val colorGradientHorizontal =
        element?.valueOrNull<EsDeThemeValue.Str>("gradientType")?.value != "vertical"
    // Real, confirmed bug this fixes: the text-fallback item renderer
    // never read this element's own real `fontSize` at all, silently
    // defaulting to Compose's ~14sp Text() default -- on a real device
    // this rendered carousel item labels as "practically invisible" tiny
    // text, confirmed via a direct on-device screenshot diffed against
    // the bundled theme's own reference sys.png. Real ES-DE's own default
    // (`Font::getFromTheme`'s fallback, `FONT_SIZE_LARGE_FIXED`) is a
    // fixed absolute size, not a screen-height fraction -- but every real
    // theme declares its own real `fontSize`, so the 0.045f fallback here
    // only matters for a theme that genuinely omits it.
    val fontSizeFraction = element?.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * screenHeight.value).dp.toSp() }
    val itemFontFamily = element?.let { themeFontFamily(it) }
    // Real CarouselComponent item-image properties (its own
    // mImageColorShift/mImageSaturation/mImageBrightness, applied per item
    // -- see that real source): `imageColor` is a color SHIFT (multiply,
    // the same real modulate semantics as ImageComponent::setColorShift),
    // `imageSelectedColor` replaces it for the focused item.
    val imageColor = element?.valueOrNull<EsDeThemeValue.Color>("imageColor")?.let { colorOf(it) }
    val imageSelectedColor = element?.valueOrNull<EsDeThemeValue.Color>("imageSelectedColor")?.let { colorOf(it) } ?: imageColor
    // Real `imageFit` (CarouselComponent.h:1515-1534): contain (default) /
    // fill / cover map onto exactly Compose's Fit / FillBounds / Crop.
    val imageFit = when (element?.valueOrNull<EsDeThemeValue.Str>("imageFit")?.value) {
        "fill" -> ContentScale.FillBounds
        "cover" -> ContentScale.Crop
        else -> ContentScale.Fit
    }

    // Every real layout-affecting property, resolved against the themed
    // area exactly as real ES-DE resolves them against its screen. Dp is
    // used as the unit throughout (rather than raw pixels) because every
    // one of these is a proportion of that same area, so the density
    // conversion cancels out.
    val config = remember(element, screenWidth, screenHeight) {
        esDeCarouselConfig(element, screenWidth.value, screenHeight.value)
    }

    var focusedIndex by remember { mutableStateOf(0) }
    var positiveDirection by remember { mutableStateOf(false) }
    val camOffset = remember { Animatable(0f) }

    LaunchedEffect(focusedIndex, config.instantItemTransitions) {
        val startPos = camOffset.value
        val target = focusedIndex.toFloat()
        if (config.instantItemTransitions) {
            camOffset.snapTo(target)
            return@LaunchedEffect
        }
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

    // Real ES-DE input architecture: the CAROUSEL is the focus/input
    // target (`CarouselComponent::input` -> `List::listInput`), items are
    // render entries with no input identity of their own. Which axis
    // moves the cursor is real, type-dependent behavior
    // (CarouselComponent.h:588-622): the vertical types step on UP/DOWN,
    // the horizontal ones on LEFT/RIGHT. Before this pass a vertical
    // carousel could not be navigated at all. The cross-axis keys are
    // consumed as no-ops so a stray press cannot escape the carousel and
    // land Compose focus on droidtop's own chrome, after which every
    // arrow key would move the wrong surface.
    val verticalType = config.type == EsDeCarouselType.VERTICAL || config.type == EsDeCarouselType.VERTICAL_WHEEL
    fun step(delta: Int): Boolean {
        if (items.isEmpty()) return true
        positiveDirection = delta > 0
        focusedIndex = (focusedIndex + delta + items.size) % items.size
        onFocusedIndexChanged(focusedIndex)
        return true
    }

    BoxWithConstraints(
        modifier = (if (firstItemFocus != null) modifier.focusRequester(firstItemFocus) else modifier)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (GamepadKeyMap.actionFor(event.key)) {
                    GamepadAction.A -> {
                        items.getOrNull(focusedIndex)?.onSelect?.invoke()
                        true
                    }
                    GamepadAction.LEFT -> if (verticalType) true else step(-1)
                    GamepadAction.RIGHT -> if (verticalType) true else step(1)
                    GamepadAction.UP -> if (verticalType) step(-1) else true
                    GamepadAction.DOWN -> if (verticalType) step(1) else true
                    else -> false
                }
            }
            .background(
                if (colorGradientHorizontal) {
                    Brush.horizontalGradient(listOf(carouselColor, carouselColorEnd))
                } else {
                    Brush.verticalGradient(listOf(carouselColor, carouselColorEnd))
                },
            ),
    ) {
        val itemWidth = config.itemSizeX.dp
        val itemHeight = config.itemSizeY.dp
        val placements = layoutEsDeCarousel(
            config = config,
            sizeX = maxWidth.value,
            sizeY = maxHeight.value,
            camOffset = camOffset.value,
            entryCount = items.size,
            positiveDirection = positiveDirection,
        )

        // Real `reflections` (CarouselComponent.h:1271-1305): a mirrored
        // copy of the item drawn directly beneath it, at
        // `reflectionsOpacity`, fading out over `size.y / reflectionsFalloff`.
        // Drawn before the items themselves so an item always wins over a
        // neighbor's reflection.
        if (config.reflections) {
            placements.forEach { placement ->
                val item = items.getOrNull(placement.index) ?: return@forEach
                if (item.logoPath == null) return@forEach
                EsDeCarouselItem(
                    item = item,
                    width = itemWidth,
                    height = itemHeight,
                    isFocused = placement.index == focusedIndex,
                    textColor = textColor,
                    textBackgroundColor = textBackgroundColor,
                    textSelectedColor = textSelectedColor,
                    textSelectedBackgroundColor = textSelectedBackgroundColor,
                    letterCase = letterCase,
                    fontSize = fontSizeSp,
                    fontFamily = itemFontFamily,
                    imageColorShift = imageColor,
                    imageSelectedColorShift = imageSelectedColor,
                    imageSaturation = placement.saturation ?: config.imageSaturation,
                    imageBrightness = config.imageBrightness,
                    dimming = placement.dimming,
                    imageContentScale = imageFit,
                    imageFilterQuality = imageFilterQuality,
                    modifier = Modifier
                        .placeCarouselItem(placement, itemWidth, itemHeight)
                        // Applied OUTSIDE the mirror below, deliberately:
                        // real ES-DE flips the texture, not the geometry,
                        // so the falloff still fades downward on screen.
                        .reflectionFalloff(config.reflectionsFalloff)
                        .graphicsLayer {
                            // Mirrored, then dropped by exactly one item
                            // height so it sits directly beneath -- real
                            // ES-DE's own reflection translation. The
                            // item scale in that translation comes for
                            // free from the placement transform this
                            // sits inside.
                            alpha = placement.opacity * config.reflectionsOpacity
                            scaleY = -1f
                            translationY = size.height
                        },
                )
            }
        }

        placements.forEach { placement ->
            val item = items.getOrNull(placement.index) ?: return@forEach
            EsDeCarouselItem(
                item = item,
                width = itemWidth,
                height = itemHeight,
                isFocused = placement.index == focusedIndex,
                textColor = textColor,
                textBackgroundColor = textBackgroundColor,
                textSelectedColor = textSelectedColor,
                textSelectedBackgroundColor = textSelectedBackgroundColor,
                letterCase = letterCase,
                fontSize = fontSizeSp,
                fontFamily = itemFontFamily,
                imageColorShift = imageColor,
                imageSelectedColorShift = imageSelectedColor,
                imageSaturation = placement.saturation ?: config.imageSaturation,
                imageBrightness = config.imageBrightness,
                dimming = placement.dimming,
                imageContentScale = imageFit,
                imageFilterQuality = imageFilterQuality,
                modifier = Modifier
                    .placeCarouselItem(placement, itemWidth, itemHeight)
                    .graphicsLayer { alpha = placement.opacity },
            )
        }
    }
}

/**
 * Places one item exactly where [layoutEsDeCarousel] said it goes: the
 * placement's anchor is the point real ES-DE translates to, and the
 * item's own origin fraction (set from `itemHorizontalAlignment`/
 * `itemVerticalAlignment`, real CarouselComponent.h:404-419) is both the
 * part of the item that sits on that anchor AND the point its scale and
 * rotation act around -- which is exactly Compose's `transformOrigin`.
 */
private fun Modifier.placeCarouselItem(
    placement: EsDeCarouselPlacement,
    width: Dp,
    height: Dp,
): Modifier = this
    .absoluteOffset(
        x = (placement.anchorX - placement.originFractionX * width.value).dp,
        y = (placement.anchorY - placement.originFractionY * height.value).dp,
    )
    .size(width = width, height = height)
    .graphicsLayer {
        transformOrigin = TransformOrigin(placement.originFractionX, placement.originFractionY)
        scaleX = placement.scale
        scaleY = placement.scale
        rotationZ = placement.rotationDegrees
    }

/**
 * Real reflections falloff, ported from ES-DE's own core.glsl fragment
 * shader (`sampledColor.argb *= mix(0.0, 1.0, reflectionsFalloff -
 * position.y) / reflectionsFalloff`, where the uniform is the item's own
 * height divided by the theme's `reflectionsFalloff`): a linear fade to
 * fully transparent a `1 / reflectionsFalloff` fraction of the way down
 * the mirrored copy. A falloff of zero disables it, same as the shader's
 * own guard.
 */
private fun Modifier.reflectionFalloff(falloff: Float): Modifier {
    if (falloff <= 0f) return this
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    (1f / falloff).coerceIn(0.01f, 1f) to Color.Transparent,
                    1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * Real ES-DE carousel item rendering (`CarouselComponent::addEntry`/
 * `updateEntry`) -- an item is EITHER its own image (a real system logo/
 * marquee) OR a text-label fallback, never both, and never wrapped in a
 * card/border/background box -- droidtop used to draw one here, and the
 * grid used to draw its own; both are gone, because a real ES-DE list
 * widget's only per-item chrome is the background/selector layers the
 * THEME asks for. Real default text colors are black text on a fully
 * transparent background, not white-on-a-dark-rounded-rect, which was a
 * fabricated droidtop-only look with no real ES-DE basis.
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
    letterCase: EsDeLetterCase,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    imageColorShift: Color?,
    imageSelectedColorShift: Color?,
    imageSaturation: Float,
    imageBrightness: Float,
    dimming: Float,
    imageContentScale: ContentScale,
    imageFilterQuality: FilterQuality,
    modifier: Modifier,
) {
    // No focusable()/onKeyEvent here: the carousel CONTAINER owns focus
    // and key handling (real CarouselComponent::input architecture -- see
    // EsDeCarousel's own comment). clickable stays: touch taps a specific
    // item directly.
    val baseModifier = modifier
        .size(width = width, height = height)
        .clickable(onClick = item.onSelect)

    if (item.logoPath != null) {
        val shift = if (isFocused) imageSelectedColorShift else imageColorShift
        AsyncImage(
            model = item.logoPath,
            contentDescription = null,
            contentScale = imageContentScale,
            filterQuality = imageFilterQuality,
            colorFilter = esDeImageColorFilter(shift, imageSaturation, imageBrightness, dimming),
            modifier = baseModifier,
        )
    } else {
        Text(
            letterCase.applyTo(item.label),
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
 * Real ES-DE image color pipeline, in its own real order -- read
 * straight off ES-DE's own `core.glsl` fragment shader, which applies
 * brightness (`rgb += 0.3 * brightness`), then saturation (luma-weighted
 * 0.299/0.587/0.114 mix), then the color shift as a MULTIPLY, then
 * dimming as a further multiply. Folded into one `ColorMatrix` here
 * because Compose applies a single filter per draw; the brightness
 * offset survives the saturation step unchanged (saturation maps a grey
 * vector to itself), so it only needs scaling by the shift and dimming.
 *
 * Replaces a pair of earlier approximations: a `ColorFilter.tint` that
 * REPLACED rather than modulated an image's color, and an
 * "unfocusedItemSaturation lowers alpha by 15%" alpha hack.
 */
internal fun esDeImageColorFilter(
    shift: Color?,
    saturation: Float,
    brightness: Float,
    dimming: Float,
): ColorFilter? {
    val shiftR = shift?.red ?: 1f
    val shiftG = shift?.green ?: 1f
    val shiftB = shift?.blue ?: 1f
    val shiftA = shift?.alpha ?: 1f
    if (saturation == 1f && brightness == 0f && dimming == 1f &&
        shiftR == 1f && shiftG == 1f && shiftB == 1f && shiftA == 1f
    ) {
        return null
    }
    val kR = shiftR * dimming
    val kG = shiftG * dimming
    val kB = shiftB * dimming
    val lumaR = 0.299f
    val lumaG = 0.587f
    val lumaB = 0.114f
    val s = saturation
    val inv = 1f - s
    // ColorMatrix offsets are on a 0-255 scale in Compose, the same as
    // Android's own ColorMatrix.
    val offset = 0.3f * brightness * 255f
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                kR * (s + inv * lumaR), kR * inv * lumaG, kR * inv * lumaB, 0f, offset * kR,
                kG * inv * lumaR, kG * (s + inv * lumaG), kG * inv * lumaB, 0f, offset * kG,
                kB * inv * lumaR, kB * inv * lumaG, kB * (s + inv * lumaB), 0f, offset * kB,
                0f, 0f, 0f, shiftA, 0f,
            ),
        ),
    )
}

/** Real `letterCase` parsing, shared by every list widget (real ES-DE's own four values). */
private fun esDeLetterCaseOf(value: String?): EsDeLetterCase = when (value) {
    "uppercase" -> EsDeLetterCase.UPPERCASE
    "lowercase" -> EsDeLetterCase.LOWERCASE
    "capitalize" -> EsDeLetterCase.CAPITALIZE
    else -> EsDeLetterCase.NONE
}

/**
 * Real ES-DE `<textlist>` rendering -- a port of
 * `TextListComponent<T>::render()`, with the arithmetic living in
 * [layoutEsDeTextList]/[esDeTextListConfig] (runtime-common's own
 * `EsDeTextListLayout.kt`) so it can be unit tested off-device.
 *
 * This replaces a `LazyColumn` of individually-focusable rows, which was
 * the wrong model in a way that made most of the element's real schema
 * unimplementable: real ES-DE draws a FIXED window of rows (its own
 * `startEntry`/`screenCount` arithmetic) with ONE selector bar
 * positioned at `(cursor - startEntry) * entrySize + selectorVerticalOffset`,
 * not a scrolling list where the highlight is a per-row background. With
 * the real model in place, the real selector (`selectorWidth`/
 * `selectorHeight`/`selectorHorizontalOffset`/`selectorVerticalOffset`/
 * `selectorColor`/`selectorColorEnd`/`selectorGradientType`/
 * `selectorImagePath`), the real primary/secondary and selected/
 * selected-secondary color pairs, the real selected-row background
 * (`selectedBackgroundColor`/`selectedSecondaryBackgroundColor`/
 * `selectedBackgroundMargins`/`selectedBackgroundCornerRadius`), the real
 * `horizontalMargin`, the full real `letterCase` set and the real
 * `indicators` prefix all render.
 *
 * Also a real behavioral correction: droidtop hardcoded a 48dp
 * horizontal row padding that exists nowhere in ES-DE. The real property
 * is `horizontalMargin`, and its real default is zero.
 *
 * Honestly still unimplemented, and NOT faked: the four
 * `textHorizontalScroll*` properties (real ES-DE scrolls a too-long row
 * horizontally rather than clipping it), `selectorImageTile` (a tiled
 * selector image needs a repeating image shader), `collectionIndicators`
 * (real ES-DE only shows those while a custom collection is being edited
 * IN the list, a mode droidtop has no equivalent of -- its collection
 * editor is a separate screen), and `fadeAbovePrimary`.
 */
@Composable
private fun EsDeTextList(
    element: EsDeThemeElement,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier,
    onFocusedIndexChanged: (Int) -> Unit,
    screenWidth: Dp,
    screenHeight: Dp,
) {
    // Real ES-DE default font size for a textlist. Fraction of the THEMED
    // area (see EsDeSystemListView's own screenWidth/screenHeight doc
    // comment), not a fixed 1080 reference.
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val fontSizeDp = fontSizeFraction * screenHeight.value
    val fontSizeSp = with(LocalDensity.current) { fontSizeDp.dp.toSp() }
    val rowFontFamily = themeFontFamily(element)

    var cursor by remember { mutableStateOf(0) }

    // Real `TextListComponent::input` -> `List::listInput`: the LIST owns
    // the cursor and the keys, rows are render output. Real ES-DE builds
    // its textlist with `ListLoopType::LIST_PAUSE_AT_END`, so unlike the
    // system carousel it deliberately does NOT wrap around.
    fun step(delta: Int): Boolean {
        if (items.isEmpty()) return true
        cursor = (cursor + delta).coerceIn(0, items.size - 1)
        onFocusedIndexChanged(cursor)
        return true
    }

    BoxWithConstraints(
        modifier = (if (firstItemFocus != null) modifier.focusRequester(firstItemFocus) else modifier)
            .focusable()
            .clipToBounds()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (GamepadKeyMap.actionFor(event.key)) {
                    GamepadAction.A -> {
                        items.getOrNull(cursor)?.onSelect?.invoke()
                        true
                    }
                    GamepadAction.UP -> step(-1)
                    GamepadAction.DOWN -> step(1)
                    // Consumed so a stray horizontal press can't escape
                    // the list and move Compose focus onto another
                    // surface -- same reasoning as the carousel's own
                    // cross-axis handling.
                    GamepadAction.LEFT, GamepadAction.RIGHT -> true
                    else -> false
                }
            },
    ) {
        val listWidth = maxWidth.value
        val listHeight = maxHeight.value
        val config = remember(element, listWidth, listHeight, screenWidth, screenHeight, fontSizeDp) {
            esDeTextListConfig(
                element = element,
                width = listWidth,
                height = listHeight,
                screenWidth = screenWidth.value,
                screenHeight = screenHeight.value,
                fontSize = fontSizeDp,
            )
        }
        val window = layoutEsDeTextList(config, listHeight, cursor, items.size)

        // Real draw order (TextListComponent.h:331-345): the selector bar
        // first, every row on top of it.
        if (items.isNotEmpty() && window.startEntry < window.listCutoff) {
            val selectorModifier = Modifier
                .absoluteOffset(x = window.selectorX.dp, y = window.selectorY.dp)
                .size(width = config.selectorWidth.dp, height = config.selectorHeight.dp)
            if (config.selectorImagePath != null) {
                AsyncImage(
                    model = config.selectorImagePath,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    // Real `mSelectorImage.setColorShift(mSelectorColor)`
                    // -- a MULTIPLY over the image, not a replacement.
                    colorFilter = esDeImageColorFilter(
                        shift = colorOfPacked(config.selectorColor),
                        saturation = 1f,
                        brightness = 0f,
                        dimming = 1f,
                    ),
                    modifier = selectorModifier,
                )
            } else {
                val start = colorOfPacked(config.selectorColor)
                val end = colorOfPacked(config.selectorColorEnd)
                Box(
                    modifier = selectorModifier.background(
                        if (config.selectorColorGradientHorizontal) {
                            Brush.horizontalGradient(listOf(start, end))
                        } else {
                            Brush.verticalGradient(listOf(start, end))
                        },
                    ),
                )
            }
        }

        for (index in window.visibleIndices) {
            val item = items.getOrNull(index) ?: continue
            val selected = index == cursor
            // Real color selection (TextListComponent.h:373-384): the
            // primary/secondary pair is chosen by ENTRY TYPE, then the
            // selected variant of that pair by the cursor.
            val color = when {
                item.isSecondary && selected -> config.selectedSecondaryColor
                item.isSecondary -> config.secondaryColor
                selected -> config.selectedColor
                else -> config.primaryColor
            }
            val backgroundColor = when {
                !selected -> 0L
                item.isSecondary -> config.selectedSecondaryBackgroundColor
                else -> config.selectedBackgroundColor
            }
            EsDeTextListRow(
                item = item,
                config = config,
                color = colorOfPacked(color),
                backgroundColor = colorOfPacked(backgroundColor),
                fontSize = fontSizeSp,
                fontFamily = rowFontFamily,
                y = window.rowY(index).dp,
                onSelect = {
                    cursor = index
                    onFocusedIndexChanged(index)
                    item.onSelect()
                },
            )
        }
    }
}

@Composable
private fun EsDeTextListRow(
    item: EsDeListItem,
    config: EsDeTextListConfig,
    color: Color,
    backgroundColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    y: Dp,
    onSelect: () -> Unit,
) {
    // Real name construction (GamelistBase.cpp:896-949): the indicator
    // prefix goes on first, and letterCase is applied to the whole
    // resulting string afterwards -- not the other way round.
    val label = config.letterCase.applyTo(
        esDeIndicatorPrefix(config.indicators, item.favorite) + item.label,
    )
    val marginStart = config.selectedBackgroundMarginsX
    val marginEnd = config.selectedBackgroundMarginsY
    val selectorHorizontalOffset = config.selectorHorizontalOffset
    val selectorHeight = config.selectorHeight
    val cornerRadius = config.selectedBackgroundCornerRadius
    val verticalOffset = config.selectorVerticalOffset
    val drawBackground = backgroundColor.alpha > 0f

    Box(
        modifier = Modifier
            .absoluteOffset(y = y)
            .fillMaxWidth()
            .height(config.entrySize.dp)
            .padding(horizontal = config.horizontalMargin.dp),
        contentAlignment = when (config.alignment) {
            EsDePrimaryAlignment.LEFT -> androidx.compose.ui.Alignment.CenterStart
            EsDePrimaryAlignment.CENTER -> androidx.compose.ui.Alignment.Center
            EsDePrimaryAlignment.RIGHT -> androidx.compose.ui.Alignment.CenterEnd
        },
    ) {
        Text(
            label,
            color = color,
            fontSize = fontSize,
            fontFamily = fontFamily,
            // Real ES-DE TextListComponent rows are strictly single-line
            // -- a long title clips (or horizontally scrolls, its
            // `textHorizontalScrolling` feature, not built here yet),
            // never wraps. Real, confirmed-live bug this fixes: without
            // maxLines, long real titles wrapped to two lines inside a
            // one-line-tall row, painting over the next row.
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            modifier = Modifier
                // Real selected-row background (TextListComponent.h:
                // 421-444): a rect exactly as wide as the row's own TEXT
                // plus its margins -- which is why this is drawn behind
                // the measured text node rather than as a row-wide
                // background -- offset by the selector's own horizontal
                // offset and drawn at the selector's height.
                .drawBehind {
                    if (!drawBackground) return@drawBehind
                    drawRoundRect(
                        color = backgroundColor,
                        topLeft = Offset(
                            (selectorHorizontalOffset - marginStart).dp.toPx(),
                            verticalOffset.dp.toPx(),
                        ),
                        size = Size(
                            size.width + (marginStart + marginEnd).dp.toPx(),
                            selectorHeight.dp.toPx(),
                        ),
                        cornerRadius = CornerRadius(cornerRadius.dp.toPx()),
                    )
                }
                // Real touch-input fix, reported directly: nothing in
                // Handheld mode responded to taps -- the list's own
                // container-level key handling only covers D-pad/gamepad.
                .clickable(onClick = onSelect),
        )
    }
}

/**
 * Real ES-DE `<grid>` rendering -- a port of
 * `GridComponent<T>::calculateLayout()`/`render()`, with the arithmetic
 * living in [layoutEsDeGrid]/[esDeGridConfig]/[esDeGridItemCenter]/
 * [esDeGridScrollRow] (runtime-common's own `EsDeGridLayout.kt`) so it
 * can be unit tested off-device.
 *
 * This replaces a `LazyVerticalGrid` of individually-focusable tiles.
 * Two things made that model unable to express the element's real
 * schema. First, the tile itself was invented: a dark rounded card with
 * an accent border and an "N items" line, none of which exists in ES-DE
 * -- and it occupied exactly the surface a theme's own real
 * `backgroundColor`/`backgroundImage` and `selectorColor`/`selectorImage`
 * layers are supposed to own. Second, the item spacing was a hardcoded
 * 16dp, where real ES-DE AUTO-CALCULATES spacing from `itemScale` when a
 * theme doesn't declare it. A grid entry in real ES-DE is up to three
 * stacked layers -- a background, the item image or its name as text,
 * and the selector, which the theme places above, between or below them
 * via `selectorLayer` -- and that is what this now draws, in that real
 * order.
 *
 * The item count that used to appear on each tile is gone with the card,
 * and no information is lost: real ES-DE's own mechanism for it is a
 * `systemdata` text element the theme places where it wants, which
 * droidtop already renders.
 *
 * `imageType` IS now honored, through the same [esDePrimaryImage] the
 * carousel uses.
 *
 * Honestly still unimplemented, and NOT faked:
 * the `imageColorEnd`/`imageGradientType`/
 * `imageSelectedColorEnd`/`imageSelectedGradientType` positional
 * gradients, `imageCropPos`,
 * `textBackgroundCornerRadius`, the four `textHorizontalScroll*`
 * properties, and `fadeAbovePrimary`. Real ES-DE's own easing between
 * scroll rows and between item scales is not ported either -- the
 * resting positions are real, the motion between them is a plain Compose
 * animation.
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
    val fontSizeFraction = element.valueOrNull<EsDeThemeValue.FloatValue>("fontSize")?.value ?: 0.045f
    val fontSizeSp = with(LocalDensity.current) { (fontSizeFraction * screenHeight.value).dp.toSp() }
    val tileFontFamily = themeFontFamily(element)
    // Real `imageInterpolation` -- same real property, same two literals
    // as the carousel's; see esDeFilterQuality.
    val imageFilterQuality = esDeFilterQuality(element, "imageInterpolation") ?: FilterQuality.Low
    val imageFit = when (element.valueOrNull<EsDeThemeValue.Str>("imageFit")?.value) {
        "fill" -> ContentScale.FillBounds
        "cover" -> ContentScale.Crop
        else -> ContentScale.Fit
    }
    val config = remember(element, screenWidth, screenHeight) {
        esDeGridConfig(element, screenWidth.value, screenHeight.value)
    }

    var cursor by remember { mutableStateOf(0) }

    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val layout = remember(config, maxWidth, maxHeight) {
            layoutEsDeGrid(config, maxWidth.value, maxHeight.value)
        }

        val scrollRow = esDeGridScrollRow(layout, cursor)
        val animatedScrollRow by animateFloatAsState(
            targetValue = scrollRow,
            animationSpec = if (config.instantRowTransitions) snap() else tween(durationMillis = 250),
            label = "esDeGridScrollRow",
        )
        val scrollOffset = (config.itemSizeY + config.itemSpacingY) * animatedScrollRow

        // Real render window (GridComponent.h:692-728): whole rows only,
        // starting one row above the first visible one so a partially
        // scrolled row still draws.
        val visibleRows = kotlin.math.ceil(layout.visibleRows).toInt()
        val firstRow = kotlin.math.max(0, kotlin.math.ceil(animatedScrollRow).toInt() - 1)
        val startIndex = firstRow * layout.columns
        val endIndex = kotlin.math.min(items.size, startIndex + layout.columns * (visibleRows + 2))
        // Real draw order: every other entry first, the cursor's entry
        // last, so a scaled-up selected item is never overlapped by its
        // own neighbours.
        val drawOrder = (startIndex until endIndex).filter { it != cursor } +
            listOfNotNull(cursor.takeIf { it in startIndex until endIndex })

        // Real `GridComponent::input`: left/right step one entry, up/down
        // step a whole row. The GRID owns the cursor and the keys -- items
        // are render output with no focus identity of their own, the same
        // real architecture as the carousel and the textlist. This sits on
        // an inner full-size box rather than the outer one because the
        // column count it steps by is only known once the grid has been
        // measured.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    fun step(delta: Int): Boolean {
                        if (items.isEmpty()) return true
                        cursor = (cursor + delta).coerceIn(0, items.size - 1)
                        return true
                    }
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.A -> {
                            items.getOrNull(cursor)?.onSelect?.invoke()
                            true
                        }
                        GamepadAction.LEFT -> step(-1)
                        GamepadAction.RIGHT -> step(1)
                        GamepadAction.UP -> step(-layout.columns)
                        GamepadAction.DOWN -> step(layout.columns)
                        else -> false
                    }
                },
        ) {
            for (index in drawOrder) {
                val item = items.getOrNull(index) ?: continue
                EsDeGridEntry(
                    item = item,
                    config = config,
                    layout = layout,
                    index = index,
                    scrollOffset = scrollOffset,
                    selected = index == cursor,
                    fontSize = fontSizeSp,
                    fontFamily = tileFontFamily,
                    imageContentScale = imageFit,
                    imageFilterQuality = imageFilterQuality,
                    onSelect = {
                        cursor = index
                        item.onSelect()
                    },
                )
            }
        }
    }
}

/**
 * One grid entry: its real layers, drawn in real ES-DE's own order
 * (`GridComponent::render`'s per-entry block) -- the selector when
 * `selectorLayer` is `bottom`, then the background, then the selector
 * when it is `middle`, then the item itself, then the selector when it
 * is `top` (the real default).
 */
@Composable
private fun EsDeGridEntry(
    item: EsDeListItem,
    config: EsDeGridConfig,
    layout: EsDeGridLayout,
    index: Int,
    scrollOffset: Float,
    selected: Boolean,
    imageFilterQuality: FilterQuality,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    imageContentScale: ContentScale,
    onSelect: () -> Unit,
) {
    val center = esDeGridItemCenter(config, layout, index)
    val centerX = center.first
    val centerY = center.second - scrollOffset
    val scale by animateFloatAsState(
        targetValue = if (selected) config.itemScale else 1f,
        animationSpec = if (config.instantItemTransitions) snap() else tween(durationMillis = 250),
        label = "esDeGridItemScale",
    )
    val opacity = if (selected) 1f else config.unfocusedItemOpacity
    val saturation = if (selected) config.imageSaturation else (config.unfocusedItemSaturation ?: config.imageSaturation)
    val dimming = if (selected) 1f else config.unfocusedItemDimming

    // Real `scaleInwards` (GridComponent.h:834-856): an item on an edge
    // grows toward the middle of the grid instead of off it, which is
    // exactly a shifted scale origin.
    var originX = 0.5f
    var originY = 0.5f
    if (config.scaleInwards && scale != 1f) {
        if (index < layout.columns) originY = 0f
        if (index % layout.columns == 0) originX = 0f
        if (index % layout.columns == layout.columns - 1) originX = 1f
    }

    if (config.selectorLayer == EsDeSelectorLayer.BOTTOM && selected) {
        EsDeGridSelector(config, centerX, centerY, scale, originX, originY, opacity)
    }

    // Real background layer: an image when the theme gives one (color
    // shifted by backgroundColor if it gives that too), otherwise a rect
    // -- and NOTHING at all when the theme declares neither, which is
    // real ES-DE's own mHasBackgroundColor behavior and the reason this
    // element must not carry a built-in card of its own.
    if (config.backgroundImage != null) {
        AsyncImage(
            model = config.backgroundImage,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = config.backgroundColor?.let {
                esDeImageColorFilter(colorOfPacked(it), saturation, 0f, dimming)
            },
            modifier = Modifier
                .placeGridLayer(centerX, centerY, config, config.backgroundRelativeScale, scale, originX, originY)
                .graphicsLayer { alpha = opacity },
        )
    } else {
        val backgroundColor = config.backgroundColor
        if (backgroundColor != null) {
            Box(
                modifier = Modifier
                    .placeGridLayer(centerX, centerY, config, config.backgroundRelativeScale, scale, originX, originY)
                    .graphicsLayer { alpha = opacity }
                    .clip(RoundedCornerShape(config.backgroundCornerRadius.dp))
                    .background(
                        esDeGradient(backgroundColor, config.backgroundColorEnd, config.backgroundGradientHorizontal),
                    ),
            )
        }
    }

    if (config.selectorLayer == EsDeSelectorLayer.MIDDLE && selected) {
        EsDeGridSelector(config, centerX, centerY, scale, originX, originY, opacity)
    }

    // The item itself -- an image, or its name as text when it has none,
    // which is real ES-DE's own fallback rather than a placeholder.
    if (item.logoPath != null) {
        AsyncImage(
            model = item.logoPath,
            contentDescription = null,
            contentScale = imageContentScale,
            filterQuality = imageFilterQuality,
            colorFilter = esDeImageColorFilter(
                (if (selected) config.imageSelectedColor else config.imageColor)?.let { colorOfPacked(it) },
                saturation,
                config.imageBrightness,
                dimming,
            ),
            modifier = Modifier
                .placeGridLayer(centerX, centerY, config, config.imageRelativeScale, scale, originX, originY)
                .graphicsLayer { alpha = opacity }
                .clip(RoundedCornerShape(config.imageCornerRadius.dp))
                .clickable(onClick = onSelect),
        )
    } else {
        Box(
            modifier = Modifier
                .placeGridLayer(centerX, centerY, config, config.textRelativeScale, scale, originX, originY)
                .graphicsLayer { alpha = opacity }
                .background(
                    colorOfPacked(if (selected) config.textSelectedBackgroundColor else config.textBackgroundColor),
                )
                .clickable(onClick = onSelect),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                config.letterCase.applyTo(item.label),
                color = colorOfPacked(if (selected) config.textSelectedColor else config.textColor),
                fontSize = fontSize,
                fontFamily = fontFamily,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (config.selectorLayer == EsDeSelectorLayer.TOP && selected) {
        EsDeGridSelector(config, centerX, centerY, scale, originX, originY, opacity)
    }
}

/**
 * The grid's real selector layer -- an image when the theme gives one
 * (color shifted by `selectorColor` if it gives that too), a rounded
 * gradient rect when it gives only a color, and nothing at all when it
 * gives neither, which is real ES-DE's own `mHasSelectorColor` gate.
 */
@Composable
private fun EsDeGridSelector(
    config: EsDeGridConfig,
    centerX: Float,
    centerY: Float,
    scale: Float,
    originX: Float,
    originY: Float,
    opacity: Float,
) {
    if (config.selectorImage != null) {
        AsyncImage(
            model = config.selectorImage,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = config.selectorColor?.let { esDeImageColorFilter(colorOfPacked(it), 1f, 0f, 1f) },
            modifier = Modifier
                .placeGridLayer(centerX, centerY, config, config.selectorRelativeScale, scale, originX, originY)
                .graphicsLayer { alpha = opacity },
        )
        return
    }
    val selectorColor = config.selectorColor ?: return
    Box(
        modifier = Modifier
            .placeGridLayer(centerX, centerY, config, config.selectorRelativeScale, scale, originX, originY)
            .graphicsLayer { alpha = opacity }
            .clip(RoundedCornerShape(config.selectorCornerRadius.dp))
            .background(esDeGradient(selectorColor, config.selectorColorEnd, config.selectorGradientHorizontal)),
    )
}

/**
 * Places one of a grid entry's layers: a box of `itemSize *
 * relativeScale` centred on the item's own cell centre, scaled about
 * [originX]/[originY] -- which is real `calculateOffsetPos`'s own result
 * expressed the way Compose expresses it.
 */
private fun Modifier.placeGridLayer(
    centerX: Float,
    centerY: Float,
    config: EsDeGridConfig,
    relativeScale: Float,
    scale: Float,
    originX: Float,
    originY: Float,
): Modifier {
    val width = config.itemSizeX * relativeScale
    val height = config.itemSizeY * relativeScale
    return this
        .absoluteOffset(x = (centerX - width / 2f).dp, y = (centerY - height / 2f).dp)
        .size(width = width.dp, height = height.dp)
        .graphicsLayer {
            transformOrigin = TransformOrigin(originX, originY)
            scaleX = scale
            scaleY = scale
        }
}

/** Real two-stop gradient, in whichever direction the element's own `*GradientType` asked for. */
private fun esDeGradient(start: Long, end: Long, horizontal: Boolean): Brush {
    val colors = listOf(colorOfPacked(start), colorOfPacked(end))
    return if (horizontal) Brush.horizontalGradient(colors) else Brush.verticalGradient(colors)
}

/** Real packed-RRGGBBAA to Compose color, for the layout layer's own color fields (which stay graphics-type-free). */
private fun colorOfPacked(packed: Long): Color = colorOf(EsDeThemeValue.Color(packed))
