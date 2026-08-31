package dev.droidtop.library.scraper

import android.content.Context

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
    private const val PREFS_NAME = "com.android.launcher3.prefs"
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

    fun scrapeArtwork(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_CONTENT_ARTWORK, true)

    fun setScrapeArtwork(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONTENT_ARTWORK, value).apply()
    }
}
