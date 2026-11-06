package dev.droidtop.runtime

import android.content.Context
import dev.droidtop.library.settings.CatalogPrefs

/**
 * The stick's dead zone as the shell's D-pad reads it (Settings > Controls, Droidtop/tracker#723): how far the stick
 * must lean before it moves the cursor, so a drifting stick stops walking the menus on its own. A percentage of full
 * travel; the stick releases its direction under it and presses one past it plus a margin. The default, 30, is the
 * shell's long-standing 0.3 / 0.5 band. Process-wide, read by the pad's front ([dev.droidtop.shell.gamepad.input.PadGateCore]).
 */
object StickDeadZone {
    const val KEY = "pref_stick_dead_zone"
    const val DEFAULT_PERCENT = 30
    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 70

    @Volatile
    var percent: Int = DEFAULT_PERCENT
        private set

    /** Reads the stored value; call once off the main thread at start. */
    fun load(context: Context) {
        percent = CatalogPrefs.prefs(context).getInt(KEY, DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    /** Stores [value] and applies it at once. */
    fun set(context: Context, value: Int) {
        percent = value.coerceIn(MIN_PERCENT, MAX_PERCENT)
        CatalogPrefs.prefs(context).edit().putInt(KEY, percent).apply()
    }

    /** The (press, release) travel for [value]: release under the dead zone, press 0.2 past it, never under 0.5. Pure. */
    fun thresholds(value: Int = percent): Pair<Float, Float> {
        val release = value.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f
        val press = maxOf(release + 0.2f, 0.5f).coerceAtMost(0.9f)
        return press to release
    }
}
