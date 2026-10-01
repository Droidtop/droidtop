package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.LibraryRescan
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginTrustState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Unifies droidtop's two `acquire_content` mechanisms into the ONE "Get
 * games" surface a system's own settings screen (:app) and Gaming's
 * gamelist options menu (:shell-gamepad) both offer -- per "one mechanism
 * per job", neither should hand-roll its own merge of [IntegrationStore]
 * and [PluginStore], or its own copy of the search/download screen:
 *
 * - a JSON [Integration] (docs/SPEC.md §12): one-way, fire-and-forget,
 *   launches another app with extras and never hears back;
 * - a real plugin declaring [PluginCapability.ACQUIRE_CONTENT] (§12a):
 *   `invoke(action=search)` returns real results droidtop renders itself,
 *   `startJob(action=download)` is a real job with progress and a real
 *   completion signal droidtop rescans on.
 *
 * [systemScreen] is the per-system "Get games" entry point both :app and
 * :shell-gamepad's gamelist options menu still offer. As of the "Search
 * fan-out" change (SPEC 12a), [search] is also the backing call for the
 * generic mechanism in `dev.droidtop.library.integrations.PluginSearchAggregator`,
 * which every existing game-search surface (the shared LibraryQuery
 * search, the launcher drawer/QSB search) asks in parallel and folds into
 * a "Get more" group -- so a plugin never needs a second, dedicated
 * screen to be found; droidtop's own search already asks it.
 *
 * **The wire contract for a plugin's `acquire_content` calls**:
 * - `invoke(ACQUIRE_CONTENT, {"action": "search", "query", "systemId",
 *   "systemName"})` -> `PluginResult.success(values = {"entries": <a JSON
 *   array string>})`. Each element is a JSON object; droidtop reads a
 *   generic shape -- `id` (or `slug`/`rom_id`, falling back to a hash of
 *   the object when neither is present), `title` (or `name`), `platform`,
 *   a size label (`size_str`, `sizeLabel`, or the first `links` entry's
 *   own `size_str`), an optional `boxart`/`boxart_url`/`artUrl`, an
 *   optional `subtitle` (falling back to the comma-joined `regions`, if
 *   any), and an `options` list built from a `links` array (each link's
 *   `name`/`host`/`size_str` joined as the option's label, its position
 *   as the index `startJob`'s `linkIndex` expects) -- everything else in
 *   the object is opaque and round-tripped back to the plugin unread
 *   (§12a point 5: a plugin's own values are untrusted and never parsed
 *   beyond what droidtop actually needs).
 * - `startJob(ACQUIRE_CONTENT, {"action": "download", "entry": <the exact
 *   JSON object a search result came from>, "destinationPath": <the
 *   system's real, already-resolved folder>, "linkIndex": <the chosen
 *   option's index, "0" when a result has no options>})`,
 *   progress/completion over the normal `PluginJobProgress` callback.
 */
sealed interface AcquireContentSource {
    val id: String
    val label: String
    val description: String?

    data class Json(val integration: Integration) : AcquireContentSource {
        override val id get() = "json_${integration.id}"
        override val label get() = integration.label
        override val description get() = integration.description
    }

    data class Plugin(val record: PluginRecord) : AcquireContentSource {
        override val id get() = "plugin_${record.manifest.id}"
        override val label get() = record.manifest.label
        override val description get() = record.manifest.description
    }
}

/**
 * One labelled choice a result carries -- a mirror, a region, a format --
 * shown in the options sheet the search surfaces (LibraryQueryUi's "Get
 * more" group, docs/SPEC.md 12a "Search fan-out") open on selection.
 * [index] round-trips back as `linkIndex` in the download job, the same
 * field [searchScreen] already defaults to 0.
 */
data class AcquireContentOption(val label: String, val index: Int)

/**
 * One search result a plugin returned. [raw] is the exact JSON object the
 * plugin sent, round-tripped back unchanged in the download job -- droidtop
 * never re-derives or edits it beyond the generic display shape SPEC 12a
 * settled on: [id], [title], [subtitle], [platform], an optional [artUrl],
 * and an [options] list of labelled choices (source/mirror/region/format).
 * [sizeLabel] is kept for the older per-system "Get games" screen this
 * file already renders.
 */
data class AcquireContentResult(
    val id: String,
    val title: String,
    val subtitle: String?,
    val sizeLabel: String?,
    val platform: String?,
    val artUrl: String?,
    val options: List<AcquireContentOption>,
    val raw: String,
)

/** What a finished (or failed) download job reported, translated out of plugin-host's own `PluginResult` so callers never need that type. */
data class AcquireDownloadOutcome(val ok: Boolean, val message: String, val filePath: String?)

/**
 * A live download job -- now a thin handle onto [PluginJobsCenter]'s own
 * tracking ([trackingId]) rather than a private binder connection this
 * class owned itself (docs/SPEC.md 12a "Jobs": "reused by acquire_content
 * downloads so there's one mechanism"). [close] is a no-op kept only so
 * existing call sites ([runDownloadAndAwait]) don't need to change shape
 * -- [PluginJobsCenter] owns tearing its own policy down on completion.
 */
class AcquireContentJob internal constructor(
    val trackingId: String,
) {
    fun cancel() = PluginJobsCenter.cancel(trackingId)
    fun close() {}
}

/** An installed plugin that offers downloads but cannot answer right now, and why, in words a person reads. */
data class UnavailableSource(val label: String, val reason: String)

object AcquireContentSources {
    /** The registered settings screen id of Accounts and sources > Plugins: where "no source" and "needs approval" lead. */
    const val PLUGINS_SCREEN_ID = "plugins"

    /** The registered settings screen id of Accounts and sources > App integrations. */
    const val INTEGRATIONS_SCREEN_ID = "integrations"

    /** Installed plugins that declare ACQUIRE_CONTENT but are not runnable (waiting for approval, denied, turned off, disabled after a failure). Reads the plugin store: off the main thread. */
    fun unavailablePlugins(context: Context): List<UnavailableSource> =
        PluginStore.installed(context)
            .filter { PluginCapability.ACQUIRE_CONTENT in it.manifest.capabilities && !it.runnable() }
            .map { record ->
                UnavailableSource(
                    record.manifest.label,
                    when {
                        record.trust == PluginTrustState.PENDING -> "is waiting for your approval"
                        record.trust == PluginTrustState.DENIED -> "was denied"
                        record.disabledReason != null -> "is disabled: ${record.disabledReason}"
                        else -> "is turned off"
                    },
                )
            }

    /** Every available source for a "Get games" surface: installed+approved plugins declaring ACQUIRE_CONTENT, plus JSON integrations of the same capability. Plugins first -- a real result list beats a one-way launch. */
    fun available(context: Context): List<AcquireContentSource> =
        PluginStore.runnableFor(context, PluginCapability.ACQUIRE_CONTENT).map { AcquireContentSource.Plugin(it) } +
            IntegrationStore.available(context, IntegrationCapability.ACQUIRE_CONTENT).map { AcquireContentSource.Json(it) }

    /** Just the plugin sources -- what [PluginSearchAggregator] fans a query out to; JSON integrations stay one-way/fire-and-forget and have no results to merge. */
    fun availablePlugins(context: Context): List<AcquireContentSource.Plugin> =
        PluginStore.runnableFor(context, PluginCapability.ACQUIRE_CONTENT).map { AcquireContentSource.Plugin(it) }

    /**
     * The one "Get games" screen for one system -- lists every source
     * [available], a JSON one as its existing text-field-or-button shape,
     * a plugin one as a [NestedScreenItem] opening [searchScreen]. Both
     * :app's per-system settings screen and :shell-gamepad's gamelist
     * options menu ("Get games") push exactly this screen, through their
     * own renderer (`CatalogNavigator`/the Preference surface).
     */
    fun systemScreen(systemId: String, systemName: String, systemFolder: File): CatalogScreen = CatalogScreen(
        id = "acquire_system_$systemId",
        title = "Get games",
        subtitle = "for $systemName",
        groups = { context ->
            withContext(Dispatchers.IO) {
                val sources = available(context)
                if (sources.isEmpty()) {
                    listOf(noSourceGroup(context, systemId))
                } else {
                    listOf(
                        CatalogGroup(
                            id = "acquire_sources",
                            title = null,
                            items = sources.map { source ->
                                when (source) {
                                    is AcquireContentSource.Plugin -> NestedScreenItem(
                                        id = "acquire_src_${source.id}",
                                        title = source.label,
                                        subtitle = source.description ?: "Search ${source.label} for $systemName",
                                        inline = searchScreen(source, systemId, systemName, systemFolder),
                                    )
                                    is AcquireContentSource.Json -> jsonSourceItem(source, systemId, systemName, systemFolder)
                                }
                            },
                        ),
                    )
                }
            }
        },
    )

    /**
     * What a "Get games" screen says when no source can answer: in plain words whether nothing is
     * installed or something is installed but waiting (approval, turned off), and a way to Plugins and
     * App integrations from right there. Reads the plugin store: call off the main thread.
     */
    private fun noSourceGroup(context: Context, key: String): CatalogGroup {
        val waiting = unavailablePlugins(context)
        return CatalogGroup(
            id = "acquire_empty",
            title = null,
            items = listOf(
                ActionItem(
                    id = "acquire_empty_$key",
                    title = if (waiting.isEmpty()) "No download source is installed" else "No download source is ready",
                    subtitle = if (waiting.isEmpty()) {
                        "A download source is a plugin or an app integration that finds games for you."
                    } else {
                        waiting.joinToString("\n") { "${it.label} ${it.reason}" }
                    },
                    run = {},
                ),
                NestedScreenItem(
                    id = "acquire_empty_plugins_$key",
                    title = "Plugins",
                    subtitle = "Install a plugin that offers downloads, or approve one you already added",
                    registryId = PLUGINS_SCREEN_ID,
                ),
                NestedScreenItem(
                    id = "acquire_empty_integrations_$key",
                    title = "App integrations",
                    subtitle = "Hook another installed app in as a download source",
                    registryId = INTEGRATIONS_SCREEN_ID,
                ),
            ),
        )
    }

    /**
     * "Get games" from a list that is not one console system (All games, the PC list): asks which
     * system to download for, then opens that system's own [systemScreen]. With no source it says so
     * and leads to Plugins instead of an empty list of systems.
     */
    fun chooseSystemScreen(): CatalogScreen = CatalogScreen(
        id = "acquire_choose_system",
        title = "Get games",
        subtitle = "Pick the system to download for",
        groups = { context ->
            withContext(Dispatchers.IO) {
                if (available(context).isEmpty()) {
                    listOf(noSourceGroup(context, "all"))
                } else {
                    val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
                    val targets = SystemFolders.all(context, systemsById)
                        .distinctBy { (_, system) -> system.id }
                        .sortedBy { (_, system) -> system.displayName.lowercase() }
                    listOf(
                        CatalogGroup(
                            id = "acquire_choose_systems",
                            title = null,
                            items = if (targets.isEmpty()) {
                                listOf(
                                    ActionItem(
                                        id = "acquire_choose_none",
                                        title = "No system folders yet",
                                        subtitle = "Downloads go into a system's own folder. Add a games folder under Settings > Game folders first.",
                                        run = {},
                                    ),
                                )
                            } else {
                                targets.map { (folder, system) ->
                                    NestedScreenItem(
                                        id = "acquire_pick_${system.id}",
                                        title = system.displayName,
                                        inline = systemScreen(system.id, system.displayName, folder),
                                    )
                                }
                            },
                        ),
                    )
                }
            }
        },
    )

    private fun jsonSourceItem(
        source: AcquireContentSource.Json,
        systemId: String,
        systemName: String,
        systemFolder: File,
    ): dev.droidtop.library.settings.CatalogItem {
        val integration = source.integration
        // A template that uses {query} needs a search string before it
        // can run: it is offered as a text field, and committing the
        // text runs it -- unchanged from §12's original shape.
        if (IntegrationPlaceholders.QUERY in IntegrationPlaceholders.usedIn(integration.argumentsTemplate)) {
            return TextInputItem(
                id = "acquire_json_${integration.id}_$systemId",
                title = integration.label,
                subtitle = integration.description
                    ?: "Type what to search for; ${integration.packageName} opens with it for $systemName",
                value = "",
                onChange = { ctx, query ->
                    if (query.isNotBlank()) {
                        runJson(context = ctx, source = source, systemId = systemId, systemName = systemName, systemFolder = systemFolder, query = query.trim())
                    }
                },
            )
        }
        return ActionItem(
            id = "acquire_json_${integration.id}_$systemId",
            title = integration.label,
            subtitle = integration.description ?: "Opens ${integration.packageName} for $systemName",
            run = { ctx -> runJson(context = ctx, source = source, systemId = systemId, systemName = systemName, systemFolder = systemFolder) },
        )
    }

    /** Runs the JSON integration's own mechanism, unchanged -- see [IntegrationStore.run]. */
    fun runJson(
        context: Context,
        source: AcquireContentSource.Json,
        systemId: String,
        systemName: String,
        systemFolder: File,
        query: String? = null,
    ) {
        IntegrationStore.run(
            context = context,
            integration = source.integration,
            systemId = systemId,
            systemName = systemName,
            systemFolder = systemFolder,
            query = query,
        )
    }

    /**
     * A plugin's own "Get games" screen: a live query field and its
     * results, each a real [AsyncActionItem] that starts the real
     * download job and shows its progress inline (the async item's own
     * `onStatus` stream). Kept alongside the generic search fan-out
     * (SPEC 12a "Search fan-out") for a person who wants to search ONE
     * named source directly rather than through the shared game search.
     *
     * `currentQuery`/`lastResults`/`lastError` are plain closure state,
     * not persisted -- this screen is rebuilt fresh from [available] each
     * time its parent screen is (re)entered, same lifetime as every other
     * per-visit catalog state.
     */
    fun searchScreen(
        source: AcquireContentSource.Plugin,
        systemId: String,
        systemName: String,
        systemFolder: File,
    ): CatalogScreen {
        var currentQuery = ""
        var lastResults: List<AcquireContentResult> = emptyList()
        var lastError: String? = null
        return CatalogScreen(
            id = "acquire_search_${source.id}_$systemId",
            title = source.label,
            subtitle = "Search ${source.label} for $systemName; picking a result downloads it straight into this system's folder",
            groups = { context ->
                listOf(
                    CatalogGroup(
                        id = "acquire_query",
                        title = null,
                        items = listOf(
                            TextInputItem(
                                id = "acquire_query_${source.id}",
                                title = "Search",
                                subtitle = "Type what to search for and commit to run it",
                                value = currentQuery,
                                onChange = { ctx, query ->
                                    currentQuery = query
                                    lastError = null
                                    if (query.isBlank()) {
                                        lastResults = emptyList()
                                    } else {
                                        search(ctx, source, systemId, systemName, query.trim())
                                            .onSuccess { lastResults = it }
                                            .onFailure {
                                                lastResults = emptyList()
                                                lastError = it.message ?: "Search failed"
                                            }
                                    }
                                },
                            ),
                        ),
                    ),
                    CatalogGroup(
                        id = "acquire_results",
                        title = when {
                            lastError != null -> "Search failed"
                            currentQuery.isBlank() -> null
                            else -> "Results (${lastResults.size})"
                        },
                        items = when {
                            lastError != null -> listOf(
                                ActionItem(id = "acquire_error_${source.id}", title = lastError ?: "Search failed", run = {}),
                            )
                            currentQuery.isNotBlank() && lastResults.isEmpty() -> listOf(
                                ActionItem(id = "acquire_no_results_${source.id}", title = "No results", run = {}),
                            )
                            else -> lastResults.map { result ->
                                AsyncActionItem(
                                    id = "acquire_result_${source.id}_${result.raw.hashCode()}",
                                    title = result.title,
                                    subtitle = listOfNotNull(result.platform, result.sizeLabel).joinToString(" · ").ifBlank { null },
                                    run = { ctx, onStatus -> runDownloadAndAwait(ctx, source, systemFolder, result, onStatus, linkIndex = 0) },
                                )
                            }
                        },
                    ),
                )
            },
        )
    }

    /**
     * Searches a plugin source. Bounded by `PluginCrashPolicy.invoke`'s
     * own watchdog (`PluginRunner.CALL_TIMEOUT_MS`, 15s) -- fine for a
     * local index, tight for a live network search, the same tradeoff
     * every `invoke()` call already has (§12a). [systemId]/[systemName]
     * are blank when a search is not scoped to one system (the shared
     * game search calling this through [PluginSearchAggregator] outside
     * a per-system list) -- a plugin sees an empty string, not a made-up
     * system.
     */
    suspend fun search(
        context: Context,
        source: AcquireContentSource.Plugin,
        systemId: String,
        systemName: String,
        query: String,
    ): Result<List<AcquireContentResult>> {
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val result = policy.invoke(
                source.record,
                PluginCapability.ACQUIRE_CONTENT,
                mapOf("action" to "search", "query" to query, "systemId" to systemId, "systemName" to systemName),
            )
            if (!result.ok) return Result.failure(RuntimeException(result.error ?: "search failed"))
            val entriesJson = result.values["entries"] ?: return Result.success(emptyList())
            Result.success(parseResults(entriesJson))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            policy.shutdown()
        }
    }

    /** Pure so it is unit-tested without a plugin connection -- see AcquireContentSourcesParseTest. */
    internal fun parseResults(entriesJson: String): List<AcquireContentResult> {
        val array = runCatching { JSONArray(entriesJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.optString("title").ifBlank { obj.optString("name") }
                if (title.isBlank()) continue
                val id = obj.optString("id").ifBlank { obj.optString("slug") }
                    .ifBlank { obj.optString("rom_id") }
                    .ifBlank { obj.toString().hashCode().toString() }
                val platform = obj.optString("platform").takeIf { it.isNotBlank() }
                val sizeLabel = obj.optString("size_str").takeIf { it.isNotBlank() }
                    ?: obj.optString("sizeLabel").takeIf { it.isNotBlank() }
                    ?: obj.optJSONArray("links")?.optJSONObject(0)?.optString("size_str")?.takeIf { it.isNotBlank() }
                val artUrl = obj.optString("artUrl").takeIf { it.isNotBlank() }
                    ?: obj.optString("boxart").takeIf { it.isNotBlank() }
                    ?: obj.optString("boxart_url").takeIf { it.isNotBlank() }
                val regions = obj.optJSONArray("regions")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
                val subtitle = obj.optString("subtitle").takeIf { it.isNotBlank() }
                    ?: regions.takeIf { it.isNotEmpty() }?.joinToString(", ")
                val options = obj.optJSONArray("links")?.let { links ->
                    buildList {
                        for (li in 0 until links.length()) {
                            val link = links.optJSONObject(li) ?: continue
                            val label = listOfNotNull(
                                link.optString("name").takeIf { it.isNotBlank() },
                                link.optString("host").takeIf { it.isNotBlank() },
                                link.optString("size_str").takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { "Option ${li + 1}" }
                            add(AcquireContentOption(label = label, index = li))
                        }
                    }
                }.orEmpty()
                add(
                    AcquireContentResult(
                        id = id,
                        title = title,
                        subtitle = subtitle,
                        sizeLabel = sizeLabel,
                        platform = platform,
                        artUrl = artUrl,
                        options = options,
                        raw = obj.toString(),
                    ),
                )
            }
        }
    }

    /**
     * Starts the real download job (§12a's `PluginJob` shape) into
     * [systemFolder] -- the exact folder droidtop already scans for this
     * system. [onProgress]/[onComplete] fire on whatever thread the
     * binder callback arrives on. Returns null when the plugin refused
     * the job outright (not loaded, or no `startJob` override). The
     * returned [AcquireContentJob] must be [AcquireContentJob.close]d
     * once the caller is done with it.
     */
    suspend fun startDownload(
        context: Context,
        source: AcquireContentSource.Plugin,
        systemFolder: File,
        result: AcquireContentResult,
        linkIndex: Int = 0,
        onProgress: (percent: Int, statusLine: String) -> Unit,
        onComplete: (AcquireDownloadOutcome) -> Unit,
    ): AcquireContentJob? {
        val trackingId = PluginJobsCenter.start(
            context = context,
            record = source.record,
            capability = PluginCapability.ACQUIRE_CONTENT,
            args = mapOf(
                "action" to "download",
                "entry" to result.raw,
                "destinationPath" to systemFolder.absolutePath,
                "linkIndex" to linkIndex.toString(),
            ),
            title = "Downloading ${result.title}",
            onProgress = onProgress,
            onComplete = { jobResult ->
                onComplete(
                    AcquireDownloadOutcome(
                        ok = jobResult.ok,
                        message = if (jobResult.ok) "Downloaded ${result.title}" else (jobResult.error ?: "Download failed"),
                        filePath = jobResult.values["filePath"],
                    ),
                )
            },
        ) ?: return null
        return AcquireContentJob(trackingId)
    }

    /**
     * Bridges [startDownload]'s async job callback (progress/completion
     * arrive later, over the binder) into [AsyncActionItem.run]'s
     * synchronous suspend shape (report through `onStatus`, return the
     * final sentence) with a [CompletableDeferred] -- the same "wrap a
     * callback API as a suspend function" pattern this module otherwise
     * has no need for. Rescans the library on a successful download, via
     * [LibraryRescan] (the one shared "rescan and report a sentence"
     * mechanism every other screen that offers a rescan already uses), so
     * the new file shows up without a separate manual step.
     */
    internal suspend fun runDownloadAndAwait(
        context: Context,
        source: AcquireContentSource.Plugin,
        systemFolder: File,
        result: AcquireContentResult,
        onStatus: (String) -> Unit,
        linkIndex: Int,
    ): String {
        val done = CompletableDeferred<Boolean>()
        var finalMessage = ""
        val job = startDownload(
            context = context,
            source = source,
            systemFolder = systemFolder,
            result = result,
            linkIndex = linkIndex,
            onProgress = { percent, statusLine ->
                onStatus(if (percent >= 0) "$statusLine ($percent%)" else statusLine)
            },
            onComplete = { outcome ->
                if (!done.isCompleted) {
                    finalMessage = outcome.message + (outcome.filePath?.let { " -> $it" } ?: "")
                    done.complete(outcome.ok)
                }
            },
        )
        if (job == null) return "Couldn't start the download: ${source.label} has no running job support"
        return try {
            val ok = done.await()
            if (ok) LibraryRescan.run(context) {}
            finalMessage
        } finally {
            job.close()
        }
    }
}
