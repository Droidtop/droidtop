package dev.droidtop.library.theme

import kotlin.math.floor

/**
 * Real ES-DE textlist LAYOUT and configuration, as pure maths -- a direct
 * port of `TextListComponent<T>::render()` and
 * `TextListComponent<T>::applyTheme()` (es-core/src/components/primary/
 * TextListComponent.h in the real ES-DE source at /root/es-de-reference,
 * read line-by-line for this port), with no Compose or Android
 * dependency so it can be unit tested without a screen.
 *
 * The textlist was by a wide margin droidtop's thinnest primary
 * component: it read four properties and drew the rest from invented
 * constants (a 48dp horizontal padding with no basis in ES-DE at all,
 * and a per-row background standing in for what real ES-DE draws as ONE
 * selector bar positioned by its own arithmetic). Everything that made
 * a textlist-heavy theme render wrong lives in this file now: the real
 * scroll window, the real selector bar (image or gradient rect, with its
 * own width/height/offsets), the real primary/secondary and
 * selected/selected-secondary color pairs, the real selected-row
 * background, and the real horizontal margin and alignment.
 */
enum class EsDePrimaryAlignment { LEFT, CENTER, RIGHT }

/** Real `LetterCase` (es-core/src/utils/StringUtil.h) -- UNDEFINED means "inherit", not "none". */
enum class EsDeLetterCase { NONE, UPPERCASE, LOWERCASE, CAPITALIZE, UNDEFINED }

/** Real `mIndicators`/`mCollectionIndicators` values (TextListComponent.h:668-703). */
enum class EsDeIndicators { SYMBOLS, ASCII, NONE }

/**
 * Real `TextListComponent` theme configuration. Colors stay as the
 * parser's own packed RRGGBBAA longs so this layer needs no graphics
 * types; the renderer converts them.
 *
 * Every default here is real `TextListComponent`'s own constructor
 * default and every clamp is the real one from its `applyTheme`.
 */
data class EsDeTextListConfig(
    val fontSize: Float,
    val lineSpacing: Float = 1.5f,
    val selectorWidth: Float,
    val selectorHeight: Float,
    val selectorHorizontalOffset: Float = 0f,
    val selectorVerticalOffset: Float = 0f,
    val selectorColor: Long = 0x333333FFL,
    val selectorColorEnd: Long = 0x333333FFL,
    val selectorColorGradientHorizontal: Boolean = true,
    val selectorImagePath: String? = null,
    val selectorImageTile: Boolean = false,
    val primaryColor: Long = 0x0000FFFFL,
    val secondaryColor: Long = 0x00FF00FFL,
    val selectedColor: Long = 0x0000FFFFL,
    val selectedSecondaryColor: Long = 0x00FF00FFL,
    val selectedBackgroundColor: Long = 0x00000000L,
    val selectedSecondaryBackgroundColor: Long = 0x00000000L,
    val selectedBackgroundMarginsX: Float = 0f,
    val selectedBackgroundMarginsY: Float = 0f,
    val selectedBackgroundCornerRadius: Float = 0f,
    val horizontalScrolling: Boolean = true,
    val horizontalScrollSpeed: Float = 1f,
    val horizontalScrollDelayMs: Float = 3000f,
    val horizontalScrollGap: Float = 1.5f,
    val alignment: EsDePrimaryAlignment = EsDePrimaryAlignment.LEFT,
    val horizontalMargin: Float = 0f,
    val letterCase: EsDeLetterCase = EsDeLetterCase.NONE,
    val letterCaseAutoCollections: EsDeLetterCase = EsDeLetterCase.UNDEFINED,
    val letterCaseCustomCollections: EsDeLetterCase = EsDeLetterCase.UNDEFINED,
    val indicators: EsDeIndicators = EsDeIndicators.SYMBOLS,
    val collectionIndicators: EsDeIndicators = EsDeIndicators.SYMBOLS,
    val systemNameSuffix: Boolean = true,
    val letterCaseSystemNameSuffix: EsDeLetterCase = EsDeLetterCase.UPPERCASE,
    val fadeAbovePrimary: Boolean = false,
) {
    /** Real `entrySize` (TextListComponent.h:311): the height one row occupies. */
    val entrySize: Float get() = fontSize * lineSpacing
}

