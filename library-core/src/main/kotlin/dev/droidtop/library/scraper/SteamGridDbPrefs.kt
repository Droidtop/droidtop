package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * The user's own SteamGridDB API key -- see [SteamGridDbScraperClient] for
 * why it has to be theirs (a free key from their own steamgriddb.com
 * account) and never one droidtop ships. Settings backup includes it
 * only when the person opts in to the encoded credentials section.
 */
object SteamGridDbPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_API_KEY = "droidtop_steamgriddb_apikey"
    private const val KEY_VERIFIED = "droidtop_steamgriddb_verified"

    fun apiKey(context: Context): String =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(KEY_API_KEY, "") ?: ""

    fun set(context: Context, apiKey: String) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putString(KEY_API_KEY, apiKey)
        setVerified(context, false)
    }

    /** Whether the stored key passed a test call; any change to it clears this. */
    fun verified(context: Context): Boolean = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getBoolean(KEY_VERIFIED, false)

    fun setVerified(context: Context, value: Boolean) {
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).putBoolean(KEY_VERIFIED, value)
    }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}
