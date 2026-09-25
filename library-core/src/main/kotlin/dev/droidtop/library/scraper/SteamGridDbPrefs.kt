package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * The user's own SteamGridDB API key -- see [SteamGridDbScraperClient] for
 * why it has to be theirs (a free key from their own steamgriddb.com
 * account) and never one droidtop ships. Same shared prefs file as every
 * other scraper credential, so the settings backup carries it too.
 */
object SteamGridDbPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_API_KEY = "droidtop_steamgriddb_apikey"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""

    fun set(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}
