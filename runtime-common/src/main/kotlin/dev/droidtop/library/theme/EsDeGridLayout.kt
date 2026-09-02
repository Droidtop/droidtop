package dev.droidtop.library.theme

import kotlin.math.floor

/**
 * Real ES-DE grid LAYOUT and configuration, as pure maths -- a port of
 * `GridComponent<T>::calculateLayout()`, the geometry half of its
 * `render()`, and its `applyTheme()` property reading
 * (es-core/src/components/primary/GridComponent.h in the real ES-DE
 * source at /root/es-de-reference), with no Compose or Android
 * dependency so it can be unit tested without a screen. Same split, and
 * for the same reason, as `EsDeCarouselLayout.kt`/`EsDeTextListLayout.kt`.
 *
 * droidtop's grid previously read nine properties and invented the rest:
 * a fixed 16dp item spacing (real ES-DE AUTO-CALCULATES spacing from
 * `itemScale` when the theme doesn't declare it), a white default text
 * color (real ES-DE's is black on transparent), and a dark rounded card
 * with a border and an "N items" line that has no counterpart in ES-DE
 * at all -- and which is exactly the surface a theme's own real
 * `backgroundColor`/`selectorColor` layers were supposed to occupy.
 */
enum class EsDeSelectorLayer { TOP, MIDDLE, BOTTOM }

/**
 * Every real grid property this renderer honors, resolved to pixels
 * against the themed area the same way real ES-DE resolves them against
 * its screen. Colors stay as the parser's own packed RRGGBBAA longs, and
 * a NULLABLE color means real ES-DE's own `mHasBackgroundColor`/
 * `mHasSelectorColor` flag: absent means "draw no layer at all", which is
 * a different thing from "draw a transparent one".
 */
data class EsDeGridConfig(
    val itemSizeX: Float,
    val itemSizeY: Float,
    val itemScale: Float = 1.05f,
    val itemSpacingX: Float,
    val itemSpacingY: Float,
    val scaleInwards: Boolean = false,
    val fractionalRows: Boolean = false,
    val imageRelativeScale: Float = 1f,
    val imageCornerRadius: Float = 0f,
    val imageBrightness: Float = 0f,
    val imageSaturation: Float = 1f,
    val imageColor: Long? = null,
    val imageSelectedColor: Long? = null,
    val backgroundRelativeScale: Float = 1f,
    val backgroundCornerRadius: Float = 0f,
    val backgroundColor: Long? = null,
    val backgroundColorEnd: Long = 0xFFFFFFFFL,
    val backgroundGradientHorizontal: Boolean = true,
    val backgroundImage: String? = null,
    val selectorRelativeScale: Float = 1f,
    val selectorCornerRadius: Float = 0f,
    val selectorColor: Long? = null,
    val selectorColorEnd: Long = 0xFFFFFFFFL,
    val selectorGradientHorizontal: Boolean = true,
    val selectorImage: String? = null,
    val selectorLayer: EsDeSelectorLayer = EsDeSelectorLayer.TOP,
    val unfocusedItemOpacity: Float = 1f,
    val unfocusedItemSaturation: Float? = null,
    val unfocusedItemDimming: Float = 1f,
    val textRelativeScale: Float = 1f,
    val textColor: Long = 0x000000FFL,
    val textBackgroundColor: Long = 0xFFFFFF00L,
    val textSelectedColor: Long = 0x000000FFL,
    val textSelectedBackgroundColor: Long = 0xFFFFFF00L,
    val letterCase: EsDeLetterCase = EsDeLetterCase.NONE,
    val instantItemTransitions: Boolean = false,
    val instantRowTransitions: Boolean = false,
) {
    /**
     * Real `calculateLayout()`'s own margins: the room an item needs to
     * scale up into without overlapping its neighbours. Zero when items
     * scale DOWN, and zero again under `scaleInwards`, which is what that
     * property means -- the focused item grows into the grid rather than
     * out of it, so no room has to be reserved around the edges.
     */
    val horizontalMargin: Float
        get() = if (itemScale < 1f) 0f else ((itemSizeX * (if (scaleInwards) 1f else itemScale)) - itemSizeX) / 2f

    val verticalMargin: Float
        get() = if (itemScale < 1f) 0f else ((itemSizeY * (if (scaleInwards) 1f else itemScale)) - itemSizeY) / 2f
}

