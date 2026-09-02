package dev.droidtop.library.theme

/**
 * Clean, renderer-independent data model for a parsed ES-DE theme.xml file
 * -- deliberately modeled after the real `ThemeData::ThemeElement`/
 * `ThemeView`/`ElementPropertyType` structures in ES-DE's own open-source
 * `es-core/src/ThemeData.h`/`.cpp` (gitlab.com/es-de/emulationstation-de,
 * MIT-licensed), NOT a copy of that code -- their actual parser
 * (`ThemeData.cpp`) directly includes and instantiates `ImageComponent`/
 * `TextComponent` (real GPU-texture-backed renderer classes), so it isn't
 * cleanly separable from their SDL2/OpenGL engine. This is a clean-room
 * reimplementation of the same real schema (informed by reading their
 * actual code -- property names, types, and parsing rules verified
 * against it, not guessed), producing droidtop's own independent data
 * model that droidtop's own Compose code renders.
 *
 * Covers all 16 real ES-DE element types (`ThemeData::sElementMap`, fetched
 * and read directly this session): `image`, `text`, `carousel`, `grid`,
 * `textlist` (the three real types a theme can use as a view's *primary
 * browsing component* -- a system/gamelist view picks exactly one; see
 * [EsDeThemeView.primaryListElement] -- plus the two universal decoration
 * types), and `video`, `animation`, `badges`, `datetime`, `gamelistinfo`,
 * `rating`, `gameselector`, `helpsystem`, `systemstatus`, `clock`, `sound`.
 * Each type is scoped to its own most structurally load-bearing properties
 * (position, size, path, color, text, font, rotation, opacity, visibility,
 * stacking order, item geometry), not literally every property ES-DE
 * supports (`carousel` alone has ~60 in the real schema) -- an
 * unrecognized property is skipped, not a parse failure (see
 * [dev.droidtop.library.theme.EsDeThemeParser]'s own doc comment).
 *
 * Parsing coverage and rendering coverage are real, separate things: every
 * type here parses into the real data model, but rendering
 * ([dev.droidtop.shell.gamepad.theme.EsDeThemedView]) is still honestly
 * partial. `rating`/`datetime` are fully real (LibraryEntry.rating/
 * releaseDate exist, populated by real ScreenScraper/TheGamesDB scrapes).
 * `badges` renders the full real flexbox layout for eight of real
 * ES-DE's nine real badge slot types (favorite/completed/kidgame/broken/
 * controller/altemulator/manual/collection -- all real `LibraryEntry`/
 * `GameMetadataEntity`/`CollectionMemberEntity` fields, user-editable via
 * `GameMetadataEditor`/`CollectionMembershipEditor`); `folder` stays
 * unrendered (no folder-entry concept in droidtop's data model -- see
 * `BADGE_SLOTS`' own doc comment). `gamelistinfo`
 * is likewise PARTIALLY real -- the plain "N games" + favorites-count
 * case renders (real per-system counts, [EsDeThemedGamelistInfo]'s own
 * doc comment); the filtered and folder-entered cases don't, since
 * droidtop's themed gamelist screens have no filter UI or folder concept
 * at all yet. `systemstatus` is likewise PARTIALLY real -- wifi/
 * cellular/battery render (droidtop genuinely IS the host device, real
 * live status); bluetooth doesn't (a dangerous runtime permission not
 * requested without checking with the user first).
 */
data class EsDeTheme(
    val variables: Map<String, String>,
    val views: Map<String, EsDeThemeView>,
)

/** One view (real ES-DE views: "system", "gamelist", or "all" applying to both). */
data class EsDeThemeView(
    val elements: Map<String, EsDeThemeElement>,
)

/** Real ES-DE element types a view can use to actually list/browse items with -- exactly one per view in practice. */
val ES_DE_PRIMARY_LIST_TYPES = setOf("carousel", "grid", "textlist")

/**
 * One themed element -- [key] matches ES-DE's own real convention of
 * `"<elementType>_<name>"` (e.g. "image_logo"), since a `name` attribute
 * can be comma/whitespace-separated to define several same-configured
 * elements at once, same as the real parser does.
 */
data class EsDeThemeElement(
    val type: String,
    val key: String,
    val properties: Map<String, EsDeThemeValue>,
) {
    inline fun <reified T : EsDeThemeValue> valueOrNull(name: String): T? = properties[name] as? T
}

