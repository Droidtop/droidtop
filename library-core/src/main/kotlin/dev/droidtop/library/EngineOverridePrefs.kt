package dev.droidtop.library

import android.content.Context

/**
 * Explicit folder-to-engine assignment, overriding rule-based detection
 * -- the engine-game twin of [dev.droidtop.library.consoles.
 * SystemOverridePrefs] (docs/SPEC.md §7e2b v4: "users should be able to
 * specify if we don't know"). Keyed by the folder's absolute path in
 * the same shared prefs file every other droidtop setting lives in.
 * Stores the ENGINES DATABASE id (the stable vocabulary), not the enum
 * name, so a stored override keeps meaning the same thing across app
 * versions.
 */
object EngineOverridePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_PREFIX = "droidtop_engine_override_"

    fun get(context: Context, folderPath: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_PREFIX + folderPath, null)

    fun set(context: Context, folderPath: String, engineId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (engineId == null) {
            prefs.edit().remove(KEY_PREFIX + folderPath).apply()
        } else {
            prefs.edit().putString(KEY_PREFIX + folderPath, engineId).apply()
        }
    }

    /** The overridden engine for [folderPath], resolved through the registry's id map; null when unset or unknown to this app. */
    fun engineFor(context: Context, folderPath: String): GameEngine? =
        get(context, folderPath)?.let { EngineRegistryParser.ENGINE_IDS[it] }
}
