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

    /** "2x", "1.25x": a scale as the settings row shows it. */
    fun label(scale: Double): String =
        (if (scale == Math.floor(scale)) scale.toInt().toString() else scale.toString()) + "x"
}
