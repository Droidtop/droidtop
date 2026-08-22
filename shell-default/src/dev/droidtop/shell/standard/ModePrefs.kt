package dev.droidtop.shell.standard

import android.content.Context

/**
 * Remembers whichever shell the user last switched to via [BackButtonMenu]
 * (Android/Standard, Desktop, or Handheld), so it can be resumed later
 * instead of always falling back to a fixed default.
 *
 * Only covers [dev.droidtop.app.MainActivity] resuming its own last
 * app-hosted mode (Desktop vs Handheld) when relaunched without an
 * explicit [BackButtonMenu.EXTRA_MODE] — see that Activity's own
 * onCreate/onNewIntent. Auto-redirecting away from Standard's own cold
 * boot (so the device wakes directly into the last-used Desktop/Handheld
 * mode instead of showing Standard's home grid first) is real, wanted,
 * follow-up work, not implemented here yet — it means hooking
 * `com.android.launcher3.Launcher`'s own onCreate, a bigger, riskier
 * change to the forked AOSP tree than this pass makes.
 */
object ModePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_LAST_MODE = "droidtop_last_mode"

    fun lastMode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE, BackButtonMenu.MODE_STANDARD) ?: BackButtonMenu.MODE_STANDARD

    fun setLastMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MODE, mode)
            .apply()
    }
}
