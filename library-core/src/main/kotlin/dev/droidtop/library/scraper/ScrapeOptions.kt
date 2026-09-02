package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * ES-DE-style scraper options (per direction: the scrape flow prompts
 * like real ES-DE, with game filters and content selection). Real
 * ES-DE's GuiScraperMenu offers "scrape these games" filters and its
 * scraper settings gate which content types are fetched; droidtop keeps
 * the same two axes as stored options every scrape action honors, in
 * the same settings screen the scraper is configured in.
 */
enum class ScrapeFilter(val label: String) {
    MISSING_ANY("Games missing artwork or metadata"),
    MISSING_ARTWORK("Games missing artwork"),
    MISSING_METADATA("Games missing metadata"),
    FAVORITES("Favorite games only"),
    ALL("All games (rescrape everything)"),
}

object ScrapeOptionsPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_FILTER = "droidtop_scrape_filter"
    private const val KEY_CONTENT_METADATA = "droidtop_scrape_content_metadata"
    private const val KEY_CONTENT_ARTWORK = "droidtop_scrape_content_artwork"

    fun filter(context: Context): ScrapeFilter {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FILTER, null) ?: return ScrapeFilter.MISSING_ANY
        return runCatching { ScrapeFilter.valueOf(raw) }.getOrDefault(ScrapeFilter.MISSING_ANY)
    }

    fun setFilter(context: Context, value: ScrapeFilter) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_FILTER, value.name).apply()
    }

    fun scrapeMetadata(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_CONTENT_METADATA, true)

    fun setScrapeMetadata(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONTENT_METADATA, value).apply()
    }

    private fun bool(context: Context, key: String, default: Boolean = true): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, default)

    private fun setBool(context: Context, key: String, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    // Per-media-type toggles, every default ON exactly like real ES-DE's
    // own Scrape* settings defaults (Settings.cpp, read directly).
    fun scrapeScreenshots(context: Context) = bool(context, "droidtop_scrape_screenshots")
    fun setScrapeScreenshots(context: Context, value: Boolean) = setBool(context, "droidtop_scrape_screenshots", value)
    fun scrapeTitleScreens(context: Context) = bool(context, "droidtop_scrape_titlescreens")
    fun setScrapeTitleScreens(context: Context, value: Boolean) = setBool(context, "droidtop_scrape_titlescreens", value)
    fun scrapeMarquees(context: Context) = bool(context, "droidtop_scrape_marquees")
    fun setScrapeMarquees(context: Context, value: Boolean) = setBool(context, "droidtop_scrape_marquees", value)
    fun scrapePhysicalMedia(context: Context) = bool(context, "droidtop_scrape_physicalmedia")
    fun setScrapePhysicalMedia(context: Context, value: Boolean) = setBool(context, "droidtop_scrape_physicalmedia", value)
    fun scrapeFanArt(context: Context) = bool(context, "droidtop_scrape_fanart")
    fun setScrapeFanArt(context: Context, value: Boolean) = setBool(context, "droidtop_scrape_fanart", value)
    fun scrapeVideos(context: Context) = bool(context, "droidtop_scrape_videos")
    fun setScrapeVideos(context: Context, value: Boolean) = setBool(context, "droidtop_scrape_videos", value)
    fun generateMiximages(context: Context) = bool(context, "droidtop_scrape_miximages")
    fun setGenerateMiximages(context: Context, value: Boolean) = setBool(context, "droidtop_scrape_miximages", value)

    fun scrapeArtwork(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_CONTENT_ARTWORK, true)

    fun setScrapeArtwork(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONTENT_ARTWORK, value).apply()
    }
}
