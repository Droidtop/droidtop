package dev.droidtop.library.scraper

import android.content.Context

/**
 * Real, optional ScreenScraper credentials -- see [ScreenScraperClient]'s
 * own doc comment for why all four are optional (a real anonymous mode
 * exists, just with lower daily rate limits) rather than a hard
 * requirement before this scraper can be used at all. Same shared prefs
 * file every other droidtop setting already uses.
 */
object ScreenScraperPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_DEV_ID = "droidtop_screenscraper_devid"
    private const val KEY_DEV_PASSWORD = "droidtop_screenscraper_devpassword"
    private const val KEY_USER_ID = "droidtop_screenscraper_ssid"
    private const val KEY_USER_PASSWORD = "droidtop_screenscraper_sspassword"

    fun devId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_ID, "") ?: ""

    fun devPassword(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_PASSWORD, "") ?: ""

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
