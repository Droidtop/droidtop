package dev.droidtop.library.settings

import android.content.Context

/**
 * The companion screen's own "Lock to landscape" (docs/SPEC.md "Companion layout and per-mode orientation",
 * Droidtop/tracker#213). Owner, 2026-10-01: the Retroid add-on does not rotate and its own "lock to landscape"
 * button is usually on, "but we should include a 'lock to landscape' option in droidtop too, for other systems".
 * Off by default: the companion then follows the device and the active mode's Screen orientation, as before.
 */
object CompanionOrientation {
    const val KEY_LOCK_LANDSCAPE = "pref_companion_lock_landscape"

    /** The mode's Screen orientation values that ask for a landscape screen (ScreenOrientationPrefs in :app). */
    private val LANDSCAPE_CHOICES = setOf("landscape", "landscape_flipped")
    private const val FOLLOW = "follow"

    fun lockLandscape(context: Context): Boolean =
        CatalogPrefs.prefs(context).getBoolean(KEY_LOCK_LANDSCAPE, false)

    fun setLockLandscape(context: Context, on: Boolean) {
        CatalogPrefs.prefs(context).edit().putBoolean(KEY_LOCK_LANDSCAPE, on).apply()
    }

    /**
     * Whether a companion surface's content has to be drawn turned a quarter inside its window, because the
     * window's shape is not the one asked for. A `Presentation`, and an activity on a display that cannot rotate,
     * get the display's own shape whatever they request, so the content turns instead. The lock asks for
     * landscape; without it the mode's choice ([modeChoice]) does, and "follow" asks for nothing.
     */
    fun rotateContent(modeChoice: String?, lockLandscape: Boolean, windowLandscape: Boolean): Boolean {
        val wantsLandscape = when {
            lockLandscape -> true
            modeChoice == null || modeChoice == FOLLOW -> return false
            else -> modeChoice in LANDSCAPE_CHOICES
        }
        return wantsLandscape != windowLandscape
    }
}
