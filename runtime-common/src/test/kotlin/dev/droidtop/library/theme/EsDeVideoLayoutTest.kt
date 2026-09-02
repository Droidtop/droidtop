package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks for the real ES-DE video fit + pillarbox port. Every expected
 * value is derived by hand from `VideoFFmpegComponent::resize()`
 * (lines 117-190), `::updateBlackFramePosition()` (lines 1085-1145) and
 * the corner-radius rule in `::render()` (lines 261-273) in real ES-DE's
 * own source, cited per test.
 */
class EsDeVideoLayoutTest {

    /** VideoFFmpegComponent.cpp:127-142 -- maxSize contains, it never crops. */
    @Test
    fun `wide video in a square area is letterboxed to fit`() {
        val frame = esDeVideoFrame(
            areaWidth = 400f, areaHeight = 400f,
            sourceWidth = 640, sourceHeight = 480,
            stretch = false, drawPillarboxes = false,
            thresholdX = 0.85f, thresholdY = 0.90f, cornerRadius = 0f,
        )
        // scale = min(400/640, 400/480) = 0.625 -> 400 x 300.
        assertEquals(400f, frame.videoWidth, 0.001f)
        assertEquals(300f, frame.videoHeight, 0.001f)
        // pillarboxes off -> the black frame is exactly the video.
        assertEquals(400f, frame.frameWidth, 0.001f)
        assertEquals(300f, frame.frameHeight, 0.001f)
    }

    /** VideoFFmpegComponent.cpp:173-176 -- a two-component `size` stretches instead. */
    @Test
    fun `stretch mode fills the whole area regardless of aspect`() {
        val frame = esDeVideoFrame(
            400f, 400f, 640, 480,
            stretch = true, drawPillarboxes = true,
            thresholdX = 0.85f, thresholdY = 0.90f, cornerRadius = 0f,
        )
        assertEquals(400f, frame.videoWidth, 0.001f)
        assertEquals(400f, frame.videoHeight, 0.001f)
        assertEquals(400f, frame.frameWidth, 0.001f)
        assertEquals(400f, frame.frameHeight, 0.001f)
    }

    /**
     * VideoFFmpegComponent.cpp:1105-1123 -- landscape video, height
     * ratio 300/400 = 0.75 which IS below the 0.90 default, so the frame
     * is expanded to the full area height (letterbox bars).
     */
    @Test
    fun `letterbox bars appear when the height ratio is below the threshold`() {
        val frame = esDeVideoFrame(
            400f, 400f, 640, 480,
            stretch = false, drawPillarboxes = true,
            thresholdX = 0.85f, thresholdY = 0.90f, cornerRadius = 0f,
        )
        assertEquals(300f, frame.videoHeight, 0.001f)
        assertEquals(400f, frame.frameHeight, 0.001f)
        // Width already fills the area, so it is left alone.
        assertEquals(400f, frame.frameWidth, 0.001f)
    }

    /**
     * Same geometry, but a threshold of 0.7 puts 0.75 ABOVE it -- real
     * ES-DE then draws no bars at all, since narrow bars look worse than
     * none (VideoFFmpegComponent.cpp:1095-1099).
     */
    @Test
    fun `no bars when the ratio is above the threshold`() {
        val frame = esDeVideoFrame(
            400f, 400f, 640, 480,
            stretch = false, drawPillarboxes = true,
            thresholdX = 0.85f, thresholdY = 0.70f, cornerRadius = 0f,
        )
        assertEquals(300f, frame.frameHeight, 0.001f)
    }

    /**
     * VideoFFmpegComponent.cpp:1124-1133 -- a portrait video only ever
     * gets its WIDTH expanded; the frame height stays the video height.
     */
    @Test
    fun `portrait video only widens the frame`() {
        val frame = esDeVideoFrame(
            400f, 400f, 240, 320,
            stretch = false, drawPillarboxes = true,
            thresholdX = 0.85f, thresholdY = 0.90f, cornerRadius = 0f,
        )
        // scale = min(400/240, 400/320) = 1.25 -> 300 x 400.
        assertEquals(300f, frame.videoWidth, 0.001f)
        assertEquals(400f, frame.videoHeight, 0.001f)
        // 300/400 = 0.75 < 0.85 -> widened to the full area.
        assertEquals(400f, frame.frameWidth, 0.001f)
        assertEquals(400f, frame.frameHeight, 0.001f)
    }

    /** VideoComponent.cpp:430-434 -- thresholds are clamped to 0.2..1.0. */
    @Test
    fun `thresholds are clamped`() {
        val frame = esDeVideoFrame(
            400f, 400f, 640, 480,
            stretch = false, drawPillarboxes = true,
            thresholdX = 9f, thresholdY = 9f, cornerRadius = 0f,
        )
        // Clamped to 1.0, so 0.75 < 1.0 and the bars are drawn.
        assertEquals(400f, frame.frameHeight, 0.001f)
    }

    /**
     * VideoFFmpegComponent.cpp:261-273 -- once a bar is at least twice
     * the corner radius the video quad stops rounding its own corners.
     */
    @Test
    fun `corner rounding is suppressed behind wide bars`() {
        val wideBars = esDeVideoFrame(
            400f, 400f, 640, 480,
            stretch = false, drawPillarboxes = true,
            thresholdX = 0.85f, thresholdY = 0.90f, cornerRadius = 10f,
        )
        // Letterbox bar is (400 - 300) = 100 tall, well over 2 * 10.
        assertFalse(wideBars.roundVideoCorners)

        val noBars = esDeVideoFrame(
            400f, 300f, 640, 480,
            stretch = false, drawPillarboxes = true,
            thresholdX = 0.85f, thresholdY = 0.90f, cornerRadius = 10f,
        )
        // Video exactly fills the area -> no bars -> corners stay rounded.
        assertTrue(noBars.roundVideoCorners)
    }

