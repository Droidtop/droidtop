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
 * clipboard, macros, emoji, autofill chips) and the glide trail take [colors]: light beside a light keyboard, dark
 * beside every other, in the host's colours when it gives them ([Host.colors]), else the built-in [LIGHT] and [DARK].
 */
object KeyboardAppearance {
    /** The theme id of "Follow light or dark": after every existing id, as strings.xml asks (never renumber). */
    const val FOLLOW = 8
    const val MATERIAL_DARK = 4
    const val MATERIAL_LIGHT = 5

    /** The app the keyboard runs in, when it has its own Light or dark setting. */
    fun interface Host {
        fun light(context: Context): Boolean

        /** The host's own colours for a [light] or dark keyboard, or null for the built-in ones. */
        fun colors(context: Context, light: Boolean): KeyboardColors? = null
    }

    @JvmStatic
    @Volatile
    var host: Host? = null

    /** Whether the keyboard draws light now: the host's answer, else the system's night mode. */
    @JvmStatic
    fun isLight(context: Context): Boolean =
        host?.let { logged("host theme not read") { it.light(context) }.getOrNull() }
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

    /** HKR's own dark (#903 H2), for a keyboard with no host colours, and in the input method. */
    val DARK = KeyboardColors(
        ground = Color.rgb(32, 33, 36),
        panel = Color.rgb(41, 42, 45),
        key = Color.rgb(60, 64, 67),
        keyFocused = Color.rgb(95, 99, 104),
        text = Color.WHITE,
        muted = Color.rgb(170, 172, 176),
        accent = Color.rgb(26, 115, 232),
    )

    /** HKR's own light: white keys on a light grey ground (#903 H2), for a keyboard with no host colours. */
    val LIGHT = KeyboardColors(
        ground = Color.rgb(232, 234, 237),
        panel = Color.WHITE,
        key = Color.WHITE,
        keyFocused = Color.rgb(200, 204, 209),
        text = Color.rgb(32, 33, 36),
        muted = Color.rgb(95, 99, 104),
        accent = Color.rgb(26, 115, 232),
    )

    /** Whether the panels beside the theme [drawn] are light: only beside the light keyboard. Pure. */
    @JvmStatic
    fun lightPanels(drawn: Int): Boolean = drawn == MATERIAL_LIGHT

    /**
     * The keyboard's colours now, for what is drawn in [context] (Droidtop/tracker#903 H2, the theme seam). Inside the
     * input method, Hacker Keyboard Reborn's own [LIGHT] or [DARK]: HKR has a look of its own wherever it types. In a
     * keyboard the host embeds (droidtop's in-window, companion and overlay keyboards), the host's colours for this
     * light or dark, so it matches the screen around it; else the same built-in ones.
     */
    @JvmStatic
    fun colors(context: Context): KeyboardColors {
        val light = lightPanels(current(context))
        val hosted = if (drawnByInputMethod(context)) null else host
        return hosted?.let { logged("host colours not read") { it.colors(context, light) }.getOrNull() } ?: if (light) LIGHT else DARK
    }

    /** Whether [context] is the input method's (its service, or a wrapper around it), not a host's own window. */
    @JvmStatic
    fun drawnByInputMethod(context: Context): Boolean {
        var c: Context? = context
        while (c != null) {
            if (c is android.inputmethodservice.InputMethodService) return true
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return false
    }
}

/**
 * The colours of everything droidtop draws for the keyboard (Droidtop/tracker#772: the slab, the panels and the glide
 * trail had three sources): [ground] behind it, [panel] for a panel's sheet, [key] and [keyFocused] for keys and
 * chips, [text] and [muted] for their words, [accent] for what is lit (a pressed key, the glide trail). The panels
 * and the trail take [KeyboardAppearance.colors]; a surface that draws the slab passes its own surface's.
 */
data class KeyboardColors(
    val ground: Int,
    val panel: Int,
    val key: Int,
    val keyFocused: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
) {
    /** The colours with [panel] the same as [ground], for a surface that has no panel (a slab on its own). */
    constructor(ground: Int, key: Int, keyFocused: Int, text: Int, muted: Int, accent: Int) :
        this(ground, ground, key, keyFocused, text, muted, accent)
}
