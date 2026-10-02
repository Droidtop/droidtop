package dev.droidtop.shell.gamepad

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal fun Modifier.opticallyCentred(height: Dp, fontSize: TextUnit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val boxHeight = height.roundToPx()
    val baseline = placeable[FirstBaseline]
    val y = if (baseline == AlignmentLine.Unspecified) {
        (boxHeight - placeable.height) / 2
    } else {
        val em = (if (fontSize.isSp) fontSize else 16.sp).toPx()
        (boxHeight / 2f - (baseline - CAP_MIDDLE_EM * em)).roundToInt()
    }
    layout(placeable.width, boxHeight) { placeable.place(0, y) }
}

private const val CAP_MIDDLE_EM = 0.36f

/**
 * The hairline that ends a frame bar on the side facing the content: the
 * header's bottom edge, the footer's top edge. The only shared drawing the
 * two bars have beyond their height and gutter, so they read as one frame
 * (docs/SPEC.md 7k) while the footer keeps its own plate.
 */
internal fun Modifier.frameEdge(atTop: Boolean): Modifier = drawBehind {
    val y = if (atTop) 0.5f else size.height - 0.5f
    drawLine(MenuTokens.FrameHairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
}
