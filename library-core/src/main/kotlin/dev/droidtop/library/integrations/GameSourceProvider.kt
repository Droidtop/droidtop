package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.CatalogScreen
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
 * **Timeout.** A plugin's [search]/[lookup] has its own budget
 * ([PluginGameSource.SEARCH_TIMEOUT_MS], 10 s, never a crash);
 * [PluginSearchAggregator.PER_SOURCE_TIMEOUT_MS] (11 s) is the caller-side
 * backstop for a source that never returns at all. A store adapter must declare and honor its own bound the same way
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
 * (`handle` calls on the `library.sources` extension point: `search`,
 * `featured`, `detail` and the `acquire` job, docs/plugin-api.md 1.8) into
 * this shape. Adding a method
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

    /**
     * What the source features now, newest or most popular first, for Get new games' page (docs/plugin-api.md 1.8
     * "featured", Droidtop/tracker#612): the same results [search] gives, scoped to [platform] when given. A source
     * with no such list has nothing, which is not a failure.
     */
    suspend fun featured(context: Context, platform: String?, limit: Int): Result<List<AcquireContentResult>> = Result.success(emptyList())

    /**
     * Gets the source ready to answer, before it is asked (Droidtop/tracker#848): a plugin source is started in its
     * process, held by [SourceWarmth], so a cold start is not spent out of [search]'s own budget. Null when it is
     * ready, else why not. A source that needs nothing started has nothing to do.
     */
    suspend fun warm(context: Context): String? = null

    fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: File?): CatalogScreen

    fun settingsScreen(): CatalogScreen? = null

    /** The source's own search options (docs/plugin-api.md 1.10, `search.filter`); null when it gives none. Reads files. */
    fun filterScreen(context: Context): CatalogScreen? = null

    /** True when [result] can be fetched straight from its row, without its detail page (a result's `get`). */
    fun canGet(result: AcquireContentResult): Boolean = false

    /**
     * Fetches [result] from its row into [destination] (docs/plugin-api.md "Fetchers answer in the one search",
     * Droidtop/tracker#218): the same `acquire` job the detail page starts, with the values the result gave. The
     * outcome's message is the sentence to show. Only called when [canGet].
     */
    suspend fun get(
        context: Context,
        result: AcquireContentResult,
        systemId: String?,
        systemName: String?,
        destination: File,
        onStatus: (String) -> Unit,
    ): AcquireDownloadOutcome = AcquireDownloadOutcome(false, "$label can't fetch this from search", null)
}

/**
 * The one real [GameSourceProvider] implementation today: a plugin that
 * provides `library.sources` (listed by [AcquireContentSources]), adapted
 * onto that point's `search`/`featured`/`detail`/`acquire` ops through
 * [PluginViews] -- named as a Sources API implementation so the UI and
 * [PluginSearchAggregator]/`GetMoreComposer` can hold a
 * `List<GameSourceProvider>` without knowing plugins exist.
 *
 * SPEC 12a "Sources API" has the built-in stores implement this interface
 * too; none does yet, so a store's catalog is not part of this search.
 */
class PluginGameSource(val source: AcquireContentSource.Plugin) : GameSourceProvider {
    override val id get() = source.id
    override val label get() = source.label

    override suspend fun search(context: Context, query: String, platform: String?): Result<List<AcquireContentResult>> =
        searchWithValues(context, query, platform, emptyMap(), null, platform.orEmpty(), limit = UnifiedSearch.MAX_ROWS_PER_SOURCE)

    override suspend fun warm(context: Context): String? = SourceWarmth.warm(context, source.record)

    /**
     * [limit] is how many results the caller will show: the one search shows ten per source, so it asks for ten
     * (`args.limit`, docs/plugin-api.md 1.8) and a source that honours it sends a page, not its whole answer. A
     * source that ignores it is cut to [limit] here.
     */
    internal suspend fun searchWithValues(context: Context, query: String, platform: String?, values: Map<String, String>, destination: File?, systemName: String, limit: Int? = null): Result<List<AcquireContentResult>> {
        val systemId = platform.orEmpty()
        val hostContext = org.json.JSONObject().put("system", org.json.JSONObject().put("id", systemId).put("name", systemName))
        destination?.let { hostContext.put("destination", it.absolutePath) }
        val args = org.json.JSONObject().put("query", query).put("values", org.json.JSONObject(values as Map<*, *>)).put("context", hostContext)
        limit?.let { args.put("limit", it) }
        // What the person set on the source's own options travels with every search, kept per source (search.filter).
        if (PluginFacets.declares(source.record, PluginFacets.SEARCH_FILTER)) {
            SourceFilters.read(context, source.record.manifest.id).takeIf { it.length() > 0 }?.let { args.put("filters", it) }
        }
        // A slow site is slow, not broken: a search that misses its budget is "didn't answer in time", not a crash.
        val reply = PluginViews.call(context, source.record, "library.sources", "search", args, timeoutMs = SEARCH_TIMEOUT_MS)
        if (!reply.ok) return Result.failure(SourceReplyFailure(reply.code ?: dev.droidtop.pluginhost.PluginErrorCode.FAILED, reply.message, reply.detail))
        return Result.success(dev.droidtop.pluginhost.SourceResultProtocol.results(reply.data).let { rows -> if (limit != null) rows.take(limit) else rows }.map(::resultOf))
    }

