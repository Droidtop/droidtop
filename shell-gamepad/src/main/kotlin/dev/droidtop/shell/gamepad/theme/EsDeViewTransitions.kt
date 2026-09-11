package dev.droidtop.shell.gamepad.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import dev.droidtop.library.theme.EsDeTransitionAnimation
import dev.droidtop.library.theme.EsDeViewTransition

/**
 * ES-DE's own view-transition timings, from ViewController.cpp.
 *
 *  * FADE is NOT a cross-fade. ViewController.cpp:951-992 fades the whole
 *    screen out over `FADE_DURATION` (120 ms), waits `FADE_WAIT` (200 ms)
 *    with the screen black and the view swapped underneath, then fades
 *    back in over another 120 ms. Reproduced as an exit, a gap and a
 *    delayed enter rather than two overlapping fades, because the black
 *    in the middle is the effect.
 *  * SLIDE is a camera move of 400 ms on an ease-out cubic
 *    (MoveCameraAnimation.h:25-33 -- duration 400, `t*t*t + 1` after
 *    `t -= 1`, which is exactly ease-out cubic).
 *  * INSTANT is a cut, and it is what every transition is before a theme
 *    profile says otherwise (ThemeData.cpp:1053-1054).
 */
private const val FADE_DURATION_MS = 120
private const val FADE_WAIT_MS = 200
private const val SLIDE_DURATION_MS = 400

/**
 * One transition as a Compose [ContentTransform].
 *
 * [towardsGamelist] carries ES-DE's own screen layout, which decides
 * which way a slide goes: the system-select view sits one screen height
 * BELOW the gamelists (ViewController.cpp:1229 positions it at
 * `0, getScreenHeight()`), so entering a gamelist moves the camera up and
 * the gamelist arrives from the top edge, and leaving one reverses that.
 */
fun AnimatedContentTransitionScope<*>.esDeViewTransition(
    animation: EsDeTransitionAnimation,
    towardsGamelist: Boolean,
): ContentTransform = when (animation) {
    EsDeTransitionAnimation.INSTANT ->
        (EnterTransition.None togetherWith ExitTransition.None).withoutSizeAnimation()
    EsDeTransitionAnimation.FADE ->
        (
            fadeIn(tween(FADE_DURATION_MS, delayMillis = FADE_DURATION_MS + FADE_WAIT_MS)) togetherWith
                fadeOut(tween(FADE_DURATION_MS))
            ).withoutSizeAnimation()
    EsDeTransitionAnimation.SLIDE -> {
        val towards =
            if (towardsGamelist) AnimatedContentTransitionScope.SlideDirection.Down
            else AnimatedContentTransitionScope.SlideDirection.Up
        (
            slideIntoContainer(towards, tween(SLIDE_DURATION_MS, easing = EaseOutCubic)) togetherWith
                slideOutOfContainer(towards, tween(SLIDE_DURATION_MS, easing = EaseOutCubic))
            ).withoutSizeAnimation()
    }
}

/**
 * Both views fill the same screen, so there is no size change to animate
 * -- but the container must still CLIP, or a sliding view would be drawn
 * outside it. Snapping the size keeps the clip without inventing a resize
 * ES-DE has no equivalent of.
 *
 * Built through the public constructor: the property setter is internal and
 * the `using` infix is not importable in this Compose version.
 */
private fun ContentTransform.withoutSizeAnimation(): ContentTransform = ContentTransform(
    targetContentEnter,
    initialContentExit,
    targetContentZIndex,
    SizeTransform(clip = true) { _, _ -> snap() },
)

/**
 * What the view around an element is doing right now, so the element can
 * apply ES-DE's two transition-time behaviours: `stationary` and
 * `renderDuringTransitions` (both decided per rendered child in
 * GamelistView.cpp:484-548 and SystemView.cpp:1583-1610, ported in
 * [dev.droidtop.library.theme.EsDeTransitionBehaviour]).
 *
 * [displacement] is how far this copy of the view is currently pushed
 * away from its resting place, as a fraction of the container's height
 * and signed the way the slide moves it. ES-DE gets the same number for
 * free -- a stationary child is simply rendered with the identity matrix
 * instead of the camera's (GamelistView.cpp:552-556), because the camera
 * IS the displacement -- so here a stationary element is translated back
 * by exactly that fraction and ends up pinned to the screen.
 */
data class EsDeTransitionContext(
    val kind: EsDeViewTransition,
    val animation: EsDeTransitionAnimation,
    /** True while the transition is actually moving. */
    val running: Boolean,
    /** True for the copy of the view that is being left behind. */
    val outgoing: Boolean,
    val displacement: Float,
)

/**
 * The transition context for the content currently being composed inside
 * an [androidx.compose.animation.AnimatedContent].
 *
 * The displacement is animated with the SAME duration and easing the
 * slide itself uses, over the same three [EnterExitState] values, so it
 * tracks the container's real offset rather than estimating it.
 */
@Composable
fun AnimatedVisibilityScope.esDeTransitionContext(
    kind: EsDeViewTransition,
    animation: EsDeTransitionAnimation,
    towardsGamelist: Boolean,
    outgoing: Boolean,
): EsDeTransitionContext {
    val displacement = if (animation != EsDeTransitionAnimation.SLIDE) {
        0f
    } else {
        // slideIntoContainer(Down) brings the new view in from the top
        // edge and slideOutOfContainer(Down) pushes the old one off the
        // bottom; Up is the mirror of that.
        transition.animateFloat(
            transitionSpec = { tween(SLIDE_DURATION_MS, easing = EaseOutCubic) },
            label = "ES-DE slide displacement",
        ) { state ->
            when (state) {
                EnterExitState.PreEnter -> if (towardsGamelist) -1f else 1f
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> if (towardsGamelist) 1f else -1f
            }
        }.value
    }
    return EsDeTransitionContext(
        kind = kind,
        animation = animation,
        running = transition.currentState != transition.targetState,
        outgoing = outgoing,
        displacement = displacement,
    )
}

/**
 * Which transition ES-DE would call a move between these two views
 * (ViewController.cpp:1042-1120 names the six; droidtop's system view is
 * the `null` group and everything else is a gamelist).
 */
fun esDeTransitionKind(from: Any?, to: Any?): EsDeViewTransition = when {
    from == null && to == null -> EsDeViewTransition.SYSTEM_TO_SYSTEM
    from == null -> EsDeViewTransition.SYSTEM_TO_GAMELIST
    to == null -> EsDeViewTransition.GAMELIST_TO_SYSTEM
    else -> EsDeViewTransition.GAMELIST_TO_GAMELIST
}
