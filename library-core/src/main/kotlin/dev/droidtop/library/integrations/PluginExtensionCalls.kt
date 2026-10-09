package dev.droidtop.library.integrations

import android.content.Context
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
import dev.droidtop.library.settings.CatalogScreen
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

    /** The actions whose static filter matches [target]. Reads manifests only; call it off the main thread. */
    fun actionsFor(context: Context, target: ContextTarget, mode: String? = null): List<Action> =
        providersOf(context, POINT, mode).filter { (_, entry) -> ContextActionFilter.matches(entry, target) }.map { (record, entry) -> Action(record, entry) }

    private fun argsFor(context: Context, action: Action, target: ContextTarget): JSONObject =
        JSONObject().put("actionId", action.id).put("target", targetArg(context, action.record, target))

    /** Whether [action] is enabled for [target] right now. Anything but a clear "no" in time is yes. */
    suspend fun enabled(context: Context, action: Action, target: ContextTarget): Boolean {
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(
                action.record,
                newCall(POINT, "enabled", "game.detail", argsFor(context, action, target), ENABLED_BUDGET_MS),
                timeoutMs = ENABLED_BUDGET_MS,
                crashOnTimeout = false,
            )
            !reply.ok || reply.data.optBoolean("enabled", true)
        } finally {
            policy.shutdown()
        }
    }

    /** Runs [action] on [target] and returns the outcome (message or reply screen). */
    suspend fun run(context: Context, action: Action, target: ContextTarget): PluginOutcome {
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
            val reply = policy.handle(action.record, newCall(POINT, "run", "game.detail", args, 15_000L))
            if (!reply.ok) {
                return PluginOutcome(
                    message = "${action.label} failed: ${reply.message}",
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
    }

    fun sources(context: Context): List<Source> = providersOf(context, POINT).map { (record, entry) -> Source(record, entry) }

    /** One scrape pass's connection to the sources. [close] when the pass ends. */
    class Session(context: Context, private val sources: List<Source>) {
        private val policy = PluginCrashPolicy(context.applicationContext)

        /** What each source knows about [facts], one entry per source that found something. A failing source is skipped, the pass goes on. */
        suspend fun lookup(facts: JSONObject): List<MetadataFields> = sources.mapNotNull { source ->
            try {
                lookupOne(source, facts)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
        }

        private suspend fun lookupOne(source: Source, facts: JSONObject): MetadataFields? {
            val match = policy.handle(source.record, newCall(POINT, "match", "scrape", facts, 15_000L))
            if (!match.ok) return null
            val ids = PluginMetadataProtocol.bestCandidateIds(match.data) ?: return null
            val fetch = policy.handle(source.record, newCall(POINT, "fetch", "scrape", JSONObject().put("ids", ids), 15_000L))
            if (!fetch.ok) return null
            return PluginMetadataProtocol.fields(source.label, fetch.data)
        }

        fun close() = policy.shutdown()
    }

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

    /** The tiles for [surface], from manifests only; call it off the main thread. */
    fun tilesFor(context: Context, surface: String = QUICK_MENU_SURFACE, mode: String? = null): List<Tile> =
        (providersOf(context, STATUS_POINT, mode) + providersOf(context, QUICK_POINT, mode))
            .filter { (_, entry) -> PluginTileProtocol.onSurface(entry, surface) }
            .map { (record, entry) -> Tile(record, entry) }

    /** The last state seen for [tile], for drawing before the refresh answers. */
    fun cached(tile: Tile): TileState? = lastState[tile.key]

    private fun args(tile: Tile) = JSONObject().apply { tile.entry.id?.let { put("tileId", it) } }

    /** Asks every tile for its state in parallel; a tile that does not answer in time keeps its last value. */
    suspend fun refresh(context: Context, tiles: List<Tile>): Map<String, TileState?> = coroutineScope {
        tiles.map { tile ->
            async {
                val policy = PluginCrashPolicy(context.applicationContext)
                try {
                    val reply = policy.handle(
                        tile.record,
                        newCall(tile.entry.point, "state", QUICK_MENU_SURFACE, args(tile), STATE_BUDGET_MS),
                        timeoutMs = STATE_BUDGET_MS,
                        crashOnTimeout = false,
                    )
                    if (reply.ok) PluginTileProtocol.state(reply.data, tile.fallbackLabel).also { lastState[tile.key] = it } else lastState[tile.key]
                } finally {
                    policy.shutdown()
                }
            }.let { tile.key to it }
        }.associate { (key, deferred) -> key to deferred.await() }
    }

    /** Presses a quick tile: `toggle` when it has an on state, otherwise `action`. Returns the outcome. */
    suspend fun press(context: Context, tile: Tile, state: TileState?): PluginOutcome? {
        if (!tile.quick) return null
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(tile.record, newCall(tile.entry.point, PluginTileProtocol.pressOp(state), QUICK_MENU_SURFACE, args(tile), 15_000L))
            if (!reply.ok) {
                return PluginOutcome(
                    message = "${tile.fallbackLabel} failed: ${reply.message}",
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
 * Ops, all quick calls: `check {ids}`, `resolve {text}`, `match {title, versions}`.
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

    private suspend fun call(source: String, op: String, args: JSONObject, timeoutMs: Long, userInitiated: Boolean): dev.droidtop.pluginhost.PluginReply? {
        val (record, _) = providers().firstOrNull { (_, entry) -> entry.id == source } ?: return null
        val policy = PluginCrashPolicy(app)
        return try {
            policy.handle(record, newCall(POINT, op, "library.updates", args.put("source", source), timeoutMs), timeoutMs = timeoutMs, crashOnTimeout = false, userInitiated = userInitiated)
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

        /** A round's `check`: the source paces its own requests, so it gets longer than a quick call; a miss is not a crash. */
        const val CHECK_BUDGET_MS = 120_000L

        /** `resolve` and `match` answer a person waiting on a sheet. */
        const val QUICK_BUDGET_MS = 15_000L

        const val MAX_CANDIDATES = 20
    }
}
