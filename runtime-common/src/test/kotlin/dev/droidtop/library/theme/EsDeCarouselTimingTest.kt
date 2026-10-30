package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A carousel camera's slide length, ES-DE's CarouselComponent::onCursorChanged (Droidtop/tracker#782). */
class EsDeCarouselTimingTest {
    @Test
    fun `a whole-item step from rest takes 400 ms`() {
        assertEquals(400, esDeCarouselAnimMs(3f, 4f, scrollVelocity = 1))
        assertEquals(400, esDeCarouselAnimMs(4f, 3f, scrollVelocity = -1))
    }

    @Test
    fun `by ES-DE's own rule a press made mid-flight is more than an item away and takes the full 400 ms`() {
        assertEquals(400, esDeCarouselAnimMs(3.4f, 5f, scrollVelocity = 1))
    }

    @Test
    fun `droidtop retargets a press made mid-flight in 200 ms, by pad or not`() {
        assertEquals(200, esDeCarouselAnimMs(3.4f, 5f, scrollVelocity = 1, retarget = true))
        assertEquals(200, esDeCarouselAnimMs(3.4f, 9f, scrollVelocity = 0, retarget = true))
    }

    @Test
    fun `five taps 125 ms apart keep the camera within an item and settle 200 ms after the last`() {
        // The carousel's own ease-out, replayed: each tap restarts the slide from where the camera is.
        val easeOut = { t: Float -> 1f - (1f - t) * (1f - t) }
        var cam = 0f
        var start = 0f
        var target = 0f
        var duration = 0
        var elapsed = 0
        for (tap in 1..5) {
            if (tap > 1) {
                elapsed += 125
                cam = start + (target - start) * easeOut((elapsed.toFloat() / duration).coerceAtMost(1f))
                assertTrue("at tap $tap the camera was ${target - cam} of an item behind", target - cam < 0.5f)
            }
            val retarget = cam != target
            start = cam
            target = tap.toFloat()
            duration = esDeCarouselAnimMs(start, target, scrollVelocity = 1, retarget = retarget)
            elapsed = 0
        }
        assertEquals(200, duration)
    }

    @Test
    fun `the release after a held run settles over what is left, never under 200 ms`() {
        assertEquals(240, esDeCarouselAnimMs(4.6f, 5f, scrollVelocity = 1))
        assertEquals(200, esDeCarouselAnimMs(4.9f, 5f, scrollVelocity = 1))
        assertEquals(240, esDeCarouselAnimMs(5.4f, 5f, scrollVelocity = -1))
    }

    @Test
    fun `a move from rest that is not the pad's takes 400 ms`() {
        assertEquals(400, esDeCarouselAnimMs(3.4f, 9f, scrollVelocity = 0))
        assertEquals(400, esDeCarouselAnimMs(4.9f, 5f, scrollVelocity = 0))
    }
}
