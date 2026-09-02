package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks for the real ES-DE grid layout port. Expected values are
 * derived by hand from `GridComponent<T>::calculateLayout()`/`render()`/
 * `applyTheme()` in real ES-DE's own source, cited per test.
 */
class EsDeGridLayoutTest {

    private fun element(vararg properties: Pair<String, EsDeThemeValue>) = EsDeThemeElement(
        type = "grid",
        key = "grid_test",
        properties = properties.toMap(),
    )

    @Test
    fun `item spacing is calculated from itemScale when the theme declares none`() {
        // Real ES-DE: with no itemSpacing property, spacing becomes
        // ((itemSize * itemScale) - itemSize) / 2 so scaled items don't
        // overlap. droidtop previously substituted a flat 16dp, which is
        // neither this nor anything else in ES-DE.
        val config = esDeGridConfig(
            element(
                "itemSize" to EsDeThemeValue.Pair(0.1f, 0.2f),
                "itemScale" to EsDeThemeValue.FloatValue(1.5f),
            ),
            1000f,
            1000f,
        )
        assertEquals(100f, config.itemSizeX, 0.001f)
        assertEquals(200f, config.itemSizeY, 0.001f)
        assertEquals(25f, config.itemSpacingX, 0.001f)
        assertEquals(50f, config.itemSpacingY, 0.001f)

        // Items that scale DOWN need no spacing at all.
        val shrinking = esDeGridConfig(
            element("itemScale" to EsDeThemeValue.FloatValue(0.8f)),
            1000f,
            1000f,
        )
        assertEquals(0f, shrinking.itemSpacingX, 0f)
        assertEquals(0f, shrinking.itemSpacingY, 0f)
    }

    @Test
    fun `an axis of minus one means square, sized from the other axis`() {
        val fromX = esDeGridConfig(element("itemSize" to EsDeThemeValue.Pair(0.2f, -1f)), 1000f, 500f)
        assertEquals(200f, fromX.itemSizeX, 0.001f)
        assertEquals(200f, fromX.itemSizeY, 0.001f)
        val fromY = esDeGridConfig(element("itemSize" to EsDeThemeValue.Pair(-1f, 0.2f)), 1000f, 500f)
        assertEquals(100f, fromY.itemSizeY, 0.001f)
        assertEquals(100f, fromY.itemSizeX, 0.001f)
        // Both axes at -1 is real ES-DE's own "ignore the property".
        val neither = esDeGridConfig(element("itemSize" to EsDeThemeValue.Pair(-1f, -1f)), 1000f, 500f)
        assertEquals(150f, neither.itemSizeX, 0.001f)
        assertEquals(125f, neither.itemSizeY, 0.001f)
    }

    @Test
    fun `margins reserve scale-up room, and scaleInwards removes the need for it`() {
        // Real calculateLayout: margin = ((itemSize * itemScale) - itemSize) / 2,
        // but with scaleInwards the item grows into the grid, so no room
        // is reserved at the edges at all.
        val outward = esDeGridConfig(
            element(
                "itemSize" to EsDeThemeValue.Pair(0.1f, 0.1f),
                "itemScale" to EsDeThemeValue.FloatValue(1.4f),
            ),
            1000f,
            1000f,
        )
        assertEquals(20f, outward.horizontalMargin, 0.001f)
        val inward = esDeGridConfig(
            element(
                "itemSize" to EsDeThemeValue.Pair(0.1f, 0.1f),
                "itemScale" to EsDeThemeValue.FloatValue(1.4f),
                "scaleInwards" to EsDeThemeValue.Bool(true),
            ),
            1000f,
            1000f,
        )
        assertTrue(inward.scaleInwards)
        assertEquals(0f, inward.horizontalMargin, 0.001f)
        // scaleInwards is ignored entirely unless items scale up.
        val shrinking = esDeGridConfig(
            element(
                "itemScale" to EsDeThemeValue.FloatValue(0.9f),
                "scaleInwards" to EsDeThemeValue.Bool(true),
            ),
            1000f,
            1000f,
        )
        assertTrue(!shrinking.scaleInwards)
    }

