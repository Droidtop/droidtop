package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.StoreIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What Get new games opens on (docs/SPEC.md 13, "Get new games"; owner, 2026-10-23, Droidtop/tracker#612): "The
 * 'get new games' page should NOT open to a blank search bar. It should open to a recommendations list, with the
 * ability to search at the top." Everything here comes from the person's library and their sources; nothing is
 * invented. The sources have no list of new or popular games of their own yet (a `featured` call is the plugin
 * lane's to add), so the page has what the library already knows and what the sources answer for it.
 */
object NewGames {
    /** How many of the most played games are asked about, and how many of each answer are kept. */
    const val ASKS = 3
    const val PER_ASK = 6

    /** One question for the sources: [query] (a developer or a series), asked because the person played [because]. */
    data class Ask(val query: String, val because: String)

    /** A source's answer the person does not have yet, with the source that gave it. */
    data class Find(val sourceLabel: String, val result: AcquireContentResult)

    /** One "More from ..." list. */
    data class Shelf(val ask: Ask, val finds: List<Find>)

    /** What the page shows: the person's games with a newer version, and the lists the sources answered. */
    data class Loaded(val updates: List<LibraryEntry>, val shelves: List<Shelf>, val sources: Int)

    /** The person's games a source names a newer version of, by title. Pure. */
    fun updates(entries: List<LibraryEntry>): List<LibraryEntry> =
        entries.filter { it.availableUpdate != null && !it.hidden }.sortedBy { it.title.lowercase() }

    /**
     * What to ask the sources: for the most played games, most played first, their series or else their developer,
     * each asked once. A game with neither is passed over: there is nothing honest to look for. Pure.
     */
    fun asks(entries: List<LibraryEntry>, limit: Int = ASKS): List<Ask> =
        entries.asSequence()
            .filter { it.playCount > 0 && !it.hidden }
            .sortedWith(compareByDescending<LibraryEntry> { it.playCount }.thenByDescending { it.lastPlayedEpochMs ?: 0L })
            .mapNotNull { entry ->
                val query = entry.series?.trim()?.takeIf { it.isNotEmpty() } ?: entry.developer?.trim()?.takeIf { it.isNotEmpty() }
                query?.let { Ask(it, entry.title) }
            }
            .distinctBy { it.query.lowercase() }
            .take(limit)
            .toList()

    /** [finds] less the games the library has and repeats of one title, at most [limit]. Pure. */
    fun fresh(finds: List<Find>, owned: List<LibraryEntry>, limit: Int = PER_ASK): List<Find> {
        val have = owned.mapTo(HashSet()) { StoreIdentity.titleKey(it.title) }
        return finds
            .filter { StoreIdentity.titleKey(it.result.title).let { key -> key.isNotEmpty() && key !in have } }
            .distinctBy { StoreIdentity.titleKey(it.result.title) }
            .take(limit)
    }

    /**
     * Asks every installed source each [asks] question at once, each bounded by the one search's per-source timeout.
     * A source that fails or does not answer in time adds nothing. Off the main thread.
     */
    suspend fun load(context: Context, entries: List<LibraryEntry>): Loaded = withContext(Dispatchers.IO) {
        val sources = GameSources.plugins(context)
        val asks = asks(entries)
        val shelves = if (sources.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                asks.map { ask ->
                    async {
                        val finds = sources.map { source ->
                            async {
                                withTimeoutOrNull(PluginSearchAggregator.PER_SOURCE_TIMEOUT_MS) {
                                    source.search(context, ask.query, null).getOrNull()
                                }.orEmpty().map { Find(source.label, it) }
                            }
                        }.awaitAll().flatten()
                        Shelf(ask, fresh(finds, entries))
                    }
                }.awaitAll().filter { it.finds.isNotEmpty() }
            }
        }
        Loaded(updates(entries), shelves, sources.size)
    }
}
