package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values here are derived by hand from real ES-DE's own source,
 * not from recorded droidtop output:
 * ImageComponent.cpp:612-647, VideoComponent.cpp:350-391,
 * CarouselComponent.h:1367-1409, GridComponent.h:988-1029, and the two
 * string helpers they call, StringUtil.cpp:269-292 (`replace`, whose
 * OUTER loop repeats until no occurrence of the needle remains) and
 * StringUtil.cpp:390-405 (`delimitedStringToVector`, which never drops an
 * empty token and always emits at least one element).
 */
class EsDeImageTypesTest {

    @Test
    fun `single type parses`() {
        assertEquals(listOf("marquee"), EsDeImageTypes.forImageElement("marquee"))
    }

    @Test
    fun `theme order is preserved, it is not a fixed priority`() {
        assertEquals(
            listOf("marquee", "cover"),
            EsDeImageTypes.forImageElement("marquee,cover"),
        )
        assertEquals(
            listOf("cover", "marquee"),
            EsDeImageTypes.forImageElement("cover,marquee"),
        )
    }

    @Test
    fun `whitespace between entries acts as a separator`() {
        // "screenshot cover" -> every whitespace char becomes ',' ->
        // "screenshot,cover" -> no ",," to collapse -> two tokens.
        assertEquals(
            listOf("screenshot", "cover"),
            EsDeImageTypes.forImageElement("screenshot cover"),
        )
        // Mixed comma+space: "screenshot, cover" -> "screenshot,,cover"
        // -> collapse -> "screenshot,cover".
        assertEquals(
            listOf("screenshot", "cover"),
            EsDeImageTypes.forImageElement("screenshot, cover"),
        )
        // Three spaces collapse the same way, because `replace` loops
        // until no ",," is left: "a   b" -> "a,,,b" -> "a,,b" -> "a,b".
        assertEquals(
            listOf("screenshot", "cover"),
            EsDeImageTypes.forImageElement("screenshot   cover"),
        )
    }

    @Test
    fun `an unsupported value clears the whole list, it does not drop one entry`() {
        assertEquals(emptyList<String>(), EsDeImageTypes.forImageElement("marquee,boxart"))
    }

    @Test
    fun `a duplicated value clears the whole list`() {
        assertEquals(emptyList<String>(), EsDeImageTypes.forImageElement("marquee,cover,marquee"))
    }

    @Test
    fun `an empty token is an unsupported value and clears the list`() {
        // " marquee" -> ",marquee" -> tokens ["", "marquee"]; "" is not a
        // supported type. This is why untrimmed element text matters in
        // real ES-DE.
        assertEquals(emptyList<String>(), EsDeImageTypes.forImageElement(" marquee"))
        assertEquals(emptyList<String>(), EsDeImageTypes.forImageElement(""))
    }

    @Test
    fun `none is legal on video and illegal on image`() {
        assertEquals(listOf("none"), EsDeImageTypes.forVideoElement("none"))
        assertEquals(emptyList<String>(), EsDeImageTypes.forImageElement("none"))
    }

    @Test
    fun `the image pseudo-type is legal on image and video, illegal on carousel and grid`() {
        assertEquals(listOf("image"), EsDeImageTypes.forImageElement("image"))
        assertEquals(listOf("image"), EsDeImageTypes.forVideoElement("image"))
        assertEquals(emptyList<String>(), EsDeImageTypes.forPrimaryElement("image"))
    }

    @Test
    fun `carousel and grid truncate to two entries before validating`() {
        assertEquals(
            listOf("marquee", "cover"),
            EsDeImageTypes.forPrimaryElement("marquee,cover,screenshot"),
        )
        // The truncation runs FIRST, so a third entry that is invalid is
        // discarded rather than clearing the list.
        assertEquals(
            listOf("marquee", "cover"),
            EsDeImageTypes.forPrimaryElement("marquee,cover,boxart"),
        )
        // ...and a duplicate outside the first two is likewise gone
        // before the duplicate check can see it.
        assertEquals(
            listOf("marquee", "cover"),
            EsDeImageTypes.forPrimaryElement("marquee,cover,marquee"),
        )
    }

    @Test
    fun `an image element is not truncated`() {
        assertEquals(
            listOf("marquee", "cover", "screenshot"),
            EsDeImageTypes.forImageElement("marquee,cover,screenshot"),
        )
    }

    @Test
    fun `null means the theme asked for nothing`() {
        assertEquals(emptyList<String>(), EsDeImageTypes.forImageElement(null))
    }
}
