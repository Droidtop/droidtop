package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.preference.PreferenceManager

/**
 * Light or dark for the keyboard (Droidtop/tracker#562, #337; docs/SPEC.md 6a "Light and dark").
 *
 * The theme list gains "Follow light or dark" ([FOLLOW]), the default for anyone who never chose a theme: it draws
 * Material light or Material dark. Which one is the host's answer when there is a host ([host]: droidtop sets its own
 * Light or dark setting), otherwise the system's night mode, which is all the standalone app (Hacker Keyboard Reborn,
 * Droidtop/tracker#344) has. A theme the person chose stays as it is. The keyboard's own panels (tools strip,
 * clipboard, macros, emoji, autofill chips) take [palette]: light beside a light keyboard, dark beside every other.
 */
object KeyboardAppearance {
    /** The theme id of "Follow light or dark": after every existing id, as strings.xml asks (never renumber). */
    const val FOLLOW = 8
    const val MATERIAL_DARK = 4
    const val MATERIAL_LIGHT = 5

    /** The app the keyboard runs in, when it has its own Light or dark setting. */
    fun interface Host {
        fun light(context: Context): Boolean
    }

    @JvmStatic
    @Volatile
    var host: Host? = null

    /** Whether the keyboard draws light now: the host's answer, else the system's night mode. */
    @JvmStatic
    fun isLight(context: Context): Boolean =
        host?.let { runCatching { it.light(context) }.getOrNull() }
            ?: ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES)

    /** The theme drawn for the stored choice [stored]. Pure. */
    @JvmStatic
    fun resolve(stored: Int, light: Boolean): Int =
        if (stored == FOLLOW) (if (light) MATERIAL_LIGHT else MATERIAL_DARK) else stored

    /** The theme id the person stored, or [FOLLOW] when they never chose or it cannot be read. */
    @JvmStatic
    fun storedTheme(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KeyboardSwitcher.PREF_KEYBOARD_LAYOUT, KeyboardSwitcher.DEFAULT_LAYOUT_ID)
            ?.toIntOrNull() ?: FOLLOW

    /** The theme to draw now. */
    @JvmStatic
    fun current(context: Context): Int = resolve(storedTheme(context), isLight(context))

    /** The panels' colours. */
    data class Palette(val background: Int, val panel: Int, val text: Int, val muted: Int, val accent: Int, val chip: Int)

    val DARK = Palette(
        background = Color.rgb(32, 33, 36),
        panel = Color.rgb(41, 42, 45),
        text = Color.WHITE,
        muted = Color.rgb(170, 172, 176),
        accent = Color.rgb(26, 115, 232),
        chip = Color.rgb(60, 64, 67),
    )

    val LIGHT = Palette(
        background = Color.rgb(241, 243, 244),
        panel = Color.WHITE,
        text = Color.rgb(32, 33, 36),
        muted = Color.rgb(95, 99, 104),
        accent = Color.rgb(26, 115, 232),
        chip = Color.rgb(218, 220, 224),
    )

    /** Light panels beside the light keyboard, dark beside every other theme. Pure. */
    @JvmStatic
    fun paletteFor(drawn: Int): Palette = if (drawn == MATERIAL_LIGHT) LIGHT else DARK

    @JvmStatic
    fun palette(context: Context): Palette = paletteFor(current(context))
}
