package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

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
    private val prefs = { context: Context -> PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).keyedStrings("droidtop_engine_override_") }

    fun get(context: Context, folderPath: String): String? = prefs(context).get(folderPath)

    fun set(context: Context, folderPath: String, engineId: String?) = prefs(context).set(folderPath, engineId)
}
