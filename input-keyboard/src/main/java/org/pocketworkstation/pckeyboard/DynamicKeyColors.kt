// The tone choices in [DynamicKeyColors.derive] are ported from HeliBoard (https://github.com/Helium314/HeliBoard at
// 2d9869b2, app/src/main/java/helium314/keyboard/latin/common/Colors.kt, class DynamicColors; GPL-3.0-only, Copyright
// (C) HeliBoard contributors): the ground in neutral 50 or 900, keys in neutral 0 or 800, key text in a tone near the
// far end of the scale, and the accent in accent1 200 by night and 600 by day. See NOTICE.md.
package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * "Dynamic (wallpaper)" (Droidtop/tracker#903 H12b; docs/SPEC.md 6a, "Themes, light and dark"): Hacker Keyboard
 * Reborn's keys in Android's Material You palette, the one Android 12 and later derives from the wallpaper. Below
 * Android 12 there is no palette and the theme draws as Modern ([KeyboardAppearance.LIGHT] and [DARK]).
 *
 * The palette is read from the system's resource colours (no permission, no file) by [DynamicPaletteReader], a class
 * of its own reached only under SDK_INT >= 31. A new wallpaper's palette reaches the keyboard as a configuration change
 * (Android bumps the configuration when the palette changes), so the read is kept per [Configuration] and done again
 * only when it differs: reading a dozen resource colours, never on every draw.
 */
object DynamicKeyColors {
    /** The first Android with a system palette (Android 12, API 31). */
    const val FIRST_SDK = 31

    /**
     * The system palette's tones, each list the 13 tones 0, 10, 50, 100, 200, ..., 900, 1000 of one family, as
     * `android.R.color.system_accent1_0` ... `system_accent1_1000` name them.
     */
    data class TonalPalette(val accent1: List<Int>, val neutral1: List<Int>, val neutral2: List<Int>) {
        init {
            require(accent1.size == TONES.size && neutral1.size == TONES.size && neutral2.size == TONES.size)
        }

        /** Whether the palette is filled in: a device with no Material You reports every tone the same. */
        val filled: Boolean get() = accent1.toSet().size > 1 && neutral1.toSet().size > 1
    }

    /** The tones each family lists, in order. */
    @JvmField
    val TONES = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    private fun List<Int>.tone(t: Int): Int = this[TONES.indexOf(t)]

    /**
     * The keyboard's roles from [palette] for a [light] or dark keyboard, made readable ([KeyContrast.readable]).
     * HeliBoard's choices for the ground, keys, key text and accent; the pressed key, the hint text and the panels are
     * the neighbouring tones of the same families. Pure.
     */
    @JvmStatic
    fun derive(palette: TonalPalette, light: Boolean): KeyboardColors {
        val n1 = palette.neutral1
        val n2 = palette.neutral2
        val a1 = palette.accent1
        val colors = if (light) {
            KeyboardColors(
                ground = n1.tone(50),
                panel = n1.tone(10),
                key = n1.tone(0),
                keyFocused = n2.tone(200),
                text = n1.tone(900),
                muted = n2.tone(700),
                accent = a1.tone(600),
            )
        } else {
            KeyboardColors(
                ground = n1.tone(900),
                panel = n1.tone(800),
                key = n2.tone(800),
                keyFocused = n2.tone(600),
                text = n1.tone(50),
                muted = n2.tone(200),
                accent = a1.tone(200),
            )
        }
        return KeyContrast.readable(colors)
    }

    @Volatile
    private var cached: Cached? = null

    private class Cached(val configuration: Configuration, val palette: TonalPalette?)

    /**
     * The system palette now, or null below Android 12 or where it is not filled in. Reads a dozen resource colours
     * when [context]'s configuration changed since the last read (a new wallpaper's palette, night mode), else none.
     */
    @JvmStatic
    fun palette(context: Context): TonalPalette? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val configuration = context.resources.configuration
        cached?.let { if (it.configuration == configuration) return it.palette }
        val read = logged("system palette not read") { DynamicPaletteReader.read(context) }.getOrNull()?.takeIf { it.filled }
        cached = Cached(Configuration(configuration), read)
        return read
    }

    /** The Dynamic theme's colours for a [light] or dark keyboard, or null when there is no system palette. */
    @JvmStatic
    fun colors(context: Context, light: Boolean): KeyboardColors? = palette(context)?.let { derive(it, light) }
}
