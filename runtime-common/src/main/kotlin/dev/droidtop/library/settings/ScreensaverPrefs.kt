package dev.droidtop.library.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose

/**
 * How long the Gaming shell sits idle before its screensaver shows. The
 * one definition of the setting: the settings row writes it and the
 * shell's timer observes it.
 */
enum class ScreensaverMode(val label: String, val idleSeconds: Int) {
    OFF("Off", 0),
    AFTER_2("After 2 minutes", 120),
    AFTER_5("After 5 minutes", 300),
    AFTER_10("After 10 minutes", 600),
}

object ScreensaverPrefs {
    private const val KEY_MODE = "droidtop_screensaver_mode"

    // OFF by default (directed): a slideshow that appears on its own
    // is an interruption unless somebody asked for it.
    fun mode(context: Context): ScreensaverMode {
        val raw = prefs(context).getString(KEY_MODE, null) ?: return ScreensaverMode.OFF
        return runCatching { ScreensaverMode.valueOf(raw) }.getOrDefault(ScreensaverMode.OFF)
    }

    fun setMode(context: Context, mode: ScreensaverMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    /**
     * The mode now and every change to it. The row is changed from the
     * shell's own Settings section, inside the very composition whose
     * timer reads it, so a value read once when the shell started kept
     * the timer on Off until the app was restarted: "After 2 minutes"
     * never showed anything (rig, build 814). A preference listener sees
     * the write itself, whichever screen made it.
     */
    fun changes(context: Context): Flow<ScreensaverMode> = callbackFlow {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // A null key is a clear() of the whole file.
            if (key == null || key == KEY_MODE) trySend(mode(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(mode(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
}