    @Test
    fun `column count is the real greedy fit, margins and spacing included`() {
        // itemSize 100 wide, itemScale 1.0 so margin 0 and spacing 0:
        // exactly ten columns fit in 1000, and the eleventh would exceed.
        val config = esDeGridConfig(
            element(
                "itemSize" to EsDeThemeValue.Pair(0.1f, 0.1f),
                "itemScale" to EsDeThemeValue.FloatValue(1f),
                "itemSpacing" to EsDeThemeValue.Pair(0f, 0f),
            ),
            1000f,
            1000f,
        )
        assertEquals(10, layoutEsDeGrid(config, 1000f, 1000f).columns)
        // A grid too narrow for even one item still gets one column, not
        // zero -- real ES-DE's own guard.
        assertEquals(1, layoutEsDeGrid(config, 10f, 1000f).columns)
    }

    @Test
    fun `visible rows floor unless the theme asked for fractional rows`() {
        // itemSize.y 300, no spacing, no margin: 1000 / 300 = 3.33 rows.
        val properties = arrayOf(
            "itemSize" to EsDeThemeValue.Pair(0.1f, 0.3f),
            "itemScale" to EsDeThemeValue.FloatValue(1f),
            "itemSpacing" to EsDeThemeValue.Pair(0f, 0f),
        )
        val whole = esDeGridConfig(element(*properties), 1000f, 1000f)
        assertEquals(3f, layoutEsDeGrid(whole, 1000f, 1000f).visibleRows, 0.001f)
        val fractional = esDeGridConfig(
            element(*properties, "fractionalRows" to EsDeThemeValue.Bool(true)),
            1000f,
            1000f,
        )
        assertEquals(1000f / 300f, layoutEsDeGrid(fractional, 1000f, 1000f).visibleRows, 0.001f)
        // A grid shorter than one row still reports one, never zero.
        assertEquals(1f, layoutEsDeGrid(whole, 1000f, 100f).visibleRows, 0.001f)
    }

    @Test
    fun `the grid does not scroll until the cursor passes the last visible row`() {
        // Real onCursorChanged: endRow = cursor / columns; it stays at 0
        // while that row is within visibleRows - 1, then tracks it.
        val layout = EsDeGridLayout(columns = 4, visibleRows = 3f)
        assertEquals(0f, esDeGridScrollRow(layout, cursor = 0), 0f)
        assertEquals(0f, esDeGridScrollRow(layout, cursor = 7), 0f)
        // Row 2 is the last one fully visible with visibleRows 3.
        assertEquals(0f, esDeGridScrollRow(layout, cursor = 8), 0f)
        assertEquals(1f, esDeGridScrollRow(layout, cursor = 12), 0f)
        assertEquals(3f, esDeGridScrollRow(layout, cursor = 23), 0f)
    }

    @Test
    fun `items are placed by their cell centre, row by row`() {
        val config = esDeGridConfig(
            element(
                "itemSize" to EsDeThemeValue.Pair(0.1f, 0.1f),
                "itemScale" to EsDeThemeValue.FloatValue(1f),
                "itemSpacing" to EsDeThemeValue.Pair(0.01f, 0.02f),
            ),
            1000f,
            1000f,
        )
        val layout = EsDeGridLayout(columns = 3, visibleRows = 3f)
        // margin 0 (itemScale 1), so entry 0's centre is half an item in.
        assertEquals(kotlin.Pair(50f, 50f), esDeGridItemCenter(config, layout, 0))
        // One column across: + itemSize + spacing.x (10).
        assertEquals(kotlin.Pair(160f, 50f), esDeGridItemCenter(config, layout, 1))
        // One row down: + itemSize + spacing.y (20).
        assertEquals(kotlin.Pair(50f, 170f), esDeGridItemCenter(config, layout, 3))
    }

