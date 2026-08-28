package dev.droidtop.library.scraper

import android.content.Context

/**
 * Real, user-supplied TheGamesDB API key -- see [TheGamesDbClient]'s own
 * doc comment for why droidtop needs its own key (a real, free,
 * self-service account at thegamesdb.net) rather than reusing ES-DE's own
 * public one. Same shared prefs file every other droidtop setting already
 * uses.
 */
object TheGamesDbPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_API_KEY = "droidtop_thegamesdb_apikey"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""

    fun set(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}
