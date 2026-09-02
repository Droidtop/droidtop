package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Geometry checks for the real ES-DE carousel layout port. Every
 * expected value here is derived by hand from the formulas in real
 * ES-DE's own `CarouselComponent<T>::render()` (cited per test), not
 * captured from droidtop's own output -- a regression test written by
 * recording current behavior would happily lock in a wrong port.
 */
class EsDeCarouselLayoutTest {

    private fun element(vararg properties: Pair<String, EsDeThemeValue>) = EsDeThemeElement(
        type = "carousel",
        key = "carousel_test",
        properties = properties.toMap(),
    )

    private fun placementAtDistance(placements: List<EsDeCarouselPlacement>, distance: Float) =
        placements.first { abs(it.distance - distance) < 1e-4f }

    @Test
    fun `horizontal carousel centers the selected item`() {
        // Defaults: itemSize 0.25 x 0.155 of the themed area, maxItemCount
        // 3. itemSpacing = ((1000 - 250*3) / 3) + 250 = 333.33, and
        // xOff = (1000 - 250) / 2 = 375, so entry 0's anchor (its center,
        // since the default alignment is centered) lands at 375 + 125 =
        // 500 -- the middle of a 1000-wide carousel.
        val config = esDeCarouselConfig(null, 1000f, 1000f)
        val placements = layoutEsDeCarousel(config, 1000f, 200f, camOffset = 0f, entryCount = 5, positiveDirection = false)
        val selected = placementAtDistance(placements, 0f)
        assertEquals(500f, selected.anchorX, 0.01f)
        assertEquals(100f, selected.anchorY, 0.01f)
        assertEquals(0.5f, selected.originFractionX, 0f)
        assertEquals(0.5f, selected.originFractionY, 0f)
        assertEquals(0f, selected.rotationDegrees, 0.01f)
    }

    @Test
    fun `vertical carousel advances along y instead of x`() {
        val config = esDeCarouselConfig(
            element("type" to EsDeThemeValue.Str("vertical")),
            1000f,
            1000f,
        )
        assertEquals(EsDeCarouselType.VERTICAL, config.type)
        val placements = layoutEsDeCarousel(config, 1000f, 1000f, camOffset = 0f, entryCount = 5, positiveDirection = false)
        val selected = placementAtDistance(placements, 0f)
        val next = placementAtDistance(placements, 1f)
        assertEquals(500f, selected.anchorX, 0.01f)
        assertEquals(500f, selected.anchorY, 0.01f)
        // itemSpacing.y = ((1000 - 155*3) / 3) + 155 = 333.33.
        assertEquals(selected.anchorX, next.anchorX, 0.01f)
        assertEquals(333.33f, next.anchorY - selected.anchorY, 0.05f)
    }

    @Test
    fun `vertical wheel rotates items by itemRotation per item of distance`() {
        val config = esDeCarouselConfig(
            element("type" to EsDeThemeValue.Str("verticalWheel")),
            1000f,
            1000f,
        )
        assertEquals(EsDeCarouselType.VERTICAL_WHEEL, config.type)
        // Real default itemRotation is 7.5 degrees.
        assertEquals(7.5f, config.itemRotation, 0f)
        val placements = layoutEsDeCarousel(config, 1000f, 1000f, camOffset = 0f, entryCount = 9, positiveDirection = false)
        assertEquals(0f, placementAtDistance(placements, 0f).rotationDegrees, 0.01f)
        assertEquals(7.5f, placementAtDistance(placements, 1f).rotationDegrees, 0.01f)
        assertEquals(-15f, placementAtDistance(placements, -2f).rotationDegrees, 0.01f)
    }

