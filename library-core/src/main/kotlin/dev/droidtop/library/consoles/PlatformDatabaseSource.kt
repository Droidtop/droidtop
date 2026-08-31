package dev.droidtop.library.consoles

import android.content.Context

/**
 * The one place the platform-database repository lives (docs/SPEC.md
 * §7e2/§7e2b). Every data-driven database droidtop refreshes -- players,
 * platforms, engine routing, BIOS -- is a file in the same repo, so the
 * URL is one base plus a filename rather than four separately-hardcoded
 * full URLs, which is what this replaced.
 *
 * Overridable at runtime for two real reasons, not speculation:
 *
 * 1. **Repo moves.** These URLs ship compiled into the app, and
 *    `raw.githubusercontent.com` does not reliably redirect after a
 *    repository transfer. Without an override, moving the repo (e.g. to
 *    a dedicated organization) would silently break database updates on
 *    every already-installed build until the user updated the app
 *    itself. With one, an existing install is repointed from Settings.
 * 2. **Forks and mirrors.** The databases are deliberately data, not
 *    code -- someone maintaining their own player/platform set should be
 *    able to point droidtop at it without rebuilding.
 */
object PlatformDatabaseSource {
    const val DEFAULT_BASE_URL =
        "https://raw.githubusercontent.com/bi0shacker001/droidtop-platforms/main"

    private const val PREFS_NAME = "com.android.launcher3.prefs"
    const val KEY_BASE_URL = "pref_platform_database_base_url"

    /** The configured base, or [DEFAULT_BASE_URL]; never with a trailing slash. */
    fun baseUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_BASE_URL

    fun setBaseUrl(context: Context, baseUrl: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            val cleaned = baseUrl?.trim()?.trimEnd('/')
            if (cleaned.isNullOrEmpty()) remove(KEY_BASE_URL) else putString(KEY_BASE_URL, cleaned)
        }.apply()
    }

    fun urlFor(context: Context, fileName: String): String = "${baseUrl(context)}/$fileName"
}
