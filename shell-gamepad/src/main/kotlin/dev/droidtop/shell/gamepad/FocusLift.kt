package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/*
 * The focus treatment for a capsule (docs/SPEC.md "Gaming motion and focus", [FocusLook]): while focused the card
 * rises a few dp, its black shadow deepens and its art comes up to full brightness and grows a little inside the
 * card, as Steam's does; otherwise it sits slightly dimmed on a small shadow. It glides in over [Motion.LiftMs] and
 * settles back over the slower [Motion.ReleaseMs]. A wide (landscape) card grows half as much.
 *
 * The card itself keeps its size: the art grows inside its fixed edge (owner on the console, Droidtop/tracker#632:
 * the image "scales up but the outline stays at the original size, so the image is clipped by it or pokes
 * through"). When the whole card grew, its top passed the edge its row clips at, more on a taller card, and the
 * window's ring, measured through the same clip, landed inside the art. Now the ring and the clip are the card's own
 * edge at every size. A card puts [focusRise] first, its ring and plate ([selectionFrame]) next, then [focusZoom]
 * over its art, all reading one [rememberFocusLift].
 *
 * Every animated value is read in the layer and draw phases only, so a focus move recomposes nothing, and the dim
 * is one flat rectangle, not a colour filter.
 */

/** How lifted the card is, 0 at rest to 1 focused, animated. */
@Composable
fun rememberFocusLift(focused: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (focused) 1f else 0f,
    animationSpec = if (focused) Motion.lift<Float>() else Motion.release<Float>(),
    label = "focus lift",
)

/** The card as a whole: it rises and its shadow deepens. Its size and edge do not change. */
fun Modifier.focusRise(lift: State<Float>, shape: Shape): Modifier = graphicsLayer {
    val p = lift.value
    translationY = FocusLook.riseDp(p) * density
    shadowElevation = FocusLook.shadowDp(p) * density
    this.shape = shape
    clip = false
}

/**
 * What the card shows: clipped to its [shape], the art grows from the middle inside that fixed edge, and the rest
 * dim lifts. [wide] is a landscape card, which grows half as much.
 */
fun Modifier.focusZoom(lift: State<Float>, shape: Shape, wide: Boolean = false): Modifier = this
    .clip(shape)
    .graphicsLayer {
        val s = FocusLook.scale(lift.value, wide)
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin.Center
    }
    .drawWithContent {
        drawContent()
        val dim = FocusLook.dimAlpha(lift.value)
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
    }
