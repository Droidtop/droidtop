package dev.droidtop.library.integrations

import android.content.Context
import android.util.Log
import dev.droidtop.pluginhost.ContextActionFilter
import dev.droidtop.pluginhost.ContextTarget
import dev.droidtop.pluginhost.ExtensionPoints
import dev.droidtop.pluginhost.GrantState
import dev.droidtop.pluginhost.MetadataFields
import dev.droidtop.pluginhost.PluginApiResolver
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginGrants
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.pluginhost.PluginMetadataProtocol
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginStore
import dev.droidtop.pluginhost.PluginTileProtocol
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.pluginhost.TileState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import dev.droidtop.library.settings.CatalogScreen
import dev.droidtop.library.toLibraryEntryKind
import org.json.JSONObject

/** A plugin action or tile reply: either a message, or a screen to show, or both. */
data class PluginOutcome(
    val message: String?,
    val screen: CatalogScreen?,
)

/** The plugins that are running and provide [point] at a version this build serves: manifests only, nothing is loaded or called. */
internal fun providersOf(context: Context, point: String, mode: String? = null): List<Pair<PluginRecord, ProvidedPoint>> {
    val resolution = PluginApiResolver.current(context)
    val grants = if (mode == null) null else PluginGrants.forContext(context)
    return PluginStore.installed(context)
        .filter { it.runnable() && !resolution.isWaiting(it.manifest.id) }
        .flatMap { record ->
            // docs/plugin-api.md 1.9: in a mode, only the entries the plugin wants there and the person has not taken out of it.
            val snapshot = grants?.read(record.manifest.id)
            record.manifest.v2.provides
                .filter { it.point == point && ExtensionPoints.supports(it.point, it.version) }
                .filter { entry -> mode == null || PluginModes.shows(snapshot!!, entry, mode) }
                .map { record to it }
        }
}

/**
 * The `target` a call about one game or app carries (docs/plugin-api.md 3 C4, C18): a game's identity (id, title,
 * system) only when [record] holds `library.read`, otherwise just the kind. One rule for every point that is told
 * which game the person is looking at.
 */
internal fun targetArg(context: Context, record: PluginRecord, target: ContextTarget): JSONObject {
    val identity = target.kind != "game" ||
        PluginGrants.stateOf(record, PluginGrants.forContext(context).read(record.manifest.id), "library.read") == GrantState.GRANTED
    return ContextActionFilter.targetJson(target, identity)
}

internal fun newCall(point: String, op: String, surfacePlace: String, args: JSONObject, deadlineMs: Long): PluginCall =
    PluginCall(
        callId = "c-" + UUID.randomUUID().toString().take(8),
        deadlineMs = deadlineMs,
        point = point,
        version = 1,
        op = op,
        surface = JSONObject().put("place", surfacePlace),
        args = args,
    )

/**
 * Context actions (docs/plugin-api.md 3 C4): "do X with this" on a game or an
 * app, offered on its detail screen. Which actions apply is decided from the
 * manifest's static filter ([ContextActionFilter]) with no plugin loaded;
 * `enabled` is asked only when the screen opens, with a 500 ms budget, and a
 * plugin that misses it (or fails) is shown enabled, never treated as a crash.
 * `run` is a quick call, or a job when the entry says so.
 */
object PluginContextActions {
    const val POINT = "ui.context_action"

    /** docs/plugin-api.md 8: `enabled` is asked when the menu opens, so it may not hold the menu up. */
    const val ENABLED_BUDGET_MS = 500L

    /** One action a plugin offers: [entry] is the `provides` row that declared it. */
    data class Action(val record: PluginRecord, val entry: ProvidedPoint) {
        val id: String get() = entry.id ?: "default"
        val label: String get() = entry.label ?: record.manifest.label
        val runsAsJob: Boolean get() = ContextActionFilter.runsAsJob(entry)
    }

    /**
     * The target a library entry is (docs/plugin-api.md 3 C4, C18): an Android app is an `app` with its package,
     * anything else a `game`. The one rule for every host that tells a plugin which game or app the person means.
     */
    fun targetOf(entry: dev.droidtop.library.LibraryEntry): ContextTarget {
        val app = entry.kind == dev.droidtop.library.LibraryEntryKind.NATIVE_ANDROID_APP
        return ContextTarget(
            kind = if (app) "app" else "game",
            id = entry.id,
            title = dev.droidtop.library.GameNaming.displayName(entry.title),
            systemId = entry.systemId,
            packageName = if (app) entry.id else null,
        )
    }

    /** An installed Android app as a target (Standard's long-press app menu). */
    fun appTarget(packageName: String, label: String): ContextTarget =
        ContextTarget(kind = "app", id = packageName, title = label, packageName = packageName)

    /** The actions whose static filter matches [target]. Reads manifests only; call it off the main thread. */
    fun actionsFor(context: Context, target: ContextTarget, mode: String? = null): List<Action> =
        providersOf(context, POINT, mode).filter { (_, entry) -> ContextActionFilter.matches(entry, target) }.map { (record, entry) -> Action(record, entry) }

    private fun argsFor(context: Context, action: Action, target: ContextTarget): JSONObject =
        JSONObject().put("actionId", action.id).put("target", targetArg(context, action.record, target))

