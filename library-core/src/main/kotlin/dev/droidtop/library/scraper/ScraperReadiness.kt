package dev.droidtop.library.scraper

import android.content.Context

/**
 * Where every scraper credential is entered. Named in every refusal below,
 * so the sentence alone tells a person what to open.
 */
internal const val SCRAPER_SETTINGS = "Settings > Library > Scraper"

/** Where IGDB and SteamGridDB are set up: a guided screen behind each source row. */
internal const val SOURCE_SETUP = "Settings > Accounts and sources"

/**
 * Whether a scraper source can be asked at all, and what to do when it
 * cannot (docs/SPEC.md 7h). Two cases, one rule: a source with a missing
 * key refuses BEFORE it is asked, and a source that rejected the key it was
 * sent says so and names the same place to fix it. Neither may end in a
 * count: "Scraped 3 systems." with no key set was the whole bug (rig,
 * build 814).
 */
object ScraperReadiness {

    /** TheGamesDB asked for with no key: the pass, the manual match and the picker all say this. */
    const val THEGAMESDB_KEY_MISSING =
        "TheGamesDB needs your own free API key in this build, and none is set. Get one at thegamesdb.net " +
            "and enter it under $SOURCE_SETUP > TheGamesDB, or choose ScreenScraper or the " +
            "libretro database under $SCRAPER_SETTINGS, which need no key."

    /**
     * Why the selected ROM source cannot run, with the fix; null when it can.
     * Checked once by every ROM pass before any request, whole-library or
     * one system.
     */
    fun romSourceProblem(context: Context): String? = when (ScraperSourcePrefs.get(context)) {
        ScraperSource.THEGAMESDB -> if (TheGamesDbPrefs.isConfigured(context)) null else THEGAMESDB_KEY_MISSING
        ScraperSource.SCREENSCRAPER -> null
        ScraperSource.LIBRETRO -> null
    }

    /**
     * Why the selected PC and engine game source cannot run, with the fix;
     * null when it can. Named settings, not codes: a person who has never
     * opened the scraper screen can act on this sentence alone.
     */
    fun pcSourceProblem(context: Context): String? = when (PcScraperSourcePrefs.get(context)) {
        PcScraperSource.LUTRIS -> null
        PcScraperSource.IGDB -> if (ScraperPrefs.isConfigured(context)) {
            null
        } else {
            "IGDB needs your own free API credentials: create an application at dev.twitch.tv/console, " +
                "then enter its Client ID and Client Secret under $SOURCE_SETUP > IGDB. " +
                "Lutris needs no account at all if you would rather not."
        }
        PcScraperSource.STEAMGRIDDB -> if (SteamGridDbPrefs.isConfigured(context)) null else STEAMGRIDDB_KEY_MISSING
    }

    /** SteamGridDB selected with no key: the pass and the picker both say this. */
    const val STEAMGRIDDB_KEY_MISSING =
        "SteamGridDB needs your own free API key, and none is set. Sign in at steamgriddb.com and create one " +
            "under Preferences > API (steamgriddb.com/profile/preferences/api), then enter it under " +
            "$SOURCE_SETUP > SteamGridDB. Lutris needs no account at all if you would rather not."

    /**
     * Whether [refusal] is the server rejecting the credentials the source
     * was sent: a 401 or 403, and the one 400 that is one -- IGDB's Twitch
     * sign-in answers a wrong Client ID or Secret with HTTP 400, not
     * 401/403 (Twitch's own OAuth2 token endpoint, observed in the review
     * of 64d6547d, 2026-09-29). Such a refusal will be identical on the
     * next request, so [PcFlavour] silences the source for the pass and
     * [credentialFix] names the setting.
     */
    fun rejectedCredentials(refusal: ScrapeLookup.Refused): Boolean =
        refusal.httpStatus == 401 || refusal.httpStatus == 403 ||
            (refusal.httpStatus == 400 && refusal.source == IgdbScraperClient.SIGNIN_SOURCE)

    /**
     * What to change when [refusal] is a source rejecting the credentials
     * it was sent (see [rejectedCredentials]); null for any other refusal,
     * which is not the person's to fix.
     */
    fun credentialFix(refusal: ScrapeLookup.Refused): String? {
        if (!rejectedCredentials(refusal)) return null
        return when {
            refusal.source == "TheGamesDB" ->
                "Check the API key under $SOURCE_SETUP > TheGamesDB."
            refusal.source.startsWith("IGDB") ->
                "Check the Client ID and Client Secret under $SOURCE_SETUP > IGDB."
            refusal.source == SteamGridDbScraperClient.SOURCE ->
                "Check the API key under $SOURCE_SETUP > SteamGridDB."
            else -> null
        }
    }
}