/** Real `GridComponent::applyTheme` property reading, clamps and all. */
fun esDeGridConfig(
    element: EsDeThemeElement?,
    screenWidth: Float,
    screenHeight: Float,
): EsDeGridConfig {
    fun float(name: String): Float? = element?.valueOrNull<EsDeThemeValue.FloatValue>(name)?.value
    fun bool(name: String): Boolean? = element?.valueOrNull<EsDeThemeValue.Bool>(name)?.value
    fun str(name: String): String? = element?.valueOrNull<EsDeThemeValue.Str>(name)?.value
    fun path(name: String): String? = element?.valueOrNull<EsDeThemeValue.Path>(name)?.resolved
    fun pair(name: String): EsDeThemeValue.Pair? = element?.valueOrNull<EsDeThemeValue.Pair>(name)
    fun color(name: String): Long? = element?.valueOrNull<EsDeThemeValue.Color>(name)?.argbLikeRgba

    // GridComponent.h:1034-1053 -- real constructor default is 0.15 x
    // 0.25 of the screen, and either axis may be -1, meaning "square,
    // sized from the other axis".
    var itemSizeX = 0.15f * screenWidth
    var itemSizeY = 0.25f * screenHeight
    val declaredItemSize = pair("itemSize")
    if (declaredItemSize != null && !(declaredItemSize.x == -1f && declaredItemSize.y == -1f)) {
        if (declaredItemSize.x == -1f) {
            itemSizeY = declaredItemSize.y.coerceIn(0.05f, 1f) * screenHeight
            itemSizeX = itemSizeY
        } else if (declaredItemSize.y == -1f) {
            itemSizeX = declaredItemSize.x.coerceIn(0.05f, 1f) * screenWidth
            itemSizeY = itemSizeX
        } else {
            itemSizeX = declaredItemSize.x.coerceIn(0.05f, 1f) * screenWidth
            itemSizeY = declaredItemSize.y.coerceIn(0.05f, 1f) * screenHeight
        }
    }

    // GridComponent.h:1055-1056 -- note the grid's own clamp is 0.5-2.0,
    // NOT the carousel's 0.2-3.0.
    val itemScale = float("itemScale")?.coerceIn(0.5f, 2f) ?: 1.05f
    // GridComponent.h:1061-1062 -- scaleInwards only takes effect when
    // items actually scale up.
    val scaleInwards = itemScale > 1f && (bool("scaleInwards") ?: false)

    // GridComponent.h:1288-1315 -- when the theme declares no itemSpacing
    // at all, it is CALCULATED so scaled items don't overlap; it does not
    // fall back to a constant. Either axis may be -1, meaning "the same
    // pixel value as the other axis".
    var itemSpacingX: Float
    var itemSpacingY: Float
    val declaredSpacing = pair("itemSpacing")
    if (declaredSpacing != null) {
        if (declaredSpacing.x == -1f && declaredSpacing.y == -1f) {
            itemSpacingX = 0f
            itemSpacingY = 0f
        } else if (declaredSpacing.x == -1f) {
            itemSpacingY = declaredSpacing.y.coerceIn(0f, 0.1f) * screenHeight
            itemSpacingX = itemSpacingY
        } else if (declaredSpacing.y == -1f) {
            itemSpacingX = declaredSpacing.x.coerceIn(0f, 0.1f) * screenWidth
            itemSpacingY = itemSpacingX
        } else {
            itemSpacingX = declaredSpacing.x.coerceIn(0f, 0.1f) * screenWidth
            itemSpacingY = declaredSpacing.y.coerceIn(0f, 0.1f) * screenHeight
        }
    } else if (itemScale < 1f) {
        itemSpacingX = 0f
        itemSpacingY = 0f
    } else {
        itemSpacingX = ((itemSizeX * itemScale) - itemSizeX) / 2f
        itemSpacingY = ((itemSizeY * itemScale) - itemSizeY) / 2f
    }

    // GridComponent.h:1178-1196/1216-1234 -- every corner radius on this
    // element is a fraction of screen WIDTH, additionally scaled by
    // itemScale when items scale up.
    val radiusScale = (if (itemScale >= 1f) itemScale else 1f) * screenWidth
    fun cornerRadius(name: String): Float = (float(name)?.coerceIn(0f, 0.5f) ?: 0f) * radiusScale

    val backgroundColor = color("backgroundColor")
    val selectorColor = color("selectorColor")

    return EsDeGridConfig(
        itemSizeX = itemSizeX,
        itemSizeY = itemSizeY,
        itemScale = itemScale,
        itemSpacingX = itemSpacingX,
        itemSpacingY = itemSpacingY,
        scaleInwards = scaleInwards,
        fractionalRows = bool("fractionalRows") ?: false,
        // GridComponent.h:1058-1059/1102-1104/1132-1133 -- all three
        // relative scales share the same 0.2-1.0 clamp.
        imageRelativeScale = float("imageRelativeScale")?.coerceIn(0.2f, 1f) ?: 1f,
        imageCornerRadius = cornerRadius("imageCornerRadius"),
        imageBrightness = float("imageBrightness")?.coerceIn(-2f, 2f) ?: 0f,
        imageSaturation = float("imageSaturation")?.coerceIn(0f, 1f) ?: 1f,
        imageColor = color("imageColor"),
        imageSelectedColor = color("imageSelectedColor") ?: color("imageColor"),
        backgroundRelativeScale = float("backgroundRelativeScale")?.coerceIn(0.2f, 1f) ?: 1f,
        backgroundCornerRadius = cornerRadius("backgroundCornerRadius"),
        backgroundColor = backgroundColor,
        backgroundColorEnd = color("backgroundColorEnd") ?: backgroundColor ?: 0xFFFFFFFFL,
        backgroundGradientHorizontal = str("backgroundGradientType") != "vertical",
        backgroundImage = path("backgroundImage"),
        selectorRelativeScale = float("selectorRelativeScale")?.coerceIn(0.2f, 1f) ?: 1f,
        selectorCornerRadius = cornerRadius("selectorCornerRadius"),
        selectorColor = selectorColor,
        selectorColorEnd = color("selectorColorEnd") ?: selectorColor ?: 0xFFFFFFFFL,
        selectorGradientHorizontal = str("selectorGradientType") != "vertical",
        selectorImage = path("selectorImage"),
        // GridComponent.h:1236-1254 -- an unrecognized value warns and
        // falls back to top.
        selectorLayer = when (str("selectorLayer")) {
            "middle" -> EsDeSelectorLayer.MIDDLE
            "bottom" -> EsDeSelectorLayer.BOTTOM
            else -> EsDeSelectorLayer.TOP
        },
        // GridComponent.h:1378-1388 -- the grid's own unfocusedItemOpacity
        // default is 1.0, unlike the carousel's 0.5.
        unfocusedItemOpacity = float("unfocusedItemOpacity")?.coerceIn(0.1f, 1f) ?: 1f,
        unfocusedItemSaturation = float("unfocusedItemSaturation")?.coerceIn(0f, 1f),
        unfocusedItemDimming = float("unfocusedItemDimming")?.coerceIn(0f, 1f) ?: 1f,
        textRelativeScale = float("textRelativeScale")?.coerceIn(0.2f, 1f) ?: 1f,
        textColor = color("textColor") ?: 0x000000FFL,
        textBackgroundColor = color("textBackgroundColor") ?: 0xFFFFFF00L,
        textSelectedColor = color("textSelectedColor") ?: color("textColor") ?: 0x000000FFL,
        textSelectedBackgroundColor =
            color("textSelectedBackgroundColor") ?: color("textBackgroundColor") ?: 0xFFFFFF00L,
        letterCase = when (str("letterCase")) {
            "uppercase" -> EsDeLetterCase.UPPERCASE
            "lowercase" -> EsDeLetterCase.LOWERCASE
            "capitalize" -> EsDeLetterCase.CAPITALIZE
            else -> EsDeLetterCase.NONE
        },
        instantItemTransitions = str("itemTransitions") == "instant",
        instantRowTransitions = str("rowTransitions") == "instant",
    )
}