    /**
     * Whether [action] is enabled for [target] right now. Anything but a clear "no" in time is yes. [place] is where
     * the person is ([PluginModes.Surfaces]), the call's `surface.place`.
     */
    suspend fun enabled(context: Context, action: Action, target: ContextTarget, place: String = PluginModes.Surfaces.GAME_DETAIL): Boolean {
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(
                action.record,
                newCall(POINT, "enabled", place, argsFor(context, action, target), ENABLED_BUDGET_MS),
                timeoutMs = ENABLED_BUDGET_MS,
                crashOnTimeout = false,
            )
            !reply.ok || reply.data.optBoolean("enabled", true)
        } finally {
            policy.shutdown()
        }
    }

    /** Runs [action] on [target] and returns the outcome (message or reply screen). */
    suspend fun run(context: Context, action: Action, target: ContextTarget, place: String = PluginModes.Surfaces.GAME_DETAIL): PluginOutcome {
        val args = argsFor(context, action, target)
        if (action.runsAsJob) {
            val flat = buildMap { args.keys().forEach { put(it, args.optString(it)) } }
            val jobId = PluginJobsCenter.start(context, action.record, PluginCapability.LIBRARY_ACTION, flat, title = action.label)
            return PluginOutcome(
                message = if (jobId != null) "${action.label} started; it is under Jobs" else "${action.label} could not start",
                screen = null,
            )
        }
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(action.record, newCall(POINT, "run", place, args, 15_000L))
            if (!reply.ok) {
                return PluginOutcome(
                    message = "${action.label} failed: ${dev.droidtop.pluginhost.SourceFailures.forCode(reply.code, reply.message)}",
                    screen = null,
                )
            }
            val message = reply.data.optString("message").takeIf { it.isNotBlank() } ?: "${action.label} done"
            val view = dev.droidtop.pluginhost.PluginViewCall.replyView(reply.data)
            val screen = view?.let {
                PluginViews.screenFor(
                    record = action.record,
                    point = POINT,
                    view = it,
                    id = "ctx_view_${action.record.manifest.id}_${action.id}",
                    hostContext = JSONObject().put("target", args.get("target")),
                )
            }
            PluginOutcome(message = message, screen = screen)
        } finally {
            policy.shutdown()
        }
    }

    /**
     * The actions plugins offer on one game or app, as a page (Droidtop/tracker#67): what Standard's long-press app menu
     * and Desktop's Start menu open from their "Plugin actions" row, in [mode] at [place]. Which actions apply is read
     * from manifests and `enabled` asked when the page opens, off the main thread; each action is a row that runs it and
     * says what came of it, and a reply's view opens through [onReplyScreen].
     */
    fun screen(
        target: ContextTarget,
        mode: String,
        place: String,
        onReplyScreen: (CatalogScreen) -> Unit,
    ): CatalogScreen = CatalogScreen(
        id = "plugin_actions_${target.kind}_${target.id}",
        title = target.title,
        subtitle = "Plugin actions",
        groups = { context ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val actions = actionsFor(context, target, mode).filter { enabled(context, it, target, place) }
                val items: List<dev.droidtop.library.settings.CatalogItem> = actions.map { action ->
                    dev.droidtop.library.settings.AsyncActionItem(
                        id = "plugin_action_${action.record.manifest.id}_${action.id}",
                        title = action.label,
                        subtitle = "From ${action.record.manifest.label}",
                        run = { ctx, _ ->
                            val outcome = run(ctx, action, target, place)
                            outcome.screen?.let(onReplyScreen)
                            outcome.message ?: "${action.label} done"
                        },
                    )
                }.ifEmpty {
                    listOf(dev.droidtop.library.settings.TextBlockItem(id = "plugin_actions_none", text = "No plugin has anything for ${target.title} right now."))
                }
                listOf(dev.droidtop.library.settings.CatalogGroup(id = "plugin_actions", title = null, items = items))
            }
        },
        // The settings search must never call a plugin.
        indexGroups = { emptyList() },
    )
}

/**
 * Metadata sources (docs/plugin-api.md 3 A3), asked from inside the scrape
 * pass and nowhere else, never from list rendering. A source offers what the
 * built-in scrapers did not find; the scrape merges it, records the source's
 * name against each field it supplied and stores it itself: the plugin
 * never writes to the database. Two calls per game: `match` then `fetch`.
 */
object PluginMetadataSources {
    const val POINT = "library.metadata"

    data class Source(val record: PluginRecord, val entry: ProvidedPoint) {
        val label: String get() = entry.label ?: record.manifest.label

        /** The game-link keys this plugin owns: its own `library.updates` entries. A fetch links a game under these only. */
        val ownLinkKeys: Set<String>
            get() = record.manifest.v2.provides.filter { it.point == "library.updates" }.mapNotNull { it.id }.toSet()

        /** The sites the plugin declared (`net.domains`): a picture it names is fetched only from these. */
        val netDomains: List<String>
            get() = dev.droidtop.pluginhost.NetScope.declaredDomains(record.manifest.v2.permissions)
    }

    /**
     * What one source said about one game: the fields it fetched for its match and the links that match names
     * (`sourceLinks`, so droidtop links the game to its record), or, when its best candidates tie, those candidates
     * for the person to pick from (docs/SPEC.md 7h, "A source's own match").
     */
    data class Answer(
        val source: Source,
        val fields: MetadataFields?,
        val links: Map<String, String> = emptyMap(),
        val ambiguous: List<PluginMetadataProtocol.Candidate> = emptyList(),
        /** The cover picture's address, which droidtop fetches itself from the plugin's own sites. */
        val cover: String? = null,
        /** The source searched and found nothing for this game (not a failure, which gives no answer at all). */
        val noMatch: Boolean = false,
    )

    fun sources(context: Context): List<Source> = providersOf(context, POINT).map { (record, entry) -> Source(record, entry) }