    @Test
    fun `background and selector layers are absent unless the theme asks for them`() {
        // Real mHasBackgroundColor/mHasSelectorColor: an undeclared color
        // means "draw no layer", which is a different thing from drawing a
        // transparent one -- and the reason the element must carry no
        // built-in card of its own.
        val bare = esDeGridConfig(null, 1000f, 1000f)
        assertNull(bare.backgroundColor)
        assertNull(bare.selectorColor)
        assertNull(bare.backgroundImage)
        assertNull(bare.selectorImage)
        assertEquals(EsDeSelectorLayer.TOP, bare.selectorLayer)
        // Real grid defaults that differ from the carousel's.
        assertEquals(1.05f, bare.itemScale, 0f)
        assertEquals(1f, bare.unfocusedItemOpacity, 0f)
        assertEquals(150f, bare.itemSizeX, 0.001f)
        assertEquals(250f, bare.itemSizeY, 0.001f)
        assertEquals(0x000000FFL, bare.textColor)

        val declared = esDeGridConfig(
            element(
                "backgroundColor" to EsDeThemeValue.Color(0x102030FFL),
                "selectorColor" to EsDeThemeValue.Color(0xAABBCCFFL),
                "selectorLayer" to EsDeThemeValue.Str("bottom"),
            ),
            1000f,
            1000f,
        )
        assertEquals(0x102030FFL, declared.backgroundColor)
        // colorEnd mirrors the base color, so a theme declaring only the
        // base gets a flat fill rather than a gradient into white.
        assertEquals(0x102030FFL, declared.backgroundColorEnd)
        assertEquals(0xAABBCCFFL, declared.selectorColorEnd)
        assertEquals(EsDeSelectorLayer.BOTTOM, declared.selectorLayer)
    }

    @Test
    fun `real clamps for the grid's own properties`() {
        val config = esDeGridConfig(
            element(
                // The grid's itemScale clamp is 0.5-2.0, NOT the
                // carousel's 0.2-3.0.
                "itemScale" to EsDeThemeValue.FloatValue(9f),
                "imageRelativeScale" to EsDeThemeValue.FloatValue(9f),
                "selectorRelativeScale" to EsDeThemeValue.FloatValue(0.01f),
                "itemSpacing" to EsDeThemeValue.Pair(9f, 9f),
                "unfocusedItemDimming" to EsDeThemeValue.FloatValue(-1f),
                "selectorLayer" to EsDeThemeValue.Str("nonsense"),
            ),
            1000f,
            1000f,
        )
        assertEquals(2f, config.itemScale, 0f)
        assertEquals(1f, config.imageRelativeScale, 0f)
        assertEquals(0.2f, config.selectorRelativeScale, 0f)
        // itemSpacing clamps to 0.1 of the screen on each axis.
        assertEquals(100f, config.itemSpacingX, 0f)
        assertEquals(100f, config.itemSpacingY, 0f)
        assertEquals(0f, config.unfocusedItemDimming, 0f)
        assertEquals(EsDeSelectorLayer.TOP, config.selectorLayer)
    }

    @Test
    fun `corner radii are fractions of screen width, scaled by itemScale`() {
        // Real: clamp(value, 0, 0.5) * (itemScale >= 1 ? itemScale : 1) *
        // screenWidth -- so the radius grows with the item as it scales.
        val config = esDeGridConfig(
            element(
                "itemScale" to EsDeThemeValue.FloatValue(2f),
                "backgroundCornerRadius" to EsDeThemeValue.FloatValue(0.01f),
                "selectorCornerRadius" to EsDeThemeValue.FloatValue(0.9f),
            ),
            1000f,
            1000f,
        )
        assertEquals(20f, config.backgroundCornerRadius, 0.001f)
        assertEquals(1000f, config.selectorCornerRadius, 0.001f)
    }

