package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.BuildConfig
import dev.droidtop.library.credentials.CredentialStore

/**
 * Real, user-supplied TheGamesDB API key -- see [TheGamesDbClient]'s own
 * doc comment for why droidtop needs its own key (a real, free,
 * self-service account at thegamesdb.net) rather than reusing ES-DE's own
 * public one. The person's own key is kept in [CredentialStore] (encrypted).
 */
object TheGamesDbPrefs {
    /**
     * droidtop's own TheGamesDB application key, injected at build time from
     * the THEGAMESDB_APP_KEY CI secret (library-core/build.gradle.kts); blank
     * in a build made without it. Never committed.
     */
    val builtInKey: String get() = BuildConfig.THEGAMESDB_APP_KEY

    /** The key the person entered, blank when none. */
    fun ownKey(context: Context): String = CredentialStore.get(context, CredentialStore.THEGAMESDB_API_KEY)

    /** A key the person entered wins; otherwise the built-in one, so a fresh install needs no setup. */
    fun apiKey(context: Context): String = ownKey(context).ifBlank { builtInKey }

    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()
}
