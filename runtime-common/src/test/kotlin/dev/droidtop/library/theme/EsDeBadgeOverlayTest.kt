package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks for the real badge overlay placement port. Every expected value
 * is derived by hand from `FlexboxComponent::calculateLayout()`
 * (FlexboxComponent.cpp:222-231) and the real defaults at
 * FlexboxComponent.h:27-28 -- not from recorded output.
 */
class EsDeBadgeOverlayTest {

    /**
     * FlexboxComponent.h:27-28 -- position (0.5, 0.5), size 0.5. The
     * overlay is CENTRED on the named point, so the real defaults put a
     * half-width overlay exactly in the middle of the badge: on a 100x100
     * badge at (10, 20) that is a 50x50 box at
     * (10 + 50 - 25, 20 + 50 - 25) = (35, 45).
     */
    @Test
    fun `real defaults centre a half-size overlay on the badge`() {
        val overlay = esDeBadgeOverlay(
            baseX = 10f, baseY = 20f, baseWidth = 100f, baseHeight = 100f,
            overlayPositionX = 0.5f, overlayPositionY = 0.5f, overlaySize = 0.5f,
        )
        assertEquals(50f, overlay.size, 0.001f)
        assertEquals(35f, overlay.x, 0.001f)
        assertEquals(45f, overlay.y, 0.001f)
    }

    /**
     * FlexboxComponent.cpp:223 -- the overlay is sized from the base
     * badge's WIDTH on both axes here (real ES-DE passes 0 for the height
     * and lets the image's aspect decide; droidtop's cells are square).
     * A non-square badge therefore still gets a square overlay, but its
     * vertical CENTRING uses the badge's own height (:229-230).
     * 200 wide x 100 tall, position (1, 0), size 0.25 -> a 50-wide box at
     * (0 + 200 - 25, 0 + 0 - 25) = (175, -25): hanging off the top-right
     * corner, which is exactly why the position clamp allows -1..2.
     */
    @Test
    fun `an overlay can hang off a corner`() {
        val overlay = esDeBadgeOverlay(
            baseX = 0f, baseY = 0f, baseWidth = 200f, baseHeight = 100f,
            overlayPositionX = 1f, overlayPositionY = 0f, overlaySize = 0.25f,
        )
        assertEquals(50f, overlay.size, 0.001f)
        assertEquals(175f, overlay.x, 0.001f)
        assertEquals(-25f, overlay.y, 0.001f)
    }

    /**
     * `controllerSize` clamps to 2.0 (BadgeComponent.cpp:504-506), so an
     * overlay really can be bigger than the badge it sits on: 2.0 of an
     * 80-wide badge is 160, centred at 0.5 -> 80 + 40 - 80 = 40 - 80,
     * i.e. x = 0 + 40 - 80 = -40.
     */
    @Test
    fun `an oversized overlay overhangs symmetrically`() {
        val overlay = esDeBadgeOverlay(
            baseX = 0f, baseY = 0f, baseWidth = 80f, baseHeight = 80f,
            overlayPositionX = 0.5f, overlayPositionY = 0.5f, overlaySize = 2f,
        )
        assertEquals(160f, overlay.size, 0.001f)
        assertEquals(-40f, overlay.x, 0.001f)
        assertEquals(-40f, overlay.y, 0.001f)
    }
}
