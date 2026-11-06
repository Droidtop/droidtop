package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.res.Configuration
import android.preference.PreferenceManager

/**
 * Light or dark for the keyboard (Droidtop/tracker#562, #337; docs/SPEC.md 6a "Light and dark"), and Hacker Keyboard
 * Reborn's own themes (Droidtop/tracker#903 H2 and H12b; docs/SPEC.md 6a, "Themes, light and dark").
 *
 * HKR's modern themes ([modern]) draw flat plates ([KeyPlates]) in colours of their own: Modern ([FOLLOW], the default
 * for anyone who never chose a theme, HKR's [LIGHT] and [DARK]), Dynamic ([DYNAMIC], the wallpaper's Material You
 * palette on Android 12 and later, [DynamicKeyColors]), High contrast ([HIGH_CONTRAST]) and, where a host gives
 * colours, Match droidtop ([MATCH_HOST], the host's). Each is light or dark by the "Light and dark" setting
 * ([KEY_LIGHT_DARK]): following the system (the host's Light or dark when there is a host: droidtop's, #562), or always
 * one. Every classic Hacker's Keyboard theme stays selectable and keeps its fixed look. The keyboard's own panels (tools
 * strip, clipboard, macros, emoji, autofill chips) and the glide trail take [colors], the same colours as the keys.
 */
object KeyboardAppearance {
    /** The theme id of Modern, once "Follow light or dark": after every classic id, as strings.xml asks (never renumber). */
    const val FOLLOW = 8

    /** Dynamic (wallpaper): Material You's palette on Android 12 and later, Modern below it (#903 H12b). */
    const val DYNAMIC = 9

    /** High contrast: black and white keys with a strong accent (#903 H12b). */
    const val HIGH_CONTRAST = 10

    /** Match droidtop: the host's colours in the input method too, offered only where there is a host (#903 H12b). */
    const val MATCH_HOST = 11

    const val MATERIAL_DARK = 4
    const val MATERIAL_LIGHT = 5

    /** "Light and dark" (#903 H12b): [FOLLOW_SYSTEM], the default, [ALWAYS_LIGHT] or [ALWAYS_DARK]. */
    const val KEY_LIGHT_DARK = "pref_keyboard_light_dark"
    const val FOLLOW_SYSTEM = "system"
    const val ALWAYS_LIGHT = "light"
    const val ALWAYS_DARK = "dark"

    /** The app the keyboard runs in, when it has its own Light or dark setting. */
    fun interface Host {
        fun light(context: Context): Boolean

        /**
         * The host's own colours for a [light] or dark keyboard, or null for the built-in ones: what a keyboard the
         * host embeds draws in, and the input method under Match droidtop ([MATCH_HOST]).
         */
        fun colors(context: Context, light: Boolean): KeyboardColors? = null
    }

    @JvmStatic
    @Volatile
    var host: Host? = null

    /** Whether [stored] is one of HKR's modern themes, drawn as plates in colours of its own. Pure. */
    @JvmStatic
    fun modern(stored: Int): Boolean = stored in FOLLOW..MATCH_HOST

    /**
     * Whether the keyboard draws light. Pure. A keyboard the host embeds ([embedded]) and Match droidtop follow the
     * host's Light or dark ([hostLight]), else the system's ([systemLight]); every other theme follows [choice], the
     * "Light and dark" setting, where following the system means the host's answer when there is a host (#562).
     * A classic theme ignores it: [resolve] keeps it as it is.
     */
    @JvmStatic
    fun lightFor(stored: Int, choice: String?, embedded: Boolean, hostLight: Boolean?, systemLight: Boolean): Boolean = when {
        embedded || stored == MATCH_HOST -> hostLight ?: systemLight
        choice == ALWAYS_LIGHT -> true
        choice == ALWAYS_DARK -> false
        else -> hostLight ?: systemLight
    }

    /** Whether the keyboard drawn in [context] draws light now. */
    @JvmStatic
    fun isLight(context: Context): Boolean = lightOf(context, storedTheme(context), !drawnByInputMethod(context))

    /** Whether the theme [stored] draws light now, in a keyboard the host embeds or not ([embedded]). */
    @JvmStatic
    fun lightOf(context: Context, stored: Int, embedded: Boolean): Boolean = lightFor(
        stored,
        lightDarkChoice(context),
        embedded,
        host?.let { logged("host theme not read") { it.light(context) }.getOrNull() },
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES,
    )

    /** The "Light and dark" setting, [FOLLOW_SYSTEM] when it was never set. */
    @JvmStatic
    fun lightDarkChoice(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_LIGHT_DARK, FOLLOW_SYSTEM) ?: FOLLOW_SYSTEM

