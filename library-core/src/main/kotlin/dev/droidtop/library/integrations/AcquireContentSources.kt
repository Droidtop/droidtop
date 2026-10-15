package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.consoles.canResolveFromFolder
import dev.droidtop.library.settings.ActionItem
import dev.droidtop.library.settings.AsyncActionItem
import dev.droidtop.library.settings.CatalogGroup
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.settings.NestedScreenItem
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.library.settings.TextInputItem
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
 * [systemScreen] is the per-system "Get games" entry point both :app and
 * :shell-gamepad's gamelist options menu still offer. As of the "Search
 * fan-out" change (SPEC 12a), [search] is also the backing call for the
 * search in `dev.droidtop.library.integrations.UnifiedSearch`, which asks
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

    /** Whether this source is listed under [systemId] and asked by a search scoped to it (a blank id: every source). A JSON integration names no systems, so it serves all. */
    fun serves(systemId: String?): Boolean = true

    data class Json(val integration: Integration) : AcquireContentSource {
        override val id get() = "json_${integration.id}"
        override val label get() = integration.label
        override val description get() = integration.description
    }

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
    val columns: List<String> = emptyList(),
    val badges: List<String> = emptyList(),
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
data class UnavailableSource(
    val label: String,
    val reason: String,
    /** The plugin's id: what opens its page in Settings, Plugins, where it is approved or turned on. */
    val pluginId: String,
    /** True while the plugin only waits for the person's approval (the sentence "is waiting for your approval"). */
    val waitingForApproval: Boolean,
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
            return UnavailableSource(label, reason, pluginId, waiting)
        }

        /** The one row that stands for every source waiting for the person, in the search and in Get games alike. */
        fun waitingTitle(waiting: List<UnavailableSource>): String =
            if (waiting.size == 1) "${waiting.first().label} is waiting for your approval" else "${waiting.size} sources are waiting for your approval"

        /** What the row under [waitingTitle] says it does. */
        fun waitingSubtitle(waiting: List<UnavailableSource>): String = if (waiting.size == 1) "Open it to approve" else "Open to approve them"
    }
}

object AcquireContentSources {
    /** The registered settings screen id of Accounts and sources > Plugins: where "no source" and "needs approval" lead. */
    const val PLUGINS_SCREEN_ID = "plugins"

    /** The registered id of the one "Get games" screen every menu opens (see [chooseSystemScreen]); deep-link it with a console system id. */
    const val GET_GAMES_SCREEN_ID = "get_games"

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

    /**
     * Every available source for a "Get games" surface: installed+approved plugins declaring ACQUIRE_CONTENT, plus JSON
     * integrations of the same capability. Plugins first -- a real result list beats a one-way launch. With [systemId],
     * only the sources that serve that system (a plugin's declared `systems`, Droidtop/tracker#543).
     */
    fun available(context: Context, systemId: String? = null): List<AcquireContentSource> =
        (
            availablePlugins(context) +
                IntegrationStore.available(context, IntegrationCapability.ACQUIRE_CONTENT).map { AcquireContentSource.Json(it) }
            ).filter { it.serves(systemId) }

    /** Just the plugin sources -- what [PluginSearchAggregator] fans a query out to; JSON integrations stay one-way/fire-and-forget and have no results to merge. [systemId] scopes it to the sources serving that system ("pc": the PC library). */
    fun availablePlugins(context: Context, systemId: String? = null): List<AcquireContentSource.Plugin> {
        val grants = PluginGrants.forContext(context)
        return PluginStore.runnableFor(context, PluginCapability.ACQUIRE_CONTENT)
            .filter { pointState(it, grants) == GrantState.GRANTED }
            .map { AcquireContentSource.Plugin(it) }
            .filter { it.serves(systemId) }
    }

