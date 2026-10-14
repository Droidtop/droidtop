package dev.droidtop.runtime

import kotlin.math.roundToInt

/**
 * How large the Linux desktop draws (Droidtop/tracker#386, docs/SPEC.md 4
 * "Desktop scale"): the compositor output's scale, logical pixel to
 * physical pixels. The output keeps the screen's full pixel size, so
 * nothing is lost to it; only text and controls grow.
 *
 * The person picks it in Desktop settings ([KEY]); [AUTOMATIC], the
 * default, follows the screen's physical density: one logical pixel per
 * 1/200 inch, rounded to a quarter and never below 1. That is 2 on the
 * Retroid Pocket 5 (5.5 inches, 1920x1080, about 400 dpi), and 1 on a
 * desktop monitor (a 24 inch 1080p one is about 92 dpi), where a laptop's
 * desktop would sit.
 */
object DesktopScale {
    /** The SharedPreferences key, in the launcher prefs file every surface reads. */
    const val KEY = "pref_desktop_scale"

    const val AUTOMATIC = "auto"

    /** The fixed choices besides [AUTOMATIC], as stored. */
    val CHOICES: List<String> = listOf("1", "1.25", "1.5", "1.75", "2", "2.5", "3")

    private const val DOTS_PER_LOGICAL_INCH = 200f

    /**
     * The scale for [setting] (a stored [KEY] value, null for none) on a
     * screen of [xdpi] physical dots per inch, falling back to [densityDpi]
     * when [xdpi] is not a believable figure (some devices report 0 or
     * Android's density bucket instead).
     */
    fun resolve(setting: String?, xdpi: Float, densityDpi: Int): Double {
        setting?.takeIf { it != AUTOMATIC }?.toDoubleOrNull()?.takeIf { it in 1.0..4.0 }?.let { return it }
        return automatic(xdpi, densityDpi)
    }

    fun automatic(xdpi: Float, densityDpi: Int): Double {
        val dpi = if (xdpi in 72f..1000f) xdpi else densityDpi.toFloat()
        val quarters = (dpi / DOTS_PER_LOGICAL_INCH * 4).roundToInt()
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