    /** The theme drawn for the stored choice [stored]: a modern theme is drawn over Material light or dark. Pure. */
    @JvmStatic
    fun resolve(stored: Int, light: Boolean): Int =
        if (modern(stored)) (if (light) MATERIAL_LIGHT else MATERIAL_DARK) else stored

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
        ground = rgb(32, 33, 36),
        panel = rgb(41, 42, 45),
        key = rgb(60, 64, 67),
        keyFocused = rgb(95, 99, 104),
        text = WHITE,
        muted = rgb(170, 172, 176),
        accent = rgb(26, 115, 232),
    )

    /** HKR's own light: white keys on a light grey ground (#903 H2), for a keyboard with no host colours. */
    val LIGHT = KeyboardColors(
        ground = rgb(232, 234, 237),
        panel = WHITE,
        key = WHITE,
        keyFocused = rgb(200, 204, 209),
        text = rgb(32, 33, 36),
        muted = rgb(95, 99, 104),
        accent = rgb(26, 115, 232),
    )

    /** High contrast, dark: black ground, near-black keys, white labels, a yellow accent (#903 H12b). */
    val HIGH_CONTRAST_DARK = KeyboardColors(
        ground = BLACK,
        panel = rgb(18, 18, 18),
        key = rgb(30, 30, 30),
        keyFocused = rgb(84, 84, 84),
        text = WHITE,
        muted = rgb(224, 224, 224),
        accent = rgb(255, 214, 0),
    )

    /** High contrast, light: white keys on a mid-grey ground, black labels, a deep blue accent (#903 H12b). */
    val HIGH_CONTRAST_LIGHT = KeyboardColors(
        ground = rgb(176, 176, 176),
        panel = WHITE,
        key = WHITE,
        keyFocused = rgb(160, 160, 160),
        text = BLACK,
        muted = rgb(32, 32, 32),
        accent = rgb(0, 64, 192),
    )

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** An opaque colour: android.graphics.Color.rgb, kept here so the palettes above are plain JVM values (tests). */
    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Whether the panels beside the theme [drawn] are light: only beside the light keyboard. Pure. */
    @JvmStatic
    fun lightPanels(drawn: Int): Boolean = drawn == MATERIAL_LIGHT

    /**
     * HKR's own colours for the theme [stored], [light] or dark, made readable ([KeyContrast.readable]): Dynamic's
     * [dynamic] when there is a system palette (else Modern's), High contrast's, and Modern's for Modern, for Match
     * droidtop with no host colours, and beside a classic theme. Pure.
     */
    @JvmStatic
    fun ownColors(stored: Int, light: Boolean, dynamic: KeyboardColors?): KeyboardColors = KeyContrast.readable(
        when {
            stored == DYNAMIC && dynamic != null -> dynamic
            stored == HIGH_CONTRAST -> if (light) HIGH_CONTRAST_LIGHT else HIGH_CONTRAST_DARK
            else -> if (light) LIGHT else DARK
        },
    )

    /**
     * The keyboard's colours now, for what is drawn in [context] (Droidtop/tracker#903 H2, the theme seam, extended
     * by H12b). In a keyboard the host embeds (droidtop's in-window, companion and overlay keyboards), the host's
     * colours for its light or dark, so it matches the screen around it. Inside the input method, the colours of the
     * theme chosen ([themeColors]): HKR's own, or the host's under Match droidtop.
     */
    @JvmStatic
    fun colors(context: Context): KeyboardColors {
        val stored = storedTheme(context)
        val embedded = !drawnByInputMethod(context)
        val light = lightOf(context, stored, embedded)
        return if (embedded) hostColors(context, light) ?: ownColors(FOLLOW, light, null) else themeColors(context, stored, light)
    }

    /**
     * The input method's colours for the theme [stored], drawn [light] or dark: the host's for Match droidtop (Modern's
     * where there is no host), Dynamic's from the system palette, HKR's own for the other modern themes, and beside a
     * classic theme Modern's, light only beside Material light (#562). Labels are made readable on every plate
     * ([KeyContrast.readable]), whatever the source. Reads the system palette for Dynamic ([DynamicKeyColors.palette]).
     */
    @JvmStatic
    fun themeColors(context: Context, stored: Int, light: Boolean): KeyboardColors = when {
        stored == MATCH_HOST -> hostColors(context, light) ?: ownColors(FOLLOW, light, null)
        stored == DYNAMIC -> ownColors(stored, light, DynamicKeyColors.colors(context, light))
        modern(stored) -> ownColors(stored, light, null)
        else -> ownColors(stored, lightPanels(stored), null)
    }

    /** The host's colours for a [light] or dark keyboard, made readable, or null when there are none. */
    private fun hostColors(context: Context, light: Boolean): KeyboardColors? =
        host?.let { h -> logged("host colours not read") { h.colors(context, light) }.getOrNull() }?.let { KeyContrast.readable(it) }

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