    /**
     * One scrape pass's connection to the sources. [close] when the pass ends. The pass paces each source itself
     * (docs/plugin-api.md 3 A3): calls one at a time, spaced to its `rate.perMinute`, and a source that answers
     * `RATE_LIMITED` rests for the rest of the pass.
     */
    class Session(context: Context, private val sources: List<Source>, private val nowMs: () -> Long = android.os.SystemClock::elapsedRealtime) {
        private val policy = PluginCrashPolicy(context.applicationContext)
        private val lastCallAt = ConcurrentHashMap<String, Long>()
        private val resting = ConcurrentHashMap.newKeySet<String>()
        private val lanes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

        /** [call] to [source], paced; null while the source rests, which reads as no answer. */
        private suspend fun paced(source: Source, call: PluginCall): dev.droidtop.pluginhost.PluginReply? {
            val id = source.record.manifest.id
            return lanes.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }.withLock {
                if (id in resting) return@withLock null
                val wait = PluginMetadataProtocol.waitMs(lastCallAt[id], nowMs(), PluginMetadataProtocol.perMinute(source.entry))
                if (wait > 0) kotlinx.coroutines.delay(wait)
                lastCallAt[id] = nowMs()
                val reply = policy.handle(source.record, call)
                if (!reply.ok && reply.code == dev.droidtop.pluginhost.PluginErrorCode.RATE_LIMITED) {
                    resting += id
                    dev.droidtop.library.ScanLog.write("scrape: ${source.label} asked to slow down; not asked again this pass")
                }
                reply
            }
        }

        /** What each source knows about [facts], one entry per source that found something. A failing source is skipped, the pass goes on. */
        suspend fun lookup(facts: JSONObject): List<MetadataFields> = answers(facts).mapNotNull { it.fields }

