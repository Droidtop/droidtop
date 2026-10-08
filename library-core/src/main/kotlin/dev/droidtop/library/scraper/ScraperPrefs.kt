package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore

/**
 * User-supplied IGDB/Twitch developer credentials -- see
 * [IgdbScraperClient]'s own doc comment for why this has to be the user's
 * own free, self-service Twitch developer app rather than something
 * droidtop can bundle or auto-provision. Kept in [CredentialStore]
 * (encrypted); whether they passed a test is [ScraperKeyVerified].
 */
object ScraperPrefs {
    fun clientId(context: Context): String = CredentialStore.get(context, CredentialStore.IGDB_CLIENT_ID)

    fun clientSecret(context: Context): String = CredentialStore.get(context, CredentialStore.IGDB_CLIENT_SECRET)

    fun isConfigured(context: Context): Boolean = clientId(context).isNotBlank() && clientSecret(context).isNotBlank()
}
