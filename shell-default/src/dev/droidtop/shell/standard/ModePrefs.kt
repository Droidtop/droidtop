package dev.droidtop.shell.standard

import android.content.Context

/**
 * Remembers whichever shell the user last switched to via [BackButtonMenu]
 * (Android/Standard, Desktop, or Handheld), so it can be resumed later
 * instead of always falling back to a fixed default.
 *
 * Read from two places. [dev.droidtop.app.MainActivity] resumes its own
 * last app-hosted mode (Desktop vs Handheld) when relaunched without an
 * explicit [BackButtonMenu.EXTRA_MODE] — see that Activity's own
 * onCreate/onNewIntent.
 *
 * Cold-boot auto-redirect — waking straight into the last-used
 * Desktop/Handheld mode rather than showing Standard's home grid first —
 * IS implemented, in both activities that can hold the HOME role on boot:
 *
 * - `com.android.launcher3.Launcher` records the pending mode in onCreate
 *   (guarded on `isTaskRoot()` + no saved state, so warm recreations and
 *   config changes don't trigger it) and performs the redirect in
 *   onStart, because finishing from inside onCreate would destroy a
 *   half-initialized Activity.
 * - [AlternativeLauncherActivity] does the same for the "Alternative"
 *   home implementation, which is what actually runs on boot when the
 *   user forwards HOME to a different launcher.
 *
 * Picking "Android" from [BackButtonMenu] records `standard` here first,
 * which is what stops the redirect bouncing straight back.
 */
object ModePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_LAST_MODE = "droidtop_last_mode"
    // Real, distinct from KEY_LAST_MODE: a user-set preference ("always
    // start in Handheld"), not auto-overwritten every time the user
    // switches shells the way lastMode is -- see [defaultMode]'s own doc
    // comment for how the two combine in MainActivity's real resolveMode.
    private const val KEY_DEFAULT_MODE = "droidtop_default_mode"
    // Real per-mode enable/disable -- Desktop and Handheld only (matches
    // docs/SPEC.md's own "optional, toggleable" language for both); Standard
    // is foundational (it's what actually hosts the other two's own launch
    // path back to itself) and has its own real HOME-role toggle already
    // (see HomeRolePrefs), a different, real concern from "does this
    // app-hosted mode show up as a choice."
    private const val KEY_MODE_ENABLED_PREFIX = "droidtop_mode_enabled_"

    // @JvmStatic: Launcher.java (Java, not Kotlin) calls these directly as
    // ModePrefs.lastMode(...)/setLastMode(...) -- without this a Kotlin
    // object's members are only reachable from Java via ModePrefs.INSTANCE.
    @JvmStatic
    fun lastMode(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE, BackButtonMenu.MODE_STANDARD) ?: BackButtonMenu.MODE_STANDARD

    @JvmStatic
    fun setLastMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MODE, mode)
            .apply()
    }

    /**
     * Real, user-set default -- MainActivity's own resolveMode prefers this
     * over [lastMode] when set (and still enabled, see [isModeEnabled]), so
     * "always start in Handheld" actually sticks instead of being
     * overwritten the moment the user switches to Desktop once. Null means
     * "no override" -- falls back to [lastMode]'s own real behavior.
     */
    fun defaultMode(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEFAULT_MODE, null)

    fun setDefaultMode(context: Context, mode: String?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (mode == null) editor.remove(KEY_DEFAULT_MODE) else editor.putString(KEY_DEFAULT_MODE, mode)
        editor.apply()
    }

    /** Real per-mode enable/disable -- [BackButtonMenu.show] hides a disabled mode's own menu entry entirely. Defaults to enabled: a fresh install shows both real modes, matching today's actual behavior. */
    fun isModeEnabled(context: Context, mode: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_MODE_ENABLED_PREFIX + mode, true)

    fun setModeEnabled(context: Context, mode: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MODE_ENABLED_PREFIX + mode, enabled)
            .apply()
    }
}
