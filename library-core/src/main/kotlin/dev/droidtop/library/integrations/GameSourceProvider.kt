package dev.droidtop.library.integrations

import android.content.Context
import java.io.File

/**
 * The Sources API (docs/SPEC.md 12a "Sources API"): the one interface
 * droidtop's UI talks to for "search for a game" and "where can I get
 * this game" -- a built-in store and a content plugin are interchangeable
 * implementations of the SAME interface, so droidtop's UI never imports
 * or branches on a store's or a plugin's own type. Owner principle:
 * "We need to think in terms of APIs."
 *
 * **Threading.** Every method is `suspend` and expected to do its own
 * work off the caller's thread (a plugin implementation already runs its
 * binder call off the main thread inside [PluginCrashPolicy]; a future
 * store adapter making a real HTTP call must do the same) -- nothing in
 * this interface is safe to call from a UI thread directly.
 *
 * **Timeout.** A plugin implementation is bounded by the plugin runner's
 * own watchdog ([dev.droidtop.pluginhost.PluginRunner.CALL_TIMEOUT_MS],
 * 15s) for [search]/[lookup]; [PluginSearchAggregator.PER_SOURCE_TIMEOUT_MS]
 * (16s) is the caller-side backstop for a source that never returns at
 * all. A store adapter must declare and honor its own bound the same way
 * when one is built.
 *
 * **Errors.** [search] and [lookup] return `Result.failure` rather than
 * throwing across this boundary -- one source's failure is data
 * (something to log or silently drop from a fan-out), never an exception
 * a caller has to catch per-source.
 *
 * **Versioning.** This is droidtop's own internal Kotlin interface, not a
 * wire contract -- a plugin never implements it directly; [PluginGameSource]
 * is the one adapter translating plugin-host's actual wire contract
 * (`invoke`/`startJob` over [dev.droidtop.pluginhost.PluginCapability.ACQUIRE_CONTENT],
 * documented on [AcquireContentSources]) into this shape. Adding a method
 * here is a normal Kotlin source change gated by droidtop's own compile,
 * not a plugin-facing breaking change; [dev.droidtop.pluginhost.PLUGIN_CONTRACT_VERSION]
 * is what actually gates plugin compatibility.
 */
interface GameSourceProvider {
    val id: String
    val label: String

    /** Full text search, scoped to [platform] when given (a system id, or null/blank for "any"). */
    suspend fun search(context: Context, query: String, platform: String?): Result<List<AcquireContentResult>>

    /**
     * "Can you supply this game" for one already-known title -- what the
     * Recommendations composition (`GetMoreComposer.composeEmpty`) asks
     * every source for each recommended game's "where to get it" list.
     * [ids] carries whatever cross-source identity droidtop already
     * resolved for this title (a Steam appid, a ScreenScraper id, ...)
     * when the provider can use it; a provider with no use for [ids]
     * ignores them. The default implementation is a plain [search] by
     * title -- correct for a source with no better "exact title" lookup
     * (every plugin today, since the wire contract has only `search`);
     * a store adapter with a real catalog id overrides this with a
     * direct lookup instead of a fuzzy text search.
     */
    suspend fun lookup(
        context: Context,
        title: String,
        platform: String?,
        ids: Map<String, String> = emptyMap(),
    ): Result<List<AcquireContentResult>> = search(context, title, platform)

    /** The labelled choices one result offers before acquiring it. Every provider built so far attaches its choices to the result itself ([AcquireContentResult.options]), so the default just returns that; a provider whose choices need a live round trip (a store's own region/edition picker) overrides this. */
    suspend fun options(context: Context, result: AcquireContentResult): List<AcquireContentOption> = result.options

    /**
     * Starts acquiring [result] with the given [choice] (an index into
     * [options]) into [destination]. Returns null when this provider has
     * no running-job support for this result -- droidtop shows that as
     * "can't start this download" rather than treating it as a crash.
     * [onProgress]/[onComplete] fire on whatever thread the
     * implementation's own callback arrives on, same as
     * [AcquireContentSources.startDownload] today.
     */
    suspend fun acquire(
        context: Context,
        result: AcquireContentResult,
        choice: Int,
        destination: File,
        onProgress: (percent: Int, statusLine: String) -> Unit,
        onComplete: (AcquireDownloadOutcome) -> Unit,
    ): AcquireContentJob?
}

/**
 * The one real [GameSourceProvider] implementation today: a plugin
 * declaring [dev.droidtop.pluginhost.PluginCapability.ACQUIRE_CONTENT],
 * adapted straight onto [AcquireContentSources]'s existing invoke/startJob
 * calls -- no behavior change from before this interface existed, just
 * named as a Sources API implementation so the UI and
 * [PluginSearchAggregator]/`GetMoreComposer` can hold a
 * `List<GameSourceProvider>` without knowing plugins exist.
 *
 * Built-in store adapters (Steam/GOG/Epic/Amazon/itch/DLsite, and later
 * official re-release markets) are the next implementations of this same
 * interface -- not built yet (SPEC 12a "Sources API", tracked as its own
 * follow-up on Droidtop/tracker), since a real store "does this exist,
 * where" lookup needs each store's own search/catalog API or scrape,
 * which is separate infrastructure from this interface itself.
 */
class PluginGameSource(val source: AcquireContentSource.Plugin) : GameSourceProvider {
    override val id get() = source.id
    override val label get() = source.label

    override suspend fun search(context: Context, query: String, platform: String?): Result<List<AcquireContentResult>> =
        AcquireContentSources.search(
            context = context,
            source = source,
            systemId = platform.orEmpty(),
            systemName = platform.orEmpty(),
            query = query,
        )

    override suspend fun acquire(
        context: Context,
        result: AcquireContentResult,
        choice: Int,
        destination: File,
        onProgress: (percent: Int, statusLine: String) -> Unit,
        onComplete: (AcquireDownloadOutcome) -> Unit,
    ): AcquireContentJob? = AcquireContentSources.startDownload(
        context = context,
        source = source,
        systemFolder = destination,
        result = result,
        linkIndex = choice,
        onProgress = onProgress,
        onComplete = onComplete,
    )
}

/** Every installed+approved+enabled source-plugin, wrapped as [GameSourceProvider] -- the whole plugin half of the Sources API's implementation list until a store adapter exists. */
object GameSources {
    fun plugins(context: Context): List<GameSourceProvider> =
        AcquireContentSources.availablePlugins(context).map { PluginGameSource(it) }
}