    /**
     * `featured {platform, limit, context}` (docs/plugin-api.md 1.8, Droidtop/tracker#612): optional, so a source that
     * answers UNSUPPORTED features nothing. Any other failure is the
     * source's to show, as a search's is. The same budget as a search.
     */
    override suspend fun featured(context: Context, platform: String?, limit: Int): Result<List<AcquireContentResult>> {
        val hostContext = org.json.JSONObject().put("system", org.json.JSONObject().put("id", platform.orEmpty()))
        val args = org.json.JSONObject().put("limit", limit).put("context", hostContext)
        platform?.takeIf { it.isNotBlank() }?.let { args.put("platform", it) }
        val reply = PluginViews.call(context, source.record, "library.sources", "featured", args, timeoutMs = SEARCH_TIMEOUT_MS)
        if (!reply.ok) {
            if (reply.code == dev.droidtop.pluginhost.PluginErrorCode.UNSUPPORTED) return Result.success(emptyList())
            return Result.failure(SourceReplyFailure(reply.code ?: dev.droidtop.pluginhost.PluginErrorCode.FAILED, reply.message, reply.detail))
        }
        return Result.success(dev.droidtop.pluginhost.SourceResultProtocol.results(reply.data).take(limit).map(::resultOf))
    }

    private fun resultOf(row: dev.droidtop.pluginhost.SourceResultRow) = AcquireContentResult(
        row.id, row.title, row.subtitle, row.sizeBytes?.let(SourceSize::label), row.platform,
        dev.droidtop.pluginhost.SourceResultProtocol.boxArt(row.facets), emptyList(), row.refJson,
        columns = listOfNotNull(row.version) + row.columns, badges = row.badges,
        version = row.version, sizeBytes = row.sizeBytes, getValuesJson = row.get?.valuesJson, getTitle = row.get?.title,
        facets = row.facets,
    )

    override fun canGet(result: AcquireContentResult): Boolean = result.getValuesJson != null

    override suspend fun get(
        context: Context,
        result: AcquireContentResult,
        systemId: String?,
        systemName: String?,
        destination: File,
        onStatus: (String) -> Unit,
    ): AcquireDownloadOutcome {
        if (!canGet(result)) return super.get(context, result, systemId, systemName, destination, onStatus)
        if (!destination.isDirectory) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { destination.mkdirs() }
        val given = org.json.JSONObject(result.getValuesJson ?: "{}")
        val values = given.keys().asSequence().associateWith { given.optString(it) }
        val hostContext = org.json.JSONObject()
            .put("system", org.json.JSONObject().put("id", systemId.orEmpty()).put("name", systemName.orEmpty()))
            .put("destination", destination.absolutePath)
        val args = dev.droidtop.pluginhost.PluginViewCall.args(org.json.JSONObject().put("ref", org.json.JSONObject(result.raw)), values, hostContext)
        val title = result.getTitle ?: "Getting ${result.title}"
        val done = PluginViews.acquire(context, source.record, title, hostContext, args, onStatus)
        val message = if (done.ok) done.values["summary"] ?: done.values["message"] ?: "Got ${result.title}" else "$label: ${dev.droidtop.pluginhost.SourceFailures.plain(done.error)}"
        return AcquireDownloadOutcome(done.ok, message, done.values["filePath"])
    }

    companion object {
        /** A source's `search` budget (docs/plugin-api.md "Fetchers answer in the one search", §8): missed, it is not a crash. */
        const val SEARCH_TIMEOUT_MS = 10_000L
    }

    override fun detailScreen(result: AcquireContentResult, systemId: String?, systemName: String?, destination: File?): CatalogScreen {
        if (destination == null) return SourceScreens.noDestination(result, systemName)
        val ref = org.json.JSONObject(result.raw)
        val context = org.json.JSONObject().put("system", org.json.JSONObject().put("id", systemId.orEmpty()).put("name", systemName.orEmpty())).put("destination", destination.absolutePath)
        val fallback = {
            dev.droidtop.pluginhost.PluginView(null, null, listOf(dev.droidtop.pluginhost.ViewSection("main", null, listOf(
                dev.droidtop.pluginhost.ViewNode.Info("title", result.title, null, null),
                dev.droidtop.pluginhost.ViewNode.Button("download", "Download", null, null, null,
                    dev.droidtop.pluginhost.ViewAction(dev.droidtop.pluginhost.ViewAction.Kind.JOB, "acquire", org.json.JSONObject().put("ref", ref).toString())),
            ))))
        }
        return PluginViews.screen(source.record, "library.sources", "detail", "source_detail_${source.record.manifest.id}_${result.id}", result.title,
            org.json.JSONObject().put("ref", ref), context, fallback,
            // A page that failed always has a way on: the plugin's own page, where it is signed in, allowed or fixed.
            extraWhenFailed = {
                listOf(AcquireContentSources.pluginPageRow("source_detail_page_${source.record.manifest.id}", "Open $label's page", source.record.manifest.id))
            },
            // A reply of the item's bundles is its item page, drawn as droidtop draws a record (Droidtop/tracker#737).
            itemPage = { ctx, data, messages -> SourceItemPage.read(ctx, source.record, result.id, ref, context, data, messages) },
        )
    }

    override fun settingsScreen(): CatalogScreen? =
        if (PluginSettingsRows.has(source.record)) PluginSettingsRows.screenFor(source.record) else null

    override fun filterScreen(context: Context): CatalogScreen? =
        if (PluginFacets.shows(context, source.record, PluginFacets.SEARCH_FILTER)) PluginFacets.filterScreen(source.record) else null

}

/** Every installed+approved+enabled source-plugin, wrapped as [GameSourceProvider] -- the whole plugin half of the Sources API's implementation list until a store adapter exists. */
object GameSources {
    /** [systemId] scopes the list to the sources that serve it ("pc": the PC library); null asks them all. */
    fun plugins(context: Context, systemId: String? = null): List<GameSourceProvider> =
        AcquireContentSources.availablePlugins(context, systemId).map { PluginGameSource(it) }
}