    /**
     * The one "Get games" screen for one system -- lists every source
     * [available], a JSON one as its existing text-field-or-button shape,
     * a plugin one as a [NestedScreenItem] opening [SourceScreens.searchScreen]. Both
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
                val sources = available(context, systemId)
                if (sources.isEmpty()) {
                    if (available(context).isEmpty()) listOf(noSourceGroup(context, systemId)) else listOf(noSourceForSystemGroup(systemId, systemName))
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
                                        inline = SourceScreens.searchScreen(source, systemId, systemName, systemFolder),
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
        val unavailable = unavailablePlugins(context)
        val waiting = unavailable.filter { it.waitingForApproval }
        val others = unavailable.filter { !it.waitingForApproval }
        return CatalogGroup(
            id = "acquire_empty",
            title = null,
            items = buildList {
                add(
                    ActionItem(
                        id = "acquire_empty_$key",
                        title = if (unavailable.isEmpty()) "No download source is installed" else "No download source is ready",
                        subtitle = if (unavailable.isEmpty()) "A download source is a plugin or an app integration that finds games for you." else null,
                        run = {},
                    ),
                )
                // Every source waiting for the person is ONE row that leads to where they are approved, however many there are.
                if (waiting.isNotEmpty()) {
                    add(pluginsLink("acquire_empty_waiting_$key", UnavailableSource.waitingTitle(waiting), UnavailableSource.waitingSubtitle(waiting), waiting.singleOrNull()?.pluginId))
                }
                others.forEach { off -> add(pluginsLink("acquire_empty_off_${off.pluginId}_$key", "${off.label} ${off.reason}", "Open its page", off.pluginId)) }
                add(
                    NestedScreenItem(
                        id = "acquire_empty_plugins_$key",
                        title = "Plugins",
                        subtitle = "Install a plugin that offers downloads, or approve one you already added",
                        registryId = PLUGINS_SCREEN_ID,
                    ),
                )
                add(
                    NestedScreenItem(
                        id = "acquire_empty_integrations_$key",
                        title = "App integrations",
                        subtitle = "Hook another installed app in as a download source",
                        registryId = INTEGRATIONS_SCREEN_ID,
                    ),
                )
            },
        )
    }

    /** Sources are installed, but none of them declares this system: said so, with the way to the plugins that could be added. */
    private fun noSourceForSystemGroup(systemId: String, systemName: String): CatalogGroup = CatalogGroup(
        id = "acquire_empty",
        title = null,
        items = listOf(
            ActionItem(
                id = "acquire_empty_system_$systemId",
                title = "No source gets $systemName games",
                subtitle = "The sources you have list the systems they serve, and $systemName is not one of them.",
                run = {},
            ),
            NestedScreenItem(
                id = "acquire_empty_system_plugins_$systemId",
                title = "Plugins",
                subtitle = "Install a plugin that offers downloads",
                registryId = PLUGINS_SCREEN_ID,
            ),
        ),
    )

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
     * The registered "Get games" screen (docs/SPEC.md 12a "Get games everywhere"): the ONE screen every
     * menu, page, empty state and the search dialog opens, through
     * `SettingsScreenRegistry.get(GET_GAMES_SCREEN_ID, systemId)`. With no system id it asks which
     * system to download for, then opens that system's own [systemScreen]; deep-linked at a console
     * system id it opens that system's [systemScreen] directly. With no source it says so and leads to
     * Plugins instead of an empty list of systems.
     */
    fun chooseSystemScreen(): CatalogScreen = CatalogScreen(
        id = GET_GAMES_SCREEN_ID,
        title = "Get games",
        subtitle = "Pick the system to download for",
        forDeepLink = { systemId -> deepLinkedSystemScreen(systemId) },
        // Settings search must not walk the systems' folders to index a screen that is entered from a menu.
        indexGroups = { emptyList() },
        groups = { context ->
            withContext(Dispatchers.IO) {
                if (available(context).isEmpty()) {
                    listOf(noSourceGroup(context, "all"))
                } else {
                    val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
                    val sources = available(context)
                    // Only the systems some installed source serves (Droidtop/tracker#543): a list of every system
                    // would lead to pages that say nothing can be found there.
                    val (yours, others) = downloadTargets(
                        existing = SystemFolders.all(context, systemsById),
                        all = systemsById.values,
                        root = GamesRoots.current(context).first(),
                    ).let { (a, b) -> a.filter { (_, s) -> sources.any { it.serves(s.id) } } to b.filter { (_, s) -> sources.any { it.serves(s.id) } } }
                    fun item(folder: File, system: ConsoleSystemDef) = NestedScreenItem(
                        id = "acquire_pick_${system.id}",
                        title = system.displayName,
                        inline = systemScreen(system.id, system.displayName, folder),
                    )
                    listOfNotNull(
                        yours.takeIf { it.isNotEmpty() }?.let { list ->
                            CatalogGroup(id = "acquire_choose_systems", title = "Your systems", items = list.map { (folder, system) -> item(folder, system) })
                        },
                        CatalogGroup(
                            id = "acquire_choose_other",
                            title = if (yours.isEmpty()) null else "Other systems",
                            items = others.map { (folder, system) -> item(folder, system) },
                        ),
                    )
                }
            }
        },
    )

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

    /** [systemScreen] for a system known only by id: its name and folder are read when the screen opens, off the main thread. */
    private fun deepLinkedSystemScreen(systemId: String): CatalogScreen = CatalogScreen(
        id = "acquire_system_$systemId",
        title = "Get games",
        indexGroups = { emptyList() },
        groups = { context ->
            withContext(Dispatchers.IO) {
                val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
                val name = systemsById[systemId]?.displayName ?: systemId
                // No folder for the system yet: the first games folder's own, made when a download is placed (#536).
                val folder = SystemFolders.all(context, systemsById).firstOrNull { (_, system) -> system.id == systemId }?.first
                    ?: File(GamesRoots.current(context).first(), systemId)
                systemScreen(systemId, name, folder).groups(context)
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
        // A system whose folder does not exist yet (Get games offers every system): made now, under the games folder.
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
