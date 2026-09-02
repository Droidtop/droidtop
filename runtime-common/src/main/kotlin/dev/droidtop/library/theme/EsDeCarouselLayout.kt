package dev.droidtop.library.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Real ES-DE carousel LAYOUT, as pure maths -- a direct port of
 * `CarouselComponent<T>::render()` and the theme-property parsing in
 * `CarouselComponent<T>::applyTheme()` (es-core/src/components/primary/
 * CarouselComponent.h in the real ES-DE source, kept at
 * /root/es-de-reference and read line-by-line for this port), with no
 * Compose or Android dependency at all so the geometry can be unit
 * tested without a screen.
 *
 * Why this exists as its own file: droidtop's carousel renderer
 * previously implemented exactly ONE of real ES-DE's four real carousel
 * types (`horizontal`), silently rendering `vertical`/`verticalWheel`/
 * `horizontalWheel` themes -- both wheel types are common in real
 * community themes -- with horizontal geometry. The type-dependent
 * geometry is where nearly all of the carousel's unimplemented
 * properties live (`itemStacking`, `itemsBeforeCenter`/`itemsAfterCenter`,
 * `itemRotation`/`itemRotationOrigin`/`itemAxisHorizontal`/
 * `itemAxisRotation`, `wheelHorizontalAlignment`/`wheelVerticalAlignment`,
 * `itemHorizontalAlignment`/`itemVerticalAlignment`, `horizontalOffset`/
 * `verticalOffset`, `itemLinearScale`/`itemLinearSpacing`,
 * `selectedItemOffset`, `itemDiagonalOffset`), so it is one coherent
 * piece of work rather than a property checklist.
 */
enum class EsDeCarouselType {
    HORIZONTAL,
    VERTICAL,
    VERTICAL_WHEEL,
    HORIZONTAL_WHEEL,
    ;

    val isWheel: Boolean get() = this == VERTICAL_WHEEL || this == HORIZONTAL_WHEEL
}

/** Real `CarouselComponent::ItemStacking` -- the z-order overlapping items are drawn in. */
enum class EsDeItemStacking { CENTERED, ASCENDING, ASCENDING_RAISED, DESCENDING, DESCENDING_RAISED }

enum class EsDeHorizontalAlign { LEFT, CENTER, RIGHT }

enum class EsDeVerticalAlign { TOP, CENTER, BOTTOM }

/**
 * Every real carousel property that affects LAYOUT, already resolved to
 * pixels against the themed area the same way real ES-DE resolves them
 * against its screen. Colors/fonts/text deliberately stay out -- those
 * are the renderer's business and carry Compose types.
 *
 * Defaults are real `CarouselComponent`'s own constructor defaults, and
 * every clamp is the real one from its `applyTheme` (both cited inline).
 */
data class EsDeCarouselConfig(
    val type: EsDeCarouselType = EsDeCarouselType.HORIZONTAL,
    val itemStacking: EsDeItemStacking = EsDeItemStacking.CENTERED,
    val maxItemCount: Float = 3f,
    val itemsBeforeCenter: Int = 8,
    val itemsAfterCenter: Int = 8,
    val itemSizeX: Float,
    val itemSizeY: Float,
    val itemScale: Float = 1.2f,
    val itemLinearScaleX: Float = 0f,
    val itemLinearScaleY: Float = 0f,
    val itemLinearSpacingX: Float = 0f,
    val itemLinearSpacingY: Float = 0f,
    val selectedItemMarginsX: Float = 0f,
    val selectedItemMarginsY: Float = 0f,
    val selectedItemOffsetX: Float = 0f,
    val selectedItemOffsetY: Float = 0f,
    val itemRotation: Float = 7.5f,
    val itemRotationOriginX: Float = -3f,
    val itemRotationOriginY: Float = 0.5f,
    val itemAxisHorizontal: Boolean = false,
    val itemAxisRotation: Float = 0f,
    val itemDiagonalOffset: Float = 0f,
    val itemHorizontalAlignment: EsDeHorizontalAlign = EsDeHorizontalAlign.CENTER,
    val itemVerticalAlignment: EsDeVerticalAlign = EsDeVerticalAlign.CENTER,
    val wheelHorizontalAlignment: EsDeHorizontalAlign = EsDeHorizontalAlign.CENTER,
    val wheelVerticalAlignment: EsDeVerticalAlign = EsDeVerticalAlign.CENTER,
    val horizontalOffset: Float = 0f,
    val verticalOffset: Float = 0f,
    val reflections: Boolean = false,
    val reflectionsOpacity: Float = 0.5f,
    val reflectionsFalloff: Float = 1f,
    val unfocusedItemOpacity: Float = 0.5f,
    /** Null when the theme declares none -- real `mHasUnfocusedItemSaturation`, which gates saturation handling entirely. */
    val unfocusedItemSaturation: Float? = null,
    val unfocusedItemDimming: Float = 1f,
    val imageSaturation: Float = 1f,
    val imageBrightness: Float = 0f,
    val instantItemTransitions: Boolean = false,
    val fastScrolling: Boolean = false,
)

