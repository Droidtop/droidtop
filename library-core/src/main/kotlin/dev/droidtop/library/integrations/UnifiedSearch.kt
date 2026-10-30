package dev.droidtop.library.integrations

import android.content.Context
import android.graphics.Bitmap
import dev.droidtop.pluginhost.RuntimeNeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a row of the one search list is. The order is the tie-break between
 * two rows that match equally well: what is on the device comes before what
 * could be fetched (docs/SPEC.md 12a "One search"), and a web search, where a
 * surface offers one, last.
 */
enum class SearchRowKind { APP, GAME, SOURCE, WEB }

/**
 * A row the caller found on the device (an installed app, a library game).
 * Local rows are matched by the caller over lists it already holds in memory
 * (no disk lookups while typing); [UnifiedSearch] only ranks them.
 */
class LocalSearchRow(
    val key: String,
    val kind: SearchRowKind,
    val title: String,
    val detail: String? = null,
    val icon: Bitmap? = null,
    val open: () -> Unit,
)

/** One row of the one result list, whatever it is and wherever it came from. */
class SearchRow(
    val key: String,
    val kind: SearchRowKind,
    val title: String,
    val detail: String?,
    val icon: Bitmap?,
    /** How well [title] matches the query: [SearchRank]; lower is better. */
    val score: Int,
    /** The row's place among rows that tie: a local row's alphabetical index, a source row's place in that source's own answer. */
    val order: Int,
    /** Set for a local row: what picking it does. */
    val open: (() -> Unit)?,
    /** Set for a source row: the source and its result, for the detail and the download. */
    val hit: SourceHit?,
    /**
     * The same game from elsewhere (owner, 2026-10-16: "Clicking on the game entry should give the game's page, or
     * options if it's available from multiple sources"): the library's row and every source's row for one title are
     * one row, and this holds every one of them as found, the front one first. Picking a row with others asks which.
     */
    val others: List<SearchRow> = emptyList(),
) {
    /** The places a pick offers: every row merged into this one, or this row alone. */
    val choices: List<SearchRow> get() = others.ifEmpty { listOf(this) }

    /**
     * A game the person does not have yet: no place it is found is on the device, or it is a site to search on. Such
     * rows go under "Get new games", after what is here (owner, 2026-10-23, Droidtop/tracker#612: "Get new games should
     * be presented under the search results, presenting matches that are relevant").
     */
    val isNew: Boolean get() = kind == SearchRowKind.WEB || choices.none { it.open != null }
}

/** Where one source stands in the current search. */
class SourceStatus(
    val source: GameSourceProvider,
    /** Null while the source has not answered. */
    val outcome: SourceOutcome?,
) {
    val pending: Boolean get() = outcome == null
    val failure: String? get() = outcome?.failure
    val runtimeNeed: RuntimeNeed? get() = outcome?.runtimeNeed
    val technical: String? get() = outcome?.technical
    val access: dev.droidtop.pluginhost.PluginWebAccount.Need? get() = outcome?.access
}

/**
 * The one action that fixes a failed source's search (docs/SPEC.md 12a, "One search", Droidtop/tracker#746): what A on
 * the source's row runs, read from what the failure already says. Pure.
 */
enum class SourceFix(val label: String) {
    SIGN_IN("Sign in"),
    OPEN_SITE("Open the site"),
    OPEN_PAGE("Open its page"),
    GET_RUNTIME("Get the runtime"),
    TRY_AGAIN("Try again"),
    ;

    companion object {
        /**
         * [access] is what the source needs the person to do in its site, honoured only when droidtop knows that site
         * ([siteKnown]); [permissionOff] is droidtop's own permission check refusing it; [needsRuntime] a runtime it
         * runs on is missing. Anything else is tried again.
         */
        fun of(
            access: dev.droidtop.pluginhost.PluginWebAccount.Need?,
            siteKnown: Boolean,
            permissionOff: Boolean,
            needsRuntime: Boolean,
        ): SourceFix = when {
            access == dev.droidtop.pluginhost.PluginWebAccount.Need.SIGN_IN && siteKnown -> SIGN_IN
            access != null && siteKnown -> OPEN_SITE
            permissionOff -> OPEN_PAGE
            needsRuntime -> GET_RUNTIME
            else -> TRY_AGAIN
        }

        /** Several failed sources fold into this one row until it is opened. */
        fun foldedTitle(count: Int): String = if (count == 1) "1 source needs attention" else "$count sources need attention"
    }
}

