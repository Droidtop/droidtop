package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.canResolveFromFolder
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticeItem
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginTrustState
import dev.droidtop.pluginhost.SourceSystems
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
 * There is no Get games screen (docs/SPEC.md 13, "There is no Get games sheet"). [search] is the contract 1
 * backing call for the search in `dev.droidtop.library.integrations.UnifiedSearch`, which asks
 * every source in parallel and ranks the answers in the one result list
 * with the device's own rows -- so a plugin never needs a second, dedicated
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

    /** Whether this source is listed under [systemId] and asked by a search scoped to it (a blank id: every source). */
    fun serves(systemId: String?): Boolean = true

    data class Plugin(val record: PluginRecord) : AcquireContentSource {
        override val id get() = "plugin_${record.manifest.id}"
        override val label get() = record.manifest.label
        override val description get() = record.manifest.description

        /** The systems the plugin's `library.sources` entries declare (docs/plugin-api.md 1.6); a plugin that declares none serves every system, as every source did before. */
        val systems: SourceSystems get() = SourceSystems.of(record.manifest)

        override fun serves(systemId: String?) = systems.serves(systemId)
    }
}

/** Where an installed source plugin stands, for the Game sources list and the "not ready" notes. */
enum class SourceState { READY, WAITING_FOR_APPROVAL, DENIED, DISABLED, TURNED_OFF }

/** One installed source plugin as the Game sources place lists it (Droidtop/tracker#540): ready or not, and why. */
data class SourceListing(val record: PluginRecord, val state: SourceState) {
    val label: String get() = record.manifest.label
    val pluginId: String get() = record.manifest.id

    /** The row's value: a person's word for the state. */
    val stateLabel: String
        get() = when (state) {
            SourceState.READY -> "Ready"
            SourceState.WAITING_FOR_APPROVAL -> "Waiting for your approval"
            SourceState.DENIED -> "Denied"
            SourceState.DISABLED -> "Disabled"
            SourceState.TURNED_OFF -> "Turned off"
        }
}

/**
 * One labelled choice a result carries -- a mirror, a region, a format --
 * shown in the options sheet the search surfaces (LibraryQueryUi's "Get
 * more" group, docs/SPEC.md 12a "Search fan-out") open on selection.
 * [index] round-trips back as `linkIndex` in the download job, the same
 * field [SourceScreens.searchScreen] now opens result details.
 */
data class AcquireContentOption(val label: String, val index: Int)

