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
 * ([dev.droidtop.shell.gamepad.theme.EsDeThemedView]) is honestly partial
 * for `badges`/`rating`/`gamelistinfo`/`gameselector` -- they need real
 * per-game metadata (favorites, ratings, play counts) droidtop's own
 * [dev.droidtop.library.LibraryEntry] doesn't model yet, so rendering them
 * would mean fabricating data, not a real gap in the parser itself.
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
 * uses, per ES-DE's own convention: a real theme.xml declares exactly one
 * of these per system/gamelist view (confirmed against the bundled DEcaffe
 * theme's own real system view, which declares one `<carousel>` and
 * nothing else in [ES_DE_PRIMARY_LIST_TYPES]) -- droidtop's own Games
 * system list should render whichever shape the loaded theme actually
 * specifies, not a hardcoded app-level choice.
 */
fun EsDeThemeView.primaryListElement(): EsDeThemeElement? =
    elements.values.firstOrNull { it.type in ES_DE_PRIMARY_LIST_TYPES }

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
 * guessed) -- all 16 real element types, within each restricted to the
 * properties this parser actually implements (see [EsDeTheme]'s own doc
 * comment for the real parsing-vs-rendering coverage distinction).
 */
internal val ES_DE_ELEMENT_SCHEMA: Map<String, Map<String, EsDePropertyType>> = mapOf(
    "image" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        // Real, distinct ES-DE property from "size" (confirmed against
        // ImageComponent.cpp's own setResize/setMaxSize): "size" stretches
        // to an exact size, "maxSize" scales down to fit WITHIN bounds
        // while preserving aspect ratio -- a real theme can use either
        // (Art Book Next's own system-logo element uses ONLY maxSize, no
        // size at all), and this was missing from the schema entirely,
        // meaning such an element fell through to the renderer's generic
        // default-size fallback instead of its own real bounds.
        "maxSize" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        // Real, previously-missing properties -- DEcaffe's own carousel
        // outline/fade decorative art (systemcar fade/fadebot,
        // caroutlinetop/caroutline) mirrors ONE real source image around
        // the carousel using exactly these two, one flipped, one flipped
        // AND rotated 180 -- without them both instances render in the
        // same orientation, a real, visually-confirmed misalignment bug
        // (found by comparing a real on-device screenshot against
        // theme.xml directly), not a missing-asset problem.
        "flipHorizontal" to EsDePropertyType.BOOLEAN,
        "flipVertical" to EsDePropertyType.BOOLEAN,
        "path" to EsDePropertyType.PATH,
        "default" to EsDePropertyType.PATH,
        "color" to EsDePropertyType.COLOR,
        // Real gameselector-driven properties (ThemeData.cpp's own
        // ImageComponent entries for the system view's game-preview
        // grid/collage) -- previously missing entirely, meaning an
        // element like DEcaffe's own `game1`..`game9`/`screen2` (real
        // per-game preview art, not static background) fell through to
        // the plain static-`path` renderer and rendered nothing, since
        // those elements have no `path` of their own at all. `colorEnd`/
        // `gradientType` cover the OTHER real use of "image" for a
        // gradient-filled decorative band (DEcaffe's own leftband/
        // rightband elements), a second, unrelated real use of the same
        // element type -- both fell through the same gap for different
        // reasons.
        "gameselectorEntry" to EsDePropertyType.UNSIGNED_INTEGER,
        "imageType" to EsDePropertyType.STRING,
        "cropSize" to EsDePropertyType.NORMALIZED_PAIR,
        "gradientType" to EsDePropertyType.STRING,
        "colorEnd" to EsDePropertyType.COLOR,
        "cornerRadius" to EsDePropertyType.FLOAT,
        "brightness" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "saturation" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Real property set expanded beyond the original pass to include
    // ES-DE's own metadata-binding properties (metadata/systemdata/
    // gameselector/defaultValue) -- what a real gamelist theme uses to
    // bind a text element to a specific per-game field (developer, genre,
    // release date, ...) rather than static text. See [EsDeTheme]'s own
    // doc comment for why rendering these still needs real per-game
    // metadata droidtop doesn't model yet.
    "text" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "rotation" to EsDePropertyType.FLOAT,
        "text" to EsDePropertyType.STRING,
        "systemdata" to EsDePropertyType.STRING,
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
        // Real carousel-wide background bar drawn ONCE behind every item
        // (CarouselComponent::render's own single drawRect call) -- NOT a
        // per-item box. Real default 0xFFFFFFD8 (translucent white),
        // confirmed against the real constructor default, not guessed.
        "color" to EsDePropertyType.COLOR,
        "colorEnd" to EsDePropertyType.COLOR,
        "colorGradientHorizontal" to EsDePropertyType.BOOLEAN,
        "text" to EsDePropertyType.STRING,
        "textColor" to EsDePropertyType.COLOR,
        // Real, previously-missing carousel text properties -- an item's
        // OWN text fallback (shown only when it has no image, see
        // CarouselComponent::onDemandTextureLoad/addEntry) has real
        // default colors distinct from a themed "text" element's own
        // defaults (0x000000FF text, transparent background).
        "textBackgroundColor" to EsDePropertyType.COLOR,
        "textSelectedColor" to EsDePropertyType.COLOR,
        "textSelectedBackgroundColor" to EsDePropertyType.COLOR,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "unfocusedItemOpacity" to EsDePropertyType.FLOAT,
        "unfocusedItemSaturation" to EsDePropertyType.FLOAT,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Real property set transcribed from ES-DE's own ThemeData.cpp
    // sElementMap (fetched and read directly this session, not guessed --
    // ~60 real properties exist; scoped here to the ones that materially
    // change layout/appearance for a real Compose grid renderer).
    "grid" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "itemSize" to EsDePropertyType.NORMALIZED_PAIR,
        "itemScale" to EsDePropertyType.FLOAT,
        "itemSpacing" to EsDePropertyType.NORMALIZED_PAIR,
        "unfocusedItemOpacity" to EsDePropertyType.FLOAT,
        "unfocusedItemSaturation" to EsDePropertyType.FLOAT,
        "imageColor" to EsDePropertyType.COLOR,
        "imageSelectedColor" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "selectorColor" to EsDePropertyType.COLOR,
        "text" to EsDePropertyType.STRING,
        "textColor" to EsDePropertyType.COLOR,
        "textSelectedColor" to EsDePropertyType.COLOR,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Same real source as "grid" above.
    "textlist" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "selectorColor" to EsDePropertyType.COLOR,
        "primaryColor" to EsDePropertyType.COLOR,
        "secondaryColor" to EsDePropertyType.COLOR,
        "selectedColor" to EsDePropertyType.COLOR,
        "selectedBackgroundColor" to EsDePropertyType.COLOR,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "horizontalMargin" to EsDePropertyType.FLOAT,
        "letterCase" to EsDePropertyType.STRING,
        "lineSpacing" to EsDePropertyType.FLOAT,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Real fallback-image-only rendering (see EsDeThemedView) -- actual
    // video playback is real, separate work, not attempted this pass.
    "video" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "path" to EsDePropertyType.PATH,
        "default" to EsDePropertyType.PATH,
        "defaultImage" to EsDePropertyType.PATH,
        "color" to EsDePropertyType.COLOR,
        // Same real gameselector-driven properties as "image" -- DEcaffe's
        // own `screen2` element (the large game-preview poster) is a
        // "video", not an "image", and was missing these for the same
        // reason.
        "gameselectorEntry" to EsDePropertyType.UNSIGNED_INTEGER,
        "imageType" to EsDePropertyType.STRING,
        "cropSize" to EsDePropertyType.NORMALIZED_PAIR,
        "imageCornerRadius" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Same real fallback-image-only rendering as "video" -- real GIF/frame
    // animation playback is separate work, not attempted this pass.
    "animation" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "path" to EsDePropertyType.PATH,
        "color" to EsDePropertyType.COLOR,
        "cornerRadius" to EsDePropertyType.FLOAT,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Parses real; rendering deferred -- needs real per-game favorite/
    // controller-support/folder metadata droidtop's LibraryEntry doesn't
    // model yet (see EsDeTheme's own doc comment).
    "badges" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "lines" to EsDePropertyType.UNSIGNED_INTEGER,
        "itemsPerLine" to EsDePropertyType.UNSIGNED_INTEGER,
        "itemMargin" to EsDePropertyType.NORMALIZED_PAIR,
        "customBadgeIcon" to EsDePropertyType.PATH,
        "customControllerIcon" to EsDePropertyType.PATH,
        "badgeIconColor" to EsDePropertyType.COLOR,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Real, live-rendered (current date/time) -- doesn't need per-game
    // metadata, unlike badges/rating/gamelistinfo.
    "datetime" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "metadata" to EsDePropertyType.STRING,
        "defaultValue" to EsDePropertyType.STRING,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "verticalAlignment" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "letterCase" to EsDePropertyType.STRING,
        "format" to EsDePropertyType.STRING,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Parses real; rendering deferred -- "X/Y games" summary needs a real
    // list-count context this element type alone doesn't carry.
    "gamelistinfo" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
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
    // Parses real; rendering deferred -- needs a real per-game star-rating
    // value droidtop's LibraryEntry doesn't model yet.
    "rating" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "hideIfZero" to EsDePropertyType.BOOLEAN,
        "color" to EsDePropertyType.COLOR,
        "filledPath" to EsDePropertyType.PATH,
        "unfilledPath" to EsDePropertyType.PATH,
        "overlay" to EsDePropertyType.BOOLEAN,
        "opacity" to EsDePropertyType.FLOAT,
        "visible" to EsDePropertyType.BOOLEAN,
        "zIndex" to EsDePropertyType.FLOAT,
    ),
    // Not a positioned visual element in real ES-DE either -- selects
    // which game(s) other elements' gameselector/gameselectorEntry
    // properties reference. Parsed for completeness; no rendering (there's
    // nothing to draw).
    "gameselector" to mapOf(
        "selection" to EsDePropertyType.STRING,
        "gameCount" to EsDePropertyType.UNSIGNED_INTEGER,
        "allowDuplicates" to EsDePropertyType.BOOLEAN,
    ),
    // Parses real; no rendering here -- droidtop already has its own
    // hand-built persistent button-hint footer (docs/SPEC.md §7), a real,
    // working equivalent, not a gap.
    "helpsystem" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "textColor" to EsDePropertyType.COLOR,
        "iconColor" to EsDePropertyType.COLOR,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "entrySpacing" to EsDePropertyType.FLOAT,
        "backgroundColor" to EsDePropertyType.COLOR,
        "opacity" to EsDePropertyType.FLOAT,
        "customButtonIcon" to EsDePropertyType.PATH,
    ),
    // Parses real; rendering deferred -- ES-DE's own real network/
    // Bluetooth/battery status row needs real device-status plumbing this
    // pass doesn't build.
    "systemstatus" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "height" to EsDePropertyType.FLOAT,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "fontPath" to EsDePropertyType.PATH,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "customIcon" to EsDePropertyType.PATH,
        "opacity" to EsDePropertyType.FLOAT,
    ),
    // Real, live-rendered (current time) -- same real category as datetime.
    "clock" to mapOf(
        "pos" to EsDePropertyType.NORMALIZED_PAIR,
        "size" to EsDePropertyType.NORMALIZED_PAIR,
        "origin" to EsDePropertyType.NORMALIZED_PAIR,
        "fontPath" to EsDePropertyType.PATH,
        "fontSize" to EsDePropertyType.FLOAT,
        "horizontalAlignment" to EsDePropertyType.STRING,
        "verticalAlignment" to EsDePropertyType.STRING,
        "color" to EsDePropertyType.COLOR,
        "backgroundColor" to EsDePropertyType.COLOR,
        "format" to EsDePropertyType.STRING,
        "opacity" to EsDePropertyType.FLOAT,
    ),
    // Not a visual element at all in real ES-DE (plays on navigation/
    // selection) -- parsed for completeness; no rendering, real audio
    // playback wiring is separate work.
    "sound" to mapOf(
        "path" to EsDePropertyType.PATH,
    ),
)

internal val ES_DE_SUPPORTED_VIEWS = setOf("all", "system", "gamelist")
