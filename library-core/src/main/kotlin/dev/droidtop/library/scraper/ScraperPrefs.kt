package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * User-supplied IGDB/Twitch developer credentials -- see
 * [IgdbScraperClient]'s own doc comment for why this has to be the user's
 * own free, self-service Twitch developer app rather than something
 * droidtop can bundle or auto-provision. Same shared prefs file every
 * other droidtop setting already uses.
 */
object ScraperPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_CLIENT_ID = "droidtop_igdb_client_id"
    private const val KEY_CLIENT_SECRET = "droidtop_igdb_client_secret"

    fun clientId(context: Context): String =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(KEY_CLIENT_ID, "") ?: ""

    fun clientSecret(context: Context): String =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(KEY_CLIENT_SECRET, "") ?: ""

    fun set(context: Context, clientId: String, clientSecret: String) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putStrings(
            mapOf(KEY_CLIENT_ID to clientId, KEY_CLIENT_SECRET to clientSecret),
        )
    }

    fun isConfigured(context: Context): Boolean = clientId(context).isNotBlank() && clientSecret(context).isNotBlank()
}
