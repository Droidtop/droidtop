package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins `ImageComponent::resize()`'s two intrinsic-size-dependent branches
 * (ImageComponent.cpp:788-801 for maxSize, :814-821 for a one-axis size)
 * and the clamp at :827-828.
 */
class EsDeImageFittedSizeTest {
    private val screenW = 1920f
    private val screenH = 1080f

    private fun fit(tw: Float, th: Float, sw: Float, sh: Float, f: EsDeImageFit) =
        esDeImageFittedSize(tw, th, sw, sh, f, screenW, screenH)

    @Test
    fun `maxSize fits a wide source to the box width`() {
        // 1000x500 into a 600x600 box: scaleX 0.6 < scaleY 1.2, so width
        // takes the box and height follows the aspect ratio.
        val (w, h) = fit(600f, 600f, 1000f, 500f, EsDeImageFit.FIT)!!
        assertEquals(600f, w, 0.01f)
        assertEquals(300f, h, 0.01f)
    }

    @Test
    fun `maxSize fits a tall source to the box height`() {
        // 400x480 -- a 3DS two-screen capture -- into decaffe's own
        // gamedisplay box of 0.58 x 0.77 at 1920x1080: 1113.6 x 831.6.
        // scaleY 1.7325 < scaleX 2.784, so the height takes the box.
        val (w, h) = fit(1113.6f, 831.6f, 400f, 480f, EsDeImageFit.FIT)!!
        assertEquals(831.6f, h, 0.01f)
        assertEquals(693f, w, 0.1f)
    }

    @Test
    fun `maxSize never exceeds either axis of the declared box`() {
        val (w, h) = fit(300f, 200f, 97f, 13f, EsDeImageFit.FIT)!!
        assertEquals(true, w <= 300.01f)
        assertEquals(true, h <= 200.01f)
    }

    @Test
    fun `a size with no height derives one from the aspect ratio`() {
        val (w, h) = fit(800f, 0f, 1000f, 500f, EsDeImageFit.STRETCH)!!
        assertEquals(800f, w, 0.01f)
        assertEquals(400f, h, 0.01f)
    }

    @Test
    fun `a size with no width derives one from the aspect ratio`() {
        val (w, h) = fit(0f, 400f, 1000f, 500f, EsDeImageFit.STRETCH)!!
        assertEquals(800f, w, 0.01f)
        assertEquals(400f, h, 0.01f)
    }

    @Test
    fun `a size with both axes set is left alone`() {
        assertNull(fit(800f, 400f, 1000f, 500f, EsDeImageFit.STRETCH))
    }

    @Test
    fun `a cropSize box is left alone -- ContentScale Crop already covers it`() {
        assertNull(fit(800f, 400f, 1000f, 500f, EsDeImageFit.CROP))
    }

    @Test
    fun `an unknown source size changes nothing`() {
        assertNull(fit(600f, 600f, 0f, 0f, EsDeImageFit.FIT))
    }

    @Test
    fun `the result is clamped to one pixel and three screens`() {
        val (w, h) = fit(0f, 100000f, 1000f, 1f, EsDeImageFit.STRETCH)!!
        assertEquals(screenW * 3f, w, 0.01f)
        assertEquals(screenH * 3f, h, 0.01f)
    }
}
