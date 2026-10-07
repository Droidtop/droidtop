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
 * could be fetched (docs/SPEC.md 12a "One search").
 */
enum class SearchRowKind { APP, GAME, SOURCE }

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
)

/** Where one source stands in the current search. */
class SourceStatus(
    val source: GameSourceProvider,
    /** Null while the source has not answered. */
    val outcome: SourceOutcome?,
) {
    val pending: Boolean get() = outcome == null
    val failure: String? get() = outcome?.failure
    val runtimeNeed: RuntimeNeed? get() = outcome?.runtimeNeed
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
) {
    /** Every source hit in [rows]: what the next query carries over while its sources answer. */
    val sourceHits: List<SourceHit> get() = rows.mapNotNull { it.hit }

    companion object {
        fun empty(query: String = "") = UnifiedState(query, emptyList(), emptyList(), emptyList(), settled = true)
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
     * to one system when the surface has one. [carry] is the previous
     * query's source rows: they stay in the list (while they still show the
     * query) until their source answers again, so the list does not blink
     * with every keystroke.
     */
    fun search(
        context: Context,
        query: String,
        platform: String?,
        local: suspend (String) -> List<LocalSearchRow>,
        carry: List<SourceHit> = emptyList(),
    ): Flow<UnifiedState> = stream(
        query = query,
        local = local,
        carry = carry,
        loadSources = {
            withContext(Dispatchers.IO) { GameSources.plugins(context) to AcquireContentSources.unavailablePlugins(context) }
        },
        fetch = { source -> source.search(context, query.trim(), platform) },
        needOf = { source -> PluginSearchAggregator.runtimeNeedOf(context, source) },
    )

    /** [search] with the Android parts injected: what the unit tests drive with fake sources. */
    internal fun stream(
        query: String,
        local: suspend (String) -> List<LocalSearchRow>,
        carry: List<SourceHit> = emptyList(),
        debounceMs: Long = SOURCE_DEBOUNCE_MS,
        timeoutMs: Long = PluginSearchAggregator.PER_SOURCE_TIMEOUT_MS,
        loadSources: suspend () -> Pair<List<GameSourceProvider>, List<UnavailableSource>>,
        fetch: suspend (GameSourceProvider) -> Result<List<AcquireContentResult>>,
        needOf: (GameSourceProvider) -> RuntimeNeed? = { null },
    ): Flow<UnifiedState> = channelFlow {
        val q = query.trim()
        if (q.isEmpty()) {
            send(UnifiedState.empty(q))
            return@channelFlow
        }
        val localRows = local(q)
        var statuses: List<SourceStatus> = emptyList()
        var unavailable: List<UnavailableSource> = emptyList()
        val answered = LinkedHashMap<String, SourceOutcome>()
        val stale = carry.filter { SearchRank.score(it.result.title, q) < SearchRank.OTHER }

        fun state(settled: Boolean): UnifiedState {
            // A source that has not answered yet keeps its carried rows; one that has answered replaces them.
            val kept = stale.filter { it.source.id !in answered }
            val order = statuses.map { it.source }
            return UnifiedState(q, rank(q, localRows, answered.values.toList(), kept, order), statuses, unavailable, settled)
        }

        send(state(settled = false))
        delay(debounceMs)
        val (sources, notRunnable) = loadSources()
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
                    val asked = PluginSearchAggregator.fanOutOutcomes(listOf(source), timeoutMs, fetch).single()
                    val outcome = if (asked.failure == null) asked else asked.copy(runtimeNeed = needOf(source))
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
     * on an exact tie.
     */
    internal fun rank(
        query: String,
        local: List<LocalSearchRow>,
        outcomes: List<SourceOutcome>,
        carried: List<SourceHit>,
        sourceOrder: List<GameSourceProvider>,
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
                order = sourceIndex * SOURCE_ORDER_STRIDE + index,
                open = null,
                hit = hit,
            )
        }
        outcomes.forEach { outcome -> outcome.hits.take(MAX_ROWS_PER_SOURCE).forEachIndexed { i, hit -> sourceRow(hit, i) } }
        carried.groupBy { it.source.id }.values.forEach { group -> group.take(MAX_ROWS_PER_SOURCE).forEachIndexed { i, hit -> sourceRow(hit, i) } }
        return rows.sortedWith(compareBy<SearchRow>({ it.score }, { it.kind.ordinal }, { it.order }))
    }

    private const val SOURCE_ORDER_STRIDE = 100_000

    /** A source row's detail line: where it comes from first, then what the source says about it. */
    internal fun sourceDetail(hit: SourceHit): String =
        listOfNotNull(
            hit.source.label,
            hit.result.columns.joinToString(" · ").takeIf { it.isNotBlank() },
            hit.result.platform?.takeIf { it.isNotBlank() },
            hit.result.sizeLabel?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}
