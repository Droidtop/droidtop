package dev.droidtop.library.theme

/**
 * Real `direction` (GIFAnimComponent.cpp:343-368), as the two independent
 * facts ES-DE actually stores: which way the first pass runs, and whether
 * the animation bounces. The four theme literals collapse onto those two.
 */
data class EsDeAnimationDirection(val reverseStart: Boolean, val alternate: Boolean) {
    companion object {
        fun of(value: String?): EsDeAnimationDirection = when (value) {
            "reverse" -> EsDeAnimationDirection(reverseStart = true, alternate = false)
            "alternate" -> EsDeAnimationDirection(reverseStart = false, alternate = true)
            "alternateReverse" -> EsDeAnimationDirection(reverseStart = true, alternate = true)
            // "normal", and ES-DE's own warned-about fallback for anything
            // else (:361-367).
            else -> EsDeAnimationDirection(reverseStart = false, alternate = false)
        }
    }
}

/**
 * Which frame of an `animation` element is on screen at [elapsedMs].
 *
 * A port of the frame bookkeeping in `GIFAnimComponent::update` and
 * `::render` (GIFAnimComponent.cpp:444-570), expressed as a function of
 * elapsed time instead of an accumulator, because that is the same answer
 * without the frame-skipping repair ES-DE needs and Compose does not.
 * `LottieAnimComponent::update`/`::render` (LottieAnimComponent.cpp:
 * 410-459, :480-518) carries the same bookkeeping line for line, so a
 * Lottie animation's frame comes from here too.
 *
 * The rules that are not obvious from the property names:
 *
 *  * The pace is ONE rate for the whole animation, not per-frame
 *    durations: `mTargetPacing = (1000 / mFrameRate) / mSpeedModifier`
 *    (:238). `speed` is therefore a divisor of the frame interval, and
 *    ES-DE clamps it to 0.2..3.0 (:341).
 *  * A bounce does NOT repeat the frame it turned on. After the first
 *    pass, `mFrameNum` restarts at `mTotalFrames - 2` or at `1`
 *    (:525-532), so every pass after the first is one frame shorter.
 *  * `iterationCount` counts PASSES, and `alternate` doubles it
 *    (:369-373) -- so `alternate` with `iterationCount` 1 plays out and
 *    back, not out only. Zero means forever (:534).
 *  * When the count runs out ES-DE pauses and puts `mFrameNum` past the
 *    end (:534-538), which holds whatever was last drawn. Here that is
 *    the last frame of the final pass.
 */
fun esDeAnimationFrame(
    elapsedMs: Long,
    totalFrames: Int,
    targetPacingMs: Int,
    direction: EsDeAnimationDirection,
    iterationCount: Int,
): Int {
    if (totalFrames <= 1 || targetPacingMs <= 0) return 0
    val last = totalFrames - 1
    // Passes after the first skip the frame the bounce turned on.
    val laterPassLength = if (direction.alternate) last else totalFrames
    val step = (elapsedMs / targetPacingMs).coerceAtLeast(0L)

    var pass: Long
    var pos: Long
    if (step < totalFrames) {
        pass = 0
        pos = step
    } else {
        pass = 1 + (step - totalFrames) / laterPassLength
        pos = (step - totalFrames) % laterPassLength
    }

    // GIFAnimComponent.cpp:369-373: alternate doubles the declared count.
    val passLimit = if (iterationCount == 0) 0 else iterationCount * (if (direction.alternate) 2 else 1)
    if (passLimit != 0 && pass >= passLimit) {
        // Held on the last frame the final pass drew.
        pass = (passLimit - 1).toLong()
        pos = (if (pass == 0L) totalFrames else laterPassLength) - 1L
    }

    // Each pass flips the direction only when the animation alternates.
    val reverseNow =
        if (direction.alternate) direction.reverseStart != (pass % 2 == 1L)
        else direction.reverseStart

    // :525-532 -- only a BOUNCE starts one frame in from the end it turned
    // on. A plain loop restarts at the very end it started from, which is
    // the `else` here and is what droidtop got wrong first time round.
    val bouncedPass = direction.alternate && pass > 0L
    return when {
        bouncedPass && reverseNow -> (last - 1 - pos).toInt()
        bouncedPass -> (1 + pos).toInt()
        reverseNow -> (last - pos).toInt()
        else -> pos.toInt()
    }.coerceIn(0, last)
}

/** Real `speed` clamp (GIFAnimComponent.cpp:340-341) applied to a frame interval. */
fun esDeAnimationPacingMs(frameIntervalMs: Int, speed: Float?): Int {
    val modifier = (speed ?: 1f).coerceIn(0.2f, 3.0f)
    return (frameIntervalMs / modifier).toInt().coerceAtLeast(1)
}

