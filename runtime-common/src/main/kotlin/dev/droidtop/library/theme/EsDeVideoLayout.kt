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

/**
 * Real `ImageComponent::applyTheme` sizing (ImageComponent.cpp:528-557)
 * and the resize rules it selects (ImageComponent.cpp:`resize()`).
 *
 * An `image` element carries ONE size group read as a first-match chain in
 * this exact order -- `if / else if / else if`, never a merge:
 *
 *  - `size`     -> `setResize` (:542): `mTargetIsMax=false`,
 *                  `mTargetIsCrop=false`. With both axes set, `resize()`
 *                  STRETCHES the texture to exactly that box ("If both
 *                  axes are set we just stretch or squash"). With only one
 *                  axis set the other is derived from the source's aspect.
 *  - `maxSize`  -> `setMaxSize` (:548): fit inside the box, aspect kept.
 *  - `cropSize` -> `setCroppedSize` (:556): scale to COVER the box by the
 *                  larger of the two ratios, then crop the overflow.
 *
 * The clamps are ES-DE's own: a fully-zero `size` is corrected to 0.001
 * with a warning (:531-537) and each axis is clamped to 0.001..3.0, while
 * `maxSize`/`cropSize` clamp both axes unconditionally to the same range.
 *
 * droidtop previously collapsed the whole group into "size, else maxSize,
 * else 0.2 x 0.2" and always drew with `ContentScale.Fit`, so the two
 * verbs that are NOT fit rendered wrong: decaffe's own full-screen
 * backgrounds (`backart2`/`backart3`, `<size>1 1</size>` over an 8x8
 * solid-colour PNG) came out as a centred square of the view's short axis
 * instead of covering the screen, and every `cropSize`-only element fell
 * through to the 0.2 default box. Both confirmed by diffing the console's
 * own captures against an official Linux ES-DE render of the same theme
 * (reference/es-de-render/run.sh).
 *
 * Returns the box in the same [EsDeStaticImageArea] shape the video group
 * already uses -- one type for "how big, and scaled how", not two.
 */
fun esDeImageArea(
    size: EsDeThemeValue.Pair?,
    maxSize: EsDeThemeValue.Pair?,
    cropSize: EsDeThemeValue.Pair?,
    areaWidth: Float,
    areaHeight: Float,
): EsDeStaticImageArea {
    if (size != null) {
        // ImageComponent.cpp:531-541 -- a fully-zero size is a theme
        // mistake ES-DE corrects rather than honours, and only axes
        // greater than zero are clamped (a zero axis means "derive this
        // one from the source's aspect ratio").
        val corrected = if (size.x == 0f && size.y == 0f) EsDeThemeValue.Pair(0.001f, 0.001f) else size
        val x = if (corrected.x > 0f) corrected.x.coerceIn(0.001f, 3f) else corrected.x
        val y = if (corrected.y > 0f) corrected.y.coerceIn(0.001f, 3f) else corrected.y
        return EsDeStaticImageArea(x * areaWidth, y * areaHeight, EsDeImageFit.STRETCH)
    }
    if (maxSize != null) {
        return EsDeStaticImageArea(
            maxSize.x.coerceIn(0.001f, 3f) * areaWidth,
            maxSize.y.coerceIn(0.001f, 3f) * areaHeight,
            EsDeImageFit.FIT,
        )
    }
    if (cropSize != null) {
        return EsDeStaticImageArea(
            cropSize.x.coerceIn(0.001f, 3f) * areaWidth,
            cropSize.y.coerceIn(0.001f, 3f) * areaHeight,
            EsDeImageFit.CROP,
        )
    }
    // Nothing declared. Real ES-DE leaves the component at whatever size
    // it inherited, which for a themed element is the source texture's own
    // size; droidtop has no intrinsic-size decode at this layer, so the
    // renderer's own long-standing 0.2 x 0.2 stand-in is kept rather than
    // a second convention being invented -- same choice, and same reason,
    // as [esDeVideoStaticImageArea]'s own final branch.
    return EsDeStaticImageArea(0.2f * areaWidth, 0.2f * areaHeight, EsDeImageFit.FIT)
}

