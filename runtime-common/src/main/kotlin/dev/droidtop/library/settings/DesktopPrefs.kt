package dev.droidtop.library.settings

import android.content.Context
import android.content.SharedPreferences
import dev.droidtop.runtime.DesktopScale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The one definition of Desktop mode's own settings (Droidtop/tracker#764): Settings > Desktop mode writes them, and
 * the Desktop shell observes them through [changes], so a change applies to the desktop already showing and nothing
 * reads the file during composition.
 */
object DesktopPrefs {
    const val KEY_TASKBAR_TOP = "pref_desktop_taskbar_top"
    private const val KEY_PINS = "pref_desktop_taskbar_pins"
    private const val KEY_SCREEN_DPI = "pref_desktop_screen_dpi"

    /** What the shell reads: where the bar sits, and the stored scale and resolution ([DesktopScale]; null for none). */
    data class Values(val taskbarAtTop: Boolean = false, val scale: String? = null, val resolution: String? = null)

    fun read(context: Context): Values {
        val prefs = prefs(context)
        return Values(
            taskbarAtTop = prefs.getBoolean(KEY_TASKBAR_TOP, false),
            scale = prefs.getString(DesktopScale.KEY, null),
            resolution = prefs.getString(DesktopScale.RESOLUTION_KEY, null),
        )
    }

    fun setTaskbarAtTop(context: Context, atTop: Boolean) {
        prefs(context).edit().putBoolean(KEY_TASKBAR_TOP, atTop).apply()
    }

    /** The taskbar's pins as stored ([dev.droidtop.library.TaskbarPins] encodes them); null for none. Disk: IO only. */
    fun pins(context: Context): String? = prefs(context).getString(KEY_PINS, null)

    /** Stores [encoded] as the taskbar's pins. Disk: IO only. */
    fun setPins(context: Context, encoded: String) {
        prefs(context).edit().putString(KEY_PINS, encoded).apply()
    }

    /**
     * Android's density for the screen the desktop last drew on (Droidtop/tracker#386), recorded by the desktop
     * itself, so Settings' "Automatic" names the scale the desktop uses rather than the scale of the screen Settings
     * happens to be on. Null until the desktop has drawn once. Disk: IO only.
     */
    fun screenDpi(context: Context): Int? = prefs(context).getInt(KEY_SCREEN_DPI, 0).takeIf { it > 0 }

    fun setScreenDpi(context: Context, dpi: Int) {
        if (dpi > 0 && screenDpi(context) != dpi) prefs(context).edit().putInt(KEY_SCREEN_DPI, dpi).apply()
    }

    /** The values now and after every change to one of them; collect off the main thread (the first read opens the file). */
    fun changes(context: Context): Flow<Values> = callbackFlow {
        val prefs = prefs(context)
        val keys = setOf(KEY_TASKBAR_TOP, DesktopScale.KEY, DesktopScale.RESOLUTION_KEY)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) trySend(read(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
}