    /**
     * GridComponent.h:1395-1398 -- `textBackgroundCornerRadius` clamps to
     * 0..0.5, then scales by itemScale (only when scaling UP) and by
     * screen WIDTH, the same rule as every other radius on this element.
     * 0.05 * 1.5 * 1000 = 75.
     */
    @Test
    fun `textBackgroundCornerRadius uses the same scale rule as the other radii`() {
        val config = esDeGridConfig(
            element(
                "itemScale" to EsDeThemeValue.FloatValue(1.5f),
                "textBackgroundCornerRadius" to EsDeThemeValue.FloatValue(0.05f),
            ),
            1000f,
            500f,
        )
        assertEquals(75f, config.textBackgroundCornerRadius, 0.001f)

        // Over the 0.5 clamp, and with itemScale below 1 so the scale
        // factor is pinned at 1: 0.5 * 1 * 1000 = 500.
        val clamped = esDeGridConfig(
            element(
                "itemScale" to EsDeThemeValue.FloatValue(0.8f),
                "textBackgroundCornerRadius" to EsDeThemeValue.FloatValue(0.9f),
            ),
            1000f,
            500f,
        )
        assertEquals(500f, clamped.textBackgroundCornerRadius, 0.001f)

        // Real default is 0 (GridComponent.h:273).
        assertEquals(0f, esDeGridConfig(element(), 1000f, 500f).textBackgroundCornerRadius, 0.001f)
    }

    /**
     * GridComponent.h:1321-1370 -- the image color chain is NOT
     * per-property constants. `imageColor` also sets `imageColorEnd`; the
     * selected pair starts out as the unselected pair; and
     * `imageSelectedColor` in turn sets `imageSelectedColorEnd`.
     */
    @Test
    fun `image color end falls back through the real chain`() {
        val onlyColor = esDeGridConfig(
            element("imageColor" to EsDeThemeValue.Color(0xAABBCCFFL)),
            1000f,
            1000f,
        )
        // End equals start -> no gradient, which is how ES-DE itself
        // tests for one (GridComponent.h:336).
        assertEquals(0xAABBCCFFL, onlyColor.imageColor)
        assertEquals(0xAABBCCFFL, onlyColor.imageColorEnd)
        assertEquals(0xAABBCCFFL, onlyColor.imageSelectedColor)
        assertEquals(0xAABBCCFFL, onlyColor.imageSelectedColorEnd)

        val gradient = esDeGridConfig(
            element(
                "imageColor" to EsDeThemeValue.Color(0xAABBCCFFL),
                "imageColorEnd" to EsDeThemeValue.Color(0x112233FFL),
            ),
            1000f,
            1000f,
        )
        assertEquals(0x112233FFL, gradient.imageColorEnd)
        // The selected pair inherits BOTH ends, not just the start.
        assertEquals(0xAABBCCFFL, gradient.imageSelectedColor)
        assertEquals(0x112233FFL, gradient.imageSelectedColorEnd)

        // A declared imageSelectedColor collapses the selected gradient.
        val selectedFlat = esDeGridConfig(
            element(
                "imageColor" to EsDeThemeValue.Color(0xAABBCCFFL),
                "imageColorEnd" to EsDeThemeValue.Color(0x112233FFL),
                "imageSelectedColor" to EsDeThemeValue.Color(0x445566FFL),
            ),
            1000f,
            1000f,
        )
        assertEquals(0x445566FFL, selectedFlat.imageSelectedColor)
        assertEquals(0x445566FFL, selectedFlat.imageSelectedColorEnd)
    }

    /**
     * GridComponent.h:1328-1341 and :1356-1370 -- both gradient axes
     * default to horizontal, independently: the selected one does NOT
     * inherit `imageGradientType`, and an invalid value falls back to
     * horizontal rather than failing.
     */
    @Test
    fun `gradient axes are independent and default to horizontal`() {
        val defaults = esDeGridConfig(element(), 1000f, 1000f)
        assertTrue(defaults.imageGradientHorizontal)
        assertTrue(defaults.imageSelectedGradientHorizontal)

        val vertical = esDeGridConfig(
            element("imageGradientType" to EsDeThemeValue.Str("vertical")),
            1000f,
            1000f,
        )
        assertFalse(vertical.imageGradientHorizontal)
        assertTrue(vertical.imageSelectedGradientHorizontal)

        val nonsense = esDeGridConfig(
            element("imageGradientType" to EsDeThemeValue.Str("diagonal")),
            1000f,
            1000f,
        )
        assertTrue(nonsense.imageGradientHorizontal)
    }
}
