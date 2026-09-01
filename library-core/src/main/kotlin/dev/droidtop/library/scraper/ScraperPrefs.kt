package dev.droidtop.library.scraper

import android.content.Context

/**
 * User-supplied IGDB/Twitch developer credentials -- see
 * [IgdbScraperClient]'s own doc comment for why this has to be the user's
 * own free, self-service Twitch developer app rather than something
 * droidtop can bundle or auto-provision. Same shared prefs file every
 * other droidtop setting already uses.
 */
object ScraperPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_CLIENT_ID = "droidtop_igdb_client_id"
    private const val KEY_CLIENT_SECRET = "droidtop_igdb_client_secret"

    fun clientId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CLIENT_ID, "") ?: ""

    fun clientSecret(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CLIENT_SECRET, "") ?: ""

    fun set(context: Context, clientId: String, clientSecret: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_CLIENT_SECRET, clientSecret)
            .apply()
    }

    fun isConfigured(context: Context): Boolean = clientId(context).isNotBlank() && clientSecret(context).isNotBlank()
}