/**
 * Real `TextListComponent::applyTheme`. [width]/[height] are the
 * textlist element's own resolved size, [screenWidth]/[screenHeight] the
 * themed area -- real ES-DE resolves `selectorWidth`/`selectorHeight`
 * against the screen but `horizontalMargin` and the selector offsets
 * against the element's parent, which for a top-level themed element is
 * the screen too.
 */
fun esDeTextListConfig(
    element: EsDeThemeElement?,
    width: Float,
    height: Float,
    screenWidth: Float,
    screenHeight: Float,
    fontSize: Float,
): EsDeTextListConfig {
    fun float(name: String): Float? = element?.valueOrNull<EsDeThemeValue.FloatValue>(name)?.value
    fun bool(name: String): Boolean? = element?.valueOrNull<EsDeThemeValue.Bool>(name)?.value
    fun str(name: String): String? = element?.valueOrNull<EsDeThemeValue.Str>(name)?.value
    fun path(name: String): String? = element?.valueOrNull<EsDeThemeValue.Path>(name)?.resolved
    fun pair(name: String): EsDeThemeValue.Pair? = element?.valueOrNull<EsDeThemeValue.Pair>(name)
    fun color(name: String): Long? = element?.valueOrNull<EsDeThemeValue.Color>(name)?.argbLikeRgba

    // TextListComponent.h:485-495 -- selectorColorEnd defaults to
    // selectorColor, so a theme setting only the former still gets a
    // flat bar rather than a gradient into the built-in default.
    val selectorColor = color("selectorColor") ?: 0x333333FFL
    val selectorColorEnd = color("selectorColorEnd") ?: selectorColor

    // TextListComponent.h:505-524 -- the real fallback chain, which is
    // NOT "each color independently defaults to a constant":
    // selectedColor falls back to primaryColor, selectedSecondaryColor to
    // selectedColor, and selectedSecondaryBackgroundColor to
    // selectedBackgroundColor.
    val primaryColor = color("primaryColor") ?: 0x0000FFFFL
    val secondaryColor = color("secondaryColor") ?: 0x00FF00FFL
    val selectedColor = color("selectedColor") ?: primaryColor
    val selectedSecondaryColor = color("selectedSecondaryColor") ?: selectedColor
    val selectedBackgroundColor = color("selectedBackgroundColor") ?: 0x00000000L
    val selectedSecondaryBackgroundColor = color("selectedSecondaryBackgroundColor") ?: selectedBackgroundColor

    val lineSpacing = float("lineSpacing")?.coerceIn(0.5f, 3f) ?: 1.5f

    // TextListComponent.h:641-668 -- selectorHeight is a fraction of the
    // SCREEN height, falling back to 1.5 font sizes (which is the row
    // height at the default lineSpacing, but is deliberately not tied to
    // lineSpacing in real ES-DE).
    val selectorHeight = float("selectorHeight")?.coerceIn(0f, 1f)?.times(screenHeight)
        ?: (fontSize * 1.5f)
    // TextListComponent.h:723-727 -- selectorWidth falls back to the
    // textlist's own width, not the screen's.
    val selectorWidth = float("selectorWidth")?.coerceIn(0f, 1f)?.times(screenWidth) ?: width
    val selectorHorizontalOffset = float("selectorHorizontalOffset")?.coerceIn(-1f, 1f)?.times(screenWidth) ?: 0f
    // TextListComponent.h:653-659. (Real ES-DE also reads a legacy
    // `selectorOffsetY` spelling right after this, but that name is not in
    // its own `sElementMap` schema, so no theme can actually declare it and
    // droidtop's parser correctly drops it -- deliberately not ported.)
    val selectorVerticalOffset = float("selectorVerticalOffset")?.coerceIn(-1f, 1f)?.times(screenHeight) ?: 0f

    return EsDeTextListConfig(
        fontSize = fontSize,
        lineSpacing = lineSpacing,
        selectorWidth = selectorWidth,
        selectorHeight = selectorHeight,
        selectorHorizontalOffset = selectorHorizontalOffset,
        selectorVerticalOffset = selectorVerticalOffset,
        selectorColor = selectorColor,
        selectorColorEnd = selectorColorEnd,
        // TextListComponent.h:487-503 -- an unrecognized value warns and
        // falls back to horizontal.
        selectorColorGradientHorizontal = str("selectorGradientType") != "vertical",
        selectorImagePath = path("selectorImagePath"),
        selectorImageTile = bool("selectorImageTile") ?: false,
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        selectedColor = selectedColor,
        selectedSecondaryColor = selectedSecondaryColor,
        selectedBackgroundColor = selectedBackgroundColor,
        selectedSecondaryBackgroundColor = selectedSecondaryBackgroundColor,
        // TextListComponent.h:526-537 -- margins clamp 0-0.5 and scale by
        // screen WIDTH for both components, corner radius likewise.
        selectedBackgroundMarginsX = (pair("selectedBackgroundMargins")?.x?.coerceIn(0f, 0.5f) ?: 0f) * screenWidth,
        selectedBackgroundMarginsY = (pair("selectedBackgroundMargins")?.y?.coerceIn(0f, 0.5f) ?: 0f) * screenWidth,
        selectedBackgroundCornerRadius =
            (float("selectedBackgroundCornerRadius")?.coerceIn(0f, 0.5f) ?: 0f) * screenWidth,
        // TextListComponent.h:539-553.
        horizontalScrolling = bool("textHorizontalScrolling") ?: true,
        horizontalScrollSpeed = float("textHorizontalScrollSpeed")?.coerceIn(0.1f, 10f) ?: 1f,
        horizontalScrollDelayMs = (float("textHorizontalScrollDelay")?.coerceIn(0f, 10f) ?: 3f) * 1000f,
        horizontalScrollGap = float("textHorizontalScrollGap")?.coerceIn(0.1f, 5f) ?: 1.5f,
        // TextListComponent.h:557-580 -- the real default alignment for a
        // textlist is LEFT (set before applyTheme reads the property),
        // not the ALIGN_CENTER the constructor initializes.
        alignment = when (str("horizontalAlignment")) {
            "center" -> EsDePrimaryAlignment.CENTER
            "right" -> EsDePrimaryAlignment.RIGHT
            else -> EsDePrimaryAlignment.LEFT
        },
        horizontalMargin = (float("horizontalMargin") ?: 0f) * screenWidth,
        letterCase = letterCase(str("letterCase")) ?: EsDeLetterCase.NONE,
        letterCaseAutoCollections = letterCase(str("letterCaseAutoCollections")) ?: EsDeLetterCase.UNDEFINED,
        letterCaseCustomCollections = letterCase(str("letterCaseCustomCollections")) ?: EsDeLetterCase.UNDEFINED,
        // TextListComponent.h:668-703 -- an unrecognized value warns and
        // falls back to symbols; "none" is valid for indicators only.
        indicators = when (str("indicators")) {
            "ascii" -> EsDeIndicators.ASCII
            "none" -> EsDeIndicators.NONE
            else -> EsDeIndicators.SYMBOLS
        },
        collectionIndicators = when (str("collectionIndicators")) {
            "ascii" -> EsDeIndicators.ASCII
            else -> EsDeIndicators.SYMBOLS
        },
        systemNameSuffix = bool("systemNameSuffix") ?: true,
        letterCaseSystemNameSuffix = letterCase(str("letterCaseSystemNameSuffix")) ?: EsDeLetterCase.UPPERCASE,
        fadeAbovePrimary = bool("fadeAbovePrimary") ?: false,
    )
}

