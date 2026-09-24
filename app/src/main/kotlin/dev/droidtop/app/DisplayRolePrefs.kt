package dev.droidtop.app

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * User-configurable dual-screen role mapping for the launcher system
 * (docs/SPEC.md §4, Gaming-mode dual-screen roles — directed 2026-08-30).
 * Same shared LAUNCHER_PREFS_FILE_NAME file/`KEY_`-object convention
 * as every other settings concern, written by :shell-default's settings
 * rows and read here.
 *
 * Which panel the shell itself renders on is not here: that is
 * [dev.droidtop.runtime.MainScreen], the one role model both Gaming and
 * Desktop read. Desktop mode deliberately ignores [gameLaunchTarget]: its
 * windows are the compositor's job (§4), not per-launch display targets.
 */
object DisplayRolePrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_GAME_LAUNCH_DISPLAY = "pref_display_game_launch_target"

    /** Which display game/app launches target. */
    enum class GameLaunchTarget {
        /**
         * Ask per launch whenever more than one display is present (the
         * default, per direction: never silently assume a screen).
         */
        ASK,

        /** Wherever the shell currently is. */
        FOLLOW_SHELL,
        BUILT_IN,
        SECOND,
    }

    fun gameLaunchTarget(context: Context): GameLaunchTarget =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GAME_LAUNCH_DISPLAY, null)
            ?.let { runCatching { GameLaunchTarget.valueOf(it) }.getOrNull() }
            ?: GameLaunchTarget.ASK
}
