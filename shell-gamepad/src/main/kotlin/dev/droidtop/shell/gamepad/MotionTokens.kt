package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * droidtop's motion tokens (docs/SPEC.md "Gaming motion and focus"), the
 * sibling of [Space] and [TypeRole] in DesignTokens.kt: every animation the
 * shell's own chrome plays takes its duration and its curve from here by
 * ROLE, so the screens feel like one product and retuning the feel is an
 * edit in this file. A themed ES-DE view is out of scope: it plays the
 * theme's own transitions.
 *
 * The values are our own, tuned for a 5-inch handheld; the principles come
 * from studying how Steam Big Picture feels (credit to its designers, who
 * did the patient work): colour answers "where am I" at once while shape
 * glides, two curves do most of the work, a held direction glides instead of
 * stepping, and letting go of focus is slower than taking it, which reads as
 * weight.
 */
object Motion {
    /** A focus colour or fill change: instant, so the eye gets the answer first. */
    const val ColourMs = 0

    /** Focus taking a capsule or row: its lift, shadow and brightness glide in. */
    const val LiftMs = 300

    /** Focus leaving it: slower than taking it, so the item settles back. */
    const val ReleaseMs = 500

    /** The focus outline landing: it thins and fades in, then rests. */
    const val RingLandMs = 360

    /** A carousel or grid moving the focused item to the centre. */
    const val CarouselCentreMs = 120

    /** A side or bottom panel sliding in; out is shorter, the user is already done with it. */
    const val PanelInMs = 280
    const val PanelOutMs = 180

    /** One shell screen replacing another (a crossfade). */
    const val ScreenMs = 160

    /** A press acknowledging itself. */
    const val PressMs = 60

    /** Ambient, never demanding: a waiting pulse, and the art changing on an idle screen. */
    const val AmbientPulseMs = 900
    const val AmbientFadeMs = 1200

    /** Quick start, soft landing: focus, lift and the first press of a scroll. */
    val Settle: Easing = CubicBezierEasing(0.2f, 0.5f, 0.15f, 0.85f)

    /** A softer glide with a longer tail: panels and the ring landing. */
    val Glide: Easing = CubicBezierEasing(0.15f, 0.85f, 0.4f, 1f)

    /** Slow start, quick leave: something going away. */
    val Exit: Easing = CubicBezierEasing(0.55f, 0f, 1f, 0.6f)

    fun <T> lift(): TweenSpec<T> = tween(LiftMs, easing = Settle)
    fun <T> release(): TweenSpec<T> = tween(ReleaseMs, easing = Glide)
    fun <T> ringLand(): TweenSpec<T> = tween(RingLandMs, easing = Glide)
    fun <T> panelIn(): TweenSpec<T> = tween(PanelInMs, easing = Glide)
    fun <T> panelOut(): TweenSpec<T> = tween(PanelOutMs, easing = Exit)

    /**
     * A scroll that centres the focused item. The first press eases in and
     * out; a press that arrives while the last is still moving, or that is a
     * held direction repeating, replaces it with a linear one, so holding a
     * direction is one smooth glide instead of a run of small stops.
     */
    fun scroll(chained: Boolean): TweenSpec<Float> =
        tween(CarouselCentreMs, easing = if (chained) LinearEasing else Settle)
}

/**
 * What a focused capsule or list row looks like, as pure numbers so the
 * rule is testable: slightly larger and at full brightness with a deeper
 * shadow and an outline that lands; unfocused ones sit slightly dimmed.
 * [progress] is the focus animation, 0 unfocused to 1 focused.
 *
 * Cheap on purpose (no blur anywhere; the shadow is the platform's own and
 * only the focused item has one), so there is no separate low-performance
 * mode to switch off.
 */
object FocusLook {
    /** The focused item's growth: 5 percent. */
    const val LiftScale = 1.05f

    /** The share of black laid over an unfocused item: it sits at 90 percent brightness. */
    const val RestDim = 0.10f

    /** The focused item's shadow, in dp; unfocused items have none. */
    const val FocusShadowDp = 12f

    /** The outline lands from this many times its width, thinning to 1. */
    const val RingLandExtra = 1.0f

    fun scale(progress: Float): Float = 1f + (LiftScale - 1f) * progress.coerceIn(0f, 1f)
    fun shadowDp(progress: Float): Float = FocusShadowDp * progress.coerceIn(0f, 1f)
    fun dimAlpha(progress: Float): Float = RestDim * (1f - progress.coerceIn(0f, 1f))
    fun ringWidthFactor(landed: Float): Float = 1f + RingLandExtra * (1f - landed.coerceIn(0f, 1f))
    fun ringAlpha(landed: Float): Float = landed.coerceIn(0f, 1f)
}