/**
 * What the search shows right now. It is emitted again each time something
 * arrives: the device's rows first, then each source as it answers, so a
 * slow source never holds up the list. [settled] is true once every source
 * has answered or timed out.
 */
class UnifiedState(
    val query: String,
    val rows: List<SearchRow>,
    val sources: List<SourceStatus>,
    /** Installed plugins that cannot answer at all (waiting for approval, turned off). */
    val unavailable: List<UnavailableSource>,
    val settled: Boolean,
    /**
     * The sources the person turned off in Get games' source filter ([SourceSelection]): not asked, still listed so the
     * filter can draw them, off (owner, 2026-10-30, Droidtop/tracker#825: "a filter to choose where it searches").
     */
    val off: List<GameSourceProvider> = emptyList(),
    /** With no query: [rows] are what the sources feature now, not matches (Droidtop/tracker#825). */
    val featured: Boolean = false,
) {
    /** Every source hit in [rows]: what the next query carries over while its sources answer. */
    val sourceHits: List<SourceHit> get() = rows.mapNotNull { it.hit }

    companion object {
        fun empty(query: String = "", off: List<GameSourceProvider> = emptyList()) =
            UnifiedState(query, emptyList(), emptyList(), emptyList(), settled = true, off = off)
    }
}

/** The one rule for how well a title matches a query; lower is better. */
object SearchRank {
    const val EXACT = 0
    const val PREFIX = 1
    const val WORD = 2
    const val CONTAINS = 3

    /** The title does not show the query (a source matched on its description or another name). */
    const val OTHER = 4

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")

    fun score(title: String, query: String): Int {
        val t = title.trim().lowercase()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return OTHER
        if (t == q) return EXACT
        if (t.startsWith(q)) return PREFIX
        val words = t.split(NON_WORD).filter { it.isNotEmpty() }
        val tokens = q.split(SPACES).filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) } || (tokens.size > 1 && tokens.all { token -> words.any { it.startsWith(token) } })) return WORD
        if (t.contains(q)) return CONTAINS
        return OTHER
    }
}

/**
 * The ONE search (docs/SPEC.md 12a "One search", Droidtop/tracker#315): a
 * single query over the device's own rows (installed apps, library games,
 * from the caller) and every [GameSourceProvider] (built-in and plugin),
 * answered as ONE ranked list. There is no separate plugin mode and no
 * "Get more" group: a source's result is a row like any other, ranked by
 * the same [SearchRank] and told apart by its detail line.
 *
 * Cost model, for a handheld: the device's rows are computed at once from
 * the caller's in-memory lists; the sources are asked only after the text
 * has rested for [SOURCE_DEBOUNCE_MS], concurrently, each bounded by a
 * timeout, and each source's rows join the list the moment that source
 * answers. A new query cancels the old one (collect the flow in a
 * `LaunchedEffect` keyed on the text), so nothing runs for text that is
 * gone. No disk work happens per keystroke: the plugin store is read once
 * per settled query, on the IO dispatcher.
 */
object UnifiedSearch {
    /** How long the text must rest before any source is asked. */
    const val SOURCE_DEBOUNCE_MS = 350L

    /** The most rows one source adds, so a chatty source cannot bury the rest. */
    const val MAX_ROWS_PER_SOURCE = 10