/**
 * Where one carousel item lands, in the carousel's own pixel space.
 *
 * [anchorX]/[anchorY] is the item's real ES-DE anchor point (its
 * `setPosition(itemSize * origin)` point, i.e. the point that
 * [originFractionX]/[originFractionY] of the item box sits on), which is
 * also the point [scale] and [rotationDegrees] act around -- exactly the
 * real `GuiComponent::getTransform` order (translate, then scale, then
 * rotate about the origin).
 */
data class EsDeCarouselPlacement(
    val index: Int,
    val distance: Float,
    val anchorX: Float,
    val anchorY: Float,
    val originFractionX: Float,
    val originFractionY: Float,
    val rotationDegrees: Float,
    val scale: Float,
    val opacity: Float,
    /** Null when the theme declared no `unfocusedItemSaturation` -- real ES-DE leaves saturation untouched in that case. */
    val saturation: Float?,
    val dimming: Float,
)

/**
 * Minimal 2D affine transform, present so the wheel geometry can be a
 * LITERAL port of real ES-DE's `glm::translate`/`glm::rotate` call
 * sequence rather than a hand-simplified re-derivation of it -- the wheel
 * composes five transforms in a specific order and getting that order
 * subtly wrong is exactly the kind of bug that still looks plausible on
 * screen.
 *
 * Column-major like glm: columns are (a, b), (c, d), (tx, ty). Rotation
 * is glm's own matrix, which in ES-DE's y-down screen space reads as
 * clockwise -- the same direction and sign convention Compose's own
 * `rotationZ` uses, so [rotationDegrees] transfers to a renderer with no
 * sign flip.
 */
internal data class EsDeAffine2(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    operator fun times(other: EsDeAffine2): EsDeAffine2 = EsDeAffine2(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        tx = a * other.tx + c * other.ty + tx,
        ty = b * other.tx + d * other.ty + ty,
    )

    fun translate(x: Float, y: Float): EsDeAffine2 = this * EsDeAffine2(tx = x, ty = y)

    fun rotateDegrees(degrees: Float): EsDeAffine2 {
        val r = degrees * PI_OVER_180
        val cosR = cos(r)
        val sinR = sin(r)
        return this * EsDeAffine2(a = cosR, b = sinR, c = -sinR, d = cosR)
    }

    fun apply(x: Float, y: Float): kotlin.Pair<Float, Float> =
        kotlin.Pair(a * x + c * y + tx, b * x + d * y + ty)

    val rotationDegrees: Float get() = atan2(b, a) / PI_OVER_180

    companion object {
        private const val PI_OVER_180 = (Math.PI / 180.0).toFloat()
    }
}

/**
 * Real `CarouselComponent::applyTheme` property reading, clamps and all.
 * [screenWidth]/[screenHeight] are the themed area's own size, which is
 * what real ES-DE's `Renderer::getScreenWidth/Height` mean for a theme.
 */
