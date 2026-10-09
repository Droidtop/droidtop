package org.pocketworkstation.pckeyboard

import kotlin.math.roundToInt

/**
 * The shape of the key grid on the screen (docs/SPEC.md 6a, "One-handed and split", Droidtop/tracker#342): the
 * full width, a narrower grid at the left or right edge for one hand, or the grid split with a gap in the middle
 * so each thumb has a half (a handheld held in landscape). The key layouts themselves are untouched: the same
 * keys are scaled and moved once they are loaded ([layout], called from `Keyboard.applyForm`). No Android classes.
 */
object KeyboardForm {
    const val PREF = "pref_keyboard_form"

    /**
     * [widthFraction] of the keyboard's width the keys use, [gapFraction] of it opened in the middle, and whether a
     * grid narrower than the screen sits at the [atEnd] (right) edge rather than the start (left).
     */
    data class Form(val id: String, val widthFraction: Float, val gapFraction: Float, val atEnd: Boolean)

    @JvmField val FULL = Form("full", 1f, 0f, false)

    @JvmField val SPLIT = Form("split", 0.8f, 0.2f, false)

    @JvmField val LEFT = Form("left", 0.7f, 0f, false)

    @JvmField val RIGHT = Form("right", 0.7f, 0f, true)

    private val ALL = listOf(FULL, SPLIT, LEFT, RIGHT)

    /** The form named [id]; the full width for anything else. */
    @JvmStatic
    fun of(id: String?): Form = ALL.firstOrNull { it.id == id } ?: FULL

    /** The form after [form] on the strip's button: full, split, left, right, and round again. */
    fun next(form: Form): Form = ALL[(ALL.indexOf(form) + 1) % ALL.size]

    /**
     * Scales the keys' [x], [width] and [gap] (in place) by the form's width, opens the middle gap and returns the
     * grid's new total width. A key well across the middle (the space bar) is widened over the gap; a key that
     * only touches the middle by rounding stays on its side with its own width, and keys right of it move across.
     */
    @JvmStatic
    fun layout(x: IntArray, width: IntArray, gap: IntArray, total: Int, form: Form): Int {
        if (total <= 0 || (form.widthFraction >= 1f && form.gapFraction <= 0f)) return total
        val f = form.widthFraction
        for (i in x.indices) {
            x[i] = (x[i] * f).roundToInt()
            width[i] = (width[i] * f).roundToInt()
            gap[i] = (gap[i] * f).roundToInt()
        }
        val scaled = (total * f).roundToInt()
        val gapPx = (total * form.gapFraction).roundToInt()
        if (gapPx > 0) {
            val mid = scaled / 2
            for (i in x.indices) {
                // Rounding leaves a key that ends or starts at the middle a pixel or two over it; that key belongs
                // to the side it is mostly on. Only a key well across the middle (the space bar) spans the gap.
                val tolerance = width[i] / 4
                val onRight = mid - x[i] <= tolerance
                val onLeft = x[i] + width[i] - mid <= tolerance
                if (onLeft) continue
                if (onRight) x[i] += gapPx else width[i] += gapPx
            }
        }
        return scaled + gapPx
    }
}
