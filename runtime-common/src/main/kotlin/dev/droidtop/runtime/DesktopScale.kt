package dev.droidtop.runtime

import kotlin.math.roundToInt

/**
 * How large the Linux desktop draws (Droidtop/tracker#386, docs/SPEC.md 4
 * "Desktop scale"): the compositor output's scale, logical pixel to
 * physical pixels. The output keeps the screen's full pixel size, so
 * nothing is lost to it; only text and controls grow.
 *
 * The person picks it in Desktop settings ([KEY]); [AUTOMATIC], the
 * default, follows Android's own density for the screen
 * (`DisplayMetrics.densityDpi`, what Android's UI is scaled by: one
 * logical pixel per 1/160 inch of it), rounded to a quarter and never
 * below 1, so the desktop's text is the size of Android's own there.
 * That is 2.25 on the Retroid Pocket 5 (5.5 inches, 1920x1080, densityDpi
 * 360, a real 319 dpi), 1.5 on a 240-dpi screen and 1 on a desktop
 * monitor. The physical density (xdpi) is not used: it takes no account
 * of how close a handheld is held (console 2026-10-29: 1/200 inch of it
 * gave the console 1.5x, too small to read; Android uses 2.25 there).
 */
object DesktopScale {
    /** The SharedPreferences key, in the launcher prefs file every surface reads. */
    const val KEY = "pref_desktop_scale"

    const val AUTOMATIC = "auto"

    /** The fixed choices besides [AUTOMATIC], as stored. */
    val CHOICES: List<String> = listOf("1", "1.25", "1.5", "1.75", "2", "2.5", "3")

    /** Android's baseline density: `DisplayMetrics.DENSITY_DEFAULT`, its 1x. */
    private const val BASELINE_DPI = 160f

    /** The scale for [setting] (a stored [KEY] value, null for none) on a screen of Android density [densityDpi]. */
    fun resolve(setting: String?, densityDpi: Int): Double {
        setting?.takeIf { it != AUTOMATIC }?.toDoubleOrNull()?.takeIf { it in 1.0..4.0 }?.let { return it }
        return automatic(densityDpi)
    }

    fun automatic(densityDpi: Int): Double {
        val quarters = (densityDpi / BASELINE_DPI * 4).roundToInt()
        return (quarters / 4.0).coerceAtLeast(1.0)
    }

    /**
     * The SharedPreferences key of the render resolution (Droidtop/tracker#426): what fraction of the
     * screen's pixels the desktop's output has. [FULL_RESOLUTION], the default, is the screen's own size.
     */
    const val RESOLUTION_KEY = "pref_desktop_render_resolution"

    const val FULL_RESOLUTION = "full"

    /** The lower resolutions, as stored: a fraction of each side. Faster to draw and capture, blurrier. */
    val RESOLUTION_CHOICES: List<String> = listOf("0.75", "0.5")

    /** The fraction of each side for [setting] (a stored [RESOLUTION_KEY] value, null for none); 1.0 for anything else. */
    fun resolutionFactor(setting: String?): Double =
        setting?.takeIf { it != FULL_RESOLUTION }?.toDoubleOrNull()?.takeIf { it in 0.25..1.0 } ?: 1.0

    /**
     * The output size to ask the compositor for on a [viewWidth] x [viewHeight] surface at [factor]: each
     * side scaled and rounded down to an even number, never below 2.
     */
    fun outputSize(viewWidth: Int, viewHeight: Int, factor: Double): Pair<Int, Int> {
        if (factor >= 1.0) return viewWidth to viewHeight
        fun side(v: Int) = (((v * factor).toInt()) / 2 * 2).coerceAtLeast(2)
        return side(viewWidth) to side(viewHeight)
    }

    /**
     * The compositor scale that keeps the logical layout the same at a lower resolution: fewer pixels per
     * side, so fewer pixels per logical pixel. Unlike [resolve] it may go below 1, because a 1x desktop
     * at half resolution is a 0.5 scale of the smaller output.
     */
    fun outputScale(scale: Double, factor: Double): Double = scale * factor

    /** "2x", "1.25x": a scale as the settings row shows it. */
    fun label(scale: Double): String =
        (if (scale == Math.floor(scale)) scale.toInt().toString() else scale.toString()) + "x"
}
