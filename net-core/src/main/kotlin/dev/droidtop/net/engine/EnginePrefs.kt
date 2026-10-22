package dev.droidtop.net.engine

import android.content.Context

/**
 * The person's choices about the download engine (docs/SPEC.md 12a "One download engine"). One is made: whether libaria2
 * runs in an Android isolated process, under a UID of its own (API 30 and later). Off unless turned on: the engine is
 * sandboxed by droidtop's own lockdown and broker either way, and the owner's order is to test that first and "build the
 * android isolation path" after. Reads touch a preferences file: call them off the main thread.
 */
object EnginePrefs {
    private const val FILE = "download_engine"
    private const val KEY_ISOLATION = "android_isolation"

    fun androidIsolation(context: Context): Boolean = prefs(context).getBoolean(KEY_ISOLATION, false)

    fun setAndroidIsolation(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ISOLATION, on).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
