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
import org.json.JSONObject

/** The plugins that are running and provide [point] at a version this build serves: manifests only, nothing is loaded or called. */
internal fun providersOf(context: Context, point: String): List<Pair<PluginRecord, ProvidedPoint>> {
    val resolution = PluginApiResolver.current(context)
    return PluginStore.installed(context)
        .filter { it.runnable() && !resolution.isWaiting(it.manifest.id) }
        .flatMap { record ->
            record.manifest.v2.provides
                .filter { it.point == point && ExtensionPoints.supports(it.point, it.version) }
                .map { record to it }
        }
}

private fun newCall(point: String, op: String, surfacePlace: String, args: JSONObject, deadlineMs: Long): PluginCall =
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
    fun actionsFor(context: Context, target: ContextTarget): List<Action> =
        providersOf(context, POINT).filter { (_, entry) -> ContextActionFilter.matches(entry, target) }.map { (record, entry) -> Action(record, entry) }

    private fun argsFor(context: Context, action: Action, target: ContextTarget): JSONObject {
        // A game target needs `library.read`: without it the plugin learns only the kind (docs/plugin-api.md 3 C4).
        val identity = target.kind != "game" ||
            PluginGrants.stateOf(action.record, PluginGrants.forContext(context).read(action.record.manifest.id), "library.read") == GrantState.GRANTED
        return JSONObject().put("actionId", action.id).put("target", ContextActionFilter.targetJson(target, identity))
    }

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

    /** Runs [action] on [target] and returns what to tell the user. */
    suspend fun run(context: Context, action: Action, target: ContextTarget): String {
        val args = argsFor(context, action, target)
        if (action.runsAsJob) {
            val flat = buildMap { args.keys().forEach { put(it, args.optString(it)) } }
            val jobId = PluginJobsCenter.start(context, action.record, PluginCapability.LIBRARY_ACTION, flat, title = action.label)
            return if (jobId != null) "${action.label} started; it is under Jobs" else "${action.label} could not start"
        }
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(action.record, newCall(POINT, "run", "game.detail", args, 15_000L))
            if (reply.ok) reply.data.optString("message").takeIf { it.isNotBlank() } ?: "${action.label} done" else "${action.label} failed: ${reply.message}"
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
    fun tilesFor(context: Context, surface: String = QUICK_MENU_SURFACE): List<Tile> =
        (providersOf(context, STATUS_POINT) + providersOf(context, QUICK_POINT))
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

    /** Presses a quick tile: `toggle` when it has an on state, otherwise `action`. Returns what to tell the user, or null when nothing needs saying. */
    suspend fun press(context: Context, tile: Tile, state: TileState?): String? {
        if (!tile.quick) return null
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(tile.record, newCall(tile.entry.point, PluginTileProtocol.pressOp(state), QUICK_MENU_SURFACE, args(tile), 15_000L))
            if (reply.ok) reply.data.optString("message").takeIf { it.isNotBlank() } else "${tile.fallbackLabel} failed: ${reply.message}"
        } finally {
            policy.shutdown()
        }
    }
}