fun esDeCarouselConfig(
    element: EsDeThemeElement?,
    screenWidth: Float,
    screenHeight: Float,
): EsDeCarouselConfig {
    fun float(name: String): Float? = element?.valueOrNull<EsDeThemeValue.FloatValue>(name)?.value
    fun bool(name: String): Boolean? = element?.valueOrNull<EsDeThemeValue.Bool>(name)?.value
    fun str(name: String): String? = element?.valueOrNull<EsDeThemeValue.Str>(name)?.value
    fun pair(name: String): EsDeThemeValue.Pair? = element?.valueOrNull<EsDeThemeValue.Pair>(name)
    fun uint(name: String): Long? = element?.valueOrNull<EsDeThemeValue.UInt>(name)?.value

    // CarouselComponent.h:1346-1366 -- an unrecognized value logs a
    // warning and falls back to horizontal, it is not a parse failure.
    val type = when (str("type")) {
        "horizontal" -> EsDeCarouselType.HORIZONTAL
        "horizontalWheel" -> EsDeCarouselType.HORIZONTAL_WHEEL
        "vertical" -> EsDeCarouselType.VERTICAL
        "verticalWheel" -> EsDeCarouselType.VERTICAL_WHEEL
        else -> EsDeCarouselType.HORIZONTAL
    }
    val isPlain = type == EsDeCarouselType.HORIZONTAL || type == EsDeCarouselType.VERTICAL

    // CarouselComponent.h:1497-1500 -- itemSize is a fraction of the
    // themed area, clamped 0.05-1.0; the real constructor default is
    // 0.25 x 0.155 of it.
    val itemSize = pair("itemSize")
    val itemSizeX = (itemSize?.x?.coerceIn(0.05f, 1f) ?: 0.25f) * screenWidth
    val itemSizeY = (itemSize?.y?.coerceIn(0.05f, 1f) ?: 0.155f) * screenHeight

    // CarouselComponent.h:1478-1496 -- both only apply to the non-wheel
    // types, and both scale by screen WIDTH for a horizontal carousel and
    // screen HEIGHT for a vertical one.
    val marginScale = if (type == EsDeCarouselType.HORIZONTAL) screenWidth else screenHeight
    val selectedItemMargins = pair("selectedItemMargins")?.takeIf { isPlain }
    val selectedItemOffset = pair("selectedItemOffset")?.takeIf { isPlain }

    // CarouselComponent.h:1574-1582 -- clamped +-0.5, scaled by the
    // dimension the carousel does NOT advance along, non-wheel only.
    val diagonalOffset = float("itemDiagonalOffset")?.takeIf { isPlain }?.coerceIn(-0.5f, 0.5f)
    val diagonalScale = if (type == EsDeCarouselType.HORIZONTAL) screenHeight else screenWidth

    return EsDeCarouselConfig(
        type = type,
        // CarouselComponent.h:1512-1533.
        itemStacking = when (str("itemStacking")) {
            "ascending" -> EsDeItemStacking.ASCENDING
            "ascendingRaised" -> EsDeItemStacking.ASCENDING_RAISED
            "descending" -> EsDeItemStacking.DESCENDING
            "descendingRaised" -> EsDeItemStacking.DESCENDING_RAISED
            else -> EsDeItemStacking.CENTERED
        },
        maxItemCount = float("maxItemCount")?.coerceIn(0.5f, 30f) ?: 3f,
        // CarouselComponent.h:1470-1476 -- clamped 0-20, default 8 each.
        itemsBeforeCenter = uint("itemsBeforeCenter")?.toInt()?.coerceIn(0, 20) ?: 8,
        itemsAfterCenter = uint("itemsAfterCenter")?.toInt()?.coerceIn(0, 20) ?: 8,
        itemSizeX = itemSizeX,
        itemSizeY = itemSizeY,
        itemScale = float("itemScale")?.coerceIn(0.2f, 3f) ?: 1.2f,
        // CarouselComponent.h:1502-1506 -- both clamped -0.5 to 1.0.
        itemLinearScaleX = pair("itemLinearScale")?.x?.coerceIn(-0.5f, 1f) ?: 0f,
        itemLinearScaleY = pair("itemLinearScale")?.y?.coerceIn(-0.5f, 1f) ?: 0f,
        itemLinearSpacingX = pair("itemLinearSpacing")?.x?.coerceIn(-0.5f, 1f) ?: 0f,
        itemLinearSpacingY = pair("itemLinearSpacing")?.y?.coerceIn(-0.5f, 1f) ?: 0f,
        selectedItemMarginsX = (selectedItemMargins?.x?.coerceIn(-1f, 1f) ?: 0f) * marginScale,
        selectedItemMarginsY = (selectedItemMargins?.y?.coerceIn(-1f, 1f) ?: 0f) * marginScale,
        selectedItemOffsetX = (selectedItemOffset?.x?.coerceIn(-1f, 1f) ?: 0f) * marginScale,
        selectedItemOffsetY = (selectedItemOffset?.y?.coerceIn(-1f, 1f) ?: 0f) * marginScale,
        // CarouselComponent.h:1618-1627 -- itemRotation/itemRotationOrigin
        // are deliberately UNCLAMPED in real ES-DE.
        itemRotation = float("itemRotation") ?: 7.5f,
        itemRotationOriginX = pair("itemRotationOrigin")?.x ?: -3f,
        itemRotationOriginY = pair("itemRotationOrigin")?.y ?: 0.5f,
        itemAxisHorizontal = bool("itemAxisHorizontal") ?: false,
        itemAxisRotation = float("itemAxisRotation") ?: 0f,
        itemDiagonalOffset = (diagonalOffset ?: 0f) * diagonalScale,
        // CarouselComponent.h:1652-1690 -- itemHorizontalAlignment is
        // ignored for both horizontal types (they align along that axis
        // themselves), itemVerticalAlignment for the vertical type.
        itemHorizontalAlignment = if (type == EsDeCarouselType.HORIZONTAL || type == EsDeCarouselType.HORIZONTAL_WHEEL) {
            EsDeHorizontalAlign.CENTER
        } else {
            horizontalAlign(str("itemHorizontalAlignment"))
        },
        itemVerticalAlignment = if (type == EsDeCarouselType.VERTICAL) {
            EsDeVerticalAlign.CENTER
        } else {
            verticalAlign(str("itemVerticalAlignment"))
        },
        // CarouselComponent.h:1691-1728 -- each wheel alignment applies to
        // exactly one wheel type.
        wheelHorizontalAlignment = if (type == EsDeCarouselType.VERTICAL_WHEEL) {
            horizontalAlign(str("wheelHorizontalAlignment"))
        } else {
            EsDeHorizontalAlign.CENTER
        },
        wheelVerticalAlignment = if (type == EsDeCarouselType.HORIZONTAL_WHEEL) {
            verticalAlign(str("wheelVerticalAlignment"))
        } else {
            EsDeVerticalAlign.CENTER
        },
        horizontalOffset = float("horizontalOffset")?.coerceIn(-1f, 1f) ?: 0f,
        verticalOffset = float("verticalOffset")?.coerceIn(-1f, 1f) ?: 0f,
        // CarouselComponent.h:1730-1752 -- reflections are only supported
        // for the plain horizontal type; real ES-DE logs a warning and
        // ignores the property for every other type.
        reflections = (bool("reflections") ?: false) && type == EsDeCarouselType.HORIZONTAL,
        reflectionsOpacity = float("reflectionsOpacity")?.coerceIn(0.1f, 1f) ?: 0.5f,
        reflectionsFalloff = float("reflectionsFalloff")?.coerceIn(0f, 10f) ?: 1f,
        unfocusedItemOpacity = float("unfocusedItemOpacity")?.coerceIn(0.1f, 1f) ?: 0.5f,
        unfocusedItemSaturation = float("unfocusedItemSaturation")?.coerceIn(0f, 1f),
        unfocusedItemDimming = float("unfocusedItemDimming")?.coerceIn(0f, 1f) ?: 1f,
        imageSaturation = float("imageSaturation")?.coerceIn(0f, 1f) ?: 1f,
        imageBrightness = float("imageBrightness")?.coerceIn(-2f, 2f) ?: 0f,
        instantItemTransitions = str("itemTransitions") == "instant",
        fastScrolling = bool("fastScrolling") ?: false,
    )
}