/**
 * Real `VideoComponent::applyTheme` sizing for the PLAYING surface
 * (VideoComponent.cpp:179-211) -- the video group's own first-match chain,
 * `size` -> `maxSize` -> `cropSize`, each with its own verb, and the
 * video's own clamps (0.01..2.0 per axis, a fully-zero `size` corrected to
 * 0.01, only axes greater than zero clamped).
 *
 * This is the same shape [esDeVideoStaticImageArea] already reads for the
 * poster; it is separated out because droidtop's renderer sized the
 * playing surface through the generic element helper instead, which knows
 * only `size`/`maxSize` and falls back to 0.2 x 0.2. decaffe's own system
 * -view preview (`<video name="screen2">`, `cropSize` 0.58 x 0.77 and no
 * `size`/`maxSize` at all) therefore drew at a fifth of the view in each
 * axis instead of filling its box -- confirmed by diffing the console's
 * own capture against an official Linux ES-DE render of the same theme
 * (reference/es-de-render/run.sh).
 */
fun esDeVideoArea(
    videoSize: EsDeThemeValue.Pair?,
    videoMaxSize: EsDeThemeValue.Pair?,
    videoCropSize: EsDeThemeValue.Pair?,
    areaWidth: Float,
    areaHeight: Float,
): EsDeStaticImageArea = esDeVideoStaticImageArea(
    imageSize = null,
    imageMaxSize = null,
    imageCropSize = null,
    videoSize = videoSize,
    videoMaxSize = videoMaxSize,
    videoCropSize = videoCropSize,
    areaWidth = areaWidth,
    areaHeight = areaHeight,
)

/**
 * Real `ImageComponent::resize()` (ImageComponent.cpp:775-828) for the two
 * branches that need the SOURCE image's own intrinsic size, and therefore
 * could not live in [esDeImageArea] -- which runs before anything is
 * decoded and only knows the declared box:
 *
 * - `maxSize` (:788-801): the element's own size becomes the FITTED size,
 *   not the declared box. ES-DE's `origin` then anchors that fitted
 *   picture, so a `maxSize` element with an off-centre origin and a strong
 *   aspect mismatch sits somewhere droidtop's box-anchored placement does
 *   not. decaffe's `back`/`back2` edge art (`maxSize` 0.5 x 0.77,
 *   `origin` 0 0.5) is exactly that case.
 * - `size` with ONE axis set to zero (:814-821): a zero axis means
 *   "derive this one from the source's aspect ratio". droidtop multiplied
 *   the zero through and produced a box of zero height (or width), i.e.
 *   nothing drawn at all.
 *
 * The final clamp (:827-828) is ES-DE's own: at least one pixel, at most
 * three screens.
 *
 * Returns null when the source's intrinsic size is unknown or degenerate,
 * which is ES-DE's own early return at :777-782 -- the caller keeps the
 * box [esDeImageArea] gave it.
 */
fun esDeImageFittedSize(
    targetWidthPx: Float,
    targetHeightPx: Float,
    sourceWidthPx: Float,
    sourceHeightPx: Float,
    fit: EsDeImageFit,
    screenWidthPx: Float,
    screenHeightPx: Float,
): kotlin.Pair<Float, Float>? {
    if (sourceWidthPx <= 0f || sourceHeightPx <= 0f) return null
    var width: Float
    var height: Float
    when (fit) {
        EsDeImageFit.FIT -> {
            if (targetWidthPx <= 0f || targetHeightPx <= 0f) return null
            width = sourceWidthPx
            height = sourceHeightPx
            val scaleX = targetWidthPx / width
            val scaleY = targetHeightPx / height
            if (scaleX < scaleY) {
                width *= scaleX
                height = kotlin.math.min(height * scaleX, targetHeightPx)
            } else {
                height *= scaleY
                width = kotlin.math.min((height / sourceHeightPx) * sourceWidthPx, targetWidthPx)
            }
        }
        EsDeImageFit.STRETCH -> {
            // Both axes set: the declared box IS the size, nothing to derive.
            if (targetWidthPx > 0f && targetHeightPx > 0f) return null
            if (targetWidthPx <= 0f && targetHeightPx > 0f) {
                height = targetHeightPx
                width = (height / sourceHeightPx) * sourceWidthPx
            } else if (targetWidthPx > 0f) {
                height = (targetWidthPx / sourceWidthPx) * sourceHeightPx
                width = (height / sourceHeightPx) * sourceWidthPx
            } else {
                // Neither axis set: ES-DE keeps the texture's own size (:811).
                width = sourceWidthPx
                height = sourceHeightPx
            }
        }
        // The crop branch (:803-808) oversizes the texture and then trims
        // it with coverFitCrop(); Compose's ContentScale.Crop over the
        // declared box is already that result, so the element's own box
        // stays the declared one here.
        EsDeImageFit.CROP -> return null
    }
    return width.coerceIn(1f, screenWidthPx * 3f) to height.coerceIn(1f, screenHeightPx * 3f)
}
