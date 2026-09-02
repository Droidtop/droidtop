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

/** How a themed area constrains the picture inside it -- ES-DE's own three real sizing verbs. */
enum class EsDeImageFit {
    /** `size`/`imageSize`: resize to exactly this box, aspect ratio not preserved. */
    STRETCH,

    /** `maxSize`/`imageMaxSize`: the largest the picture can be while fitting inside the box. */
    FIT,

    /** `cropSize`/`imageCropSize`: fill the box and crop the overflow. */
    CROP,
}

/** The area a `video` element's own STATIC image occupies, and how it is scaled into it. */
data class EsDeStaticImageArea(
    val width: Float,
    val height: Float,
    val fit: EsDeImageFit,
)

/**
 * Real `VideoComponent::applyTheme` sizing for the STATIC image
 * (VideoComponent.cpp:144-177), which is a genuinely separate box from the
 * video's own.
 *
 * A `video` element carries two independent size groups. `imageSize`/
 * `imageMaxSize`/`imageCropSize` size the poster shown before playback
 * starts (and while a `delay` runs, and when the game has no video at
 * all); `size`/`maxSize`/`cropSize` size the playing surface. Each group
 * is a first-match chain in that exact order, not a merge -- ES-DE reads
 * them as `if / else if / else if`.
 *
 * When the theme sets NONE of the image group, the static image inherits
 * the video's own box AND its own verb: `VideoFFmpegComponent::setResize`/
 * `::setMaxSize`/`::setCroppedSize` each forward to `mStaticImage` only
 * `if (mImageAreaSize == {0, 0})` (VideoFFmpegComponent.cpp:66-98). That
 * inheritance is why droidtop reading only the video group looked right on
 * most themes and silently ignored `imageMaxSize` where it was set.
 *
 * All values are normalized fractions of [areaWidth]/[areaHeight] (the
 * element's parent, i.e. the themed view), matching ES-DE's own `scale`
 * (VideoComponent.cpp:140-142). Clamps are ES-DE's: 0.01..2.0 per axis,
 * with `size`/`imageSize` clamping only axes greater than zero (a zero
 * axis means "derive from the other one") and a fully-zero `imageSize`
 * being corrected to 0.01 with a warning (VideoComponent.cpp:147-157).
 */
fun esDeVideoStaticImageArea(
    imageSize: EsDeThemeValue.Pair?,
    imageMaxSize: EsDeThemeValue.Pair?,
    imageCropSize: EsDeThemeValue.Pair?,
    videoSize: EsDeThemeValue.Pair?,
    videoMaxSize: EsDeThemeValue.Pair?,
    videoCropSize: EsDeThemeValue.Pair?,
    areaWidth: Float,
    areaHeight: Float,
): EsDeStaticImageArea {
    fun exact(pair: EsDeThemeValue.Pair): EsDeStaticImageArea {
        // VideoComponent.cpp:147-157/181-191.
        val corrected = if (pair.x == 0f && pair.y == 0f) EsDeThemeValue.Pair(0.01f, 0.01f) else pair
        val x = if (corrected.x > 0f) corrected.x.coerceIn(0.01f, 2f) else corrected.x
        val y = if (corrected.y > 0f) corrected.y.coerceIn(0.01f, 2f) else corrected.y
        return EsDeStaticImageArea(x * areaWidth, y * areaHeight, EsDeImageFit.STRETCH)
    }
    fun bounded(pair: EsDeThemeValue.Pair, fit: EsDeImageFit): EsDeStaticImageArea =
        EsDeStaticImageArea(
            pair.x.coerceIn(0.01f, 2f) * areaWidth,
            pair.y.coerceIn(0.01f, 2f) * areaHeight,
            fit,
        )

    // VideoComponent.cpp:144-177 -- the image group, first match wins.
    if (imageSize != null) return exact(imageSize)
    if (imageMaxSize != null) return bounded(imageMaxSize, EsDeImageFit.FIT)
    if (imageCropSize != null) return bounded(imageCropSize, EsDeImageFit.CROP)

    // VideoFFmpegComponent.cpp:66-98 -- nothing in the image group, so the
    // static image inherits the video group's box and verb.
    if (videoSize != null) return exact(videoSize)
    if (videoMaxSize != null) return bounded(videoMaxSize, EsDeImageFit.FIT)
    if (videoCropSize != null) return bounded(videoCropSize, EsDeImageFit.CROP)

    // Neither group declared. Real ES-DE leaves the component at its
    // inherited GuiComponent size; droidtop's own sizeOf default (0.2 x
    // 0.2 of the view) is what stands in for that everywhere else, so it
    // does here too rather than inventing a second convention.
    return EsDeStaticImageArea(0.2f * areaWidth, 0.2f * areaHeight, EsDeImageFit.FIT)
}
