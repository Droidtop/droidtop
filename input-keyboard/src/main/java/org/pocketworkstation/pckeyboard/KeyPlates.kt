package org.pocketworkstation.pckeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Hacker Keyboard Reborn's modern key drawing (Droidtop/tracker#903 H2): each key a flat rounded plate in the
 * keyboard's colours instead of a theme's 9-patch, with function keys (modifiers, Esc, Tab, F-keys, the icon keys) a
 * shade apart from the letters, a latched modifier lit in the accent, and a pressed key in the focused shade. It draws
 * the same keys, labels, hints and icons; only the plates and their colours change.
 *
 * Who gets plates ([colorsFor]): HKR's modern themes (Modern, the default, Dynamic, High contrast and Match droidtop,
 * [KeyboardAppearance.modern]) and every keyboard droidtop embeds (in-window, companion, overlays), which takes
 * droidtop's colours through [KeyboardAppearance.Host]. A classic Hacker's Keyboard theme someone chose keeps its own
 * drawing. The theme picker's previews draw their keys with [draw] too (#903 H12b, [ThemePreference]).
 */
object KeyPlates {
    /** Corner radius and the gap left around each plate, in dp. */
    const val RADIUS_DP = 6f
    const val INSET_DP = 2.5f

    /** How far a function key's plate moves from a letter's toward the text colour. */
    private const val FUNCTION_SHADE = 0.12f

    /** How far a latched modifier's plate moves toward the accent. */
    private const val LATCHED_SHADE = 0.45f

    /**
     * Whether a key is a function key, drawn a shade apart: a modifier or sticky key, a negative (keyboard) code, an
     * icon key, or a word label on a single-code key (Esc, Tab, Ctrl, F1). The space bar never is. Pure.
     */
    @JvmStatic
    fun functional(codes: IntArray?, label: CharSequence?, hasIcon: Boolean, modifier: Boolean, sticky: Boolean): Boolean {
        val code = codes?.firstOrNull() ?: 0
        if (code == ' '.code) return false
        if (modifier || sticky || code < 0 || hasIcon) return true
        return label != null && label.length > 1 && (codes?.size ?: 0) < 2
    }

    /** The plate's colour for a key in [colors]. Pure. */
    @JvmStatic
    fun plateColor(colors: KeyboardColors, functional: Boolean, pressed: Boolean, latched: Boolean): Int = when {
        pressed -> colors.keyFocused
        latched -> blend(colors.key, colors.accent, LATCHED_SHADE)
        functional -> blend(colors.key, colors.text, FUNCTION_SHADE)
        else -> colors.key
    }

    /**
     * The colours to draw plates in for a keyboard drawn in [context], or null to keep the theme's own drawing: inside
     * the input method only for HKR's modern themes; everywhere droidtop embeds it, always (its colours are the host's).
     */
    @JvmStatic
    fun colorsFor(context: Context): KeyboardColors? {
        val inputMethod = KeyboardAppearance.drawnByInputMethod(context)
        if (inputMethod && !KeyboardAppearance.modern(KeyboardAppearance.storedTheme(context))) return null
        return KeyboardAppearance.colors(context)
    }

    /**
     * One plate in [rect] of [canvas] in [color] ([plateColor]): inset by [INSET_DP] and rounded by [RADIUS_DP] at
     * [density]. The one drawing of a plate, for the keyboard and the theme picker's previews alike; [paint] is the
     * caller's, kept between draws so a frame allocates nothing.
     */
    @JvmStatic
    fun draw(canvas: Canvas, rect: RectF, color: Int, density: Float, paint: Paint) {
        val inset = INSET_DP * density
        val radius = RADIUS_DP * density
        paint.color = color
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, radius, radius, paint)
        rect.inset(-inset, -inset)
    }

    /** [a] moved [t] of the way to [b], channel by channel, alpha included. Pure. */
    @JvmStatic
    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
