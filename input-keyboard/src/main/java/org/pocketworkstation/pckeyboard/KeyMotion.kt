package org.pocketworkstation.pckeyboard

/**
 * The rules of Hacker Keyboard Reborn's motion (Droidtop/tracker#903 H12a; docs/SPEC.md 6a, "Motion and haptics"):
 * how long each movement takes, how a pressed key eases, and when there is no motion at all. Pure, so it is tested on
 * the JVM; [KeyboardMotion] reads the device's settings into [durationScale] once per keyboard show, and the views ask
 * here for each frame's values with plain numbers, never allocating.
 *
 * The rule that an animator duration scale of 0 means no animation is HeliBoard's
 * (`EmojiPalettesView.isAnimationsDisabled`, https://github.com/Helium314/HeliBoard at 2d9869b2,
 * Apache-2.0 AND GPL-3.0-only), and droidtop's own (`Motion.enabled` in :ui-shared): Android's accessibility
 * "Remove animations" sets that scale to 0. The process's own animator switch is honoured too.
 */
object KeyMotion {
    /** A pressed key eases down to [PRESSED_SCALE] over this; its pressed colour is drawn at once. */
    const val PRESS_MS = 50L

    /** A released key eases back to full size, and its pressed colour fades, over this. */
    const val RELEASE_MS = 120L

    /** The key preview and the long-press popup grow out of the key over this, and shrink back over [POPUP_OUT_MS]. */
    const val POPUP_IN_MS = 110L
    const val POPUP_OUT_MS = 80L

    /** A tool panel (emoji, kaomoji, clipboard, macros) slides in or out over the keys over this. */
    const val PANEL_MS = 180L

    /** New suggestions fade and rise into the suggestion strip over this. */
    const val CANDIDATE_MS = 130L

    /** How far a pressed key shrinks toward its centre. */
    const val PRESSED_SCALE = 0.92f

    /** Where new suggestions start: this much of their full opacity, this many dp below their line. */
    const val CANDIDATE_FROM_ALPHA = 0.35f
    const val CANDIDATE_RISE_DP = 4f

    /** The largest scale honoured: Android's developer options offer up to 10x. */
    private const val MAX_SCALE = 10f

    /**
     * The duration scale every movement is multiplied by, from Settings.Global's ANIMATOR_DURATION_SCALE ([animator])
     * and whether this process's animators run at all ([animatorsEnabled], `ValueAnimator.areAnimatorsEnabled`, which
     * battery saver can turn off). 0 means no motion: an animator scale of 0 (developer options, or Android's
     * accessibility "Remove animations", which sets it), or animators switched off. An unreadable scale counts as 1.
     */
    @JvmStatic
    fun durationScale(animator: Float, animatorsEnabled: Boolean): Float {
        if (!animatorsEnabled) return 0f
        val a = if (animator.isNaN()) 1f else animator
        if (a <= 0f) return 0f
        return a.coerceAtMost(MAX_SCALE)
    }

    /** [baseMs] under [scale]: 0 when there is no motion, and never less than 1 ms when there is. */
    @JvmStatic
    fun scaled(baseMs: Long, scale: Float): Long = if (scale <= 0f) 0L else (baseMs * scale).toLong().coerceAtLeast(1L)

    /** How far through a movement of [durationMs] we are after [elapsedMs], 0 to 1; a movement of no length is done. */
    @JvmStatic
    fun fraction(elapsedMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || elapsedMs >= durationMs) return 1f
        if (elapsedMs <= 0L) return 0f
        return elapsedMs.toFloat() / durationMs
    }

    /** Cubic ease-out: quick at first, settling gently. */
    @JvmStatic
    fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    /**
     * A key's drawn scale [elapsedMs] after it went [pressed] (or released), starting from [from], the scale it had
     * then. With no motion a key is always drawn full size.
     */
    @JvmStatic
    fun keyScale(pressed: Boolean, from: Float, elapsedMs: Long, motion: Float): Float {
        if (motion <= 0f) return 1f
        val target = if (pressed) PRESSED_SCALE else 1f
        val f = easeOut(fraction(elapsedMs, scaled(if (pressed) PRESS_MS else RELEASE_MS, motion)))
        return from + (target - from) * f
    }

    /**
     * How much of the pressed colour a key is drawn with, 0 to 1: all of it from the moment it is pressed (the press
     * is never delayed by motion), fading from [from] after it is released. With no motion, none once released.
     */
    @JvmStatic
    fun keyTint(pressed: Boolean, from: Float, elapsedMs: Long, motion: Float): Float {
        if (pressed) return 1f
        if (motion <= 0f) return 0f
        return from * (1f - easeOut(fraction(elapsedMs, scaled(RELEASE_MS, motion))))
    }

    /** Whether a key's movement since it went [pressed] (or released) [elapsedMs] ago has finished. */
    @JvmStatic
    fun keySettled(pressed: Boolean, elapsedMs: Long, motion: Float): Boolean =
        motion <= 0f || elapsedMs >= scaled(if (pressed) PRESS_MS else RELEASE_MS, motion)

    /** New suggestions' opacity, 0 to 1, [elapsedMs] after they arrived. */
    @JvmStatic
    fun candidateAlpha(elapsedMs: Long, motion: Float): Float {
        if (motion <= 0f) return 1f
        val f = easeOut(fraction(elapsedMs, scaled(CANDIDATE_MS, motion)))
        return CANDIDATE_FROM_ALPHA + (1f - CANDIDATE_FROM_ALPHA) * f
    }

    /** How far below their line, in dp, new suggestions are drawn [elapsedMs] after they arrived. */
    @JvmStatic
    fun candidateRiseDp(elapsedMs: Long, motion: Float): Float {
        if (motion <= 0f) return 0f
        return CANDIDATE_RISE_DP * (1f - easeOut(fraction(elapsedMs, scaled(CANDIDATE_MS, motion))))
    }
}
