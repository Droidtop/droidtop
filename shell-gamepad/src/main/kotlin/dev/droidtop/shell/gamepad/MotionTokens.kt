package dev.droidtop.shell.gamepad

import dev.droidtop.ui.shared.Motion

/**
 * What a focused capsule and the focus ring look like, as pure numbers so
 * the rules are testable: the focused capsule slightly larger, raised a
 * little, at full brightness with a deeper shadow; unfocused ones sit at 90
 * percent brightness with a small shadow. [progress] is the focus animation,
 * 0 unfocused to 1 focused. The ratios are Steam's (a portrait capsule grows
 * 6 percent (the visual language, Droidtop/tracker#644), a landscape one 2.4), and the shadow is black, as Steam's is.
 *
 * Cheap on purpose (no blur anywhere; the shadow is the platform's own), so
 * there is no separate low-performance mode to switch off.
 */
object FocusLook {
    const val LiftScale = 1.06f
    const val LiftScaleWide = 1.024f

    /** How far the focused capsule rises, in dp. */
    const val RiseDp = 4f

    /** The share of black laid over an unfocused capsule: it sits at 90 percent brightness. */
    const val RestDim = 0.10f

    /** A capsule's shadow at rest and focused, in dp. */
    const val RestShadowDp = 4f
    const val FocusShadowDp = 18f

    /** The ring's width, and how far outside a capsule it sits, in dp. */
    const val RingWidthDp = 2f
    const val RingOffsetDp = 2f


    fun scale(progress: Float, wide: Boolean = false): Float =
        1f + ((if (wide) LiftScaleWide else LiftScale) - 1f) * progress.coerceIn(0f, 1f)
    fun riseDp(progress: Float): Float = -RiseDp * progress.coerceIn(0f, 1f)
    fun shadowDp(progress: Float): Float = RestShadowDp + (FocusShadowDp - RestShadowDp) * progress.coerceIn(0f, 1f)
    fun dimAlpha(progress: Float): Float = RestDim * (1f - progress.coerceIn(0f, 1f))

    /** The ring while landing, [landed] 0 to 1: how far outside its place it still is, in dp. */
    fun ringLandOutsetDp(landed: Float): Float = Motion.RingLandFromDp * (1f - landed.coerceIn(0f, 1f))
}
