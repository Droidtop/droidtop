package dev.droidtop.library.theme

import dev.droidtop.notices.ColorMath

/**
 * The focus ring's two inks (docs/SPEC.md 7k, "The focus ring"; Droidtop/tracker#485 F2a): a 3dp outer
 * edge in the theme's accent and a 1dp inner edge in whichever of black and white is farther from the
 * accent, both opaque. Whatever the ring lands on (capsule art that is pure white, pure black, or the
 * accent's own colour), one of the two edges stands out from it by at least 3:1, which one accent ring
 * at 60 percent could not promise (a light accent over a white screenshot vanished). High contrast
 * widens the outer edge to 4dp. Pure, so the guarantee is a JVM test over every theme.
 */
object FocusRingInks {
    const val INNER_DP = 1f
    const val OUTER_DP = 3f
    const val OUTER_HIGH_CONTRAST_DP = 4f

    /** The floor one of the two edges keeps over any background. */
    const val FLOOR = 3.0

    /** The inner edge's ink for an [accent]: black on a light accent, white on a dark one. */
    fun inner(accent: Int): Int {
        val m = ColorMath
        val flat = m.over(accent, ColorMath.BLACK)
        return if (m.ratio(ColorMath.BLACK, flat) >= m.ratio(ColorMath.WHITE, flat)) ColorMath.BLACK else ColorMath.WHITE
    }

    /** The outer edge's width in dp. */
    fun outerDp(highContrast: Boolean): Float = if (highContrast) OUTER_HIGH_CONTRAST_DP else OUTER_DP

    /** The better of the two edges' contrast over [background]: what a person sees the ring by. */
    fun visibility(accent: Int, background: Int): Double {
        val m = ColorMath
        val outer = m.over(accent, background)
        return maxOf(m.ratio(outer, background), m.ratio(inner(accent), background))
    }
}
