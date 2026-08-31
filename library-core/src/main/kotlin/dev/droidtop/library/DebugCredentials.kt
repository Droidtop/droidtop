package dev.droidtop.library

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * The debug-credentials pathway (directed 2026-08-31): a plain
 * properties file in app-PRIVATE storage that credentialed features read
 * from, letting the device's owner supply secrets programmatically --
 * `adb push` + `run-as cp`, a root shell, a file manager with private
 * storage access -- without typing them into UI fields and without any
 * automation layer ever having to SEE the values to make use of them.
 *
 * Generic on purpose, not scraper-specific: keys are namespaced
 * (`screenscraper.ssid`, `thegamesdb.apikey`, ...) so every current and
 * future credentialed integration reads the same file through [get],
 * and consumers layer it as an OVERRIDE above their own stored prefs --
 * file value wins while present and enabled, prefs otherwise -- so no
 * call site changes when the file appears or is wiped.
 *
 * Containment rules, enforced by shape:
 * - App-private `filesDir` only. Nothing but the app itself, adb
 *   `run-as`, and root can read or place it.
 * - Values are never logged, never rendered, never returned in bulk:
 *   the ONLY bulk accessor is [keyNames], for a settings row to say
 *   what the file configures without saying what the values are.
 * - [wipe] deletes the file outright; the settings flag ([setEnabled])
 *   disables the override without deleting, for quick A/B checks.
 *
 * File format: standard Java properties, one `key=value` per line,
 * `#` comments allowed.
 */
object DebugCredentials {

    const val FILE_NAME = "debug-credentials.properties"

    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_ENABLED = "droidtop_debug_credentials_enabled"

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun isPresent(context: Context): Boolean = file(context).isFile

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * The value for [key], or null when the pathway is disabled, the
     * file is absent, or the key isn't in it -- callers fall back to
     * their own stored prefs on null, which is the whole override
     * contract.
     */
    fun get(context: Context, key: String): String? {
        if (!isEnabled(context)) return null
        val f = file(context)
        if (!f.isFile) return null
        return runCatching {
            Properties().apply { f.inputStream().use { load(it) } }.getProperty(key)
        }.getOrNull()?.trim()?.ifEmpty { null }
    }

    /** Key NAMES only, sorted -- for a status row. Values never leave [get]. */
    fun keyNames(context: Context): List<String> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            Properties().apply { f.inputStream().use { load(it) } }
                .stringPropertyNames().sorted()
        }.getOrDefault(emptyList())
    }

    /** Deletes the file. True when it is gone afterwards (including "was never there"). */
    fun wipe(context: Context): Boolean {
        val f = file(context)
        return !f.isFile || f.delete()
    }
}