private fun letterCase(value: String?): EsDeLetterCase? = when (value) {
    "uppercase" -> EsDeLetterCase.UPPERCASE
    "lowercase" -> EsDeLetterCase.LOWERCASE
    "capitalize" -> EsDeLetterCase.CAPITALIZE
    "none" -> EsDeLetterCase.NONE
    else -> null
}

/** Real `Utils::String::toUpper`/`toLower`/`toCapitalized` application (GamelistBase.cpp:943-949). */
fun EsDeLetterCase.applyTo(value: String): String = when (this) {
    EsDeLetterCase.UPPERCASE -> value.uppercase()
    EsDeLetterCase.LOWERCASE -> value.lowercase()
    EsDeLetterCase.CAPITALIZE -> value.split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    EsDeLetterCase.NONE, EsDeLetterCase.UNDEFINED -> value
}

/**
 * Real leading indicator for a gamelist row (GamelistBase.cpp:916-943).
 *
 * ONE honest substitution: real ES-DE's `symbols` mode draws Font
 * Awesome's own U+F005 star from the `fontawesome-webfont.ttf` it bundles
 * in its own resources. droidtop bundles no Font Awesome (a theme's font
 * is whatever the theme ships, and none of them carry that private-use
 * codepoint), so emitting U+F005 would draw a missing-glyph box on every
 * favorite. droidtop emits U+2605 BLACK STAR instead -- the same
 * behavior, drawn from a codepoint the platform's own font fallback
 * actually covers. The `ascii` mode is exact.
 *
 * Real ES-DE's folder and folder-link indicators are deliberately absent:
 * droidtop's ROM scan is flat and has no folder entries to mark, the same
 * standing gap the `folder` badge slot documents.
 */
