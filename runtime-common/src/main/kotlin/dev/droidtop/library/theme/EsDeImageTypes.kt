package dev.droidtop.library.theme

/**
 * Real ES-DE `<imageType>` PARSING and VALIDATION, ported literally from
 * ES-DE's own source. This half is pure string handling and knows nothing
 * about a game or a filesystem; the RESOLUTION half (which file a parsed
 * type list actually names for one game) lives with the media layout it
 * has to walk, in `dev.droidtop.library.EsDeArtwork`.
 *
 * `imageType` selects WHICH scraped media a themed element shows. Real
 * ES-DE has two separate, genuinely different treatments of it, and the
 * difference is not cosmetic, so both are modeled here rather than
 * averaged into one:
 *
 *  * `image` and `video` elements (ImageComponent.cpp:612-647,
 *    VideoComponent.cpp:350-391): any number of entries, and the
 *    pseudo-type `image` is allowed -- which is itself a fallback chain,
 *    not a folder (see EsDeArtwork). `video` additionally allows `none`.
 *  * `carousel` and `grid` elements (CarouselComponent.h:1367-1409,
 *    GridComponent.h:988-1029): AT MOST TWO entries -- real ES-DE
 *    truncates the rest, with the source comment "Only allow two
 *    imageType entries due to performance reasons" -- no `image`
 *    pseudo-type, and `none` meaning "draw the game name as text".
 *
 * The tokenisation is deliberately ES-DE's own odd one rather than a
 * tidier equivalent: every whitespace character becomes a comma, repeated
 * commas collapse (`Utils::String::replace`'s outer loop repeats until no
 * `,,` remains, StringUtil.cpp:269-292), then a plain split on `,` that
 * does NOT drop empty tokens (StringUtil.cpp:390-405). An empty token is
 * therefore an unsupported value, which discards the whole list -- that is
 * real ES-DE behaviour, not an oversight being reproduced blindly, and it
 * is why a leading/trailing space inside the tag matters there. droidtop's
 * own parser trims element text before this point, so a well-formed theme
 * cannot reach that case here; the rule is still implemented so the two
 * implementations do not silently disagree the day it can.
 *
 * Every invalid case in real ES-DE clears the list entirely rather than
 * dropping the offending entry (all four components do this) -- and a
 * cleared list means the element falls back to its own default behaviour,
 * which is exactly what droidtop's untyped path already does.
 */
object EsDeImageTypes {

    /** ImageComponent.h:167-169 `sSupportedImageTypes`. */
    val IMAGE_ELEMENT_TYPES: List<String> = listOf(
        "image", "miximage", "marquee", "screenshot", "titlescreen",
        "cover", "backcover", "3dbox", "physicalmedia", "fanart",
    )

    /** VideoComponent.h:138-140 `sSupportedImageTypes` -- the image set plus `none`. */
    val VIDEO_ELEMENT_TYPES: List<String> = IMAGE_ELEMENT_TYPES + "none"

    /**
     * CarouselComponent.h:1368-1370 and GridComponent.h:989-991, which
     * declare the same local list. Note what is absent: the `image`
     * pseudo-type. A primary element cannot ask for ES-DE's composite
     * fallback, only for one concrete media type.
     */
    val PRIMARY_ELEMENT_TYPES: List<String> = listOf(
        "marquee", "cover", "backcover", "3dbox", "physicalmedia",
        "screenshot", "titlescreen", "miximage", "fanart", "none",
    )

    /**
     * ImageComponent.cpp:613-619 / VideoComponent.cpp:351-357 --
     * whitespace to comma, collapse repeats, split keeping empties.
     */
    fun tokenize(raw: String): List<String> {
        var s = raw.map { if (it.isWhitespace()) ',' else it }.joinToString("")
        while (s.contains(",,")) s = s.replace(",,", ",")
        return s.split(",")
    }

    /**
     * `image` / `video`: unbounded length, cleared on an unsupported or a
     * duplicated value (ImageComponent.cpp:626-647).
     */
    fun forImageElement(raw: String?): List<String> = parse(raw, IMAGE_ELEMENT_TYPES, maxEntries = null)

    /** Same, with `none` additionally legal (VideoComponent.cpp:365-391). */
    fun forVideoElement(raw: String?): List<String> = parse(raw, VIDEO_ELEMENT_TYPES, maxEntries = null)

    /**
     * `carousel` / `grid`: truncated to two BEFORE validation, so a third
     * entry that happens to be invalid is silently discarded rather than
     * clearing the list (CarouselComponent.h:1380-1382 /
     * GridComponent.h:1002-1004 run the erase first).
     */
    fun forPrimaryElement(raw: String?): List<String> = parse(raw, PRIMARY_ELEMENT_TYPES, maxEntries = 2)

    private fun parse(raw: String?, supported: List<String>, maxEntries: Int?): List<String> {
        if (raw == null) return emptyList()
        var types = tokenize(raw)
        if (maxEntries != null && types.size > maxEntries) types = types.take(maxEntries)
        if (types.any { it !in supported }) return emptyList()
        if (types.size != types.toSet().size) return emptyList()
        return types
    }
}