        /** Every source's [Answer] for [facts]: a match's fields and links, or a tie to pick from. A failing source is skipped. */
        suspend fun answers(facts: JSONObject, skip: (Source) -> Boolean = { false }): List<Answer> = sources.filterNot(skip).mapNotNull { source ->
            try {
                answerOne(source, facts)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * Every candidate each source offers for [facts], for the person to pick from ("Choose match"). A candidate with
         * no name, or only its id for one ("Thread 214581"), is named by the source's `fetch` (docs/plugin-api.md 1.11,
         * Droidtop/tracker#784), at most [MAX_NAMED] per source; one still unnamed stays untitled, and the picker says so.
         */
        suspend fun candidates(
            facts: JSONObject,
            only: (Source) -> Boolean = { true },
        ): List<Pair<Source, PluginMetadataProtocol.Candidate>> = sources.filter(only).flatMap { source ->
            try {
                val match = paced(source, newCall(POINT, "match", "pick", facts, 15_000L))
                if (match == null || !match.ok) return@flatMap emptyList()
                var asked = 0
                PluginMetadataProtocol.candidates(match.data).map { candidate ->
                    val unnamed = candidate.title == null || PluginMetadataProtocol.isIdOnly(candidate.title, candidate.ids)
                    val named = when {
                        !unnamed -> candidate
                        asked >= MAX_NAMED -> candidate.copy(title = null)
                        else -> {
                            asked++
                            candidate.copy(title = nameOf(source, candidate.ids))
                        }
                    }
                    source to named
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }
        }

        /** The name [source]'s record [ids] goes by, from its `fetch` (A3's `title`), or null when it gives none. */
        private suspend fun nameOf(source: Source, ids: JSONObject): String? = try {
            val fetch = paced(source, newCall(POINT, "fetch", "pick", JSONObject().put("ids", ids), 15_000L))
            if (fetch != null && fetch.ok) PluginMetadataProtocol.title(fetch.data)?.takeUnless { PluginMetadataProtocol.isIdOnly(it, ids) } else null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }

        /** The fields and links [source] has for one candidate's [ids]. */
        suspend fun fetch(source: Source, ids: JSONObject): Answer? {
            val fetch = paced(source, newCall(POINT, "fetch", "scrape", JSONObject().put("ids", ids), 15_000L))
            if (fetch == null || !fetch.ok) return null
            return Answer(
                source,
                PluginMetadataProtocol.fields(source.label, fetch.data),
                PluginMetadataProtocol.sourceLinks(fetch.data, source.ownLinkKeys),
                cover = PluginMetadataProtocol.cover(fetch.data),
            )
        }

        private suspend fun answerOne(source: Source, facts: JSONObject): Answer? {
            val match = paced(source, newCall(POINT, "match", "scrape", facts, 15_000L))
            if (match == null || !match.ok) return null
            return when (val decision = PluginMetadataProtocol.decide(match.data)) {
                PluginMetadataProtocol.MatchDecision.None -> Answer(source, null, noMatch = true)
                is PluginMetadataProtocol.MatchDecision.Ambiguous -> Answer(source, null, ambiguous = decision.candidates)
                is PluginMetadataProtocol.MatchDecision.Match -> fetch(source, decision.candidate.ids)
            }
        }

        fun close() = policy.shutdown()
    }

    /** How many unnamed candidates of one source are named through its `fetch` for one picker. */
    const val MAX_NAMED = 8

    /** A session over the running sources, or null when there are none (the common case: nothing is bound). */
    fun open(context: Context): Session? = sources(context).takeIf { it.isNotEmpty() }?.let { Session(context, it) }
}

/**
 * Status and quick tiles in the Quick Menu (docs/plugin-api.md 3 C2, C3). What
 * tiles exist is read from manifests when the menu opens; their state is asked
 * once per opening (5 s budget, a miss keeps the last value and is not a
 * crash), and a press is the only other call. Nothing here runs from list
 * rendering, and a plugin never runs its own refresh loop.
 */
object PluginTiles {
    const val STATUS_POINT = "ui.status_tile"
    const val QUICK_POINT = "ui.quick_tile"
    const val QUICK_MENU_SURFACE = "gaming.quick_menu"
    const val STATE_BUDGET_MS = 5_000L

    data class Tile(val record: PluginRecord, val entry: ProvidedPoint) {
        val key: String get() = record.manifest.id + "/" + entry.point + "/" + (entry.id ?: "")
        val quick: Boolean get() = entry.point == QUICK_POINT
        val fallbackLabel: String get() = entry.label ?: record.manifest.label
        val pluginLabel: String get() = record.manifest.label
    }

    private val lastState = ConcurrentHashMap<String, TileState>()

    /**
     * The tiles for [surface], from manifests only; call it off the main thread. A tile scoped to a running game
     * ([PluginTileProtocol.forGame]) is left out while none (or another app's) runs.
     */
    fun tilesFor(context: Context, surface: String = QUICK_MENU_SURFACE, mode: String? = null): List<Tile> {
        val gameRunning = dev.droidtop.library.LaunchDisplay.running.value != null
        val runningPackage = dev.droidtop.library.LaunchDisplay.runningPackageName
        return (providersOf(context, STATUS_POINT, mode) + providersOf(context, QUICK_POINT, mode))
            .filter { (_, entry) -> PluginTileProtocol.onSurface(entry, surface) && PluginTileProtocol.forGame(entry, gameRunning, runningPackage) }
            .map { (record, entry) -> Tile(record, entry) }
    }

    /** The last state seen for [tile], for drawing before the refresh answers. */
    fun cached(tile: Tile): TileState? = lastState[tile.key]

    /** A tile call's args; off the Quick Menu they say where the tile is drawn (`context.surface`, `android.widget`). */
    private fun args(tile: Tile, surface: String = QUICK_MENU_SURFACE) = JSONObject().apply {
        tile.entry.id?.let { put("tileId", it) }
        if (surface != QUICK_MENU_SURFACE) put("context", JSONObject().put("surface", surface))
    }

    /** Asks every tile for its state in parallel; a tile that does not answer in time keeps its last value. */
    suspend fun refresh(context: Context, tiles: List<Tile>, surface: String = QUICK_MENU_SURFACE, userInitiated: Boolean = true): Map<String, TileState?> = coroutineScope {
        tiles.map { tile ->
            async {
                val policy = PluginCrashPolicy(context.applicationContext)
                try {
                    val reply = policy.handle(
                        tile.record,
                        newCall(tile.entry.point, "state", surface, args(tile, surface), STATE_BUDGET_MS),
                        timeoutMs = STATE_BUDGET_MS,
                        crashOnTimeout = false,
                        userInitiated = userInitiated,
                    )
                    if (reply.ok) PluginTileProtocol.state(reply.data, tile.fallbackLabel).also { lastState[tile.key] = it } else lastState[tile.key]
                } finally {
                    policy.shutdown()
                }
            }.let { tile.key to it }
        }.associate { (key, deferred) -> key to deferred.await() }
    }

    /** Presses a quick tile: `toggle` when it has an on state, otherwise `action`. Returns the outcome. */
    suspend fun press(context: Context, tile: Tile, state: TileState?, surface: String = QUICK_MENU_SURFACE): PluginOutcome? {
        if (!tile.quick) return null
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(tile.record, newCall(tile.entry.point, PluginTileProtocol.pressOp(state), surface, args(tile, surface), 15_000L))
            if (!reply.ok) {
                return PluginOutcome(
                    message = "${tile.fallbackLabel} failed: ${dev.droidtop.pluginhost.SourceFailures.forCode(reply.code, reply.message)}",
                    screen = null,
                )
            }
            val message = reply.data.optString("message").takeIf { it.isNotBlank() }
            val view = dev.droidtop.pluginhost.PluginViewCall.replyView(reply.data)
            val screen = view?.let {
                PluginViews.screenFor(
                    record = tile.record,
                    point = tile.entry.point,
                    view = it,
                    id = "tile_view_${tile.record.manifest.id}_${(tile.entry.id ?: "")}",
                    hostContext = JSONObject().put("tileId", args(tile).optString("tileId", "")),
                )
            }
            PluginOutcome(message = message, screen = screen)
        } finally {
            policy.shutdown()
        }
    }
}

/**
 * Update sources (docs/plugin-api.md 3 A6, `library.updates`): the plugins
 * a game can be linked to, asked by the library's own update round
 * ([dev.droidtop.library.SourceUpdateCheck]) and by the link rows on a
 * game's page, never from list rendering. A provider's `provides` entry
 * `id` is the source key its links are stored under (docs/SPEC.md 7g,
 * "Where an update comes from"); an entry without one is not a source.
 * Ops, all quick calls: `check {ids}`, `resolve {text}`, `match {title, versions}`, `record {id}`; and the
 * `download {id, link, version}` job, whose acquire reply is placed as a new version folder of the game
 * (docs/SPEC.md 7g, "Getting an update").
 */
class PluginUpdateSources(context: Context) : dev.droidtop.library.UpdateSources {
    private val app = context.applicationContext

    private fun providers(): List<Pair<PluginRecord, ProvidedPoint>> =
        providersOf(app, POINT).filter { (_, entry) -> !entry.id.isNullOrBlank() }.distinctBy { (_, entry) -> entry.id }