    @Test
    fun `horizontal wheel leaves the selected item upright`() {
        // The horizontal wheel lays items out in a frame rotated -90
        // degrees and then rotates each item +90 back around its own
        // center, so the item at distance zero must come out upright.
        val config = esDeCarouselConfig(
            element("type" to EsDeThemeValue.Str("horizontalWheel")),
            1000f,
            1000f,
        )
        val placements = layoutEsDeCarousel(config, 1000f, 1000f, camOffset = 0f, entryCount = 9, positiveDirection = false)
        assertEquals(0f, placementAtDistance(placements, 0f).rotationDegrees, 0.01f)
        assertEquals(7.5f, placementAtDistance(placements, 1f).rotationDegrees, 0.01f)
    }

    @Test
    fun `wheel item counts come from itemsBeforeCenter and itemsAfterCenter`() {
        // Real windowing for a wheel: i runs from center - itemsBefore to
        // center + 1 + itemsAfter, i.e. itemsBefore + itemsAfter + 1
        // entries -- and NOT from maxItemCount, which real ES-DE
        // explicitly rejects for the wheel types.
        val config = esDeCarouselConfig(
            element(
                "type" to EsDeThemeValue.Str("verticalWheel"),
                "itemsBeforeCenter" to EsDeThemeValue.UInt(2),
                "itemsAfterCenter" to EsDeThemeValue.UInt(3),
            ),
            1000f,
            1000f,
        )
        assertEquals(2, config.itemsBeforeCenter)
        assertEquals(3, config.itemsAfterCenter)
        val placements = layoutEsDeCarousel(config, 1000f, 1000f, camOffset = 0f, entryCount = 20, positiveDirection = false)
        assertEquals(6, placements.size)
    }

    @Test
    fun `itemStacking chooses the draw order of overlapping items`() {
        fun distances(stacking: String?): List<Float> {
            val properties = mutableListOf<Pair<String, EsDeThemeValue>>()
            if (stacking != null) properties += "itemStacking" to EsDeThemeValue.Str(stacking)
            val config = esDeCarouselConfig(element(*properties.toTypedArray()), 1000f, 1000f)
            return layoutEsDeCarousel(config, 1000f, 200f, 0f, 20, false).map { it.distance }
        }
        val ascending = distances("ascending")
        assertEquals(ascending.sorted(), ascending)
        val descending = distances("descending")
        assertEquals(descending.sortedDescending(), descending)
        // The default (centered) stacking is neither -- it draws outward
        // from the middle so the selected item ends up on top.
        val centered = distances(null)
        assertEquals(0f, centered.last(), 0.01f)
    }

    @Test
    fun `unfocused items use the real default opacity`() {
        // Real CarouselComponent constructor default is 0.5, not 1.0 --
        // droidtop previously defaulted this to fully opaque, so a theme
        // that omitted the property lost the dimming entirely.
        val config = esDeCarouselConfig(null, 1000f, 1000f)
        assertEquals(0.5f, config.unfocusedItemOpacity, 0f)
        val placements = layoutEsDeCarousel(config, 1000f, 200f, 0f, 5, false)
        assertEquals(1f, placementAtDistance(placements, 0f).opacity, 0.001f)
        assertEquals(0.5f, placementAtDistance(placements, 1f).opacity, 0.001f)
        // Mid-animation, opacity ramps linearly across the gap: at half an
        // item of travel it sits halfway between 1.0 and 0.5.
        val midway = layoutEsDeCarousel(config, 1000f, 200f, 0.5f, 5, true)
        assertEquals(0.75f, placementAtDistance(midway, -0.5f).opacity, 0.001f)
    }

    @Test
    fun `item scale normalizes against itemScale so the selected item is full size`() {
        val config = esDeCarouselConfig(null, 1000f, 1000f)
        assertEquals(1.2f, config.itemScale, 0f)
        val placements = layoutEsDeCarousel(config, 1000f, 200f, 0f, 5, false)
        assertEquals(1f, placementAtDistance(placements, 0f).scale, 0.001f)
        assertEquals(1f / 1.2f, placementAtDistance(placements, 1f).scale, 0.001f)
    }