/**
 * One search result a plugin returned. [raw] is the exact JSON object the
 * plugin sent, round-tripped back unchanged in the download job -- droidtop
 * never re-derives or edits it beyond the generic display shape SPEC 12a
 * settled on: [id], [title], [subtitle], [platform], an optional [artUrl],
 * and an [options] list of labelled choices (source/mirror/region/format).
 * [sizeLabel] is the size as a row shows it. [version], [sizeBytes] and [getValuesJson] are a
 * contract 2 result's for the one search (docs/plugin-api.md "Fetchers
 * answer in the one search", Droidtop/tracker#218): [getValuesJson] is set
 * when the result can be fetched from its row without its detail page
 * ([GameSourceProvider.get]), titled [getTitle] when the source gave one.
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
    val columns: List<String> = emptyList(),
    val badges: List<String> = emptyList(),
    val version: String? = null,
    val sizeBytes: Long? = null,
    val getValuesJson: String? = null,
    val getTitle: String? = null,
)

/** A result's size in bytes as a row shows it: "640 KB", "1.2 GB" (decimal units, as Android's own Files app). */
object SourceSize {
    fun label(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unit = 0
        while (size >= 1000 && unit < units.lastIndex) {
            size /= 1000
            unit++
        }
        val number = if (unit == 0 || size >= 10) size.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", size).removeSuffix(".0")
        return "$number ${units[unit]}"
    }
}

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
data class UnavailableSource(
    val label: String,
    val reason: String,
    /** The plugin's id: what opens its page in Settings, Plugins, where it is approved or turned on. */
    val pluginId: String,
    /** True while the plugin only waits for the person's approval (the sentence "is waiting for your approval"). */
    val waitingForApproval: Boolean,
    /**
     * True when the one thing missing is the `library.sources` point, set to Ask first or not answered yet: the row then
     * asks right there ([PluginGrants.askPoint]) instead of opening a page that would only show Ask first again
     * (Droidtop/tracker#270).
     */
    val askable: Boolean = false,
) {
    companion object {
        /**
         * Why an installed source plugin cannot answer, or null when it can (docs/SPEC.md 12a "One search"). The plugin
         * itself must be approved and on, and the person must have allowed it the `library.sources` point ([point]):
         * a plugin approved as a whole but not yet allowed to give games used to be asked anyway and answered with
         * "has not been allowed to ...", one block per plugin (Droidtop/tracker#485). Pure.
         */
        fun of(
            label: String,
            pluginId: String,
            trust: PluginTrustState,
            enabled: Boolean,
            disabledReason: String?,
            point: GrantState,
        ): UnavailableSource? {
            val waiting = trust == PluginTrustState.PENDING ||
                (trust == PluginTrustState.APPROVED && enabled && disabledReason == null && point == GrantState.ASK)
            val reason = when {
                waiting -> "is waiting for your approval"
                trust == PluginTrustState.DENIED -> "was denied"
                trust != PluginTrustState.APPROVED -> "is turned off"
                disabledReason != null -> "is disabled: $disabledReason"
                !enabled -> "is turned off"
                point == GrantState.DENIED -> "was not allowed to give games"
                else -> return null
            }
            val askable = trust == PluginTrustState.APPROVED && enabled && disabledReason == null && point == GrantState.ASK
            return UnavailableSource(label, reason, pluginId, waiting, askable)
        }

        /** The one row that stands for every source waiting for the person, in the search and on Game sources alike. */
        fun waitingTitle(waiting: List<UnavailableSource>): String =
            if (waiting.size == 1) "${waiting.first().label} is waiting for your approval" else "${waiting.size} sources are waiting for your approval"

        /** What the row under [waitingTitle] says it does. */
        fun waitingSubtitle(waiting: List<UnavailableSource>): String = if (waiting.size == 1) "Open it to approve" else "Open to approve them"
    }
}

object AcquireContentSources {
    /** The registered settings screen id of Accounts and sources > Plugins: where "no source" and "needs approval" lead. */
    const val PLUGINS_SCREEN_ID = "plugins"

    /** The registered settings screen id of Accounts and sources > App integrations. */
    const val INTEGRATIONS_SCREEN_ID = "integrations"

    /** The extension point a contract 2 plugin gives games through; the person allows it per plugin. */
    const val SOURCES_POINT = "library.sources"

    private fun pointState(record: PluginRecord, grants: PluginGrants): GrantState =
        PluginGrants.provideState(record, grants.read(record.manifest.id), SOURCES_POINT)

    /** The state of an installed plugin that is not runnable; [SourceState.READY] only for one that is (the bundle's integrity is checked by [PluginStore.runnableFor], not here). */
    internal fun stateOf(record: PluginRecord): SourceState = when {
        record.trust == PluginTrustState.PENDING -> SourceState.WAITING_FOR_APPROVAL
        record.trust == PluginTrustState.DENIED -> SourceState.DENIED
        record.disabledReason != null -> SourceState.DISABLED
        record.runnable() -> SourceState.READY
        else -> SourceState.TURNED_OFF
    }

