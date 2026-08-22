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
 * Deliberately scoped to the two universal element types every real theme
 * uses (`image`, `text`) plus `carousel` (droidtop's own Games system list
 * already uses this shape) -- not the full ES-DE schema (grid, textlist,
 * video, animation, datetime, rating, badges, helpsystem, systemstatus,
 * sound all exist in the real schema and aren't covered here yet). Also
 * scoped to each covered element type's most structurally load-bearing
 * properties (position, size, path, color, text, font, rotation, opacity,
 * visibility, stacking order), not literally every property ES-DE
 * supports (`carousel` alone has ~60 in the real schema).
 */
data class EsDeTheme(
    val variables: Map<String, String>,
    val views: Map<String, EsDeThemeView>,
)

/** One view (real ES-DE views: "system", "gamelist", or "all" applying to both). */
data class EsDeThemeView(
    val elements: Map<String, EsDeThemeElement>,
)

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
 * Real element/property schema, transcribed from ES-DE's own
 * `ThemeData::sElementMap` (verified against the actual source, not
 * guessed) -- restricted to `image`, `text`, and `carousel`, and within
 * those, to the properties this parser actually implements.
 */
internal val ES_DE_ELEMENT_SCHEMA: Map<String, Map<String, EsDePropertyType>> = mapOf(
    "image" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "path" to EsDePropertyType.PATH,
        "default" to EsDePropertyType.PATH,
        "color" to EsDePropertyType.COLOR,
        "cornerRadius" to EsDePropertyType.FLOAT,
        "brightness" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "saturation" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    "text" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "text" to EsDePropertyType.STRING,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "verticalAlignment" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "letterCase" to EsDePropertyType.STRING,
        "lineSpacing" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    "carousel" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "type" to EsDePropertyType.STRING,
        "itemSize" to EsDePropertyType.NORMALIZED_PAIR,
        "itemScale" to EsDePropertyType.FLOAT,
        "color" to EsDePropertyType.COLOR,
        "text" to EsDePropertyType.STRING,
        "textColor" to EsDePropertyType.COLOR,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
)

internal val ES_DE_SUPPORTED_VIEWS = setOf("all", "system", "gamelist")
