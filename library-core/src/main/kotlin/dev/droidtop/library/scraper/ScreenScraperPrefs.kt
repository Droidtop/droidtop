package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * ScreenScraper credentials. The USERNAME/PASSWORD pair is the user's
 * own screenscraper.fr account (optional; raises rate limits). The dev
 * pair is an APPLICATION credential (real ES-DE embeds its own and
 * never surfaces it) -- no settings field exposes it; it arrives via
 * settings restore (the whole-prefs backup carries every key here) or a
 * compiled-in registered pair once droidtop has one. Same shared prefs
 * file every other droidtop setting uses, which is exactly what makes
 * the existing backup/restore cover credentials for free (directed
 * 2026-08-31, replacing the retired debug-credentials file).
 */
object ScreenScraperPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_DEV_ID = "droidtop_screenscraper_devid"
    private const val KEY_DEV_PASSWORD = "droidtop_screenscraper_devpassword"
    private const val KEY_USER_ID = "droidtop_screenscraper_ssid"
    private const val KEY_USER_PASSWORD = "droidtop_screenscraper_sspassword"

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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_ID, "")
            ?.takeIf { it.isNotBlank() }
            ?: ScreenScraperDevCredentials.devId

    fun devPassword(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_PASSWORD, "")
            ?.takeIf { it.isNotBlank() }
            ?: ScreenScraperDevCredentials.devPassword

    fun userId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_USER_ID, "") ?: ""

    fun userPassword(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_USER_PASSWORD, "") ?: ""

    fun set(context: Context, devId: String, devPassword: String, userId: String, userPassword: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEV_ID, devId)
            .putString(KEY_DEV_PASSWORD, devPassword)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_PASSWORD, userPassword)
            .apply()
    }
}
