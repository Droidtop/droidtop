package dev.droidtop.library.theme

/**
 * Real ES-DE `video` GEOMETRY as pure maths -- the aspect fit real ES-DE
 * performs on a decoded video frame plus the black pillarbox/letterbox
 * frame drawn behind it, ported from
 * `VideoFFmpegComponent::resize()`/`::updateBlackFramePosition()` and the
 * corner-radius rule in `VideoFFmpegComponent::render()` (real ES-DE
 * source at /root/es-de-reference). No Compose or Android dependency, so
 * it is unit-testable without a screen -- the same split, for the same
 * reason, as `EsDeCarouselLayout.kt`/`EsDeGridLayout.kt`/
 * `EsDeTextListLayout.kt`.
 *
 * droidtop previously drew every themed video with ExoPlayer's
 * `RESIZE_MODE_ZOOM`, which CROPS the video to fill the themed area. Real
 * ES-DE never does that for a `maxSize` video -- it fits the frame inside
 * the area and fills the leftover with a black frame -- so every video
 * whose aspect ratio differed from its themed box was rendered
 * over-zoomed with its edges cut off. All ten real themes measured for
 * this pass size their `video` elements with `maxSize`, i.e. the fitting
 * branch, and nine of them declare `pillarboxes` explicitly.
 */
data class EsDeVideoFrame(
    /** The video quad itself, after the aspect fit. */
    val videoWidth: Float,
    val videoHeight: Float,
    /** The black frame drawn behind it -- equal to the video quad when no bars are wanted. */
    val frameWidth: Float,
    val frameHeight: Float,
    /** Real ES-DE suppresses the video quad's own rounded corners once the bars are visibly wider than the radius. */
    val roundVideoCorners: Boolean,
)

/**
 * [areaWidth]/[areaHeight] are the themed element's own resolved box.
 * [sourceWidth]/[sourceHeight] are the decoded video's real pixel
 * dimensions (0 until the player reports them, in which case the frame
 * fills the whole area -- real ES-DE's own behaviour of drawing the black
 * frame for the moment before a texture exists,
 * VideoFFmpegComponent.cpp:1091-1094).
 *
 * [stretch] is real ES-DE's `size`-vs-`maxSize` distinction
 * (VideoFFmpegComponent.cpp:173-186): a `<size>` with BOTH components set
 * stretches the video and so can never produce bars, while `<maxSize>`
 * fits it (VideoFFmpegComponent.cpp:127-142).
 *
 * The threshold rule is VideoFFmpegComponent.cpp:1103-1132 verbatim: bars
 * are only drawn when the fitted video is smaller than the area on that
 * axis AND its ratio to the area is BELOW the threshold -- real ES-DE's
 * stated reason is that very narrow bars look worse than none. Real
 * defaults are 0.85 (x) and 0.90 (y), clamped to 0.2..1.0
 * (VideoComponent.cpp:39, :430-434).
 */
fun esDeVideoFrame(
    areaWidth: Float,
    areaHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    stretch: Boolean,
    drawPillarboxes: Boolean,
    thresholdX: Float,
    thresholdY: Float,
    cornerRadius: Float,
): EsDeVideoFrame {
    if (sourceWidth <= 0 || sourceHeight <= 0) {
        return EsDeVideoFrame(areaWidth, areaHeight, areaWidth, areaHeight, cornerRadius > 0f)
    }
    val videoWidth: Float
    val videoHeight: Float
    if (stretch) {
        videoWidth = areaWidth
        videoHeight = areaHeight
    } else {
        // VideoFFmpegComponent.cpp:127-142 -- scale by whichever axis
        // needs the smaller factor, i.e. contain.
        val scale = minOf(areaWidth / sourceWidth, areaHeight / sourceHeight)
        videoWidth = sourceWidth * scale
        videoHeight = sourceHeight * scale
    }

    var frameWidth = videoWidth
    var frameHeight = videoHeight
    if (drawPillarboxes) {
        val thrX = thresholdX.coerceIn(0.2f, 1.0f)
        val thrY = thresholdY.coerceIn(0.2f, 1.0f)
        if (videoWidth > videoHeight) {
            // Landscape (VideoFFmpegComponent.cpp:1105-1123).
            frameHeight =
                if (videoHeight < areaHeight && videoHeight / areaHeight < thrY) areaHeight else videoHeight
            frameWidth =
                if (videoWidth < areaWidth && videoWidth / areaWidth < thrX) areaWidth else videoWidth
        } else {
            // Portrait or square (VideoFFmpegComponent.cpp:1124-1133) --
            // note real ES-DE checks <= on this branch and only ever
            // widens; the height is left at the video's own.
            frameWidth =
                if (videoWidth <= areaWidth && videoWidth / areaWidth < thrX) areaWidth else videoWidth
            frameHeight = videoHeight
        }
    }

    // VideoFFmpegComponent.cpp:261-273: don't round the video quad's own
    // corners once a bar is at least twice the radius wide, since the
    // rounding would then just cut into the black frame behind it.
    var roundVideoCorners = cornerRadius > 0f
    if (roundVideoCorners && drawPillarboxes) {
        if (frameWidth > videoWidth && frameWidth - videoWidth >= cornerRadius * 2f) {
            roundVideoCorners = false
        } else if (frameHeight > videoHeight && frameHeight - videoHeight >= cornerRadius * 2f) {
            roundVideoCorners = false
        }
    }
    return EsDeVideoFrame(videoWidth, videoHeight, frameWidth, frameHeight, roundVideoCorners)
}