private fun horizontalAlign(value: String?): EsDeHorizontalAlign = when (value) {
    "left" -> EsDeHorizontalAlign.LEFT
    "right" -> EsDeHorizontalAlign.RIGHT
    else -> EsDeHorizontalAlign.CENTER
}

private fun verticalAlign(value: String?): EsDeVerticalAlign = when (value) {
    "top" -> EsDeVerticalAlign.TOP
    "bottom" -> EsDeVerticalAlign.BOTTOM
    else -> EsDeVerticalAlign.CENTER
}

/**
 * The real `CarouselComponent<T>::render()` layout pass, minus the actual
 * drawing: which entries are on screen, where each lands, how it is
 * scaled/faded/rotated, and -- via the returned order -- which of them
 * draws on top of which.
 *
 * [camOffset] is real ES-DE's own animated `mEntryCamOffset` (a
 * continuous, fractional cursor position), [positiveDirection] its
 * `mPositiveDirection` (which way the last move went, which decides
 * whether the center entry rounds down or up and therefore which of two
 * overlapping items wins).
 *
 * Returns placements already in real draw order: index 0 draws first,
 * i.e. furthest back.
 */
fun layoutEsDeCarousel(
    config: EsDeCarouselConfig,
    sizeX: Float,
    sizeY: Float,
    camOffset: Float,
    entryCount: Int,
    positiveDirection: Boolean,
): List<EsDeCarouselPlacement> {
    if (entryCount <= 0) return emptyList()

    val isWheel = config.type.isWheel
    var itemSpacingX = 0f
    var itemSpacingY = 0f
    var xOff: Float
    var yOff: Float

    // CarouselComponent.h:750-753.
    val scaleSize = if (config.type == EsDeCarouselType.HORIZONTAL_WHEEL) {
        config.itemSizeY * config.itemScale - config.itemSizeY
    } else {
        config.itemSizeX * config.itemScale - config.itemSizeX
    }

    if (isWheel) {
        if (config.type == EsDeCarouselType.HORIZONTAL_WHEEL) {
            // CarouselComponent.h:756-777.
            xOff = (sizeX / 2f) - (config.itemSizeY / 2f)
            yOff = when (config.wheelVerticalAlignment) {
                EsDeVerticalAlign.CENTER -> {
                    var y = (sizeY / 2f) + (config.itemSizeX / 2f)
                    if (config.itemVerticalAlignment == EsDeVerticalAlign.TOP) {
                        y -= scaleSize / 2f
                    } else if (config.itemVerticalAlignment == EsDeVerticalAlign.BOTTOM) {
                        y += scaleSize / 2f
                    }
                    y
                }
                EsDeVerticalAlign.TOP -> {
                    var y = config.itemSizeX - ((config.itemSizeX - config.itemSizeY) / 2f)
                    if (config.itemVerticalAlignment == EsDeVerticalAlign.CENTER) {
                        y += scaleSize / 2f
                    } else if (config.itemVerticalAlignment == EsDeVerticalAlign.BOTTOM) {
                        y += scaleSize
                    }
                    y
                }
                EsDeVerticalAlign.BOTTOM -> {
                    var y = sizeY + ((config.itemSizeX - config.itemSizeY) / 2f)
                    if (config.itemVerticalAlignment == EsDeVerticalAlign.CENTER) {
                        y -= scaleSize / 2f
                    } else if (config.itemVerticalAlignment == EsDeVerticalAlign.TOP) {
                        y -= scaleSize
                    }
                    y
                }
            }
        } else {
            // CarouselComponent.h:779-810 -- the vertical wheel.
            xOff = (sizeX - config.itemSizeX) / 2f
            yOff = (sizeY - config.itemSizeY) / 2f
            when (config.wheelHorizontalAlignment) {
                EsDeHorizontalAlign.RIGHT -> {
                    xOff += sizeX / 2f
                    xOff -= when (config.itemHorizontalAlignment) {
                        EsDeHorizontalAlign.LEFT -> config.itemSizeX / 2f + scaleSize
                        EsDeHorizontalAlign.RIGHT -> config.itemSizeX / 2f
                        EsDeHorizontalAlign.CENTER -> config.itemSizeX / 2f + scaleSize / 2f
                    }
                }
                EsDeHorizontalAlign.LEFT -> {
                    xOff -= sizeX / 2f
                    xOff += when (config.itemHorizontalAlignment) {
                        EsDeHorizontalAlign.LEFT -> config.itemSizeX / 2f
                        EsDeHorizontalAlign.RIGHT -> config.itemSizeX / 2f + scaleSize
                        EsDeHorizontalAlign.CENTER -> config.itemSizeX / 2f + scaleSize / 2f
                    }
                }
                EsDeHorizontalAlign.CENTER -> {
                    if (config.itemHorizontalAlignment == EsDeHorizontalAlign.RIGHT) {
                        xOff += scaleSize / 2f
                    } else if (config.itemHorizontalAlignment == EsDeHorizontalAlign.LEFT) {
                        xOff -= scaleSize / 2f
                    }
                }
            }
        }
    } else if (config.type == EsDeCarouselType.VERTICAL) {
        // CarouselComponent.h:812-821.
        itemSpacingY = ((sizeY - (config.itemSizeY * config.maxItemCount)) / config.maxItemCount) + config.itemSizeY
        yOff = (sizeY - config.itemSizeY) / 2f - (camOffset * itemSpacingY)
        xOff = when (config.itemHorizontalAlignment) {
            EsDeHorizontalAlign.LEFT -> 0f
            EsDeHorizontalAlign.RIGHT -> sizeX - config.itemSizeX
            EsDeHorizontalAlign.CENTER -> (sizeX - config.itemSizeX) / 2f
        }
    } else {
        // CarouselComponent.h:822-832 -- the plain horizontal type.
        itemSpacingX = ((sizeX - (config.itemSizeX * config.maxItemCount)) / config.maxItemCount) + config.itemSizeX
        xOff = (sizeX - config.itemSizeX) / 2f - (camOffset * itemSpacingX)
        yOff = when (config.itemVerticalAlignment) {
            EsDeVerticalAlign.TOP -> 0f
            EsDeVerticalAlign.BOTTOM ->
                sizeY - config.itemSizeY - (if (config.reflections) config.itemSizeY * config.itemScale else 0f)
            EsDeVerticalAlign.CENTER ->
                (sizeY - (config.itemSizeY * (if (config.reflections) 2f else 1f))) / 2f
        }
    }

    xOff += sizeX * config.horizontalOffset
    yOff += sizeY * config.verticalOffset

    // CarouselComponent.h:836-841.
    val center = if (positiveDirection) floor(camOffset).toInt() else ceil(camOffset).toInt()
    var centerOffset = 0
    val itemInclusion: Int
    var itemInclusionBefore = 0
    var itemInclusionAfter: Int

    if (!isWheel) {
        // CarouselComponent.h:847-874.
        itemInclusion = ceil(config.maxItemCount / 2f).toInt() + 1
        itemInclusionAfter = 2
        if (config.type == EsDeCarouselType.HORIZONTAL && config.horizontalOffset != 0f) {
            centerOffset = ceil(sizeX * abs(config.horizontalOffset) / minOf(config.itemSizeX, itemSpacingX)).toInt()
            if (config.horizontalOffset < 0f) itemInclusionAfter += centerOffset else itemInclusionBefore += centerOffset
            if (config.horizontalOffset > 0f) centerOffset = -centerOffset
        } else if (config.type == EsDeCarouselType.VERTICAL && config.verticalOffset != 0f) {
            centerOffset = ceil(sizeY * abs(config.verticalOffset) / minOf(config.itemSizeY, itemSpacingY)).toInt()
            if (config.verticalOffset < 0f) itemInclusionAfter += centerOffset else itemInclusionBefore += centerOffset
            if (config.verticalOffset > 0f) centerOffset = -centerOffset
        }
    } else {
        // CarouselComponent.h:876-880.
        itemInclusion = 1
        itemInclusionBefore = config.itemsBeforeCenter - 1
        itemInclusionAfter = config.itemsAfterCenter
    }

    val singleEntry = entryCount == 1
    // Real item origin (CarouselComponent.h:404-419): the alignment
    // decides which point of the item box the item is pinned and scaled
    // around.
    val originX = when (config.itemHorizontalAlignment) {
        EsDeHorizontalAlign.LEFT -> 0f
        EsDeHorizontalAlign.RIGHT -> 1f
        EsDeHorizontalAlign.CENTER -> 0.5f
    }
    val originY = when (config.itemVerticalAlignment) {
        EsDeVerticalAlign.TOP -> 0f
        EsDeVerticalAlign.BOTTOM -> 1f
        EsDeVerticalAlign.CENTER -> 0.5f
    }

    val items = mutableListOf<CarouselItemPass>()
    var i = center - itemInclusion - itemInclusionBefore
    val end = center + itemInclusion + itemInclusionAfter
    while (i < end) {
        var index = i
        while (index < 0) index += entryCount
        while (index >= entryCount) index -= entryCount

        val distance = if (singleEntry) 0f else i - camOffset
        val absDistance = abs(distance)

        // CarouselComponent.h:906-940 -- note the two branches differ in
        // more than sign: only the itemScale >= 1 branch normalizes by
        // itemScale at the end, because the item was pre-sized larger.
        var scale: Float
        if (config.itemScale >= 1f) {
            scale = 1f + ((config.itemScale - 1f) * (1f - absDistance))
            scale = minOf(config.itemScale, maxOf(1f, scale))
            if (!isWheel) {
                if (config.itemLinearScaleX != 0f && distance < 0) scale -= distance * config.itemLinearScaleX
                if (config.itemLinearScaleY != 0f && distance > 0) scale -= -distance * config.itemLinearScaleY
            }
            scale /= config.itemScale
        } else {
            scale = 1f + ((1f - config.itemScale) * (absDistance - 1f))
            scale = maxOf(config.itemScale, minOf(1f, scale))
            if (!isWheel) {
                if (config.itemLinearScaleX != 0f && distance < 0) scale -= distance * config.itemLinearScaleX
                if (config.itemLinearScaleY != 0f && distance > 0) scale -= -distance * config.itemLinearScaleY
            }
        }
        if (scale < 0f) scale = 0f

        // CarouselComponent.h:942-963.
        var marginX = 0f
        var marginY = 0f
        if (config.selectedItemMarginsX != 0f || config.selectedItemMarginsY != 0f) {
            if (i < camOffset) {
                if (config.type == EsDeCarouselType.HORIZONTAL) {
                    marginX = -config.selectedItemMarginsX
                } else {
                    marginY = -config.selectedItemMarginsX
                }
            } else if (i > camOffset) {
                if (config.type == EsDeCarouselType.HORIZONTAL) {
                    marginX = config.selectedItemMarginsY
                } else {
                    marginY = config.selectedItemMarginsY
                }
            }
            if (absDistance < 1f) {
                marginX *= absDistance
                marginY *= absDistance
            }
        }

        // CarouselComponent.h:965-971.
        var itemHorizontalOffset = 0f
        var itemVerticalOffset = 0f
        if ((config.selectedItemOffsetX != 0f || config.selectedItemOffsetY != 0f) && absDistance < 1f) {
            itemHorizontalOffset = (1f - absDistance) * config.selectedItemOffsetX
            itemVerticalOffset = (1f - absDistance) * config.selectedItemOffsetY
        }

        var trans = EsDeAffine2()
        if (singleEntry) {
            trans = trans.translate(xOff + itemHorizontalOffset, yOff + itemVerticalOffset)
        } else {
            // CarouselComponent.h:979-1006 -- linear spacing grows
            // triangularly outward when increasing, quadratically inward
            // when decreasing.
            var linearSpacingOffsetX = 0f
            var linearSpacingOffsetY = 0f
            if (!isWheel && (config.itemLinearSpacingX != 0f || config.itemLinearSpacingY != 0f) && absDistance > 0f) {
                val direction = if (distance > 0f) 1f else -1f
                val increasing = if (distance > 0f) config.itemLinearSpacingY > 0f else config.itemLinearSpacingX > 0f
                val spacingScale = if (distance > 0f) abs(config.itemLinearSpacingY) else abs(config.itemLinearSpacingX)
                val multiplier = if (increasing) {
                    absDistance * (absDistance + 1f) * 0.5f
                } else {
                    -(absDistance * absDistance)
                }
                if (config.type == EsDeCarouselType.HORIZONTAL) {
                    linearSpacingOffsetX = direction * multiplier * (itemSpacingX * spacingScale)
                } else {
                    linearSpacingOffsetY = direction * multiplier * (itemSpacingY * spacingScale)
                }
            }
            trans = trans.translate(
                (i * itemSpacingX) + xOff + linearSpacingOffsetX + itemHorizontalOffset + marginX,
                (i * itemSpacingY) + yOff + linearSpacingOffsetY + itemVerticalOffset + marginY,
            )
        }

        // CarouselComponent.h:1008-1009 -- the horizontal wheel lays its
        // items out in a rotated frame, then un-rotates each item below.
        if (config.type == EsDeCarouselType.HORIZONTAL_WHEEL) trans = trans.rotateDegrees(-90f)

        // CarouselComponent.h:1011-1045.
        val opacity = when {
            distance == 0f || config.unfocusedItemOpacity == 1f -> 1f
            absDistance >= 1f -> config.unfocusedItemOpacity
            else -> {
                val maxDiff = 1f - config.unfocusedItemOpacity
                config.unfocusedItemOpacity + (maxDiff - (maxDiff * absDistance))
            }
        }
        val saturation = config.unfocusedItemSaturation?.let { unfocused ->
            when {
                distance == 0f -> config.imageSaturation
                absDistance >= 1f -> unfocused
                else -> {
                    val maxDiff = config.imageSaturation - unfocused
                    unfocused + (maxDiff - (maxDiff * absDistance))
                }
            }
        }
        val dimming = when {
            distance == 0f || config.unfocusedItemDimming == 1f -> 1f
            absDistance >= 1f -> config.unfocusedItemDimming
            else -> {
                val maxDiff = 1f - config.unfocusedItemDimming
                config.unfocusedItemDimming + (maxDiff - (maxDiff * absDistance))
            }
        }

        items += CarouselItemPass(index, distance, scale, opacity, saturation, dimming, trans)
        if (singleEntry) break
        ++i
    }

    val sorted = sortByStacking(items, config, isWheel, centerOffset, positiveDirection)

    val result = mutableListOf<EsDeCarouselPlacement>()
    for (item in sorted) {
        var trans = item.trans
        if (isWheel) {
            // CarouselComponent.h:1075-1113 -- rotate the item around the
            // wheel's own far-off rotation origin, then (for a horizontal
            // wheel) rotate each item 90 degrees back around its own
            // center so it reads upright.
            val xOffTrans = -config.itemRotationOriginX * config.itemSizeX
            val yOffTrans = if (config.itemAxisHorizontal) 0f else -config.itemRotationOriginY * config.itemSizeY
            val positionCalc = trans
                .translate(-xOffTrans, -yOffTrans)
                .rotateDegrees(config.itemRotation * item.distance)
                .translate(xOffTrans, yOffTrans)

            trans = if (config.itemAxisHorizontal) {
                // Real behavior: keep the wheel POSITION but discard its
                // rotation, so items stay axis-aligned around the wheel.
                val axisAligned = trans.copy(tx = positionCalc.tx, ty = positionCalc.ty)
                if (config.type == EsDeCarouselType.HORIZONTAL_WHEEL) {
                    axisAligned.rotateAroundItemCenter(config.itemSizeX, config.itemSizeY, 90f)
                } else {
                    axisAligned
                }
            } else if (config.type == EsDeCarouselType.HORIZONTAL_WHEEL) {
                positionCalc.rotateAroundItemCenter(config.itemSizeX, config.itemSizeY, 90f)
            } else {
                positionCalc
            }
        } else if (config.itemAxisRotation != 0f) {
            // CarouselComponent.h:1114-1127.
            trans = trans.rotateAroundItemCenter(config.itemSizeX, config.itemSizeY, config.itemAxisRotation)
        }

        // CarouselComponent.h:1217-1224 -- the item's own position inside
        // its cell shifts perpendicular to the carousel's travel, giving
        // the diagonal look.
        var localX = config.itemSizeX * originX
        var localY = config.itemSizeY * originY
        if (config.itemDiagonalOffset != 0f) {
            if (config.type == EsDeCarouselType.HORIZONTAL) {
                localY -= config.itemDiagonalOffset * item.distance
            } else {
                localX -= config.itemDiagonalOffset * item.distance
            }
        }

        val (anchorX, anchorY) = trans.apply(localX, localY)
        result += EsDeCarouselPlacement(
            index = item.index,
            distance = item.distance,
            anchorX = anchorX,
            anchorY = anchorY,
            originFractionX = originX,
            originFractionY = originY,
            rotationDegrees = trans.rotationDegrees,
            scale = item.scale,
            opacity = item.opacity,
            saturation = item.saturation,
            dimming = item.dimming,
        )
    }
    return result
}