/**
 * Which real browsing shape (carousel/grid/textlist) this view actually
 * uses, per ES-DE's own convention -- a real theme.xml declares AT MOST
 * one of these per view, never a hardcoded app-level choice. For the
 * "system" view it's effectively always present (confirmed against the
 * bundled DEcaffe theme's own real system view: one `<carousel>`).
 * For "gamelist" it's genuinely OPTIONAL -- confirmed directly against
 * DEcaffe's own real gamelist view, which declares NEITHER a list widget
 * NOR a `<gameselector>` at all: every element there implicitly binds to
 * game index 0, relying on real ES-DE's own always-present underlying
 * per-game navigation cursor rather than any visual widget (real ES-DE
 * itself always tracks a "current game" regardless of whether the theme
 * renders anything for it). ArtBookNext's own gamelist view, by contrast,
 * DOES declare a real `<textlist>`/`<grid>` -- both are real, valid
 * theme designs. A `null` result here for a gamelist view is a normal,
 * expected case, not a parse gap -- see `EsDeThemedView`'s own
 * `focusedGameIndex` parameter for how droidtop drives navigation in the
 * no-widget case.
 */
fun EsDeThemeView.primaryListElement(): EsDeThemeElement? =
    elements.values.firstOrNull { it.type in ES_DE_PRIMARY_LIST_TYPES }

/*
 * Typed property accessors over a NULLABLE element.
 *
 * Every layout port in this package reads a theme element the same way:
 * "this property, at this real ES-DE property type, or null if the theme
 * didn't set it" -- and a null element means "all defaults", because a
 * view may legitimately declare no such element at all. These were six
 * identical local helper declarations repeated inside esDeCarouselConfig,
 * esDeGridConfig and esDeTextListConfig; one declaration each now, next
 * to [EsDeThemeElement.valueOrNull], which is what they all wrap.
 */

/** Real FLOAT property, or null when the theme didn't set it. */
fun EsDeThemeElement?.floatOrNull(name: String): Float? =
    this?.valueOrNull<EsDeThemeValue.FloatValue>(name)?.value

/** Real BOOLEAN property, or null when the theme didn't set it. */
fun EsDeThemeElement?.boolOrNull(name: String): Boolean? =
    this?.valueOrNull<EsDeThemeValue.Bool>(name)?.value

/** Real STRING property, or null when the theme didn't set it. */
fun EsDeThemeElement?.strOrNull(name: String): String? =
    this?.valueOrNull<EsDeThemeValue.Str>(name)?.value

/** Real PATH property, already resolved against the theme dir, or null when unset. */
fun EsDeThemeElement?.pathOrNull(name: String): String? =
    this?.valueOrNull<EsDeThemeValue.Path>(name)?.resolved

/** Real NORMALIZED_PAIR property, or null when the theme didn't set it. */
fun EsDeThemeElement?.pairOrNull(name: String): EsDeThemeValue.Pair? =
    this?.valueOrNull<EsDeThemeValue.Pair>(name)

/** Real COLOR property as packed RRGGBBAA, or null when the theme didn't set it. */
fun EsDeThemeElement?.colorOrNull(name: String): Long? =
    this?.valueOrNull<EsDeThemeValue.Color>(name)?.argbLikeRgba

/** Real UNSIGNED_INTEGER property, or null when the theme didn't set it. */
fun EsDeThemeElement?.uintOrNull(name: String): Long? =
    this?.valueOrNull<EsDeThemeValue.UInt>(name)?.value

sealed interface EsDeThemeValue {
    data class Pair(val x: Float, val y: Float) : EsDeThemeValue
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) : EsDeThemeValue
    data class Path(val resolved: String) : EsDeThemeValue
    data class Str(val value: String) : EsDeThemeValue
    /** Packed RRGGBBAA, same bit layout as ES-DE's own `getHexColor` (6-digit input gets 0xFF alpha appended). */
    data class Color(val argbLikeRgba: Long) : EsDeThemeValue
    data class UInt(val value: Long) : EsDeThemeValue
    data class FloatValue(val value: Float) : EsDeThemeValue
    data class Bool(val value: Boolean) : EsDeThemeValue
}

/** Property -> real ES-DE value type, restricted to the element types and properties this parser actually covers. See [EsDeTheme]'s own doc comment for what's deliberately out of scope. */
internal enum class EsDePropertyType { NORMALIZED_PAIR, PATH, STRING, COLOR, UNSIGNED_INTEGER, FLOAT, BOOLEAN }

