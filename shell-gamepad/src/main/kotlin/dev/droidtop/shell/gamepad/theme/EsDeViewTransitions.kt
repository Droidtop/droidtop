package dev.droidtop.shell.gamepad.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.using
import dev.droidtop.library.theme.EsDeTransitionAnimation

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
        EnterTransition.None togetherWith ExitTransition.None using noSizeAnimation()
    EsDeTransitionAnimation.FADE ->
        fadeIn(tween(FADE_DURATION_MS, delayMillis = FADE_DURATION_MS + FADE_WAIT_MS)) togetherWith
            fadeOut(tween(FADE_DURATION_MS)) using noSizeAnimation()
    EsDeTransitionAnimation.SLIDE -> {
        val towards =
            if (towardsGamelist) AnimatedContentTransitionScope.SlideDirection.Down
            else AnimatedContentTransitionScope.SlideDirection.Up
        slideIntoContainer(towards, tween(SLIDE_DURATION_MS, easing = EaseOutCubic)) togetherWith
            slideOutOfContainer(towards, tween(SLIDE_DURATION_MS, easing = EaseOutCubic)) using
            noSizeAnimation()
    }
}

/**
 * Both views fill the same screen, so there is no size change to animate
 * -- but the container must still CLIP, or a sliding view would be drawn
 * outside it. Snapping the size keeps the clip without inventing a
 * resize ES-DE has no equivalent of.
 */
private fun noSizeAnimation(): SizeTransform = SizeTransform(clip = true) { _, _ -> snap() }
