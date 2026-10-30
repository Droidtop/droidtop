package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginRuntimeNeeds
import dev.droidtop.pluginhost.PluginWebAccount
import dev.droidtop.pluginhost.RuntimeNeed
import dev.droidtop.pluginhost.SourceFailures
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
    /** What the source itself said when it failed, markup removed and bounded: behind "Technical details", never on a row. */
    val technical: String? = null,
    /**
     * Set when the source needs the person in its site first (SIGN_IN_REQUIRED, BROWSER_CHECK_REQUIRED): droidtop draws
     * Sign in or Open the site under the source's name ([PluginWebAccount]), never the plugin (Droidtop/tracker#544).
     */
    val access: PluginWebAccount.Need? = null,
    /** Set when droidtop's own permission check refused it: the search offers the plugin's page, where it is allowed. */
    val permissionOff: Boolean = false,
) {
    val hits: List<SourceHit> get() = results.map { SourceHit(source, it) }
}

/**
 * A source's error reply kept whole (docs/plugin-api.md 1.3): its [code], its one plain sentence [message] for the
 * person and the [detail] it gave for "Technical details". What a source's search fails with, so the search can draw
 * droidtop's own action for the code instead of a sentence the plugin wrote about it.
 */
class SourceReplyFailure(val code: PluginErrorCode, message: String?, val detail: String?) : Exception(message?.takeIf { it.isNotBlank() } ?: "Search failed")

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
     * A second over a source's own `search` budget ([PluginGameSource.SEARCH_TIMEOUT_MS], 10 s, which is never a
     * crash), so the plugin's own deadline is what ends a slow search: this is the backstop for a source that never
     * returns at all (a hung bind, a stuck coroutine). A future store adapter must honor its own bound inside it too.
     */
    const val PER_SOURCE_TIMEOUT_MS = 11_000L

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
                    onFailure = { failureOutcome(source, it) },
                )
            }
        }.map { it.await() }
    }

    /** What a failed [source] shows: droidtop's sentence for a code it acts on, else the source's plain sentence, guarded. */
    internal fun failureOutcome(source: GameSourceProvider, failure: Throwable, now: Long = System.currentTimeMillis()): SourceOutcome {
        val said = describe(failure)
        val reply = failure as? SourceReplyFailure
        val access = when (reply?.code) {
            PluginErrorCode.SIGN_IN_REQUIRED -> PluginWebAccount.Need.SIGN_IN
            PluginErrorCode.BROWSER_CHECK_REQUIRED -> PluginWebAccount.Need.BROWSER_CHECK
            else -> null
        }
        val permission = if (access == null) SourceFailures.permissionOff(reply?.message ?: said, reply?.code) else null
        val sentence = when (access) {
            PluginWebAccount.Need.SIGN_IN -> SourceFailures.NEEDS_SIGN_IN
            PluginWebAccount.Need.BROWSER_CHECK -> SourceFailures.NEEDS_BROWSER_CHECK
            null -> permission ?: SourceFailures.forCode(reply?.code, reply?.message ?: said)
        }
        // What actually failed, never the headline again (Droidtop/tracker#746): the call, the plugin, the code or the
        // exception, the source's own words and when.
        val manifest = (source as? PluginGameSource)?.source?.record?.manifest
        val technical = SourceFailures.details(
            call = "library.sources search",
            headline = sentence,
            plugin = manifest?.let { "${it.id} ${it.version}" } ?: source.id,
            code = reply?.code,
            exception = if (reply == null) failure::class.java.simpleName else null,
            said = said,
            detail = reply?.detail,
            at = now,
        )
        return SourceOutcome(source, emptyList(), failure = sentence, technical = technical, access = access, permissionOff = permission != null)
    }

    /** [fanOutOutcomes] for a fetcher that has no failure channel of its own (the lookup composition): a throw or timeout is simply no results. */
    internal suspend fun fanOut(
        sources: List<GameSourceProvider>,
        timeoutMs: Long = PER_SOURCE_TIMEOUT_MS,
        fetch: suspend (GameSourceProvider) -> List<AcquireContentResult>,
    ): List<SourceHit> = fanOutOutcomes(sources, timeoutMs) { source -> runCatching { fetch(source) } }.hits()
}