    @Test
    fun `saturation stays unset unless the theme declares unfocusedItemSaturation`() {
        // Real `mHasUnfocusedItemSaturation` gates the whole saturation
        // path -- an undeclared property must leave item color untouched
        // rather than resolving to some default.
        val plain = esDeCarouselConfig(null, 1000f, 1000f)
        assertEquals(null, layoutEsDeCarousel(plain, 1000f, 200f, 0f, 5, false).first().saturation)
        val declared = esDeCarouselConfig(
            element("unfocusedItemSaturation" to EsDeThemeValue.FloatValue(0.2f)),
            1000f,
            1000f,
        )
        val placements = layoutEsDeCarousel(declared, 1000f, 200f, 0f, 5, false)
        assertEquals(1f, placementAtDistance(placements, 0f).saturation!!, 0.001f)
        assertEquals(0.2f, placementAtDistance(placements, 1f).saturation!!, 0.001f)
    }

    @Test
    fun `properties that only apply to some types are ignored for the others`() {
        // Real ES-DE refuses reflections outside the plain horizontal
        // type, and refuses itemHorizontalAlignment for both horizontal
        // types -- both warn and keep the default rather than applying.
        val wheel = esDeCarouselConfig(
            element(
                "type" to EsDeThemeValue.Str("verticalWheel"),
                "reflections" to EsDeThemeValue.Bool(true),
            ),
            1000f,
            1000f,
        )
        assertTrue(!wheel.reflections)
        val horizontal = esDeCarouselConfig(
            element(
                "type" to EsDeThemeValue.Str("horizontal"),
                "itemHorizontalAlignment" to EsDeThemeValue.Str("left"),
                "reflections" to EsDeThemeValue.Bool(true),
            ),
            1000f,
            1000f,
        )
        assertEquals(EsDeHorizontalAlign.CENTER, horizontal.itemHorizontalAlignment)
        assertTrue(horizontal.reflections)
    }

    @Test
    fun `real clamps are applied to the properties that have them`() {
        val config = esDeCarouselConfig(
            element(
                "maxItemCount" to EsDeThemeValue.FloatValue(99f),
                "itemScale" to EsDeThemeValue.FloatValue(9f),
                "itemsBeforeCenter" to EsDeThemeValue.UInt(500),
                "unfocusedItemOpacity" to EsDeThemeValue.FloatValue(0f),
                "imageSaturation" to EsDeThemeValue.FloatValue(5f),
                "imageBrightness" to EsDeThemeValue.FloatValue(-9f),
                "itemSize" to EsDeThemeValue.Pair(0.001f, 5f),
            ),
            1000f,
            1000f,
        )
        assertEquals(30f, config.maxItemCount, 0f)
        assertEquals(3f, config.itemScale, 0f)
        assertEquals(20, config.itemsBeforeCenter)
        assertEquals(0.1f, config.unfocusedItemOpacity, 0f)
        assertEquals(1f, config.imageSaturation, 0f)
        assertEquals(-2f, config.imageBrightness, 0f)
        assertEquals(50f, config.itemSizeX, 0f)
        assertEquals(1000f, config.itemSizeY, 0f)
    }

    @Test
    fun `horizontalOffset shifts the whole carousel by a fraction of its own size`() {
        val config = esDeCarouselConfig(
            element("horizontalOffset" to EsDeThemeValue.FloatValue(0.1f)),
            1000f,
            1000f,
        )
        val shifted = layoutEsDeCarousel(config, 1000f, 200f, 0f, 5, false)
        val plain = layoutEsDeCarousel(esDeCarouselConfig(null, 1000f, 1000f), 1000f, 200f, 0f, 5, false)
        val delta = placementAtDistance(shifted, 0f).anchorX - placementAtDistance(plain, 0f).anchorX
        assertEquals(100f, delta, 0.01f)
    }
}