    /**
     * Every installed plugin that offers downloads, with its state: what Game sources lists so a source can be reached
     * from the first minute, with an empty library and no folders (Droidtop/tracker#540). Ready ones are those
     * [PluginStore.runnableFor] vouches for (their bundle is re-verified); a bundle that fails that check is left out,
     * as it is from every other list. Reads the plugin store: off the main thread.
     */
    fun listings(context: Context): List<SourceListing> {
        val ready = PluginStore.runnableFor(context, PluginCapability.ACQUIRE_CONTENT).map { it.manifest.id }.toSet()
        return PluginStore.installed(context)
            .filter { PluginCapability.ACQUIRE_CONTENT in it.manifest.capabilities }
            .mapNotNull { record ->
                when {
                    record.manifest.id in ready -> SourceListing(record, SourceState.READY)
                    record.runnable() -> null
                    else -> SourceListing(record, stateOf(record))
                }
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Installed plugins that declare ACQUIRE_CONTENT but are not runnable (waiting for approval, denied, turned off, disabled after a failure), only those serving [systemId] when given. Reads the plugin store: off the main thread. */
    fun unavailablePlugins(context: Context, systemId: String? = null): List<UnavailableSource> {
        val grants = PluginGrants.forContext(context)
        return PluginStore.installed(context)
            .filter { PluginCapability.ACQUIRE_CONTENT in it.manifest.capabilities }
            .filter { SourceSystems.of(it.manifest).serves(systemId) }
            .mapNotNull { record ->
                UnavailableSource.of(
                    record.manifest.label,
                    record.manifest.id,
                    record.trust,
                    record.enabled,
                    record.disabledReason,
                    pointState(record, grants),
                )
            }
    }

    /** Just the plugin sources -- what [PluginSearchAggregator] fans a query out to; JSON integrations stay one-way/fire-and-forget and have no results to merge. [systemId] scopes it to the sources serving that system ("pc": the PC library). */
    fun availablePlugins(context: Context, systemId: String? = null): List<AcquireContentSource.Plugin> {
        val grants = PluginGrants.forContext(context)
        return PluginStore.runnableFor(context, PluginCapability.ACQUIRE_CONTENT)
            .filter { pointState(it, grants) == GrantState.GRANTED }
            .map { AcquireContentSource.Plugin(it) }
            .filter { it.serves(systemId) }
    }

    /**
     * What a source that cannot give games yet opens from Game sources: why, in one notice, and the one way on, at
     * the top: Allow it to get games (the point sheet, [askRow]) when only the point waits, else its page in Plugins
     * to review and approve it. It used to open that page straight away, whose approval list is permission rows only,
     * so the source looked empty, with no systems and no reason (RP5, build 1908, Droidtop/tracker#270). Null for a
     * source that is ready and allowed.
     */
    fun blockedScreen(context: Context, listing: SourceListing): CatalogScreen? {
        val point = pointState(listing.record, PluginGrants.forContext(context))
        if (listing.state == SourceState.READY && point == GrantState.GRANTED) return null
        val label = listing.label
        val page = SettingsScreenRegistry.get(PLUGINS_SCREEN_ID, listing.pluginId)
        val (title, body) = when {
            listing.state == SourceState.WAITING_FOR_APPROVAL -> "Waiting for your approval" to "Approve it to see its systems and search them."
            listing.state == SourceState.DENIED -> "You denied it" to "Approve it on its page to use it as a source."
            listing.state == SourceState.DISABLED -> "It is disabled" to (listing.record.disabledReason ?: "It stopped after a failure.").take(90)
            listing.state == SourceState.TURNED_OFF -> "It is turned off" to "Turn it on on its page to use it as a source."
            point == GrantState.DENIED -> "Not allowed to get games" to "Allow Get games from a source on its Permissions page."
            else -> "Not allowed to get games yet" to "Allow it to search and download games for you."
        }
        val way: CatalogItem = if (listing.state == SourceState.READY && point == GrantState.ASK) {
            askRow("source_blocked_ask_${listing.pluginId}", UnavailableSource(label, "is waiting for your approval", listing.pluginId, true, askable = true))
        } else {
            NestedScreenItem(
                id = "source_blocked_page_${listing.pluginId}",
                title = if (listing.state == SourceState.WAITING_FOR_APPROVAL) "Review and approve $label" else "Open $label's page",
                subtitle = "In Settings, Plugins",
                inline = page,
                registryId = if (page == null) PLUGINS_SCREEN_ID else null,
            )
        }
        val items = listOf(
            NoticeItem(
                id = "source_blocked_${listing.pluginId}",
                notice = Notice(tone = NoticeTone.Caution, title = title, body = body, source = label),
                placement = NoticePlacement.Page,
            ),
            way,
        )
        return CatalogScreen(
            id = "source_blocked_${listing.pluginId}",
            title = label,
            subtitle = "Not ready to give games",
            groups = { listOf(CatalogGroup("source_blocked_group_${listing.pluginId}", null, items)) },
            indexGroups = { emptyList() },
        )
    }

    /**
     * The row for one source that only waits for the `library.sources` point: pressing it asks on the first-use sheet
     * ([askSource]); once allowed, the source is in every Get games list and search from then on.
     */
    fun askRow(id: String, source: UnavailableSource): AsyncActionItem = AsyncActionItem(
        id = id,
        title = UnavailableSource.waitingTitle(listOf(source)),
        subtitle = "Press to allow it to get games",
        run = { ctx, _ ->
            if (askSource(ctx, source)) "${source.label} can get games now. Go back and search again"
            else "${source.label} is still not allowed to get games"
        },
    )

    /** Asks whether [source] may give games; true when it may afterwards. Off the main thread inside. */
    suspend fun askSource(context: Context, source: UnavailableSource): Boolean = withContext(Dispatchers.IO) {
        val record = PluginStore.installed(context).firstOrNull { it.manifest.id == source.pluginId } ?: return@withContext false
        PluginGrants.askPoint(context, record, SOURCES_POINT)
    }

    /**
     * A row that opens Plugins, at [pluginId]'s own page when it names one and the page is known, else at the list.
     * The one way a "waiting for approval" line becomes something a person can press (also the search dialog's).
     */
    fun pluginsLink(id: String, title: String, subtitle: String?, pluginId: String?): NestedScreenItem {
        val page = pluginId?.let { SettingsScreenRegistry.get(PLUGINS_SCREEN_ID, it) }
        return if (page != null) {
            NestedScreenItem(id = id, title = title, subtitle = subtitle, inline = page)
        } else {
            NestedScreenItem(id = id, title = title, subtitle = subtitle, registryId = PLUGINS_SCREEN_ID)
        }
    }

    /**
     * The systems "Get games" offers to download for: those with a folder already ([existing], by name), then every
     * other system a folder can mean ([all] without the store-owned ones), each with the folder a download would create
     * under [root] (the first games folder) the first time one is placed there. Nothing is refused for lack of a system
     * folder (Droidtop/tracker#536): "system folder" is ES-DE vocabulary a newcomer does not have. Pure.
     */
    internal fun downloadTargets(
        existing: List<Pair<File, ConsoleSystemDef>>,
        all: Collection<ConsoleSystemDef>,
        root: File,
    ): Pair<List<Pair<File, ConsoleSystemDef>>, List<Pair<File, ConsoleSystemDef>>> {
        val yours = existing.distinctBy { (_, system) -> system.id }.sortedBy { (_, system) -> system.displayName.lowercase() }
        val have = yours.map { (_, system) -> system.id }.toSet()
        val others = all.filter { it.id !in have && it.canResolveFromFolder() }
            .sortedBy { it.displayName.lowercase() }
            .map { File(root, it.id) to it }
        return yours to others
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
        val startedAt = System.currentTimeMillis()
        // A system whose folder does not exist yet: made now, under the games folder.
        if (!systemFolder.isDirectory) withContext(Dispatchers.IO) { systemFolder.mkdirs() }
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
                // Said by the job, not by the screen that waits for it: the downloaded file is looked at, nothing is walked.
                if (jobResult.ok) AcquireIndexing.afterAcquire(context, source.record.manifest.id, startedAt, systemFolder, jobResult.values)
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
     * has no need for. The downloaded file reaches the library from the job
     * itself ([startDownload]), not from here, so it shows up whether or not
     * this screen is still open.
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
            done.await()
            finalMessage
        } finally {
            job.close()
        }
    }
}
