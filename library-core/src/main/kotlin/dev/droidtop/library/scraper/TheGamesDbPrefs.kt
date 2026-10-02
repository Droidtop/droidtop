package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.BuildConfig
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * Real, user-supplied TheGamesDB API key -- see [TheGamesDbClient]'s own
 * doc comment for why droidtop needs its own key (a real, free,
 * self-service account at thegamesdb.net) rather than reusing ES-DE's own
 * public one. Same shared prefs file every other droidtop setting already
 * uses.
 */
object TheGamesDbPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_API_KEY = "droidtop_thegamesdb_apikey"

    /**
     * droidtop's own TheGamesDB application key, injected at build time from
     * the THEGAMESDB_APP_KEY CI secret (library-core/build.gradle.kts); blank
     * in a build made without it. Never committed.
     */
    val builtInKey: String get() = BuildConfig.THEGAMESDB_APP_KEY

    /** The key the person entered, blank when none. */
    fun ownKey(context: Context): String =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(KEY_API_KEY, "") ?: ""

    /** A key the person entered wins; otherwise the built-in one, so a fresh install needs no setup. */
    fun apiKey(context: Context): String = ownKey(context).ifBlank { builtInKey }

    fun set(context: Context, apiKey: String) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putString(KEY_API_KEY, apiKey)
    }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}
