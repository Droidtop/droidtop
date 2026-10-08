package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.credentials.CredentialStore
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile

/**
 * ScreenScraper credentials. The USERNAME/PASSWORD pair is the user's
 * own screenscraper.fr account (optional; raises rate limits) and is kept in
 * [CredentialStore] (encrypted). The dev pair is an APPLICATION credential
 * that identifies droidtop (real ES-DE embeds its own and never surfaces it)
 * -- no settings field exposes it; it falls back to the compiled-in pair in
 * [ScreenScraperDevCredentials], and a stored value (from settings restore --
 * only an explicitly opted-in encoded credential section restores it -- or
 * from anyone running their own registered pair) wins over it. It stays in
 * the shared prefs file every other droidtop setting uses (directed
 * 2026-08-31, replacing the retired debug-credentials file): it identifies
 * the application, it does not authenticate a person.
 */
object ScreenScraperPrefs {
    private const val KEY_DEV_ID = "droidtop_screenscraper_devid"
    private const val KEY_DEV_PASSWORD = "droidtop_screenscraper_devpassword"

    /**
     * droidtop's own application credentials, unless the user has deliberately
     * overridden them.
     *
     * ScreenScraper's devid/devpassword identify the calling application rather
     * than the person using it, and are what let a client reach the API at all.
     * They are not a quota tier: the scraping limit belongs to the user's own
     * account (ssid/sspassword), which stays theirs. An explicitly stored value
     * still wins, for anyone running their own registered pair.
     */
    fun devId(context: Context): String =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(KEY_DEV_ID, "")
            ?.takeIf { it.isNotBlank() }
            ?: ScreenScraperDevCredentials.devId

    fun devPassword(context: Context): String =
        PrefsFile(context, LAUNCHER_PREFS_FILE_NAME).getString(KEY_DEV_PASSWORD, "")
            ?.takeIf { it.isNotBlank() }
            ?: ScreenScraperDevCredentials.devPassword

    fun userId(context: Context): String = CredentialStore.get(context, CredentialStore.SCREENSCRAPER_USER)

    fun userPassword(context: Context): String = CredentialStore.get(context, CredentialStore.SCREENSCRAPER_PASSWORD)
}