/**
 * Real element/property schema -- a VERBATIM transcription of ES-DE's own
 * `ThemeData::sElementMap` (`es-core/src/ThemeData.cpp:156-645`, a real
 * local clone kept at /root/es-de-reference, read directly line-by-line
 * for this transcription, not guessed or partially covered). All 16 real
 * element types, every real property each one declares, with its real
 * type. This replaces an earlier, real, confirmed bug: a hand-picked
 * SUBSET of each element's real properties (e.g. carousel was missing
 * `staticImage`/`imageColor`/`selectedItemMargins`/`itemVerticalAlignment`
 * entirely) -- a renderer reading one of those missing keys via
 * `valueOrNull` got a silent `null` indistinguishable from "theme didn't
 * set this," not a parse failure, so the gap was invisible without
 * diffing against real ES-DE source directly. Rendering coverage for a
 * newly-added property is still real, separate, incremental work (see
 * [EsDeTheme]'s own doc comment) -- this schema's job is only to make
 * sure the DATA survives parsing, so a renderer pass can actually reach
 * it instead of finding null where the theme set a real value.
 */
internal val ES_DE_ELEMENT_SCHEMA: Map<String, Map<String, EsDePropertyType>> = mapOf(
    // ThemeData.cpp:158-231
    "carousel" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "type" to EsDePropertyType.STRING,
        "staticImage" to EsDePropertyType.PATH,
        "imageType" to EsDePropertyType.STRING,
        "defaultImage" to EsDePropertyType.PATH,
        "defaultFolderImage" to EsDePropertyType.PATH,
        "maxItemCount" to EsDePropertyType.FLOAT,
        "itemsBeforeCenter" to EsDePropertyType.UNSIGNED_INTEGER,
        "itemsAfterCenter" to EsDePropertyType.UNSIGNED_INTEGER,
        "itemStacking" to EsDePropertyType.STRING,
        "selectedItemMargins" to EsDePropertyType.NORMALIZED_PAIR,
        "selectedItemOffset" to EsDePropertyType.NORMALIZED_PAIR,
        "itemSize" to EsDePropertyType.NORMALIZED_PAIR,
        "itemScale" to EsDePropertyType.FLOAT,
        "itemLinearScale" to EsDePropertyType.NORMALIZED_PAIR,
        "itemLinearSpacing" to EsDePropertyType.NORMALIZED_PAIR,
        "itemRotation" to EsDePropertyType.FLOAT,
        "itemRotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "itemAxisHorizontal" to EsDePropertyType.BOOLEAN,
        "itemAxisRotation" to EsDePropertyType.FLOAT,
        "imageFit" to EsDePropertyType.STRING,
        "imageCropPos" to EsDePropertyType.NORMALIZED_PAIR,
        "imageInterpolation" to EsDePropertyType.STRING,
        "imageCornerRadius" to EsDePropertyType.FLOAT,
        "imageColor" to EsDePropertyType.COLOR,
        "imageColorEnd" to EsDePropertyType.COLOR,
        "imageGradientType" to EsDePropertyType.STRING,
        "imageSelectedColor" to EsDePropertyType.COLOR,
        "imageSelectedColorEnd" to EsDePropertyType.COLOR,
        "imageSelectedGradientType" to EsDePropertyType.STRING,
        "imageBrightness" to EsDePropertyType.FLOAT,
        "imageSaturation" to EsDePropertyType.FLOAT,
        "itemTransitions" to EsDePropertyType.STRING,
        "itemDiagonalOffset" to EsDePropertyType.FLOAT,
        "itemHorizontalAlignment" to EsDePropertyType.STRING,
        "itemVerticalAlignment" to EsDePropertyType.STRING,
        "wheelHorizontalAlignment" to EsDePropertyType.STRING,
        "wheelVerticalAlignment" to EsDePropertyType.STRING,
        "horizontalOffset" to EsDePropertyType.FLOAT,
        "verticalOffset" to EsDePropertyType.FLOAT,
        "reflections" to EsDePropertyType.BOOLEAN,
        "reflectionsOpacity" to EsDePropertyType.FLOAT,
        "reflectionsFalloff" to EsDePropertyType.FLOAT,
        "unfocusedItemOpacity" to EsDePropertyType.FLOAT,
        "unfocusedItemSaturation" to EsDePropertyType.FLOAT,
        "unfocusedItemDimming" to EsDePropertyType.FLOAT,
        "fastScrolling" to EsDePropertyType.BOOLEAN,
        "color" to EsDePropertyType.COLOR,
        "colorEnd" to EsDePropertyType.COLOR,
        "gradientType" to EsDePropertyType.STRING,
        "text" to EsDePropertyType.STRING,
        "textRelativeScale" to EsDePropertyType.FLOAT,
        "textBackgroundCornerRadius" to EsDePropertyType.FLOAT,
        "textColor" to EsDePropertyType.COLOR,
        "textBackgroundColor" to EsDePropertyType.COLOR,
        "textSelectedColor" to EsDePropertyType.COLOR,
        "textSelectedBackgroundColor" to EsDePropertyType.COLOR,
        "textHorizontalScrolling" to EsDePropertyType.BOOLEAN,
        "textHorizontalScrollSpeed" to EsDePropertyType.FLOAT,
        "textHorizontalScrollDelay" to EsDePropertyType.FLOAT,
        "textHorizontalScrollGap" to EsDePropertyType.FLOAT,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "letterCaseAutoCollections" to EsDePropertyType.STRING,
        "letterCaseCustomCollections" to EsDePropertyType.STRING,
        "lineSpacing" to EsDePropertyType.FLOAT,
        "systemNameSuffix" to EsDePropertyType.BOOLEAN,
        "letterCaseSystemNameSuffix" to EsDePropertyType.STRING,
        "fadeAbovePrimary" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:232-296
    "grid" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "staticImage" to EsDePropertyType.PATH,
        "imageType" to EsDePropertyType.STRING,
        "defaultImage" to EsDePropertyType.PATH,
        "defaultFolderImage" to EsDePropertyType.PATH,
        "itemSize" to EsDePropertyType.NORMALIZED_PAIR,
        "itemScale" to EsDePropertyType.FLOAT,
        "itemSpacing" to EsDePropertyType.NORMALIZED_PAIR,
        "scaleInwards" to EsDePropertyType.BOOLEAN,
        "fractionalRows" to EsDePropertyType.BOOLEAN,
        "itemTransitions" to EsDePropertyType.STRING,
        "rowTransitions" to EsDePropertyType.STRING,
        "unfocusedItemOpacity" to EsDePropertyType.FLOAT,
        "unfocusedItemSaturation" to EsDePropertyType.FLOAT,
        "unfocusedItemDimming" to EsDePropertyType.FLOAT,
        "imageFit" to EsDePropertyType.STRING,
        "imageCropPos" to EsDePropertyType.NORMALIZED_PAIR,
        "imageInterpolation" to EsDePropertyType.STRING,
        "imageRelativeScale" to EsDePropertyType.FLOAT,
        "imageCornerRadius" to EsDePropertyType.FLOAT,
        "imageColor" to EsDePropertyType.COLOR,
        "imageColorEnd" to EsDePropertyType.COLOR,
        "imageGradientType" to EsDePropertyType.STRING,
        "imageSelectedColor" to EsDePropertyType.COLOR,
        "imageSelectedColorEnd" to EsDePropertyType.COLOR,
        "imageSelectedGradientType" to EsDePropertyType.STRING,
        "imageBrightness" to EsDePropertyType.FLOAT,
        "imageSaturation" to EsDePropertyType.FLOAT,
        "backgroundImage" to EsDePropertyType.PATH,
        "backgroundRelativeScale" to EsDePropertyType.FLOAT,
        "backgroundCornerRadius" to EsDePropertyType.FLOAT,
        "backgroundColor" to EsDePropertyType.COLOR,
        "backgroundColorEnd" to EsDePropertyType.COLOR,
        "backgroundGradientType" to EsDePropertyType.STRING,
        "selectorImage" to EsDePropertyType.PATH,
        "selectorRelativeScale" to EsDePropertyType.FLOAT,
        "selectorCornerRadius" to EsDePropertyType.FLOAT,
        "selectorLayer" to EsDePropertyType.STRING,
        "selectorColor" to EsDePropertyType.COLOR,
        "selectorColorEnd" to EsDePropertyType.COLOR,
        "selectorGradientType" to EsDePropertyType.STRING,
        "text" to EsDePropertyType.STRING,
        "textRelativeScale" to EsDePropertyType.FLOAT,
        "textBackgroundCornerRadius" to EsDePropertyType.FLOAT,
        "textColor" to EsDePropertyType.COLOR,
        "textBackgroundColor" to EsDePropertyType.COLOR,
        "textSelectedColor" to EsDePropertyType.COLOR,
        "textSelectedBackgroundColor" to EsDePropertyType.COLOR,
        "textHorizontalScrolling" to EsDePropertyType.BOOLEAN,
        "textHorizontalScrollSpeed" to EsDePropertyType.FLOAT,
        "textHorizontalScrollDelay" to EsDePropertyType.FLOAT,
        "textHorizontalScrollGap" to EsDePropertyType.FLOAT,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "letterCaseAutoCollections" to EsDePropertyType.STRING,
        "letterCaseCustomCollections" to EsDePropertyType.STRING,
        "lineSpacing" to EsDePropertyType.FLOAT,
        "systemNameSuffix" to EsDePropertyType.BOOLEAN,
        "letterCaseSystemNameSuffix" to EsDePropertyType.STRING,
        "fadeAbovePrimary" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:297-335
    "textlist" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "selectorWidth" to EsDePropertyType.FLOAT,
        "selectorHeight" to EsDePropertyType.FLOAT,
        "selectorHorizontalOffset" to EsDePropertyType.FLOAT,
        "selectorVerticalOffset" to EsDePropertyType.FLOAT,
        "selectorColor" to EsDePropertyType.COLOR,
        "selectorColorEnd" to EsDePropertyType.COLOR,
        "selectorGradientType" to EsDePropertyType.STRING,
        "selectorImagePath" to EsDePropertyType.PATH,
        "selectorImageTile" to EsDePropertyType.BOOLEAN,
        "primaryColor" to EsDePropertyType.COLOR,
        "secondaryColor" to EsDePropertyType.COLOR,
        "selectedColor" to EsDePropertyType.COLOR,
        "selectedSecondaryColor" to EsDePropertyType.COLOR,
        "selectedBackgroundColor" to EsDePropertyType.COLOR,
        "selectedSecondaryBackgroundColor" to EsDePropertyType.COLOR,
        "selectedBackgroundMargins" to EsDePropertyType.NORMALIZED_PAIR,
        "selectedBackgroundCornerRadius" to EsDePropertyType.FLOAT,
        "textHorizontalScrolling" to EsDePropertyType.BOOLEAN,
        "textHorizontalScrollSpeed" to EsDePropertyType.FLOAT,
        "textHorizontalScrollDelay" to EsDePropertyType.FLOAT,
        "textHorizontalScrollGap" to EsDePropertyType.FLOAT,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "horizontalMargin" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "letterCaseAutoCollections" to EsDePropertyType.STRING,
        "letterCaseCustomCollections" to EsDePropertyType.STRING,
        "lineSpacing" to EsDePropertyType.FLOAT,
        "indicators" to EsDePropertyType.STRING,
        "collectionIndicators" to EsDePropertyType.STRING,
        "systemNameSuffix" to EsDePropertyType.BOOLEAN,
        "letterCaseSystemNameSuffix" to EsDePropertyType.STRING,
        "fadeAbovePrimary" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:336-372
    "image" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "maxSize" to EsDePropertyType.NORMALIZED_PAIR,
        "cropSize" to EsDePropertyType.NORMALIZED_PAIR,
        "cropPos" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "scaleFactor" to EsDePropertyType.FLOAT,
        "stationary" to EsDePropertyType.STRING,
        "renderDuringTransitions" to EsDePropertyType.BOOLEAN,
        "flipHorizontal" to EsDePropertyType.BOOLEAN,
        "flipVertical" to EsDePropertyType.BOOLEAN,
        "path" to EsDePropertyType.PATH,
        "gameOverridePath" to EsDePropertyType.PATH,
        "default" to EsDePropertyType.PATH,
        "imageType" to EsDePropertyType.STRING,
        "metadataElement" to EsDePropertyType.BOOLEAN,
        "gameselector" to EsDePropertyType.STRING,
        "gameselectorEntry" to EsDePropertyType.UNSIGNED_INTEGER,
        "tile" to EsDePropertyType.BOOLEAN,
        "tileSize" to EsDePropertyType.NORMALIZED_PAIR,
        "tileHorizontalAlignment" to EsDePropertyType.STRING,
        "tileVerticalAlignment" to EsDePropertyType.STRING,
        "interpolation" to EsDePropertyType.STRING,
        "mipmap" to EsDePropertyType.BOOLEAN,
        "cornerRadius" to EsDePropertyType.FLOAT,
        "color" to EsDePropertyType.COLOR,
        "colorEnd" to EsDePropertyType.COLOR,
        "gradientType" to EsDePropertyType.STRING,
        "scrollFadeIn" to EsDePropertyType.BOOLEAN,
        "brightness" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "saturation" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:373-414
    "video" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "maxSize" to EsDePropertyType.NORMALIZED_PAIR,
        "cropSize" to EsDePropertyType.NORMALIZED_PAIR,
        "cropPos" to EsDePropertyType.NORMALIZED_PAIR,
        "imageSize" to EsDePropertyType.NORMALIZED_PAIR,
        "imageMaxSize" to EsDePropertyType.NORMALIZED_PAIR,
        "imageCropSize" to EsDePropertyType.NORMALIZED_PAIR,
        "imageCropPos" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "stationary" to EsDePropertyType.STRING,
        "path" to EsDePropertyType.PATH,
        "default" to EsDePropertyType.PATH,
        "defaultImage" to EsDePropertyType.PATH,
        "imageType" to EsDePropertyType.STRING,
        "metadataElement" to EsDePropertyType.BOOLEAN,
        "gameselector" to EsDePropertyType.STRING,
        "gameselectorEntry" to EsDePropertyType.UNSIGNED_INTEGER,
        "iterationCount" to EsDePropertyType.UNSIGNED_INTEGER,
        "onIterationsDone" to EsDePropertyType.STRING,
        "audio" to EsDePropertyType.BOOLEAN,
        "interpolation" to EsDePropertyType.STRING,
        "imageCornerRadius" to EsDePropertyType.FLOAT,
        "videoCornerRadius" to EsDePropertyType.FLOAT,
        "color" to EsDePropertyType.COLOR,
        "colorEnd" to EsDePropertyType.COLOR,
        "gradientType" to EsDePropertyType.STRING,
        "pillarboxes" to EsDePropertyType.BOOLEAN,
        "pillarboxThreshold" to EsDePropertyType.NORMALIZED_PAIR,
        "scanlines" to EsDePropertyType.BOOLEAN,
        "delay" to EsDePropertyType.FLOAT,
        "fadeInType" to EsDePropertyType.STRING,
        "fadeInTime" to EsDePropertyType.FLOAT,
        "scrollFadeIn" to EsDePropertyType.BOOLEAN,
        "brightness" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "saturation" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:415-438
    "animation" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "maxSize" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "scaleFactor" to EsDePropertyType.FLOAT,
        "stationary" to EsDePropertyType.STRING,
        "metadataElement" to EsDePropertyType.BOOLEAN,
        "path" to EsDePropertyType.PATH,
        "speed" to EsDePropertyType.FLOAT,
        "direction" to EsDePropertyType.STRING,
        "iterationCount" to EsDePropertyType.UNSIGNED_INTEGER,
        "interpolation" to EsDePropertyType.STRING,
        "cornerRadius" to EsDePropertyType.FLOAT,
        "color" to EsDePropertyType.COLOR,
        "colorEnd" to EsDePropertyType.COLOR,
        "gradientType" to EsDePropertyType.STRING,
        "brightness" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "saturation" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:439-471. Real ES-DE keys a slot-specific custom icon
    // by an XML ATTRIBUTE on the property tag itself (e.g. `<customBadgeIcon
    // badge="kidgame">`), not by a distinct property name per slot --
    // confirmed against sPropertyAttributeMap (ThemeData.cpp:144-154) and
    // sElementMap itself, which declares only ONE `customBadgeIcon`/
    // `customControllerIcon` property here, real type PATH. The schema
    // below matches that exactly; [EsDeThemeParser.parseElementProperties]
    // is what expands the attribute into the real per-slot/per-controller
    // storage key (`badge_$slot`/`controller_$shortName`) this renderer
    // reads -- see that function's own doc comment for the real,
    // previously-confirmed parsing bug this fixes (the parser never read
    // ANY property tag's attributes at all, so a real theme's own
    // `<customBadgeIcon badge="...">` declarations were silently
    // unparseable regardless of what this schema declared).
    "badges" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "stationary" to EsDePropertyType.STRING,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "direction" to EsDePropertyType.STRING,
        "lines" to EsDePropertyType.UNSIGNED_INTEGER,
        "itemsPerLine" to EsDePropertyType.UNSIGNED_INTEGER,
        "itemMargin" to EsDePropertyType.NORMALIZED_PAIR,
        "slots" to EsDePropertyType.STRING,
        "controllerPos" to EsDePropertyType.NORMALIZED_PAIR,
        "controllerSize" to EsDePropertyType.FLOAT,
        "customBadgeIcon" to EsDePropertyType.PATH,
        "customControllerIcon" to EsDePropertyType.PATH,
        "folderLinkPos" to EsDePropertyType.NORMALIZED_PAIR,
        "folderLinkSize" to EsDePropertyType.FLOAT,
        "customFolderLinkIcon" to EsDePropertyType.PATH,
        "badgeIconColor" to EsDePropertyType.COLOR,
        "badgeIconColorEnd" to EsDePropertyType.COLOR,
        "badgeIconGradientType" to EsDePropertyType.STRING,
        "controllerIconColor" to EsDePropertyType.COLOR,
        "controllerIconColorEnd" to EsDePropertyType.COLOR,
        "controllerIconGradientType" to EsDePropertyType.STRING,
        "folderLinkIconColor" to EsDePropertyType.COLOR,
        "folderLinkIconColorEnd" to EsDePropertyType.COLOR,
        "folderLinkIconGradientType" to EsDePropertyType.STRING,
        "interpolation" to EsDePropertyType.STRING,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:472-507
    "text" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "stationary" to EsDePropertyType.STRING,
        "text" to EsDePropertyType.STRING,
        "systemdata" to EsDePropertyType.STRING,
        "metadata" to EsDePropertyType.STRING,
        "defaultValue" to EsDePropertyType.STRING,
        "systemNameSuffix" to EsDePropertyType.BOOLEAN,
        "letterCaseSystemNameSuffix" to EsDePropertyType.STRING,
        "metadataElement" to EsDePropertyType.BOOLEAN,
        "gameselector" to EsDePropertyType.STRING,
        "gameselectorEntry" to EsDePropertyType.UNSIGNED_INTEGER,
        "container" to EsDePropertyType.BOOLEAN,
        "containerType" to EsDePropertyType.STRING,
        "containerVerticalSnap" to EsDePropertyType.BOOLEAN,
        "containerScrollSpeed" to EsDePropertyType.FLOAT,
        "containerStartDelay" to EsDePropertyType.FLOAT,
        "containerResetDelay" to EsDePropertyType.FLOAT,
        "containerScrollGap" to EsDePropertyType.FLOAT,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "verticalAlignment" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "backgroundMargins" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundCornerRadius" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "lineSpacing" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:508-533
    "datetime" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "stationary" to EsDePropertyType.STRING,
        "metadata" to EsDePropertyType.STRING,
        "defaultValue" to EsDePropertyType.STRING,
        "gameselector" to EsDePropertyType.STRING,
        "gameselectorEntry" to EsDePropertyType.UNSIGNED_INTEGER,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "verticalAlignment" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "backgroundMargins" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundCornerRadius" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "lineSpacing" to EsDePropertyType.FLOAT,
        "format" to EsDePropertyType.STRING,
        "displayRelative" to EsDePropertyType.BOOLEAN,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:534-549
    "gamelistinfo" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "stationary" to EsDePropertyType.STRING,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "verticalAlignment" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:550-567
    "rating" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "stationary" to EsDePropertyType.STRING,
        "hideIfZero" to EsDePropertyType.BOOLEAN,
        "gameselector" to EsDePropertyType.STRING,
        "gameselectorEntry" to EsDePropertyType.UNSIGNED_INTEGER,
        "interpolation" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "filledPath" to EsDePropertyType.PATH,
        "unfilledPath" to EsDePropertyType.PATH,
        "overlay" to EsDePropertyType.BOOLEAN,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:568-571. Not a positioned visual element in real
    // ES-DE either -- selects which game(s) other elements' gameselector/
    // gameselectorEntry properties reference. Parsed for completeness; no
    // rendering (there's nothing to draw).
    "gameselector" to mapOf(
        "selection" to EsDePropertyType.STRING,
        "gameCount" to EsDePropertyType.UNSIGNED_INTEGER,
        "allowDuplicates" to EsDePropertyType.BOOLEAN,
    ),
    // ThemeData.cpp:572-603
    "helpsystem" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "posDimmed" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "originDimmed" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "textColor" to EsDePropertyType.COLOR,
        "textColorDimmed" to EsDePropertyType.COLOR,
        "iconColor" to EsDePropertyType.COLOR,
        "iconColorDimmed" to EsDePropertyType.COLOR,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "fontSizeDimmed" to EsDePropertyType.FLOAT,
        "scope" to EsDePropertyType.STRING,
        "entries" to EsDePropertyType.STRING,
        "entryLayout" to EsDePropertyType.STRING,
        "entryRelativeScale" to EsDePropertyType.FLOAT,
        "entrySpacing" to EsDePropertyType.FLOAT,
        "entrySpacingDimmed" to EsDePropertyType.FLOAT,
        "iconTextSpacing" to EsDePropertyType.FLOAT,
        "iconTextSpacingDimmed" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "backgroundColor" to EsDePropertyType.COLOR,
        "backgroundColorEnd" to EsDePropertyType.COLOR,
        "backgroundGradientType" to EsDePropertyType.STRING,
        "backgroundHorizontalPadding" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundVerticalPadding" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundCornerRadius" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "opacityDimmed" to EsDePropertyType.FLOAT,
        "customButtonIcon" to EsDePropertyType.PATH,
    ),
    // ThemeData.cpp:604-623
    "systemstatus" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "height" to EsDePropertyType.FLOAT,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "scope" to EsDePropertyType.STRING,
        "fontPath" to EsDePropertyType.PATH,
        "textRelativeScale" to EsDePropertyType.FLOAT,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "backgroundColorEnd" to EsDePropertyType.COLOR,
        "backgroundGradientType" to EsDePropertyType.STRING,
        "backgroundHorizontalPadding" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundVerticalPadding" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundCornerRadius" to EsDePropertyType.FLOAT,
        "entries" to EsDePropertyType.STRING,
        "entrySpacing" to EsDePropertyType.FLOAT,
        "customIcon" to EsDePropertyType.PATH,
        "opacity" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:624-643
    "clock" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "rotationOrigin" to EsDePropertyType.NORMALIZED_PAIR,
        "scope" to EsDePropertyType.STRING,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "verticalAlignment" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "backgroundColorEnd" to EsDePropertyType.COLOR,
        "backgroundGradientType" to EsDePropertyType.STRING,
        "backgroundHorizontalPadding" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundVerticalPadding" to EsDePropertyType.NORMALIZED_PAIR,
        "backgroundCornerRadius" to EsDePropertyType.FLOAT,
        "format" to EsDePropertyType.STRING,
        "opacity" to EsDePropertyType.FLOAT,
    ),
    // ThemeData.cpp:644-645. Not a visual element at all in real ES-DE
    // (navigation-sound declarations, read back by element lookup in
    // Sound::getFromTheme) -- consumed by shell-gamepad's own
    // EsDeNavigationSounds, never by a renderer.
    "sound" to mapOf(
        "path" to EsDePropertyType.PATH,
    ),
)

