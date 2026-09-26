package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.LibraryRescan
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.TextInputItem
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
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
 * [systemScreen] is the one entry point both surfaces use: a
 * [CatalogScreen] listing every available source, rendered by whichever
 * generic catalog renderer that surface already has (:app's Preference
 * surface, :shell-gamepad's `CatalogNavigator`) -- so the on-screen
 * keyboard / controller text entry, the focusable results list, and the
 * progress row all come from that ONE shared renderer, not a second
 * hand-built UI here.
 *
 * **The wire contract for a plugin's `acquire_content` calls** (there was
 * none before this file -- this is the first real caller, both `invoke`
 * and `startJob`):
 * - `invoke(ACQUIRE_CONTENT, {"action": "search", "query", "systemId",
 *   "systemName"})` -> `PluginResult.success(values = {"entries": <a JSON
 *   array string>})`. Each element is a JSON object; droidtop only reads
 *   `title` (or `name`), `platform`, and a size label (`size_str`,
 *   `sizeLabel`, or the first entry of a `links` array's own `size_str`)
 *   -- everything else in the object is opaque and round-tripped back to
 *   the plugin unread (§12a point 5: a plugin's own values are untrusted
 *   and never parsed beyond what droidtop actually needs).
 * - `startJob(ACQUIRE_CONTENT, {"action": "download", "entry": <the exact
 *   JSON object a search result came from>, "destinationPath": <the
 *   system's real, already-resolved folder>, "linkIndex": "0"})`,
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
 * One search result a plugin returned. [raw] is the exact JSON object the
 * plugin sent, round-tripped back unchanged in the download job -- droidtop
 * never re-derives or edits it, only reads the three display fields below.
 */
data class AcquireContentResult(
    val title: String,
    val sizeLabel: String?,
    val platform: String?,
    val raw: String,
)

/** What a finished (or failed) download job reported, translated out of plugin-host's own `PluginResult` so callers never need that type. */
data class AcquireDownloadOutcome(val ok: Boolean, val message: String, val filePath: String?)

/**
 * A live download job: holds the binder connection its progress/
 * completion arrive over. [close] MUST be called once the job is done
 * (completed, cancelled, or the hosting screen leaving) so :pluginhost's
 * connection doesn't linger -- same "short-lived policy, torn down right
 * after" rule the one other real call site (the Settings "Call ... status
 * tile" row) already follows for the plain `invoke()` case.
 */
class AcquireContentJob internal constructor(
    private val policy: PluginCrashPolicy,
    private val pluginId: String,
    val jobId: String,
) {
    fun cancel() = policy.cancelJob(pluginId, jobId)
    fun close() = policy.shutdown()
}

object AcquireContentSources {
    /** Every available source for a "Get games" surface: installed+approved plugins declaring ACQUIRE_CONTENT, plus JSON integrations of the same capability. Plugins first -- a real result list beats a one-way launch. */
    fun available(context: Context): List<AcquireContentSource> =
        PluginStore.runnableFor(context, PluginCapability.ACQUIRE_CONTENT).map { AcquireContentSource.Plugin(it) } +
            IntegrationStore.available(context, IntegrationCapability.ACQUIRE_CONTENT).map { AcquireContentSource.Json(it) }

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
                    listOf(
                        CatalogGroup(
                            id = "acquire_empty",
                            title = null,
                            items = listOf(
                                ActionItem(
                                    id = "acquire_empty_$systemId",
                                    title = "No acquire_content source is installed",
                                    subtitle = "Add a JSON integration (Settings > App integrations) " +
                                        "or install a plugin declaring acquire_content",
                                    run = {},
                                ),
                            ),
                        ),
                    )
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
     * `onStatus` stream) -- the FIRST real UI caller of [startDownload]/
     * `PluginCrashPolicy.startJob` anywhere in droidtop (docs/SPEC.md
     * 12a: "not yet exercised on the rig... there is no equivalent
     * generic trigger for acquire_content yet").
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
                                    run = { ctx, onStatus -> runDownloadAndAwait(ctx, source, systemFolder, result, onStatus) },
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
     * every `invoke()` call already has (§12a).
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

    private fun parseResults(entriesJson: String): List<AcquireContentResult> {
        val array = runCatching { JSONArray(entriesJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.optString("title").ifBlank { obj.optString("name") }
                if (title.isBlank()) continue
                val platform = obj.optString("platform").takeIf { it.isNotBlank() }
                val sizeLabel = obj.optString("size_str").takeIf { it.isNotBlank() }
                    ?: obj.optString("sizeLabel").takeIf { it.isNotBlank() }
                    ?: obj.optJSONArray("links")?.optJSONObject(0)?.optString("size_str")?.takeIf { it.isNotBlank() }
                add(AcquireContentResult(title = title, sizeLabel = sizeLabel, platform = platform, raw = obj.toString()))
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
        val policy = PluginCrashPolicy(
            context.applicationContext,
            onJobProgress = { _, _, percent, statusLine -> onProgress(percent, statusLine) },
            onJobComplete = { _, _, jobResult ->
                onComplete(
                    AcquireDownloadOutcome(
                        ok = jobResult.ok,
                        message = if (jobResult.ok) "Downloaded ${result.title}" else (jobResult.error ?: "Download failed"),
                        filePath = jobResult.values["filePath"],
                    ),
                )
            },
        )
        val jobId = policy.startJob(
            source.record,
            PluginCapability.ACQUIRE_CONTENT,
            mapOf(
                "action" to "download",
                "entry" to result.raw,
                "destinationPath" to systemFolder.absolutePath,
                "linkIndex" to linkIndex.toString(),
            ),
        )
        if (jobId == null) {
            policy.shutdown()
            return null
        }
        return AcquireContentJob(policy, source.record.manifest.id, jobId)
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
    private suspend fun runDownloadAndAwait(
        context: Context,
        source: AcquireContentSource.Plugin,
        systemFolder: File,
        result: AcquireContentResult,
        onStatus: (String) -> Unit,
    ): String {
        val done = CompletableDeferred<Boolean>()
        var finalMessage = ""
        val job = startDownload(
            context = context,
            source = source,
            systemFolder = systemFolder,
            result = result,
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
