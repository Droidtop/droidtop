package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.pluginhost.RuntimeNeed
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One source's search result, tagged with the [GameSourceProvider] it
 * came from -- what a result row needs to show its source's label
 * next to a normal game row (docs/SPEC.md 12a "One search").
 */
data class SourceHit(val source: GameSourceProvider, val result: AcquireContentResult)

/**
 * What one source answered for one query: its [results] (possibly none,
 * which is a real answer), or the [failure] sentence when it could not
 * answer (not loaded, timed out, the plugin's own error). [runtimeNeed] is
 * set when the reason is a missing runtime, so a surface can offer the
 * download right there. The search list reports one of these per source
 * instead of folding every failure into "nothing found" (docs/SPEC.md 12a
 * "Search fan-out": "a source's outcome is always shown").
 */
data class SourceOutcome(
    val source: GameSourceProvider,
    val results: List<AcquireContentResult>,
    val failure: String? = null,
    val runtimeNeed: RuntimeNeed? = null,
) {
    val hits: List<SourceHit> get() = results.map { SourceHit(source, it) }
}

/** Every result of every outcome, in source order: what a caller that only wants rows (not reasons) reads. */
fun List<SourceOutcome>.hits(): List<SourceHit> = flatMap { it.hits }

/**
 * The per-source half of the one search ([UnifiedSearch] is the other
 * half): how one query is put to every [GameSourceProvider] (today: every approved+enabled
 * acquire_content plugin, via [GameSources.plugins]; later: built-in
 * store adapters too, same interface), off the main thread, bounded so
 * one slow or hung source can't hold up the rest -- built once here
 * instead of each surface hand-rolling its own source loop (SPEC 12a
 * "Search fan-out", owner directive: "Add ONE generic mechanism").
 *
 * [fanOutOutcomes] is the pure-ish core: it takes the concurrency/timeout/merge
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

    /** The runtime a failed plugin source lacks, so its row can offer the download; null for any other source or when it has what it needs. */
    internal fun runtimeNeedOf(context: Context, source: GameSourceProvider): RuntimeNeed? =
        (source as? PluginGameSource)?.let { PluginRuntimeNeeds.missing(context, it.source.record.manifest) }

    /** A failure sentence a person can read: what the source said, bounded, never a stack trace or an empty string. */
    internal fun describe(failure: Throwable): String {
        val text = failure.message?.trim().orEmpty().ifBlank { failure::class.java.simpleName }
        return if (text.length > MAX_REASON_CHARS) text.take(MAX_REASON_CHARS - 1) + "…" else text
    }

    private const val MAX_REASON_CHARS = 200

    /**
     * Runs [fetch] once per source, concurrently, each call individually
     * bounded by [timeoutMs] and individually failure-isolated: a source
     * that fails or times out contributes a [SourceOutcome] carrying why,
     * rather than failing the whole batch or delaying another source's
     * results. Pure aside from the coroutine dispatch itself, so timeout
     * behaviour is directly testable with fake delaying fetchers under
     * `kotlinx-coroutines-test`'s virtual clock.
     */
    internal suspend fun fanOutOutcomes(
        sources: List<GameSourceProvider>,
        timeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        fetch: suspend (GameSourceProvider) -> Result<List<AcquireContentResult>>,
    ): List<SourceOutcome> = coroutineScope {
        sources.map { source ->
            async {
                val answer: Result<List<AcquireContentResult>> = try {
                    withTimeoutOrNull(timeoutMs) { fetch(source) }
                        ?: Result.failure(RuntimeException("did not answer in time (it may still be starting up); try again in a moment"))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                answer.fold(
                    onSuccess = { SourceOutcome(source, it) },
                    onFailure = { SourceOutcome(source, emptyList(), failure = describe(it)) },
                )
            }
        }.map { it.await() }
    }

    /** [fanOutOutcomes] for a fetcher that has no failure channel of its own (the lookup composition): a throw or timeout is simply no results. */
    internal suspend fun fanOut(
        sources: List<GameSourceProvider>,
        timeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        fetch: suspend (GameSourceProvider) -> List<AcquireContentResult>,
    ): List<SourceHit> = fanOutOutcomes(sources, timeoutMs) { source -> runCatching { fetch(source) } }.hits()
}
