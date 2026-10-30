package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import dev.droidtop.shell.gamepad.input.PadModality
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/**
 * The shell's ONE selection idiom: a raised fill under the window's one
 * focus ring.
 *
 * Every focusable piece of chrome draws selection through this -- menu
 * rows, chips, tabs, buttons, cards, Quick Menu tiles -- so "what am I
 * on" has one answer across the shell. The fill changes at once; the ring
 * is not drawn here but claimed: each window has ONE ring (FocusGlide.kt,
 * [FocusGlideHost]) that slides from the last selected thing to this one,
 * lands and breathes (docs/SPEC.md "Gaming motion and focus").
 *
 * [rest] is the fill while not selected and [selectedFill] while selected;
 * [restOutline] is an optional hairline kept while not selected (the cards
 * keep [MenuTokens.CardOutline]); [ringOutset] puts the ring that far
 * outside the edge (capsules, whose art keeps its edge clear).
 *
 * Drawn only while a pad or keyboard is driving ([PadModality], docs/
 * SPEC.md 6e): on touch the selection is still there -- a tap moves it --
 * but a ring on a row nobody is pressing reads as "the default".
 */
fun Modifier.selectionFrame(
    selected: Boolean,
    shape: Shape,
    rest: Color = MenuTokens.Surface,
    restOutline: Color = Color.Transparent,
    selectedFill: Color = MenuTokens.SurfaceSelected,
    ringOutset: Dp = 0.dp,
    // A row (Droidtop/tracker#644, the visual language's "Focus"): focused, it is the accent at 22 percent behind it and
    // a 4 dp accent bar at its leading edge, and no ring. Art and controls keep the ring; the two never mix on a surface.
    row: Boolean = false,
): Modifier = composed {
    val shown = selected && PadModality.showsFocus
    Modifier
        .background(if (shown && !row) selectedFill else rest, shape)
        .then(if (row) Modifier.rowFocus(shown) else Modifier)
        .then(
            // Never a 0.dp stroke: it would draw as a 1px line, so "no
            // outline" is a transparent colour and nothing is drawn.
            if (restOutline.alpha > 0f) {
                Modifier.drawWithContent {
                    drawContent()
                    val w = 1.dp.toPx()
                    inset(w / 2f) { drawOutline(shape.createOutline(this.size, layoutDirection, this), restOutline, style = Stroke(w)) }
                }
            } else {
                Modifier
            },
        )
        .focusRing(shown && !row, shape, ringOutset)
}

/**
 * The one way a row shows the cursor, in every menu (Droidtop/tracker#644, docs/DESIGN-LANGUAGE.md "Type and focus"):
 * the accent at 22 percent behind it ([MenuTokens.SurfaceSelected]) and a 4 dp accent bar the row's full height at its
 * leading edge. No ring, no growth, no flash: the row never moves under the cursor. [shown] is the cursor being here
 * while a pad drives ([PadModality]). [current] is "you are here" (the left menu's destination, the Settings category
 * on show): a short accent pill on that edge while the cursor is elsewhere.
 */
fun Modifier.rowFocus(shown: Boolean, current: Boolean = false): Modifier = composed {
    if (!shown && !current) return@composed Modifier
    val fill = MenuTokens.SurfaceSelected
    val bar = MenuTokens.Accent
    Modifier.drawBehind {
        val w = RowFocusBar.toPx()
        val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) 0f else size.width - w
        if (shown) {
            drawRect(fill)
            drawRect(bar, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
        } else {
            val h = minOf(RowCurrentMark.toPx(), size.height)
            drawRoundRect(
                bar,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
            )
        }
    }
}

/** How tall "you are here" is on a row's edge. */
private val RowCurrentMark = 20.dp

/** A focused row's leading bar (Droidtop/tracker#644). */
private val RowFocusBar = 4.dp

