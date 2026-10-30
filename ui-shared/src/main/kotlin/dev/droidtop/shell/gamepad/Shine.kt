package dev.droidtop.shell.gamepad

// The sheen is ported from DroidDeck (ui/FrontEndScreen.kt at 9310d19, `shine`, GPL-3.0, see NOTICE.md),
// reworked onto droidtop's Motion roles: its brush is built once per size, not per frame.

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import dev.droidtop.ui.shared.Motion

/**
 * A one-shot diagonal sheen across the content each time [trigger] turns
 * true: the capsule that just took the cursor, the Play button. [play] is
 * the Play button's slower stripe. Drawn over the content in the draw phase
 * only; nothing is drawn while it is not sweeping.
 */
fun Modifier.shine(trigger: Boolean, play: Boolean = false, strength: Float = 0.22f): Modifier = composed {
    val x = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger && Motion.enabled) {
            x.snapTo(-1f)
            x.animateTo(1f, Motion.shine(play))
        }
    }
    drawWithCache {
        val w = size.width
        val band = w * 0.35f
        val brush = Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
            start = Offset(-band, 0f),
            end = Offset(band, size.height),
        )
        onDrawWithContent {
            drawContent()
            val p = x.value
            if (p > -1f && p < 1f) {
                val c = w * 0.5f + p * w * 0.9f
                translate(left = c) { drawRect(brush, topLeft = Offset(-c, 0f), size = size) }
            }
        }
    }
}
