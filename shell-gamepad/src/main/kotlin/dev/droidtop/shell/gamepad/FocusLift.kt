package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The focus treatment for a capsule: lifts a little, gains a deeper shadow
 * and full brightness while [focused], and sits slightly dimmed otherwise
 * (docs/SPEC.md "Gaming motion and focus"). Shape glides in over
 * [Motion.LiftMs] and settles back over [Motion.ReleaseMs]. The shape is
 * clipped here, so what is drawn inside (art, [selectionFrame]) is clipped
 * with it.
 *
 * Every animated value is read in the layer and draw phases only, so a
 * focus move recomposes nothing, and the dim is one flat rectangle, not a
 * colour filter.
 */
fun Modifier.focusLift(focused: Boolean, shape: Shape): Modifier = composed {
    val progress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (focused) Motion.lift<Float>() else Motion.release<Float>(),
        label = "focus lift",
    )
    this
        .graphicsLayer {
            val p = progress.value
            val s = FocusLook.scale(p)
            scaleX = s
            scaleY = s
            shadowElevation = FocusLook.shadowDp(p) * density
            this.shape = shape
            clip = false
        }
        .clip(shape)
        .drawWithContent {
            drawContent()
            val dim = FocusLook.dimAlpha(progress.value)
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
        }
}
