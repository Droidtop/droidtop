package org.pocketworkstation.pckeyboard

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Text on a key is readable in every look Hacker Keyboard Reborn draws itself (Droidtop/tracker#903 H12b; docs/SPEC.md
 * 6a, "Themes, light and dark"): a key's label reaches at least [TEXT] (WCAG 2's 4.5:1 for normal text) against each
 * plate it can sit on, a letter's, a function key's, a latched modifier's and a pressed key's ([KeyPlates.plateColor]),
 * and an alt-key hint at least [HINT] (3:1, WCAG's floor for what is not body text) against the letter and function
 * plates. [readable] moves colours that fall short until they pass; colours that already pass come back unchanged.
 *
 * Pure: plain ARGB ints, so it runs on the JVM. The formula is WCAG 2.x's relative luminance and contrast ratio. A
 * colour with alpha is laid over what is behind it first (a plate over the keyboard's ground, a label over its plate).
 */
object KeyContrast {
    /** The least ratio a key's label has against any plate it is drawn on. */
    const val TEXT = 4.5

    /** The least ratio an alt-key hint has against a letter's or a function key's plate. */
    const val HINT = 3.0

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** WCAG 2.x relative luminance of an opaque [color] (alpha is ignored), 0 for black to 1 for white. */
    @JvmStatic
    fun luminance(color: Int): Double {
        fun lin(shift: Int): Double {
            val c = ((color ushr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    /** [fg] laid over [bg] by its alpha, the result opaque (assumed already opaque when [bg] is not). */
    @JvmStatic
    fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) and 0xFF
        if (a == 0xFF) return fg
        fun ch(shift: Int): Int {
            val f = (fg ushr shift) and 0xFF
            val b = (bg ushr shift) and 0xFF
            return ((f * a + b * (255 - a)) / 255.0 + 0.5).toInt().coerceIn(0, 255) shl shift
        }
        return BLACK or ch(16) or ch(8) or ch(0)
    }

    /** The contrast ratio of two opaque colours, 1 (the same) to 21 (black and white). */
    @JvmStatic
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** The ratio of a label in [text] on a [plate], the plate drawn over [ground]. */
    @JvmStatic
    fun onPlate(text: Int, plate: Int, ground: Int): Double {
        val p = over(plate, over(ground, BLACK))
        return ratio(over(text, p), p)
    }

    /** The plates a label is drawn on in [colors]: a letter's, a function key's, a latched modifier's, a pressed key's. */
    @JvmStatic
    fun plates(colors: KeyboardColors): List<Int> = listOf(
        KeyPlates.plateColor(colors, functional = false, pressed = false, latched = false),
        KeyPlates.plateColor(colors, functional = true, pressed = false, latched = false),
        KeyPlates.plateColor(colors, functional = false, pressed = false, latched = true),
        KeyPlates.plateColor(colors, functional = false, pressed = true, latched = false),
    )

    /** The lowest ratio a label has on any plate in [colors]. */
    @JvmStatic
    fun worstText(colors: KeyboardColors): Double = plates(colors).minOf { onPlate(colors.text, it, colors.ground) }

    /** The lowest ratio an alt-key hint has on a letter's or a function key's plate in [colors]. */
    @JvmStatic
    fun worstHint(colors: KeyboardColors): Double = plates(colors).take(2).minOf { onPlate(colors.muted, it, colors.ground) }

    /** Whether [colors] meets [TEXT] for labels and [HINT] for hints. */
    @JvmStatic
    fun passes(colors: KeyboardColors): Boolean = worstText(colors) >= TEXT && worstHint(colors) >= HINT

    /**
     * [colors] with labels readable on every plate. The label moves toward black or white, whichever reads better on
     * the letter key, until it reads everywhere; if it is already black or white and a plate still falls short (a
     * mid-grey key, a pale accent), that plate's colour moves the other way. Hints then move toward the label's pole
     * until they reach [HINT]. The ground, the panel and what already passes are kept as they are.
     */
    @JvmStatic
    fun readable(colors: KeyboardColors): KeyboardColors {
        if (passes(colors)) return colors
        val keyOn = over(colors.key, over(colors.ground, BLACK))
        val pole = if (ratio(WHITE, keyOn) >= ratio(BLACK, keyOn)) WHITE else BLACK
        val away = if (pole == WHITE) BLACK else WHITE
        var c = colors
        for (i in 0 until MAX_STEPS) {
            if (worstText(c) >= TEXT) break
            if (c.text != pole) {
                c = c.copy(text = step(c.text, pole))
                continue
            }
            val ground = c.ground
            val letter = KeyPlates.plateColor(c, functional = false, pressed = false, latched = false)
            val function = KeyPlates.plateColor(c, functional = true, pressed = false, latched = false)
            val latched = KeyPlates.plateColor(c, functional = false, pressed = false, latched = true)
            if (onPlate(c.text, letter, ground) < TEXT || onPlate(c.text, function, ground) < TEXT) c = c.copy(key = step(c.key, away))
            if (onPlate(c.text, latched, ground) < TEXT) c = c.copy(accent = step(c.accent, away))
            if (onPlate(c.text, c.keyFocused, ground) < TEXT) c = c.copy(keyFocused = step(c.keyFocused, away))
        }
        for (i in 0 until MAX_STEPS) {
            if (worstHint(c) >= HINT || c.muted == pole) break
            c = c.copy(muted = step(c.muted, pole))
        }
        return c
    }

    /** [color] a tenth of the way to [target], made opaque; [target] itself once rounding would stop it moving. */
    private fun step(color: Int, target: Int): Int {
        val opaque = BLACK or color
        val next = KeyPlates.blend(opaque, target, 0.1f)
        return if (next == opaque) target else next
    }

    /** Enough tenths for the label and then a plate to each reach a pole from anywhere (0.9^60 of 255 is under one step). */
    private const val MAX_STEPS = 240
}
