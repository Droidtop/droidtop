package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.runtime.prefs.PrefsFile
import java.io.File

/**
 * Fewer requests to the sites behind metadata plugins (docs/SPEC.md 7h, "Fewer requests"). The owner: "Maybe caching
 * might reduce our hits to the service somewhat". A source that found nothing for a game is not asked about it again
 * for [MISS_KEEP_MS], and a game with nothing left for a source to fill is not asked about at all.
 */
internal object PluginScrapeMemory {
    const val MISS_KEEP_MS = 7L * 24 * 60 * 60 * 1000

    private fun misses(context: Context) = PrefsFile(context, "droidtop.plugin_scrape_misses").keyedStrings("miss.")

    private fun key(pluginId: String, entryId: String) = "$pluginId|$entryId"

    /** Whether a "no match" stamped at [stamp] still holds at [now]. */
    fun fresh(stamp: Long?, now: Long): Boolean = stamp != null && now - stamp in 0 until MISS_KEEP_MS

    fun recentlyMissed(context: Context, pluginId: String, entryId: String, now: Long): Boolean =
        fresh(misses(context).get(key(pluginId, entryId))?.toLongOrNull(), now)

    /** Keeps a source's "no match" for the game, or forgets it once the source found something. */
    fun note(context: Context, pluginId: String, entryId: String, missed: Boolean, now: Long) {
        val store = misses(context)
        val id = key(pluginId, entryId)
        if (missed) store.set(id, now.toString()) else if (store.get(id) != null) store.set(id, null)
    }

    /**
     * Whether a game still lacks something a metadata source fills: its description, developer, genre or cover. One
     * with all four is not asked about.
     */
    fun wantsPluginFields(row: GameMetadataEntity?): Boolean =
        row == null || row.description.isNullOrBlank() || row.developer.isNullOrBlank() || row.genre.isNullOrBlank() ||
            row.artworkPath?.let { File(it).isFile } != true
}