/**
 * The drawn size of a Lottie `animation` element, and the size it is
 * rasterised at, in pixels.
 */
data class EsDeLottieSize(val width: Int, val height: Int, val rasterWidth: Int, val rasterHeight: Int)

/**
 * A port of `LottieAnimComponent`'s sizing, which is not the image
 * element's: the animation's own viewport supplies the aspect ratio.
 *
 *  * `size` (LottieAnimComponent.cpp:276-289): each axis clamped to
 *    0.01..1 of the screen when positive; `0 0` is a theme error ES-DE
 *    turns into `0.01 0.01`. One axis left at zero is derived from the
 *    viewport's aspect ratio (:165-172), the other is exact.
 *  * `maxSize` (:290-295, :139-164): fitted inside the box, aspect kept.
 *  * Neither: ES-DE's constructor default of a fifth of the screen on
 *    each axis (:64), stretched.
 *  * `scaleFactor` (:297-298, :185-188), clamped 0.1..1, rasterises at a
 *    fraction of the drawn size, which is then scaled up to it.
 *
 * Every pixel figure is truncated to a whole number, as ES-DE's `size_t`
 * casts do.
 */
fun esDeLottieSize(
    size: Pair<Float, Float>?,
    maxSize: Pair<Float, Float>?,
    scaleFactor: Float?,
    viewportWidth: Int,
    viewportHeight: Int,
    screenWidth: Float,
    screenHeight: Float,
): EsDeLottieSize {
    var targetIsMax = false
    val boxWidth: Float
    val boxHeight: Float
    if (size != null) {
        var x = size.first
        var y = size.second
        if (x == 0f && y == 0f) {
            x = 0.01f
            y = 0.01f
        }
        if (x > 0f) x = x.coerceIn(0.01f, 1f)
        if (y > 0f) y = y.coerceIn(0.01f, 1f)
        boxWidth = x.coerceAtLeast(0f) * screenWidth
        boxHeight = y.coerceAtLeast(0f) * screenHeight
    } else if (maxSize != null) {
        boxWidth = maxSize.first.coerceIn(0.01f, 1f) * screenWidth
        boxHeight = maxSize.second.coerceIn(0.01f, 1f) * screenHeight
        targetIsMax = true
    } else {
        boxWidth = 0.2f * screenWidth
        boxHeight = 0.2f * screenHeight
    }

    // :140-144 guards a zero viewport only on the maxSize path; the ratio
    // below divides by it on every path, so it is guarded for all.
    val viewportW = viewportWidth.coerceAtLeast(1).toFloat()
    val viewportH = viewportHeight.coerceAtLeast(1).toFloat()
    val ratio = viewportW.toDouble() / viewportH.toDouble()
    val width: Int
    val height: Int
    when {
        targetIsMax -> {
            val scaleX = boxWidth / viewportW
            val scaleY = boxHeight / viewportH
            if (scaleX < scaleY) {
                width = (viewportW * scaleX).toInt()
                height = minOf(viewportH * scaleX, boxHeight).toInt()
            } else {
                val h = viewportH * scaleY
                height = h.toInt()
                width = minOf((h / viewportH) * viewportW, boxWidth).toInt()
            }
        }
        boxWidth == 0f -> {
            width = (boxHeight.toDouble() * ratio).toInt()
            height = boxHeight.toInt()
        }
        boxHeight == 0f -> {
            width = boxWidth.toInt()
            height = (boxWidth.toDouble() / ratio).toInt()
        }
        else -> {
            width = boxWidth.toInt()
            height = boxHeight.toInt()
        }
    }
    val factor = scaleFactor?.coerceIn(0.1f, 1f) ?: 1f
    val rasterWidth = if (factor != 1f) (width * factor).toInt() else width
    val rasterHeight = if (factor != 1f) (height * factor).toInt() else height
    return EsDeLottieSize(width, height, rasterWidth, rasterHeight)
}

/**
 * A Lottie animation's frame interval: `(1000 / frameRate) / speed`,
 * truncated, with the same 0.2..3.0 `speed` clamp as a GIF
 * (LottieAnimComponent.cpp:204, :319-320). Unlike a GIF, the rate is the
 * file's own declared frame rate, so it is not first rounded to whole
 * milliseconds; zero when the file declares no usable rate.
 */
fun esDeLottiePacingMs(frameRate: Float, speed: Float?): Int {
    if (frameRate <= 0f || frameRate.isNaN()) return 0
    val modifier = (speed ?: 1f).coerceIn(0.2f, 3.0f)
    return ((1000.0 / frameRate) / modifier).toInt().coerceAtLeast(1)
}
