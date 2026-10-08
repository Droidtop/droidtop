package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The focus treatment for a capsule (docs/SPEC.md "Gaming motion and
 * focus", [FocusLook]): while [focused] it grows a little from near its
 * bottom edge, rises a few dp, comes up to full brightness and its black
 * shadow deepens, as Steam's does; otherwise it sits slightly dimmed on a
 * small one. Shape glides in over [Motion.LiftMs] and settles
 * back over the slower [Motion.ReleaseMs]. [wide] is a landscape card,
 * which grows half as much. The shape is clipped here, so what is drawn
 * inside (art, the sheen) is clipped with it.
 *
 * Every animated value is read in the layer and draw phases only, so a
 * focus move recomposes nothing, and the dim is one flat rectangle, not a
 * colour filter.
 */
fun Modifier.focusLift(focused: Boolean, shape: Shape, wide: Boolean = false): Modifier = composed {
    val progress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (focused) Motion.lift<Float>() else Motion.release<Float>(),
        label = "focus lift",
    )
    this
        .graphicsLayer {
            val p = progress.value
            val s = FocusLook.scale(p, wide)
            scaleX = s
            scaleY = s
            translationY = FocusLook.riseDp(p) * density
            transformOrigin = TransformOrigin(0.5f, 0.9f)
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
