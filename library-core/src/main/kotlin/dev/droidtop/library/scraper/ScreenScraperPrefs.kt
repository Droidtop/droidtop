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
    // Debug-credentials file keys (see dev.droidtop.library.DebugCredentials):
    // a value in the file OVERRIDES the stored pref while the pathway is
    // enabled, so the owner can supply real credentials programmatically
    // (adb push into app-private storage) without typing them into UI
    // fields -- and wipe them from Settings afterwards.
    private const val FILE_DEV_ID = "screenscraper.devid"
    private const val FILE_DEV_PASSWORD = "screenscraper.devpassword"
    private const val FILE_USER_ID = "screenscraper.ssid"
    private const val FILE_USER_PASSWORD = "screenscraper.sspassword"

    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_DEV_ID = "droidtop_screenscraper_devid"
    private const val KEY_DEV_PASSWORD = "droidtop_screenscraper_devpassword"
    private const val KEY_USER_ID = "droidtop_screenscraper_ssid"
    private const val KEY_USER_PASSWORD = "droidtop_screenscraper_sspassword"

    fun devId(context: Context): String =
        dev.droidtop.library.DebugCredentials.get(context, FILE_DEV_ID)
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_ID, "") ?: ""

    fun devPassword(context: Context): String =
        dev.droidtop.library.DebugCredentials.get(context, FILE_DEV_PASSWORD)
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_PASSWORD, "") ?: ""

    fun userId(context: Context): String =
        dev.droidtop.library.DebugCredentials.get(context, FILE_USER_ID)
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_USER_ID, "") ?: ""

    fun userPassword(context: Context): String =
        dev.droidtop.library.DebugCredentials.get(context, FILE_USER_PASSWORD)
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_USER_PASSWORD, "") ?: ""


    // Stored-pref accessors for SETTINGS FIELD DISPLAY only: they skip
    // the debug-credentials override on purpose -- a field that echoed
    // the file's value would put the secret on screen, which is exactly
    // what the file pathway exists to avoid.
    fun storedDevId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_ID, "") ?: ""
    fun storedDevPassword(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DEV_PASSWORD, "") ?: ""
    fun storedUserId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_USER_ID, "") ?: ""
    fun storedUserPassword(context: Context): String =
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