    /**
     * The search for [query]. [local] answers for the device (called off the
     * main thread, must not touch the disk); [platform] scopes the sources
     * to one system when the surface has one: only the sources whose manifest declares that system (or
     * none) are asked, "pc" being the PC library (Droidtop/tracker#543). [carry] is the previous
     * query's source rows: they stay in the list (while they still show the
     * query) until their source answers again, so the list does not blink
     * with every keystroke. [keep] drops a source result the surface has no
     * use for before it is ranked (the PC library drops the console systems'
     * results), so what a source answers for other platforms never reaches the list.
     *
     * Get games is this one search (owner, 2026-10-30, Droidtop/tracker#825: "We want a single get games section, and
     * a filter to choose where it searches"): the sources the person turned off ([SourceSelection]) are not asked, and
     * with [featuredWhenEmpty] an empty query asks every source for what it features now, merged the same way.
     */
    fun search(
        context: Context,
        query: String,
        platform: String?,
        local: suspend (String) -> List<LocalSearchRow>,
        carry: List<SourceHit> = emptyList(),
        keep: (AcquireContentResult) -> Boolean = { true },
        featuredWhenEmpty: Boolean = false,
    ): Flow<UnifiedState> {
        val off = SourceSelection.off(context)
        return stream(
            query = query,
            local = local,
            carry = carry,
            keep = keep,
            loadSources = {
                withContext(Dispatchers.IO) { GameSources.plugins(context, platform) to AcquireContentSources.unavailablePlugins(context, platform) }
            },
            // One search per source in flight, the newest query asked next ("Fetchers answer in the one search").
            fetch = { source -> SourceSearchFlight.shared.run(source.id) { source.search(context, query.trim(), platform) } },
            needOf = { source -> PluginSearchAggregator.runtimeNeedOf(context, source) },
            isOff = { it.id in off },
            featured = if (featuredWhenEmpty) { source -> source.featured(context, platform, MAX_ROWS_PER_SOURCE) } else null,
        )
    }

