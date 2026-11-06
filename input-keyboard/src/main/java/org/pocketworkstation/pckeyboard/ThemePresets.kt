package org.pocketworkstation.pckeyboard

/**
 * The themes the keyboard's theme setting offers, in order (Droidtop/tracker#903 H12b; docs/SPEC.md 6a, "Themes,
 * light and dark"): HKR's modern ones first, then every classic Hacker's Keyboard theme, as before. The owner's rule
 * for HKR: "Hacker's Keyboard is our foundation. If we can rebuild it while keeping ALL of hacker's functionality...",
 * so no classic theme goes. Ids are the stored values and are never renumbered (strings.xml).
 *
 * Dynamic is offered only on Android 12 and later, where there is a wallpaper palette, as HeliBoard offers its dynamic
 * colours (KeyboardTheme.getAvailableDefaultColors). Match droidtop is offered only where a host gives colours. A
 * stored theme the list would leave out (Dynamic restored onto an older Android) stays listed, so the setting shows
 * what is stored; it draws as Modern there.
 */
object ThemePresets {
    /** HKR's modern themes, in the order offered. */
    @JvmField
    val MODERN = intArrayOf(
        KeyboardAppearance.FOLLOW,
        KeyboardAppearance.DYNAMIC,
        KeyboardAppearance.HIGH_CONTRAST,
        KeyboardAppearance.MATCH_HOST,
    )

    /**
     * Hacker's Keyboard's own themes, in the order its list had them: Ice Cream Sandwich (bright and dark hints),
     * Material black, dark and light, Gingerbread, Stone (bold) and Transparent.
     */
    @JvmField
    val CLASSIC = intArrayOf(0, 6, 7, 4, 5, 1, 2, 3)

    /** The theme ids to offer, in order, on Android [sdkInt], with a host or not ([hosted]), with [stored] chosen. Pure. */
    @JvmStatic
    fun ids(sdkInt: Int, hosted: Boolean, stored: Int): IntArray {
        val modern = MODERN.filter { id ->
            when (id) {
                KeyboardAppearance.DYNAMIC -> sdkInt >= DynamicKeyColors.FIRST_SDK || stored == id
                KeyboardAppearance.MATCH_HOST -> hosted || stored == id
                else -> true
            }
        }
        return (modern + CLASSIC.toList()).toIntArray()
    }
}
