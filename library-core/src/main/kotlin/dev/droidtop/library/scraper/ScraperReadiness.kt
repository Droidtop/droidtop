package dev.droidtop.library.scraper

import android.content.Context

/**
 * Where every scraper credential is entered. Named in every refusal below,
 * so the sentence alone tells a person what to open.
 */
internal const val SCRAPER_SETTINGS = "Settings > Library > Scraper"

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
        "TheGamesDB needs your own free API key, and none is set. Get one at thegamesdb.net and enter it " +
            "under $SCRAPER_SETTINGS > TheGamesDB > API key, or choose ScreenScraper or the libretro " +
            "database there, which need no key."

    /**
     * Why the selected ROM source cannot run, with the fix; null when it can.
     * Checked once by every ROM pass before any request, whole-library or
     * one system.
     */
    fun romSourceProblem(context: Context): String? = when (ScraperSourcePrefs.get(context)) {
        ScraperSource.THEGAMESDB -> if (TheGamesDbPrefs.isConfigured(context)) null else THEGAMESDB_KEY_MISSING
        ScraperSource.SCREENSCRAPER -> if (
            ScreenScraperPrefs.devId(context).isBlank() || ScreenScraperPrefs.devPassword(context).isBlank()
        ) {
            "ScreenScraper cannot be asked: this build carries no application credentials for it. Choose the " +
                "libretro database under $SCRAPER_SETTINGS, which needs no account."
        } else {
            null
        }
        ScraperSource.LIBRETRO -> null
    }

    /**
     * What to change when [refusal] is a source rejecting the credentials it
     * was sent (HTTP 401 or 403 from a source that takes a key or an
     * account); null for any other refusal, which is not the person's to fix.
     */
    fun credentialFix(refusal: ScrapeLookup.Refused): String? {
        if (refusal.httpStatus != 401 && refusal.httpStatus != 403) return null
        return when {
            refusal.source == "TheGamesDB" ->
                "Check the API key under $SCRAPER_SETTINGS > TheGamesDB > API key."
            refusal.source.startsWith("IGDB") ->
                "Check the Client ID and Client Secret under $SCRAPER_SETTINGS > IGDB."
            refusal.source == "ScreenScraper" ->
                "Check the ScreenScraper account under $SCRAPER_SETTINGS, or choose the libretro database there."
            else -> null
        }
    }
}