    /** [search] with the Android parts injected: what the unit tests drive with fake sources. */
    internal fun stream(
        query: String,
        local: suspend (String) -> List<LocalSearchRow>,
        carry: List<SourceHit> = emptyList(),
        debounceMs: Long = SOURCE_DEBOUNCE_MS,
        keep: (AcquireContentResult) -> Boolean = { true },
        timeoutMs: Long = PluginSearchAggregator.PER_SOURCE_TIMEOUT_MS,
        loadSources: suspend () -> Pair<List<GameSourceProvider>, List<UnavailableSource>>,
        fetch: suspend (GameSourceProvider) -> Result<List<AcquireContentResult>>,
        needOf: (GameSourceProvider) -> RuntimeNeed? = { null },
        isOff: (GameSourceProvider) -> Boolean = { false },
        featured: (suspend (GameSourceProvider) -> Result<List<AcquireContentResult>>)? = null,
    ): Flow<UnifiedState> = channelFlow {
        val q = query.trim()
        // No query: nothing, or what the sources feature now when the surface asks for it (Droidtop/tracker#825).
        if (q.isEmpty() && featured == null) {
            send(UnifiedState.empty(q))
            return@channelFlow
        }
        val showFeatured = q.isEmpty()
        val ask = if (showFeatured) featured!! else fetch
        val localRows = if (showFeatured) emptyList() else local(q)
        var statuses: List<SourceStatus> = emptyList()
        var unavailable: List<UnavailableSource> = emptyList()
        var off: List<GameSourceProvider> = emptyList()
        val answered = LinkedHashMap<String, SourceOutcome>()
        val stale = if (showFeatured) emptyList() else carry.filter { SearchRank.score(it.result.title, q) < SearchRank.OTHER && keep(it.result) }

        fun state(settled: Boolean): UnifiedState {
            // A source that has not answered yet keeps its carried rows; one that has answered replaces them.
            val kept = stale.filter { it.source.id !in answered }
            val order = statuses.map { it.source }
            val rows = rank(q, localRows, answered.values.toList(), kept, order, interleave = showFeatured)
            return UnifiedState(q, rows, statuses, unavailable, settled, off, featured = showFeatured)
        }

        send(state(settled = false))
        if (!showFeatured) delay(debounceMs)
        val (all, notRunnable) = loadSources()
        val (offSources, sources) = all.partition(isOff)
        off = offSources
        unavailable = notRunnable
        statuses = sources.map { SourceStatus(it, null) }
        if (sources.isEmpty()) {
            send(state(settled = true))
            return@channelFlow
        }
        send(state(settled = false))
        val gate = Mutex()
        coroutineScope {
            for (source in sources) {
                launch {
                    val asked = PluginSearchAggregator.fanOutOutcomes(listOf(source), timeoutMs, ask).single()
                    val outcome = if (asked.failure == null) asked.copy(results = asked.results.filter(keep)) else asked.copy(runtimeNeed = needOf(source))
                    gate.withLock {
                        answered[source.id] = outcome
                        statuses = statuses.map { if (it.source.id == source.id) SourceStatus(source, outcome) else it }
                        send(state(settled = answered.size == sources.size))
                    }
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Merges the device's rows and the sources' answers into the one list.
     * Rows sort by match quality, then device before source, then the row's
     * own order (alphabetical for the device, the source's own relevance for
     * a source). Pure, so it is unit-tested. [sourceOrder] is the sources in
     * the order they were asked: it decides which source's rows come first
     * on an exact tie. With [interleave] (what the sources feature, with no query to rank by), the sources' rows take
     * turns: every source's first, then every source's second, so no one source fills the top of the list.
     */
    internal fun rank(
        query: String,
        local: List<LocalSearchRow>,
        outcomes: List<SourceOutcome>,
        carried: List<SourceHit>,
        sourceOrder: List<GameSourceProvider>,
        interleave: Boolean = false,
    ): List<SearchRow> {
        val rows = ArrayList<SearchRow>()
        local.sortedBy { it.title.lowercase() }.forEachIndexed { index, row ->
            rows += SearchRow(row.key, row.kind, row.title, row.detail, row.icon, SearchRank.score(row.title, query), index, row.open, null)
        }
        fun sourceRow(hit: SourceHit, index: Int) {
            val sourceIndex = sourceOrder.indexOfFirst { it.id == hit.source.id }.coerceAtLeast(0)
            rows += SearchRow(
                key = "${hit.source.id}/${hit.result.id}",
                kind = SearchRowKind.SOURCE,
                title = hit.result.title,
                detail = sourceDetail(hit),
                icon = null,
                score = SearchRank.score(hit.result.title, query),
                order = if (interleave) index * SOURCE_ORDER_STRIDE + sourceIndex else sourceIndex * SOURCE_ORDER_STRIDE + index,
                open = null,
                hit = hit,
            )
        }
        outcomes.forEach { outcome -> outcome.hits.take(MAX_ROWS_PER_SOURCE).forEachIndexed { i, hit -> sourceRow(hit, i) } }
        carried.groupBy { it.source.id }.values.forEach { group -> group.take(MAX_ROWS_PER_SOURCE).forEachIndexed { i, hit -> sourceRow(hit, i) } }
        return merge(rows.sortedWith(compareBy<SearchRow>({ it.score }, { it.kind.ordinal }, { it.order })))
    }

    /**
     * One row per game: a library game's row and the sources' rows whose titles fold to the same key
     * (StoreIdentity.titleKey, the fold search matching and store matching use) are one row, the best-ranked in front
     * and its detail naming every place it is from ("Game · F95zone · Nyaa"). Apps and the web row are never merged.
     */
    internal fun merge(ranked: List<SearchRow>): List<SearchRow> {
        val groups = LinkedHashMap<String, MutableList<SearchRow>>()
        val out = ArrayList<Any>()
        ranked.forEach { row ->
            val key = if (row.kind == SearchRowKind.GAME || row.kind == SearchRowKind.SOURCE) {
                dev.droidtop.library.StoreIdentity.titleKey(row.title).takeIf { it.isNotEmpty() }
            } else {
                null
            }
            if (key == null) {
                out.add(row)
            } else {
                val group = groups[key]
                if (group == null) {
                    val created = mutableListOf(row)
                    groups[key] = created
                    out.add(created)
                } else {
                    group += row
                }
            }
        }
        return out.map { item ->
            @Suppress("UNCHECKED_CAST")
            val group = item as? MutableList<SearchRow> ?: return@map item as SearchRow
            val front = group.first()
            if (group.size == 1) return@map front
            val places = group.map { placeOf(it) }.distinct().joinToString(" · ")
            SearchRow(front.key, front.kind, front.title, places, front.icon, front.score, front.order, front.open, front.hit, group.toList())
        }
    }

    /** Where a row is from, for a merged row's detail and the choice list: the source's name, else the row's own detail. */
    fun placeOf(row: SearchRow): String = row.hit?.source?.label ?: row.detail?.takeIf { it.isNotBlank() } ?: "Library"

    private const val SOURCE_ORDER_STRIDE = 100_000

    /** A source row's detail line: where it comes from first, then what the source says about it. */
    fun sourceDetail(hit: SourceHit): String =
        listOfNotNull(
            hit.source.label,
            hit.result.columns.joinToString(" · ").takeIf { it.isNotBlank() },
            hit.result.platform?.takeIf { it.isNotBlank() },
            hit.result.sizeLabel?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}
