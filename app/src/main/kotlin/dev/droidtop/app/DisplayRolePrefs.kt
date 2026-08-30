package dev.droidtop.app

import android.content.Context

/**
 * User-configurable dual-screen role mapping for the launcher system
 * (docs/SPEC.md §4, handheld dual-screen roles — directed 2026-08-30).
 * Same shared "com.android.launcher3.prefs" file/`KEY_`-object convention
 * as every other settings concern, written by :shell-default's settings
 * rows and read here.
 *
 * Desktop mode deliberately ignores all of this: its lower screen is an
 * input surface (§4), not a shell/widgets target.
 */
object DisplayRolePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_SHELL_DISPLAY = "pref_display_shell_target"
    private const val KEY_GAME_LAUNCH_DISPLAY = "pref_display_game_launch_target"

    /** Where the Handheld shell itself renders. */
    enum class ShellTarget {
        /** The second display when one is present (the addon is the upper/main screen — per direction, the default), built-in otherwise. */
        SECOND_WHEN_PRESENT,

        /** Always the built-in screen; a second display gets the widgets panel (the pre-direction behavior, kept as a real choice). */
        BUILT_IN,
    }

    /** Which display game/app launches target. */
    enum class GameLaunchTarget {
        /** Wherever the shell currently is (default). */
        FOLLOW_SHELL,
        BUILT_IN,
        SECOND,
    }

    fun shellTarget(context: Context): ShellTarget =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SHELL_DISPLAY, null)
            ?.let { runCatching { ShellTarget.valueOf(it) }.getOrNull() }
            ?: ShellTarget.SECOND_WHEN_PRESENT

    fun gameLaunchTarget(context: Context): GameLaunchTarget =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GAME_LAUNCH_DISPLAY, null)
            ?.let { runCatching { GameLaunchTarget.valueOf(it) }.getOrNull() }
            ?: GameLaunchTarget.FOLLOW_SHELL
}