/**
 * Real ES-DE `sPropertyAttributeMap` (ThemeData.cpp:144-154) -- a handful
 * of properties are keyed not just by tag name but by an XML ATTRIBUTE on
 * that same tag (`<customBadgeIcon badge="kidgame">./icon.svg
 * </customBadgeIcon>`), letting one property name repeat with a different
 * attribute value per real declaration. Maps the property's real tag name
 * to (real attribute name, storage-key prefix this parser generates --
 * `badge_$attrValue`/`controller_$attrValue`, matching what
 * [EsDeThemedBadges]/[dev.droidtop.library.theme.EsDeControllers] already
 * read). `customButtonIcon` (helpsystem) and `customIcon` (systemstatus)
 * are real sPropertyAttributeMap entries too, but droidtop renders no
 * per-entry icon for either yet -- included for parsing completeness, not
 * silently dropped, but with no renderer consumer to wire a storage-key
 * convention against yet.
 */
internal val ES_DE_PROPERTY_ATTRIBUTE_MAP: Map<String, kotlin.Pair<String, String>> = mapOf(
    "customBadgeIcon" to ("badge" to "badge"),
    "customControllerIcon" to ("controller" to "controller"),
    "customButtonIcon" to ("button" to "customButtonIcon"),
    "customIcon" to ("icon" to "customIcon"),
)

internal val ES_DE_SUPPORTED_VIEWS = setOf("all", "system", "gamelist")
