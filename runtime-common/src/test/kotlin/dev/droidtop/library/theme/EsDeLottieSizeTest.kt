package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [esDeLottieSize] and [esDeLottiePacingMs], each expected value worked by
 * hand from LottieAnimComponent.cpp on a 1920x1080 screen.
 */
class EsDeLottieSizeTest {

    private fun size(
        size: Pair<Float, Float>? = null,
        maxSize: Pair<Float, Float>? = null,
        scaleFactor: Float? = null,
        viewport: Pair<Int, Int> = 400 to 200,
    ) = esDeLottieSize(size, maxSize, scaleFactor, viewport.first, viewport.second, 1920f, 1080f)

    @Test
    fun `an exact size stretches regardless of the viewport`() {
        // 0.5 * 1920, 0.5 * 1080.
        assertEquals(EsDeLottieSize(960, 540, 960, 540), size(size = 0.5f to 0.5f))
    }

    @Test
    fun `one zero axis is derived from the viewport aspect ratio`() {
        // Height 0.5 * 1080 = 540; width 540 * (400 / 200) = 1080 (:165-168).
        assertEquals(EsDeLottieSize(1080, 540, 1080, 540), size(size = 0f to 0.5f))
        // Width 0.25 * 1920 = 480; height 480 / 2 = 240 (:169-172).
        assertEquals(EsDeLottieSize(480, 240, 480, 240), size(size = 0.25f to 0f))
    }

    @Test
    fun `a zero size is the theme error ES-DE turns into a hundredth`() {
        // 0.01 * 1920 = 19.2 -> 19, 0.01 * 1080 = 10.8 -> 10 (:278-283).
        assertEquals(EsDeLottieSize(19, 10, 19, 10), size(size = 0f to 0f))
    }

    @Test
    fun `a size axis above one is clamped to the full screen`() {
        assertEquals(EsDeLottieSize(1920, 540, 1920, 540), size(size = 1.5f to 0.5f))
    }

    @Test
    fun `maxSize fits inside the box keeping the viewport aspect`() {
        // Box 960x540, viewport 2:1. scaleX = 960/400 = 2.4, scaleY = 540/200
        // = 2.7, so width-limited: 400 * 2.4 = 960, 200 * 2.4 = 480.
        assertEquals(EsDeLottieSize(960, 480, 960, 480), size(maxSize = 0.5f to 0.5f))
        // A square viewport in the same box is height-limited: 540x540.
        assertEquals(EsDeLottieSize(540, 540, 540, 540), size(maxSize = 0.5f to 0.5f, viewport = 100 to 100))
    }

    @Test
    fun `no size at all is a fifth of the screen each way`() {
        // 0.2 * 1920 = 384, 0.2 * 1080 = 216 (:64).
        assertEquals(EsDeLottieSize(384, 216, 384, 216), size())
    }

    @Test
    fun `scaleFactor rasterises smaller than it draws and is clamped`() {
        assertEquals(EsDeLottieSize(960, 540, 480, 270), size(size = 0.5f to 0.5f, scaleFactor = 0.5f))
        // Clamped to 0.1 (:298): 96 x 54.
        assertEquals(EsDeLottieSize(960, 540, 96, 54), size(size = 0.5f to 0.5f, scaleFactor = 0.01f))
        // Clamped to 1.
        assertEquals(EsDeLottieSize(960, 540, 960, 540), size(size = 0.5f to 0.5f, scaleFactor = 4f))
    }

    @Test
    fun `pacing is the declared frame rate divided by the clamped speed`() {
        // 1000 / 30 = 33.33 -> 33 (:204).
        assertEquals(33, esDeLottiePacingMs(30f, null))
        // 33.33 / 2 = 16.67 -> 16, not the 16.5 that rounding first would give.
        assertEquals(16, esDeLottiePacingMs(30f, 2f))
        // 1000 / 60 / 0.5 = 33.33 -> 33; rounding the interval first would give 32.
        assertEquals(33, esDeLottiePacingMs(60f, 0.5f))
        // speed clamped to 0.2..3.0 (:320).
        assertEquals(166, esDeLottiePacingMs(30f, 0.01f))
        assertEquals(0, esDeLottiePacingMs(0f, null))
    }
}
