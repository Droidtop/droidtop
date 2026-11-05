package org.pocketworkstation.pckeyboard

import kotlin.math.roundToInt

/**
 * The shape of the key grid on the screen (docs/SPEC.md 6a, "One-handed and split", Droidtop/tracker#342): the
 * full width, a narrower grid at the left or right edge for one hand, or the grid split with a gap in the middle
 * so each thumb has a half (a handheld held in landscape). The key layouts themselves are untouched: the same
 * keys are scaled and moved once they are loaded ([layout], called from `Keyboard.applyForm`). Pure but [forScreen].
 */
object KeyboardForm {
    /** The shape chosen for portrait, and for both orientations before there was one per orientation. */
    const val PREF = "pref_keyboard_form"

    /** The shape chosen for landscape (Droidtop/tracker#903 H1); unset until someone picks one there. */
    const val PREF_LANDSCAPE = "pref_keyboard_form_landscape"

    /** A screen whose short side is under this many inches is held in the hands: 600dp at Android's 160 dpi. */
    const val HANDHELD_SHORT_SIDE_INCHES = 3.75f

    /** The preference a choice made in [landscape] is kept in. */
    @JvmStatic
    fun prefFor(landscape: Boolean): String = if (landscape) PREF_LANDSCAPE else PREF

    /**
     * Whether a screen of [widthPx] by [heightPx] at [xdpi] and [ydpi] is a handheld's: its short side under
     * [HANDHELD_SHORT_SIDE_INCHES]. Physical size, not dp: droidtop's own windows lay out at a scaled canvas density, so
     * the console's 480dp short side reads as 720dp there. A screen reporting no believable dpi (outside 80 to 1000)
     * falls back to [smallestWidthDp] under 600. Pure.
     */
    @JvmStatic
    fun handheld(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float, smallestWidthDp: Int): Boolean {
        if (xdpi in 80f..1000f && ydpi in 80f..1000f && widthPx > 0 && heightPx > 0) {
            return minOf(widthPx / xdpi, heightPx / ydpi) < HANDHELD_SHORT_SIDE_INCHES
        }
        return smallestWidthDp in 1 until 600
    }

    /**
     * The shape for one orientation (Droidtop/tracker#903 H1): the one chosen for it, else, with nothing chosen,
     * split in landscape on a [handheld], so each thumb reaches its half of the full 5-row grid, and the full width
     * everywhere else. Blank or [AUTO] means unset. A shape chosen before there was one per orientation is carried into
     * landscape once, by [migrate]. Pure.
     */
    @JvmStatic
    fun choose(portrait: String?, landscapeChoice: String?, landscape: Boolean, handheld: Boolean): Form {
        val chosen = (if (landscape) landscapeChoice else portrait)?.takeUnless { it.isBlank() || it == AUTO }
        if (chosen != null) return of(chosen)
        return if (landscape && handheld) SPLIT else FULL
    }

    /** The settings' "Automatic" choice: the orientation's default, as if nothing were chosen. */
    const val AUTO = "auto"

    /** Set once [migrate] has run, so a later portrait choice no longer reaches landscape. */
    const val PREF_MIGRATED = "pref_keyboard_form_per_orientation"

    /** What [migrate] copies into landscape: a shape chosen before H1, or null to leave landscape automatic. Pure. */
    @JvmStatic
    fun carriedToLandscape(old: String?, landscapeSet: Boolean): String? =
        // "full" was the settings screen's stored default, so it may never have been chosen; the others were.
        old?.takeUnless { landscapeSet || it.isBlank() || it == AUTO || it == FULL.id }?.takeIf { of(it).id == it }

    /**
     * Before H1 one shape served both orientations; whoever chose split or one-handed keeps it in landscape too.
     * Runs once ([PREF_MIGRATED]); a portrait choice made later stays portrait's.
     */
    @JvmStatic
    fun migrate(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(PREF_MIGRATED, false)) return
        val edit = prefs.edit().putBoolean(PREF_MIGRATED, true)
        carriedToLandscape(prefs.getString(PREF, null), prefs.contains(PREF_LANDSCAPE))?.let { edit.putString(PREF_LANDSCAPE, it) }
        edit.apply()
    }

    /** [choose] for a screen of [context], read from its configuration and display metrics. */
    @JvmStatic
    fun forScreen(portrait: String?, landscapeChoice: String?, context: android.content.Context): Form {
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        return choose(
            portrait,
            landscapeChoice,
            config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
            handheld(metrics.widthPixels, metrics.heightPixels, metrics.xdpi, metrics.ydpi, config.smallestScreenWidthDp),
        )
    }

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
