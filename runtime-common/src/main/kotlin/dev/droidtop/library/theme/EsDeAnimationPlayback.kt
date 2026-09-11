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
