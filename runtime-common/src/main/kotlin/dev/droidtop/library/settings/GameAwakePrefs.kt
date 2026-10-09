package dev.droidtop.library.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** What the console does about its screen timer while a game runs (docs/SPEC.md 7f, "Sleep and return to game"). */
enum class GameAwakeMode(val label: String) {
    NEVER_SLEEP("Never sleep"),
    SYSTEM_TIMER("Use the system timer"),
}

/**
 * The one definition of "While a game runs": the Settings row writes it and `GameWakeLock` observes it,
 * so a change applies to the game already running.
 */
object GameAwakePrefs {
    private const val KEY_MODE = "droidtop_game_awake_mode"

    // Never sleep by default: the Steam Deck's model, where a game is slept with the power button.
    fun mode(context: Context): GameAwakeMode {
        val raw = prefs(context).getString(KEY_MODE, null) ?: return GameAwakeMode.NEVER_SLEEP
        return runCatching { GameAwakeMode.valueOf(raw) }.getOrDefault(GameAwakeMode.NEVER_SLEEP)
    }

    fun setMode(context: Context, mode: GameAwakeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** The mode now and every change to it; collect off the main thread (the first read opens the prefs file). */
    fun changes(context: Context): Flow<GameAwakeMode> = callbackFlow {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_MODE) trySend(mode(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(mode(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
}