/** Real `GridComponent::calculateLayout()` output: how the grid divides its own box. */
data class EsDeGridLayout(
    val columns: Int,
    val visibleRows: Float,
)

fun layoutEsDeGrid(config: EsDeGridConfig, sizeX: Float, sizeY: Float): EsDeGridLayout {
    // GridComponent.h:546-560 -- a greedy "fit as many as fit" loop that
    // reserves the scale-up margin on both sides first, and charges the
    // item spacing only BETWEEN items. Not a divide-and-floor, and not
    // Compose's own adaptive column heuristic, which reserves neither.
    var columns = 0
    var width = config.horizontalMargin * 2f
    while (true) {
        width += config.itemSizeX
        if (columns != 0) width += config.itemSpacingX
        if (width > sizeX) break
        ++columns
    }
    if (columns == 0) columns = 1

    // GridComponent.h:578-586.
    var visibleRows = sizeY / (config.itemSizeY + config.itemSpacingY)
    visibleRows -= (config.verticalMargin / sizeY) * visibleRows * 2f
    visibleRows += (config.itemSpacingY / sizeY) * visibleRows
    if (!config.fractionalRows) visibleRows = floor(visibleRows)
    if (visibleRows == 0f) visibleRows = 1f

    return EsDeGridLayout(columns = columns, visibleRows = visibleRows)
}

/**
 * Real `GridComponent::onCursorChanged`'s own scroll target
 * (GridComponent.h:1611-1619): the grid does not scroll at all until the
 * cursor's row passes the last fully visible one, and then it scrolls by
 * exactly enough to bring that row into view. Returned in ROWS; the
 * renderer converts (real ES-DE's own
 * `trans.y -= (itemSize.y + itemSpacing.y) * scrollPos`).
 *
 * Real ES-DE eases between the old and new value unless
 * `rowTransitions="instant"`; this is the resting target either way.
 */
fun esDeGridScrollRow(layout: EsDeGridLayout, cursor: Int): Float {
    val visibleRows = layout.visibleRows - 1f
    val endRow = (cursor / layout.columns).toFloat()
    return if (endRow <= visibleRows) 0f else endRow - visibleRows
}

/**
 * Centre of one item's cell, in the grid's own space before scrolling --
 * real `calculateLayout()`'s own per-entry `setPosition` (GridComponent.h:
 * 563-568), which places every item by its CENTRE.
 */
fun esDeGridItemCenter(
    config: EsDeGridConfig,
    layout: EsDeGridLayout,
    index: Int,
): kotlin.Pair<Float, Float> {
    val column = index % layout.columns
    val row = index / layout.columns
    return kotlin.Pair(
        config.horizontalMargin + (config.itemSizeX * column) + (config.itemSizeX * 0.5f) +
            config.itemSpacingX * column,
        config.verticalMargin + (config.itemSizeY * row) + (config.itemSizeY * 0.5f) +
            config.itemSpacingY * row,
    )
}
