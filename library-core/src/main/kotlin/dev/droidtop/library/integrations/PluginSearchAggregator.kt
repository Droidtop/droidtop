package dev.droidtop.library.integrations

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One source's search result, tagged with the [GameSourceProvider] it
 * came from -- what a "Get more" row needs to show its source's label
 * next to a normal game row (docs/SPEC.md 12a "Search fan-out").
 */
data class SourceHit(val source: GameSourceProvider, val result: AcquireContentResult)

/**
 * The ONE generic mechanism every existing game-search surface (the
 * shared LibraryQuery search behind the PC library and any console list
 * that adopts it, the launcher drawer/QSB search) asks to fan a query out
 * to every [GameSourceProvider] (today: every approved+enabled
 * acquire_content plugin, via [GameSources.plugins]; later: built-in
 * store adapters too, same interface), off the main thread, bounded so
 * one slow or hung source can't hold up the rest -- built once here
 * instead of each surface hand-rolling its own source loop (SPEC 12a
 * "Search fan-out", owner directive: "Add ONE generic mechanism").
 *
 * [fanOut] is the pure-ish core: it takes the concurrency/timeout/merge
 * shape as a plain suspend fetcher per source, so
 * PluginSearchAggregatorTest can drive it with fake [GameSourceProvider]s
 * under `kotlinx-coroutines-test`'s virtual clock instead of a real
 * plugin connection -- the same shape [GetMoreComposer] reuses for the
 * empty-query "Recommendations ∩ Sources.lookup" composition.
 */
object PluginSearchAggregator {
    /**
     * Slightly over the per-call watchdog
     * ([dev.droidtop.pluginhost.PluginRunner.CALL_TIMEOUT_MS], 15s) a
     * single plugin's own `invoke()` is already bounded by, so a plugin
     * that answers right at that limit is not itself the reason the
     * fan-out looks slower than any one source -- this timeout is a
     * backstop for a source that never returns at all (a hung bind, a
     * stuck coroutine), not the plugin's own watchdog's job. A future
     * store adapter must honor its own bound inside this window too.
     */
    const val PER_SOURCE_TIMEOUT_MS = 16_000L

    /** Every source's own [GameSourceProvider.search] results for [query], fanned out in parallel and merged. Blank [query] or no sources returns no results -- the empty-query "Get more" behavior is [GetMoreComposer.composeEmpty], not this. */
    suspend fun searchAll(
        context: Context,
        sources: List<GameSourceProvider>,
        query: String,
        platform: String?,
    ): List<SourceHit> {
        if (query.isBlank() || sources.isEmpty()) return emptyList()
        return fanOut(sources) { source -> source.search(context, query, platform).getOrNull().orEmpty() }
    }

    /**
     * Runs [fetch] once per source, concurrently, each call individually
     * bounded by [timeoutMs] and individually failure-isolated: a source
     * that throws or times out contributes nothing rather than failing
     * the whole batch or delaying another source's results. Pure aside
     * from the coroutine dispatch itself, so timeout behaviour is
     * directly testable with fake delaying fetchers under
     * `kotlinx-coroutines-test`'s virtual clock.
     */
    internal suspend fun fanOut(
        sources: List<GameSourceProvider>,
        timeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        fetch: suspend (GameSourceProvider) -> List<AcquireContentResult>,
    ): List<SourceHit> = coroutineScope {
        val perSource = sources.map { source ->
            source to async {
                runCatching { withTimeoutOrNull(timeoutMs) { fetch(source) } }.getOrNull().orEmpty()
            }
        }
        merge(perSource.associate { (source, deferred) -> source to deferred.await() })
    }

    /**
     * Pure grouping: every source's own results, kept in the order
     * [bySource]'s keys were given (insertion order of the
     * `LinkedHashMap` [fanOut]'s `associate` builds), each source's own
     * results kept in the order it returned them -- no cross-source
     * resorting, so a source's own relevance ranking survives into the
     * "Get more" group.
     */
    internal fun merge(bySource: Map<GameSourceProvider, List<AcquireContentResult>>): List<SourceHit> =
        bySource.entries.flatMap { (source, results) -> results.map { SourceHit(source, it) } }
}
