package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** A carousel camera's slide length, ES-DE's CarouselComponent::onCursorChanged (Droidtop/tracker#782). */
class EsDeCarouselTimingTest {
    @Test
    fun `a whole-item step from rest takes 400 ms`() {
        assertEquals(400, esDeCarouselAnimMs(3f, 4f, scrollVelocity = 1))
        assertEquals(400, esDeCarouselAnimMs(4f, 3f, scrollVelocity = -1))
    }

    @Test
    fun `a press made mid-flight is more than an item away and takes the full 400 ms`() {
        assertEquals(400, esDeCarouselAnimMs(3.4f, 5f, scrollVelocity = 1))
    }

    @Test
    fun `the release after a held run settles over what is left, never under 200 ms`() {
        assertEquals(240, esDeCarouselAnimMs(4.6f, 5f, scrollVelocity = 1))
        assertEquals(200, esDeCarouselAnimMs(4.9f, 5f, scrollVelocity = 1))
        assertEquals(240, esDeCarouselAnimMs(5.4f, 5f, scrollVelocity = -1))
    }

    @Test
    fun `a move that is not the pad's always takes 400 ms`() {
        assertEquals(400, esDeCarouselAnimMs(3.4f, 9f, scrollVelocity = 0))
        assertEquals(400, esDeCarouselAnimMs(4.9f, 5f, scrollVelocity = 0))
    }
}