private fun EsDeAffine2.rotateAroundItemCenter(itemSizeX: Float, itemSizeY: Float, degrees: Float): EsDeAffine2 {
    val xOffTransRotate = -(itemSizeX / 2f)
    val yOffTransRotate = -(itemSizeY / 2f)
    return translate(-xOffTransRotate, -yOffTransRotate)
        .rotateDegrees(degrees)
        .translate(xOffTransRotate, yOffTransRotate)
}

private data class CarouselItemPass(
    val index: Int,
    val distance: Float,
    val scale: Float,
    val opacity: Float,
    val saturation: Float?,
    val dimming: Float,
    val trans: EsDeAffine2,
)

/**
 * Real `CarouselComponent::render`'s own draw-order pass
 * (CarouselComponent.h:1072-1121): overlapping items must be drawn in an
 * order the theme chose, and the wheel/centered case additionally has to
 * lift the entry at distance zero to the very top.
 */
private fun sortByStacking(
    items: List<CarouselItemPass>,
    config: EsDeCarouselConfig,
    isWheel: Boolean,
    centerOffset: Int,
    positiveDirection: Boolean,
): List<CarouselItemPass> {
    if (items.size <= 1) return items
    // Real integer division on a size_t expression, NOT a rounded float
    // despite the std::round() wrapped around it in the C++.
    val belowCenter = (items.size - centerOffset - 1) / 2
    val sorted = mutableListOf<CarouselItemPass>()

    if (!isWheel && config.itemStacking != EsDeItemStacking.CENTERED) {
        when (config.itemStacking) {
            EsDeItemStacking.ASCENDING -> sorted += items
            EsDeItemStacking.ASCENDING_RAISED -> {
                items.forEachIndexed { idx, item -> if (idx != belowCenter) sorted += item }
                items.getOrNull(belowCenter)?.let { sorted += it }
            }
            EsDeItemStacking.DESCENDING -> sorted += items.reversed()
            EsDeItemStacking.DESCENDING_RAISED -> {
                for (idx in items.indices.reversed()) if (idx != belowCenter) sorted += items[idx]
                items.getOrNull(belowCenter)?.let { sorted += it }
            }
            EsDeItemStacking.CENTERED -> Unit
        }
        return sorted
    }

    var zeroDistanceEntry = 0
    for (idx in 0 until belowCenter) sorted += items[idx]
    for (idx in items.indices.reversed()) {
        if (idx < belowCenter) break
        if (isWheel) {
            val rounded = if (positiveDirection) ceil(items[idx].distance) else floor(items[idx].distance)
            if (rounded == 0f) {
                zeroDistanceEntry = idx
                continue
            }
        }
        sorted += items[idx]
    }
    if (isWheel) sorted += items[zeroDistanceEntry]
    return sorted
}