    /**
     * VideoFFmpegComponent.cpp:1091-1094 -- before a frame is decoded
     * there is no texture size, and the black frame covers the whole area
     * so the gap before playback starts is black rather than empty.
     */
    @Test
    fun `unknown source size fills the area with the black frame`() {
        val frame = esDeVideoFrame(
            400f, 300f, 0, 0,
            stretch = false, drawPillarboxes = true,
            thresholdX = 0.85f, thresholdY = 0.90f, cornerRadius = 0f,
        )
        assertEquals(400f, frame.frameWidth, 0.001f)
        assertEquals(300f, frame.frameHeight, 0.001f)
    }

    /**
     * VideoComponent.cpp:161-166 -- `imageMaxSize` sizes the STATIC image
     * and nothing else. 0.5 x 0.25 of a 1000x800 view is 500 x 200, and
     * the verb is FIT because that is what `setImageMaxSize` means.
     */
    @Test
    fun `imageMaxSize sizes the static image independently of the video`() {
        val area = esDeVideoStaticImageArea(
            imageSize = null,
            imageMaxSize = EsDeThemeValue.Pair(0.5f, 0.25f),
            imageCropSize = null,
            videoSize = null,
            videoMaxSize = EsDeThemeValue.Pair(0.9f, 0.9f),
            videoCropSize = null,
            areaWidth = 1000f,
            areaHeight = 800f,
        )
        assertEquals(500f, area.width, 0.001f)
        assertEquals(200f, area.height, 0.001f)
        assertEquals(EsDeImageFit.FIT, area.fit)
    }

    /**
     * VideoComponent.cpp:144-177 -- the image group is if/else-if, so
     * `imageSize` wins outright over `imageMaxSize` and brings the
     * STRETCH verb with it.
     */
    @Test
    fun `imageSize takes precedence over imageMaxSize and stretches`() {
        val area = esDeVideoStaticImageArea(
            imageSize = EsDeThemeValue.Pair(0.4f, 0.4f),
            imageMaxSize = EsDeThemeValue.Pair(0.9f, 0.9f),
            imageCropSize = EsDeThemeValue.Pair(0.8f, 0.8f),
            videoSize = null, videoMaxSize = null, videoCropSize = null,
            areaWidth = 1000f, areaHeight = 1000f,
        )
        assertEquals(400f, area.width, 0.001f)
        assertEquals(400f, area.height, 0.001f)
        assertEquals(EsDeImageFit.STRETCH, area.fit)
    }

    /**
     * VideoFFmpegComponent.cpp:66-98 -- with nothing in the image group,
     * the static image inherits the video's box AND its verb, which is why
     * a `cropSize`-only video crops its poster too.
     */
    @Test
    fun `the static image inherits the video box when it declares none`() {
        val cropped = esDeVideoStaticImageArea(
            imageSize = null, imageMaxSize = null, imageCropSize = null,
            videoSize = null, videoMaxSize = null,
            videoCropSize = EsDeThemeValue.Pair(0.3f, 0.6f),
            areaWidth = 1000f, areaHeight = 500f,
        )
        assertEquals(300f, cropped.width, 0.001f)
        assertEquals(300f, cropped.height, 0.001f)
        assertEquals(EsDeImageFit.CROP, cropped.fit)

        val stretched = esDeVideoStaticImageArea(
            imageSize = null, imageMaxSize = null, imageCropSize = null,
            videoSize = EsDeThemeValue.Pair(0.5f, 0.5f),
            videoMaxSize = EsDeThemeValue.Pair(0.9f, 0.9f),
            videoCropSize = null,
            areaWidth = 1000f, areaHeight = 1000f,
        )
        assertEquals(EsDeImageFit.STRETCH, stretched.fit)
        assertEquals(500f, stretched.width, 0.001f)
    }

    /**
     * VideoComponent.cpp:147-165 -- the clamps. A `size` axis of exactly
     * zero is left alone (it means "derive from the other axis"), a
     * fully-zero `imageSize` is corrected to 0.01, and a `maxSize` axis is
     * clamped unconditionally to 0.01..2.0.
     */
    @Test
    fun `real clamps are applied per axis`() {
        val zeroed = esDeVideoStaticImageArea(
            imageSize = EsDeThemeValue.Pair(0f, 0f),
            imageMaxSize = null, imageCropSize = null,
            videoSize = null, videoMaxSize = null, videoCropSize = null,
            areaWidth = 1000f, areaHeight = 1000f,
        )
        assertEquals(10f, zeroed.width, 0.001f)
        assertEquals(10f, zeroed.height, 0.001f)

        val oneAxis = esDeVideoStaticImageArea(
            imageSize = EsDeThemeValue.Pair(0.5f, 0f),
            imageMaxSize = null, imageCropSize = null,
            videoSize = null, videoMaxSize = null, videoCropSize = null,
            areaWidth = 1000f, areaHeight = 1000f,
        )
        assertEquals(500f, oneAxis.width, 0.001f)
        assertEquals(0f, oneAxis.height, 0.001f)

        val overMax = esDeVideoStaticImageArea(
            imageSize = null,
            imageMaxSize = EsDeThemeValue.Pair(5f, 0.001f),
            imageCropSize = null,
            videoSize = null, videoMaxSize = null, videoCropSize = null,
            areaWidth = 1000f, areaHeight = 1000f,
        )
        assertEquals(2000f, overMax.width, 0.001f)
        assertEquals(10f, overMax.height, 0.001f)
    }
}
