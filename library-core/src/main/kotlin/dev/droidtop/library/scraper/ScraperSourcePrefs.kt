package dev.droidtop.library.scraper

import android.content.Context

enum class ScraperSource { SCREENSCRAPER, THEGAMESDB, LIBRETRO }

/**
 * Real, single selected ROM scraper source -- matching real ES-DE's own
 * actual architecture exactly (confirmed against real source,
 * `es-app/src/scrapers/Scraper.cpp`'s `scraper_request_funcs` map and
 * `Settings::getInstance()->getString("Scraper")`): ES-DE has NO
 * automatic multi-source fallback/priority chain at all -- a user picks
 * exactly ONE scraper (`GuiScraperMenu`'s real "SCREENSCRAPER"/
 * "THEGAMESDB" radio choice), and only that one source is ever queried
 * for a given scrape. An earlier version of droidtop's own scrape logic
 * tried ScreenScraper then silently fell back to TheGamesDB -- a
 * droidtop invention, not real ES-DE parity, corrected here to match the
 * real single-source-selection model. Real ES-DE's own default is
 * "screenscraper" (confirmed via `GuiScraperMenu.cpp`'s own comment: "set
 * the scraper to 'screenscraper' in this case").
 */
object ScraperSourcePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_SOURCE = "droidtop_rom_scraper_source"

    fun get(context: Context): ScraperSource {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SOURCE, null)
        return if (raw == "thegamesdb") ScraperSource.THEGAMESDB else ScraperSource.SCREENSCRAPER
    }

    fun set(context: Context, source: ScraperSource) {
        val raw = if (source == ScraperSource.THEGAMESDB) "thegamesdb" else "screenscraper"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, raw).apply()
    }
}