fun esDeIndicatorPrefix(indicators: EsDeIndicators, isFavorite: Boolean): String = when {
    !isFavorite || indicators == EsDeIndicators.NONE -> ""
    indicators == EsDeIndicators.ASCII -> "* "
    else -> "★  "
}

/**
 * Which rows a textlist actually draws, and where its selector sits --
 * real `TextListComponent::render()`'s own opening arithmetic
 * (TextListComponent.h:305-330).
 */
data class EsDeTextListWindow(
    val startEntry: Int,
    val listCutoff: Int,
    val entrySize: Float,
    val screenCount: Int,
    val selectorX: Float,
    val selectorY: Float,
) {
    val visibleIndices: IntRange get() = startEntry until listCutoff
    /** Y of a visible row's top edge, in the textlist's own space. */
    fun rowY(index: Int): Float = (index - startEntry) * entrySize
}

fun layoutEsDeTextList(
    config: EsDeTextListConfig,
    height: Float,
    cursor: Int,
    entryCount: Int,
): EsDeTextListWindow {
    val entrySize = config.entrySize
    // TextListComponent.h:311-316 -- note the half line-spacing slack,
    // which is what lets a list show one more row than a naive
    // height/entrySize division would.
    val lineSpacingHeight = (config.fontSize * config.lineSpacing) - config.fontSize
    val screenCount = if (entrySize <= 0f) 0 else floor((height + lineSpacingHeight / 2f) / entrySize).toInt()

    var startEntry = 0
    if (entryCount >= screenCount && screenCount > 0) {
        startEntry = cursor - screenCount / 2
        if (startEntry < 0) startEntry = 0
        if (startEntry >= entryCount - screenCount) startEntry = entryCount - screenCount
    }
    if (startEntry < 0) startEntry = 0
    val listCutoff = minOf(startEntry + screenCount, entryCount)

    return EsDeTextListWindow(
        startEntry = startEntry,
        listCutoff = listCutoff,
        entrySize = entrySize,
        screenCount = screenCount,
        // TextListComponent.h:333-341 -- the selector is drawn ONCE, at
        // the cursor's own row, not as a per-row background.
        selectorX = config.selectorHorizontalOffset,
        selectorY = (cursor - startEntry) * entrySize + config.selectorVerticalOffset,
    )
}