    override fun sources(): List<dev.droidtop.library.UpdateSources.Source> = providers().map { (record, entry) ->
        dev.droidtop.library.UpdateSources.Source(
            key = entry.id!!,
            label = entry.label ?: record.manifest.label,
            hint = runCatching { JSONObject(entry.extra).optString("linkHint") }.getOrNull()?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun call(source: String, op: String, args: JSONObject, timeoutMs: Long, userInitiated: Boolean, point: String = POINT): dev.droidtop.pluginhost.PluginReply? {
        val (record, _) = providers().firstOrNull { (_, entry) -> entry.id == source } ?: return null
        val policy = PluginCrashPolicy(app)
        return try {
            policy.handle(record, newCall(point, op, "library.updates", args.put("source", source), timeoutMs), timeoutMs = timeoutMs, crashOnTimeout = false, userInitiated = userInitiated)
        } finally {
            policy.shutdown()
        }
    }

    override suspend fun check(source: String, externalIds: List<String>): Map<String, dev.droidtop.library.UpdateSources.Answer> {
        val args = JSONObject().put("ids", org.json.JSONArray(externalIds))
        val reply = call(source, "check", args, CHECK_BUDGET_MS, userInitiated = false)
            ?: throw java.io.IOException("The source $source is not installed or not running")
        if (!reply.ok) throw java.io.IOException(reply.message ?: "The source did not answer")
        val answers = reply.data.optJSONArray("answers") ?: return emptyMap()
        val asked = externalIds.toSet()
        return buildMap {
            for (i in 0 until answers.length()) {
                val row = answers.optJSONObject(i) ?: continue
                val id = row.optString("id").takeIf { it in asked } ?: continue
                put(
                    id,
                    if (row.optBoolean("gone", false)) {
                        dev.droidtop.library.UpdateSources.Answer.Gone
                    } else {
                        dev.droidtop.library.UpdateSources.Answer.Version(row.optStringOrNull("version"), row.optStringOrNull("url"))
                    },
                )
            }
        }
    }

    override suspend fun resolve(source: String, text: String): dev.droidtop.library.UpdateSources.Found? {
        val reply = call(source, "resolve", JSONObject().put("text", text), QUICK_BUDGET_MS, userInitiated = true) ?: return null
        if (!reply.ok) return null
        return reply.data.optJSONObject("found")?.let(::found)
    }

    override suspend fun match(source: String, title: String, versions: List<String>): List<dev.droidtop.library.UpdateSources.Found> {
        val args = JSONObject().put("title", title).put("versions", org.json.JSONArray(versions))
        val reply = call(source, "match", args, QUICK_BUDGET_MS, userInitiated = true) ?: return emptyList()
        if (!reply.ok) return emptyList()
        val rows = reply.data.optJSONArray("candidates") ?: return emptyList()
        return (0 until minOf(rows.length(), MAX_CANDIDATES)).mapNotNull { rows.optJSONObject(it)?.let(::found) }
    }

    // The last page each source gave for a record (Droidtop/tracker#599): shown at once, and its links are the ones
    // the person picks from.
    private val records = RecordCache(java.io.File(app.filesDir, "source-records"))

    override fun savedRecord(source: String, externalId: String): dev.droidtop.library.UpdateSources.RecordAnswer? {
        val saved = records.load(source, externalId) ?: return null
        val parsed = readRecord(source, externalId, saved.record) ?: return null
        return dev.droidtop.library.UpdateSources.RecordAnswer(parsed, savedAtMs = saved.savedAtMs)
    }

    override fun signedOut(source: String): Boolean {
        val (record, _) = providers().firstOrNull { (_, e) -> e.id == source } ?: return false
        val account = dev.droidtop.pluginhost.PluginWebAccount.of(app, record) ?: return false
        return account.allowed && !account.signedIn
    }

    override fun needsCheck(source: String): Boolean {
        val (record, _) = providers().firstOrNull { (_, e) -> e.id == source } ?: return false
        return dev.droidtop.pluginhost.SiteChecks.pendingFor(record.manifest.id) != null
    }

    override fun passCheck(source: String): Boolean {
        val (record, _) = providers().firstOrNull { (_, e) -> e.id == source } ?: return false
        return dev.droidtop.pluginhost.PluginWebAccount.open(app, record, dev.droidtop.pluginhost.PluginWebAccount.Need.BROWSER_CHECK)
    }

    override fun signIn(source: String): Boolean {
        val (record, _) = providers().firstOrNull { (_, e) -> e.id == source } ?: return false
        return dev.droidtop.pluginhost.PluginWebAccount.open(app, record, dev.droidtop.pluginhost.PluginWebAccount.Need.SIGN_IN)
    }

    override suspend fun record(source: String, externalId: String): dev.droidtop.library.UpdateSources.RecordAnswer {
        // A source on facets that declares the item page is asked for it (docs/plugin-api.md 1.10); any other for its record.
        val onFacets = providers().firstOrNull { (_, entry) -> entry.id == source }?.first?.let { PluginFacets.declares(it, PluginFacets.ITEM_PAGE) } == true
        val reply = if (onFacets) {
            val subject = JSONObject().put("type", "item").put("source", source).put("id", externalId)
            call(source, PluginFacets.OP_GET, PluginFacets.getArgs(PluginFacets.ITEM_PAGE, subject), RECORD_BUDGET_MS, userInitiated = true, point = PluginFacets.POINT)
        } else {
            call(source, "record", JSONObject().put("id", externalId), RECORD_BUDGET_MS, userInitiated = true)
        }
        if (reply == null || !reply.ok) {
            // droidtop's words for the source's answer (docs/plugin-api.md 1.11 point 4): "<source> could not open its page: ...".
            val label = providers().firstOrNull { (_, e) -> e.id == source }?.let { (record, entry) -> entry.label ?: record.manifest.label } ?: source
            val failure = reply?.let { dev.droidtop.pluginhost.SourceFailures.line(label, "open its page", it.code, it.message) }
                ?: "The source $source is not installed or not running"
            Log.w(TAG, "record $source/$externalId: ${reply?.code ?: "no reply"}: $failure")
            // A source that cannot answer now: droidtop's saved copy, which says when it was saved.
            savedRecord(source, externalId)?.let { return it.copy(failure = failure) }
            return dev.droidtop.library.UpdateSources.RecordAnswer(null, failure, unsupported = reply?.code == dev.droidtop.pluginhost.PluginErrorCode.UNSUPPORTED)
        }
        // Contract 3: the item's bundles (docs/plugin-api.md 1.10, item.page); contract 2: a record object.
        val raw = ItemPageFacets.itemJson(reply.data, externalId) ?: reply.data.optJSONObject("record")
        val parsed = raw?.let { readRecord(source, externalId, it) }
        if (raw != null && parsed != null) runCatching { records.save(source, externalId, raw) }
        return dev.droidtop.library.UpdateSources.RecordAnswer(parsed, if (parsed == null) "The source sent no page for this record" else null)
    }

    /**
     * A control of [source]'s item page, pressed: its call or job on `ui.facets`, as any facet's is ([PluginViews.runAction]),
     * with a switch's new state as its typed `value` and the item it is about in the host's context.
     */
    override suspend fun runControl(source: String, externalId: String, control: dev.droidtop.library.UpdateSources.Control, on: Boolean?): String {
        val action = control.action ?: return ""
        val (record, _) = providers().firstOrNull { (_, e) -> e.id == source } ?: return "The source $source is not installed or not running"
        val subject = JSONObject().put("type", "item").put("source", source).put("id", externalId)
        val hostContext = JSONObject().put("target", PluginFacets.ITEM_PAGE).put("subject", subject)
        val values = on?.let { mapOf(control.id to it.toString()) }.orEmpty()
        return PluginViews.runAction(app, record, PluginFacets.POINT, action, values, hostContext, control.title).message
    }

    /**
     * A record from what [source] gave (or droidtop saved) for [externalId]: its item bundles through the item page's
     * guards when it carries facets, else the contract 2 record. A source's `record` is its declaration of the item page.
     */
    private fun readRecord(source: String, externalId: String, raw: JSONObject): dev.droidtop.library.UpdateSources.Record? {
        if (raw.optJSONArray("facets") == null) return parseRecord(raw, externalId)
        val (record, _) = providers().firstOrNull { (_, entry) -> entry.id == source } ?: return null
        val messages = mutableListOf<String>()
        val guards = PluginFacets.guardsFor(record).let { it.copy(declaredTargets = it.declaredTargets + PluginFacets.ITEM_PAGE) }
        val parsed = ItemPageFacets.record(ItemPageFacets.admitted(raw, source, externalId, guards, messages), externalId)
        dev.droidtop.pluginhost.PluginMessages.record(app, record.manifest.id, "$POINT/record", messages)
        return parsed
    }

    override suspend fun openDownload(
        source: String,
        externalId: String,
        url: String,
        fallbackUrl: String?,
        version: String?,
        gameName: String,
        newest: dev.droidtop.library.LibraryEntry,
        onStatus: (String) -> Unit,
    ): String {
        val (record, entry) = providers().firstOrNull { (_, e) -> e.id == source } ?: return "The source $source is not installed or not running"
        val label = entry.label ?: record.manifest.label
        val (placement, name) = placementFor(label, gameName, version, newest).getOrElse { return it.message ?: "The update has nowhere to go" }
        onStatus("Opening the link from $label")
        // The link the record gave, opened out of sight first (Droidtop/tracker#783): a host droidtop serves takes it with
        // no page shown, and a page the person must act on opens for them (#599). A link the page says has expired is asked
        // of the source again, from its thread, once. A link that no longer works starts nothing, so the record's own page
        // opens next for the person to pick again.
        val refresh = {
            onStatus("That link had expired. Asking $label for a current one")
            kotlinx.coroutines.runBlocking { currentLink(source, externalId, url) }
        }
        var captured = dev.droidtop.pluginhost.PluginWebAccount.openForDownloads(app, record, url, dev.droidtop.pluginhost.DownloadJobs.MAX_FILES, refresh)
        if (captured.isEmpty() && fallbackUrl != null && fallbackUrl != url) {
            onStatus("That link started nothing. Pick the download again on ${label}'s page")
            captured = dev.droidtop.pluginhost.PluginWebAccount.openForDownloads(app, record, fallbackUrl, dev.droidtop.pluginhost.DownloadJobs.MAX_FILES)
        }
        if (captured.isEmpty()) {
            Log.w(TAG, "update $source/$externalId: the web view started no download from ${dev.droidtop.pluginhost.NetScope.hostOf(url)}")
            return dev.droidtop.library.DownloadPick.NOTHING_STARTED
        }
        val descriptors = UpdateLinks.descriptorsFor(captured)
        val title = "Download $name"
        // Files a host account or the host's own API served say so in the job's line (#638), no page having opened.
        val via = captured.firstNotNullOfOrNull { it.via }
        via?.let { onStatus("Downloading $it") }
        val say: (String) -> Unit = { line -> onStatus(if (via == null) line else "$via: $line") }
        val done = AcquireDownload.run(app, record, title, JSONObject(), emptyMap(), descriptors, say, update = placement)
        if (!done.ok) Log.w(TAG, "update $source/$externalId: the download from the web view failed: ${done.error}")
        return if (done.ok) done.values["summary"] ?: "Added $name" else "${record.manifest.label}: ${done.error ?: "the download failed"}"
    }

    /**
     * The address [source]'s record [externalId] gives now for the mirror whose address was [expired] (Droidtop/tracker#783):
     * the mirror is found in droidtop's saved copy of the record by that address, and the same mirror in the record asked
     * again ([RecordMirrors.sameIn]). Null when the record cannot be asked or no longer lists it.
     */
    private suspend fun currentLink(source: String, externalId: String, expired: String): String? =
        currentDownload(source, externalId) { it.url == expired }?.url?.takeIf { it != expired }

    /**
     * The download [picked] names in droidtop's saved copy of [source]'s record [externalId], as the record asked again
     * gives it now ([RecordMirrors.sameIn]); docs/plugin-api.md 1.11, "One link path: the record". Null when the record
     * cannot be asked or no longer lists it.
     */
    private suspend fun currentDownload(
        source: String,
        externalId: String,
        picked: (dev.droidtop.library.UpdateSources.Download) -> Boolean,
    ): dev.droidtop.library.UpdateSources.Download? {
        val before = savedRecord(source, externalId)?.record?.downloads.orEmpty()
        val was = before.firstOrNull(picked) ?: run {
            Log.w(TAG, "update $source/$externalId: the expired link is in no saved record, so it cannot be asked for again")
            return null
        }
        val fresh = record(source, externalId).takeIf { it.failure == null }?.record ?: return null
        return dev.droidtop.library.RecordMirrors.sameIn(was, before, fresh.downloads)
    }

    /**
     * Where an update of the game whose newest version is [newest] lands (7m): the job arguments of its new version folder
     * beside that version's, with the saves to bring along, and the folder's name. A failure says why in one sentence.
     */
    private fun placementFor(label: String, gameName: String, version: String?, newest: dev.droidtop.library.LibraryEntry): Result<Pair<Map<String, String>, String>> {
        val folder = dev.droidtop.library.PcRunnerOptions.gameFolderFor(newest) ?: return Result.failure(IllegalStateException("The game's newest version has no folder on this device"))
        val games = folder.parentFile ?: return Result.failure(IllegalStateException("The game's folder has no folder above it"))
        val name = UpdatePlacement.folderName(gameName, version) ?: return Result.failure(IllegalStateException("$label gave no version to name the new folder by"))
        if (java.io.File(games, name).exists()) return Result.failure(IllegalStateException("$name is already in ${games.name}"))
        val engine = dev.droidtop.library.GameEngine.entries.firstOrNull { it.toLibraryEntryKind() == newest.kind }
        val beside = engine?.let { dev.droidtop.library.EnginesDatabase.defFor(app, it)?.saves?.beside }.orEmpty()
        val placement = mapOf(
            "destinationPath" to games.absolutePath,
            dev.droidtop.pluginhost.DownloadJobs.ARG_VERSION_FOLDER to name,
            UpdatePlacement.ARG_PREVIOUS to folder.absolutePath,
        ) + UpdatePlacement.savesArgs(folder, beside)
        return Result.success(placement to name)
    }

    override suspend fun download(
        source: String,
        externalId: String,
        link: String,
        version: String?,
        gameName: String,
        newest: dev.droidtop.library.LibraryEntry,
        onStatus: (String) -> Unit,
    ): String {
        val (record, entry) = providers().firstOrNull { (_, e) -> e.id == source } ?: return "The source $source is not installed or not running"
        val label = entry.label ?: record.manifest.label
        // 7m: the new version is a folder beside the game's newest one; nothing that is there is touched.
        val (placement, name) = placementFor(label, gameName, version, newest).getOrElse { return it.message ?: "The update has nowhere to go" }
        val args = JSONObject().put("source", source).put("id", externalId).put("link", link).apply { version?.let { put("version", it) } }
        val title = "Download $name"
        val result = PluginViews.runJob(app, record, POINT, "download", args, title, onStatus)
        if (!result.ok) {
            Log.w(TAG, "update $source/$externalId: the source's download job for $link failed: ${result.error}")
            return "${record.manifest.label}: ${result.error ?: "the download did not start"}"
        }
        val descriptors = dev.droidtop.pluginhost.AcquireDownloads.parse(result.values) ?: run {
            Log.w(TAG, "update $source/$externalId: the source's download job for $link named no file")
            return "$label named no file to download"
        }
        val done = AcquireDownload.run(app, record, title, JSONObject(), result.values, descriptors, onStatus, update = placement)
        if (!done.ok) Log.w(TAG, "update $source/$externalId: the download for $link failed: ${done.error}")
        return if (done.ok) done.values["summary"] ?: "Added $name" else "${record.manifest.label}: ${done.error ?: "the download failed"}"
    }

    /**
     * A split release, fetched part by part and placed as one Downloads job: a part with a page opens in droidtop's web
     * view, where the person starts it as for any link (#599), and a part with a token goes to the source's `download`
     * job. Nothing is placed until every part is in hand; one that brings nothing stops it all, saying which. A part whose
     * link has expired is asked of the source again, from its record, once.
     */
    override suspend fun downloadParts(
        source: String,
        externalId: String,
        parts: List<dev.droidtop.library.UpdateSources.Download.Part>,
        version: String?,
        gameName: String,
        newest: dev.droidtop.library.LibraryEntry,
        onStatus: (String) -> Unit,
    ): String {
        val (record, entry) = providers().firstOrNull { (_, e) -> e.id == source } ?: return "The source $source is not installed or not running"
        val label = entry.label ?: record.manifest.label
        val (placement, name) = placementFor(label, gameName, version, newest).getOrElse { return it.message ?: "The update has nowhere to go" }
        val again = suspend { currentDownload(source, externalId) { it.parts == parts }?.parts }
        val gathered = UpdateLinks.gatherParts(app, record, parts, onStatus, again) { link, which ->
            onStatus("$which: asking $label")
            val args = JSONObject().put("source", source).put("id", externalId).put("link", link).apply { version?.let { put("version", it) } }
            val result = PluginViews.runJob(app, record, POINT, "download", args, "Download $name ($which)", onStatus)
            if (result.ok) dev.droidtop.pluginhost.AcquireDownloads.parse(result.values).orEmpty() else emptyList()
        }.getOrElse {
            Log.w(TAG, "update $source/$externalId: ${it.message}")
            return it.message ?: "A part was not downloaded, so nothing was added"
        }
        val done = AcquireDownload.run(app, record, "Download $name", JSONObject(), emptyMap(), gathered, onStatus, update = placement)
        if (!done.ok) Log.w(TAG, "update $source/$externalId: the download in ${parts.size} parts failed: ${done.error}")
        return if (done.ok) done.values["summary"] ?: "Added $name" else "${record.manifest.label}: ${done.error ?: "the download failed"}"
    }

    private fun found(row: JSONObject): dev.droidtop.library.UpdateSources.Found? {
        val id = row.optString("id").trim().takeIf { it.isNotEmpty() && it.length <= 200 } ?: return null
        return dev.droidtop.library.UpdateSources.Found(
            externalId = id,
            title = row.optStringOrNull("title"),
            version = row.optStringOrNull("version"),
            url = row.optStringOrNull("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            note = row.optStringOrNull("note"),
        )
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    companion object {
        const val POINT = "library.updates"

        /** Where a failed record or update download says why, so a console without a screen to read still tells it. */
        private const val TAG = "droidtop.Updates"

        /** A round's `check`: the source paces its own requests, so it gets longer than a quick call; a miss is not a crash. */
        const val CHECK_BUDGET_MS = 120_000L

        /** `resolve` and `match` answer a person waiting on a sheet. */
        const val QUICK_BUDGET_MS = 15_000L

        /**
         * A record's page, asked for by the person (the record page, a Download's current link): long enough for a site's
         * browser check to pass by itself in droidtop's hidden view ([dev.droidtop.pluginhost.WebFetch.SETTLE_MS]) after
         * the plugin has loaded, which [QUICK_BUDGET_MS] was not: on the console the check had about 13 s
         * (Droidtop/tracker#783). The page says "Asking <source>..." meanwhile.
         */
        const val RECORD_BUDGET_MS = 45_000L

        const val MAX_CANDIDATES = 20

        /** The most links a record's Downloads tab shows (docs/plugin-api.md 3 A6). */
        const val MAX_DOWNLOADS = 200
        const val MAX_TEXT = 20_000
        const val MAX_TAGS = 64
        const val MAX_LINK = 2_048

        /**
         * A `record` reply's `record`, checked (docs/plugin-api.md 3 A6): long texts are cut, a link or address that is
         * not what the contract says is dropped, and an id other than the one asked for is refused. Pure, for the tests.
         */
        fun parseRecord(row: JSONObject, askedId: String): dev.droidtop.library.UpdateSources.Record? {
            fun text(name: String, max: Int = 200): String? =
                if (row.isNull(name)) null else row.optString(name).trim().takeIf { it.isNotEmpty() }?.take(max)
            val id = text("id") ?: askedId
            if (id != askedId) return null
            // Each mirror once, whatever systems it covers (Droidtop/tracker#838), as for a contract 3 record.
            val downloads = row.optJSONArray("downloads")?.let { array ->
                (0 until minOf(array.length(), MAX_DOWNLOADS)).mapNotNull { i ->
                    val d = array.optJSONObject(i) ?: return@mapNotNull null
                    // A download is the address droidtop opens itself (Droidtop/tracker#599), the source's token, or both.
                    val url = d.optString("url").trim().takeIf { it.startsWith("https://") && it.length <= MAX_LINK }
                    val link = d.optString("link").takeIf { it.isNotBlank() && it.length <= MAX_LINK }
                    if (url == null && link == null) return@mapNotNull null
                    val section = d.optString("section").trim().take(80).ifEmpty { "Downloads" }
                    dev.droidtop.library.UpdateSources.Download(
                        section = section,
                        host = d.optString("host").trim().take(80).ifEmpty { "Link" },
                        label = d.optString("label").trim().take(200).ifEmpty { d.optString("host").trim().take(80).ifEmpty { "Download" } },
                        link = link.orEmpty(),
                        url = url,
                        // Its systems, as a contract 3 entry names them, else from its heading ("Win/Linux").
                        systems = dev.droidtop.pluginhost.DownloadSystems.of(dev.droidtop.pluginhost.DownloadSystems.read(d.optJSONArray("systems")), section),
                        role = d.optString("role", "build").takeIf { it == "patch" || it == "extra" } ?: "build",
                        official = d.optBoolean("official", true),
                        build = d.optString("build").trim().take(80).ifEmpty { null },
                    )
                }
            }.orEmpty().let(ItemPageFacets::distinctDownloads)
            val tags = row.optJSONArray("tags")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { t -> t.isNotEmpty() && t.length <= 40 } }.distinct().take(MAX_TAGS)
            }.orEmpty()
            return dev.droidtop.library.UpdateSources.Record(
                externalId = id,
                title = text("title") ?: "#$id",
                url = text("url", MAX_LINK)?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                version = text("version", 80),
                developer = text("developer"),
                status = text("status", 40),
                rating = row.optDouble("rating").takeIf { !it.isNaN() && it in 0.0..1.0 },
                votes = row.optInt("votes", -1).takeIf { it >= 0 },
                tags = tags,
                updatedEpochMs = row.optLong("updated", 0L).takeIf { it > 0 },
                cover = text("cover", MAX_LINK)?.takeIf { it.startsWith("https://") },
                overview = text("overview", MAX_TEXT),
                changelog = text("changelog", MAX_TEXT),
                downloads = downloads,
            )
        }
    }
}
