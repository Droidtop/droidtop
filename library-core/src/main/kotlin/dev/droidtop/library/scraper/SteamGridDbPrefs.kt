package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore

/**
 * The user's own SteamGridDB API key -- see [SteamGridDbScraperClient] for
 * why it has to be theirs (a free key from their own steamgriddb.com
 * account) and never one droidtop ships. Kept in [CredentialStore]
 * (encrypted). Settings backup includes it only when the person opts in to
 * the encoded credentials section.
 */
object SteamGridDbPrefs {
    fun apiKey(context: Context): String = CredentialStore.get(context, CredentialStore.STEAMGRIDDB_API_KEY)

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}
