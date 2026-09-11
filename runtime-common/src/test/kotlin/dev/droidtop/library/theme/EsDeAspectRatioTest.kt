package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected values derived by hand from ES-DE's own source
 * (ThemeData.cpp:66-115 for the tables, :1232-1252 and :1766-1775 for
 * the capability list, :736-771 for the selection), not from recorded
 * droidtop output. The two real themes used here are the ones droidtop
 * bundles: DEcaffe (no vertical variant) and Slate (16:9_vertical and
 * 4:3_vertical), both quoted from their real capabilities.xml.
 */
class EsDeAspectRatioTest {

    private val decaffe = listOf("16:9", "4:3", "16:10", "21:9", "19.5:9")
    private val slate = listOf("16:9", "16:9_vertical", "4:3", "4:3_vertical")

    // 1080x1920 phone/BlueStacks portrait, and the 1920x1080 console.
    private val portrait = 1080f / 1920f
    private val landscape = 1920f / 1080f

    @Test
    fun `capability list prepends automatic and keeps supported order`() {
        assertEquals(
            listOf("automatic", "16:9", "16:10", "4:3", "19.5:9", "21:9"),
            EsDeAspectRatio.capabilityList(decaffe),
        )
        assertEquals(
            listOf("automatic", "16:9", "16:9_vertical", "4:3", "4:3_vertical"),
            EsDeAspectRatio.capabilityList(slate),
        )
    }

    @Test
    fun `capability list drops unsupported names and duplicates`() {
        assertEquals(
            listOf("automatic", "16:9", "1:1"),
            EsDeAspectRatio.capabilityList(listOf("16:9", "9:16", "1:1", "16:9", "")),
        )
    }

    @Test
    fun `a theme declaring nothing gets no list at all`() {
        assertEquals(emptyList<String>(), EsDeAspectRatio.capabilityList(emptyList()))
        // "automatic" alone is not a declaration -- ES-DE only prepends it
        // when at least one real ratio survived validation.
        assertEquals(emptyList<String>(), EsDeAspectRatio.capabilityList(listOf("automatic")))
    }

    @Test
    fun `automatic picks the exact vertical variant on a portrait screen`() {
        val selection = EsDeAspectRatio.select(
            EsDeAspectRatio.capabilityList(slate),
            screenAspectRatio = portrait,
        )
        assertEquals("16:9_vertical", selection.name)
        assertTrue(selection.exactMatch)
    }

    @Test
    fun `automatic picks the landscape variant on a landscape screen`() {
        val selection = EsDeAspectRatio.select(
            EsDeAspectRatio.capabilityList(slate),
            screenAspectRatio = landscape,
        )
        assertEquals("16:9", selection.name)
        assertTrue(selection.exactMatch)
    }

    /**
     * The fallback the portrait pass exists because of: DEcaffe has no
     * vertical variant, so on 1080x1920 ES-DE compares 16:9 (diff
     * 1.2152), 16:10 (1.0375), 4:3 (0.7708), 19.5:9 (1.6042) and 21:9
     * (1.8078) and takes 4:3 -- the closest LANDSCAPE layout, drawn
     * stretched over the portrait screen. Nothing rotates, nothing
     * letterboxes, and it is not an exact match.
     */
    @Test
    fun `a theme with no vertical variant falls back to its closest landscape ratio`() {
        val selection = EsDeAspectRatio.select(
            EsDeAspectRatio.capabilityList(decaffe),
            screenAspectRatio = portrait,
        )
        assertEquals("4:3", selection.name)
        assertFalse(selection.exactMatch)
    }

    @Test
    fun `decaffe on the console is an exact 16 to 9 match`() {
        val selection = EsDeAspectRatio.select(
            EsDeAspectRatio.capabilityList(decaffe),
            screenAspectRatio = landscape,
        )
        assertEquals("16:9", selection.name)
        assertTrue(selection.exactMatch)
    }

    /**
     * ThemeData.cpp:750-753 seeds the search with "16:9" and its own
     * difference, so a theme whose every declared ratio is further from
     * the screen than 16:9 is yields "16:9" even though the theme never
     * declared it.
     */
    @Test
    fun `the 16 to 9 seed wins when every declared ratio is further away`() {
        val selection = EsDeAspectRatio.select(
            EsDeAspectRatio.capabilityList(listOf("32:9", "1:1")),
            screenAspectRatio = landscape,
        )
        assertEquals("16:9", selection.name)
        assertFalse(selection.exactMatch)
    }

    @Test
    fun `a user setting the theme declares wins over automatic`() {
        val selection = EsDeAspectRatio.select(
            EsDeAspectRatio.capabilityList(slate),
            setting = "4:3_vertical",
            screenAspectRatio = landscape,
        )
        assertEquals("4:3_vertical", selection.name)
    }

    @Test
    fun `a user setting the theme does not declare falls back to automatic`() {
        val selection = EsDeAspectRatio.select(
            EsDeAspectRatio.capabilityList(slate),
            setting = "21:9_vertical",
            screenAspectRatio = portrait,
        )
        assertEquals("16:9_vertical", selection.name)
    }

    @Test
    fun `with no screen to measure the selection is the 16 to 9 seed`() {
        assertEquals(
            "16:9",
            EsDeAspectRatio.select(EsDeAspectRatio.capabilityList(slate)).name,
        )
    }

    @Test
    fun `a theme with no aspect ratios at all selects 16 to 9`() {
        assertEquals("16:9", EsDeAspectRatio.select(emptyList(), screenAspectRatio = portrait).name)
    }

    @Test
    fun `vertical variants are the rotation of their landscape ratio`() {
        for (name in EsDeAspectRatio.SUPPORTED) {
            if (!name.endsWith("_vertical")) continue
            val landscapeName = name.removeSuffix("_vertical")
            val vertical = EsDeAspectRatio.RATIO_MAP.getValue(name)
            val horizontal = EsDeAspectRatio.RATIO_MAP.getValue(landscapeName)
            // ES-DE rounds each constant to four decimals independently,
            // so 1/horizontal and the tabulated vertical agree to within
            // that rounding, not exactly.
            assertTrue(name, kotlin.math.abs(1f / horizontal - vertical) < 0.001f)
        }
    }

    @Test
    fun `vertical variant detection`() {
        assertTrue(EsDeAspectRatio.hasVerticalVariant(EsDeAspectRatio.capabilityList(slate)))
        assertFalse(EsDeAspectRatio.hasVerticalVariant(EsDeAspectRatio.capabilityList(decaffe)))
    }
}
