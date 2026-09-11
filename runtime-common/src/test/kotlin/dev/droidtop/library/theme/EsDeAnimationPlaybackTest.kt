package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** See [esDeAnimationFrame] for the ES-DE source of every rule here. */
class EsDeAnimationPlaybackTest {

    private val normal = EsDeAnimationDirection.of("normal")
    private val reverse = EsDeAnimationDirection.of("reverse")
    private val alternate = EsDeAnimationDirection.of("alternate")
    private val alternateReverse = EsDeAnimationDirection.of("alternateReverse")

    private fun frames(
        count: Int,
        direction: EsDeAnimationDirection,
        steps: Int,
        iterationCount: Int = 0,
    ): List<Int> = (0 until steps).map {
        esDeAnimationFrame(it * 100L, count, 100, direction, iterationCount)
    }

    @Test
    fun `the four theme literals collapse onto two facts`() {
        assertEquals(EsDeAnimationDirection(false, false), normal)
        assertEquals(EsDeAnimationDirection(true, false), reverse)
        assertEquals(EsDeAnimationDirection(false, true), alternate)
        assertEquals(EsDeAnimationDirection(true, true), alternateReverse)
        // An unrecognised value warns and keeps normal (:361-367).
        assertEquals(normal, EsDeAnimationDirection.of("bounce"))
        assertEquals(normal, EsDeAnimationDirection.of(null))
    }

    @Test
    fun `normal runs forward and restarts at the first frame`() {
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), frames(4, normal, 8))
    }

    @Test
    fun `reverse runs backward and restarts at the last frame`() {
        assertEquals(listOf(3, 2, 1, 0, 3, 2, 1, 0), frames(4, reverse, 8))
    }

    @Test
    fun `alternate bounces without repeating the frame it turned on`() {
        // Out 0,1,2,3 then back 2,1,0 -- NOT 3,2,1,0 (:525-532).
        assertEquals(listOf(0, 1, 2, 3, 2, 1, 0, 1, 2, 3), frames(4, alternate, 10))
    }

    @Test
    fun `alternateReverse starts at the far end and bounces the same way`() {
        assertEquals(listOf(3, 2, 1, 0, 1, 2, 3, 2, 1, 0), frames(4, alternateReverse, 10))
    }

    @Test
    fun `iterationCount counts passes and then holds the last frame drawn`() {
        // One pass forward, then held on the last frame forever.
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 3), frames(4, normal, 7, iterationCount = 1))
    }

    @Test
    fun `alternate doubles the iteration count, so one iteration plays out and back`() {
        assertEquals(
            listOf(0, 1, 2, 3, 2, 1, 0, 0, 0),
            frames(4, alternate, 9, iterationCount = 1),
        )
    }

    @Test
    fun `a single-frame animation and a zero pace never divide by anything`() {
        assertEquals(0, esDeAnimationFrame(10_000L, 1, 100, alternate, 0))
        assertEquals(0, esDeAnimationFrame(10_000L, 8, 0, normal, 0))
    }

    @Test
    fun `speed divides the frame interval within ES-DE's own clamp`() {
        assertEquals(100, esDeAnimationPacingMs(100, null))
        assertEquals(50, esDeAnimationPacingMs(100, 2f))
        // Clamped to 0.2..3.0 (:340-341).
        assertEquals(500, esDeAnimationPacingMs(100, 0.05f))
        assertEquals(33, esDeAnimationPacingMs(100, 9f))
    }
}
