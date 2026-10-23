package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameNaming
import dev.droidtop.library.GameRecordStore
import dev.droidtop.library.LaunchFacts
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.saves.ReportedSaveFolders
import dev.droidtop.library.saves.ReportedSaves
import dev.droidtop.library.saves.SaveHandoff
import dev.droidtop.library.saves.SaveSyncEngines
import dev.droidtop.library.stores.EnginehostSaves
import dev.droidtop.library.stores.StoreSaves
import dev.droidtop.library.toLibraryEntryKind
import dev.droidtop.pluginhost.SavesExchange
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves both ways between droidtop and an app's plugin, and the engine games droidtop offers it (docs/plugin-api.md 3
 * A7a, Droidtop/tracker#547). The owner: "The two applications should detect and work with each other over the plugin
 * API so no matter what, saves go where they're needed". `saves where` gives the app the same folder droidtop's own
 * launch passes ([dev.droidtop.library.EngineGameProvider.launch]), after the same sync, so which app starts a game
 * makes no difference; `saves report_changed` is the game-ended report droidtop takes from Enginehost's broadcast
 * ([EnginehostSaves.afterGameEnded]), over the broker.
 *
 * Games are found from the library's in-memory index and each game's record, never a folder walk. Only the folder the
 * app names is answered for; the library is listed only by [engineGames], which carries no configs: those are the
 * person's, made in the app.
 */
class AppInterfaceSaves(
    private val context: Context,
    private val library: () -> Library,
    private val records: GameRecordStore,
) : SavesExchange {

    /** An engine game Enginehost can run, with its record's launch facts. */
    data class EngineGame(val entry: LibraryEntry, val facts: LaunchFacts.Engine) {
        val path: String get() = facts.gameRoot
        val engine: String get() = facts.enginehostTarget?.engine.orEmpty()
    }

    override fun where(pluginId: String, args: JSONObject): JSONObject = runBlocking {
        val entries = entries() ?: return@runBlocking notReady()
        val game = find(entries, args.optString("path")) ?: return@runBlocking unknown()
        val storeKey = EnginehostSaves.storeKeyOf(game.entry.id, game.entry.pcInfo?.storeId)
        val answer = JSONObject().put("known", true).put("gameId", game.entry.id).put("synced", storeKey != null)
        // The launch's own steps (EngineGameProvider.launch): the folder is made and recorded, synced down, then named.
        val saveRoot = storeKey?.let { key ->
            if (!EnginehostSaves.prepare(context, key, File(game.path))) return@let null
            StoreSaves.beforeLaunch(context, key, GameNaming.displayName(game.entry.title))
            EnginehostSaves.saveRoot(context, key)
        }
        answer.put("saveRoot", saveRoot?.absolutePath ?: JSONObject.NULL)
    }

    /**
     * Idempotent, as the app sends it for droidtop's own launches too, beside the `GAME_ENDED` broadcast: adopting copies
     * only files newer than droidtop's, and a second upload request joins the job already running
     * ([dev.droidtop.pluginhost.PluginJobsCenter.startNative] keeps one job per game).
     */
    override fun changed(pluginId: String, args: JSONObject): JSONObject = runBlocking {
        val entries = entries() ?: return@runBlocking refused("droidtop's library is not ready yet; tell it again in a moment")
        val path = args.optString("path")
        val game = find(entries, path) ?: return@runBlocking refused("droidtop has no game at $path")
        val saveFolder = args.optString("saveFolder")
        if (EnginehostSaves.readableSaveFolder(saveFolder) == null) {
            return@runBlocking refused("droidtop takes saves only from a folder in shared storage or on a card, and $saveFolder is not one")
        }
        val saves = ReportedSaves(
            gameId = game.entry.id,
            title = GameNaming.displayName(game.entry.title),
            gameFolder = game.path,
            saveFolder = saveFolder,
            storeKey = EnginehostSaves.storeKeyOf(game.entry.id, game.entry.pcInfo?.storeId),
            reportedAtMs = args.optLong("endedAtMs", 0L).takeIf { it > 0 } ?: System.currentTimeMillis(),
            by = pluginId,
        )
        // Where it saved is kept for every game, synced or not; then every engine that covers the game takes it at once.
        ReportedSaveFolders.record(context, saves)
        reply(SaveSyncEngines.saved(context, saves))
    }

    override fun engineGames(args: JSONObject): JSONObject = runBlocking {
        val entries = entries() ?: return@runBlocking JSONObject().put("ready", false).put("games", JSONArray()).put("complete", false)
        val engines = args.optJSONArray("engines")?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() }
        val games = cached(entries) { engineGamesOf(entries) { records.get(it.id)?.launch as? LaunchFacts.Engine } }
        toJson(games, engines) { game -> File(game.path).lastModified().takeIf { it > 0 } ?: game.entry.firstSeenEpochMs }
    }

    /** The games the index holds now, waiting briefly for the first load in a process that has none yet; null when none came. */
    private suspend fun entries(): List<LibraryEntry>? {
        val library = library()
        val state = library.backgroundScanState(LibraryKinds.GAMES)
        return state.value ?: run {
            library.scanInBackground(LibraryKinds.GAMES)
            withTimeoutOrNull(PluginLibraryRead.FIRST_SCAN_WAIT_MS) { state.filterNotNull().first() }
        }
    }

    /** The engine game at [path], reading only the records of entries whose folder could hold it. */
    private fun find(entries: List<LibraryEntry>, path: String): EngineGame? {
        val candidates = entries.filter { it.kind in ENGINE_KINDS && !it.missing && related(it.id, path) }
        return match(engineGamesOf(candidates) { records.get(it.id)?.launch as? LaunchFacts.Engine }, path)
    }

    @Volatile private var cache: Pair<List<LibraryEntry>, List<EngineGame>>? = null

    /** One read of every record per index publication: the list is asked on connect, after a knock and after a scan. */
    private fun cached(entries: List<LibraryEntry>, read: () -> List<EngineGame>): List<EngineGame> {
        cache?.takeIf { it.first === entries }?.let { return it.second }
        return read().also { cache = entries to it }
    }

    private fun notReady(): JSONObject = JSONObject().put("known", false).put("ready", false)

    private fun unknown(): JSONObject = JSONObject().put("known", false)

    private fun refused(message: String): JSONObject = JSONObject().put("accepted", false).put("message", message)

    companion object {
        /**
         * The `report_changed` reply from what each engine did: `synced` when any engine covers the game, `engines` one
         * line each. With none, nothing is copied and that is no failure. Pure.
         */
        fun reply(handoffs: List<SaveHandoff>): JSONObject {
            val engines = JSONArray()
            handoffs.forEach { engines.put(JSONObject().put("engine", it.engine).put("line", it.line)) }
            val message = if (handoffs.isEmpty()) "no sync covers this game; droidtop noted where it saved" else handoffs.joinToString("; ") { it.line }
            return JSONObject().put("accepted", true).put("synced", handoffs.isNotEmpty()).put("engines", engines).put("message", message)
        }

        /** The most games one `list_engine_games` answer carries; `complete` is false past it. */
        const val MAX_GAMES = 2_000

        private val ENGINE_KINDS = GameEngine.entries.map { it.toLibraryEntryKind() }.toSet()

        /** Each entry that is an engine game Enginehost can run, with its facts, by title. Pure. */
        fun engineGamesOf(entries: List<LibraryEntry>, factsOf: (LibraryEntry) -> LaunchFacts.Engine?): List<EngineGame> =
            entries.asSequence()
                .filter { it.kind in ENGINE_KINDS && !it.missing }
                .mapNotNull { entry -> factsOf(entry)?.takeIf { it.enginehostTarget != null }?.let { EngineGame(entry, it) } }
                .sortedBy { it.entry.title.lowercase() }
                .toList()

        /** The game whose folder is [path] (its game root, or the folder the library shows it by). Pure. */
        fun match(games: List<EngineGame>, path: String): EngineGame? {
            val wanted = normal(path) ?: return null
            return games.firstOrNull { normal(it.path) == wanted } ?: games.firstOrNull { normal(it.entry.id) == wanted }
        }

        /** Whether a game shown by the folder [id] could be the one at [path]: the same folder, or one inside the other. Pure. */
        internal fun related(id: String, path: String): Boolean {
            val a = normal(id) ?: return false
            val b = normal(path) ?: return false
            return a == b || b.startsWith("$a/") || a.startsWith("$b/")
        }

        private fun normal(path: String): String? = path.takeIf { it.startsWith("/") }?.let { File(it).absolutePath.trimEnd('/') }

        /**
         * The reply: `{ready, games: [{gameId, title, path, engine, engineVersion?, updatedAtMs}], complete}`, the games of
         * [engines] only when given, at most [MAX_GAMES]. No configs. Pure but for [updatedAt].
         */
        fun toJson(games: List<EngineGame>, engines: Set<String>?, updatedAt: (EngineGame) -> Long): JSONObject {
            val wanted = games.filter { engines == null || it.engine in engines }
            val array = JSONArray()
            for (game in wanted.take(MAX_GAMES)) {
                array.put(
                    JSONObject()
                        .put("gameId", game.entry.id)
                        .put("title", game.entry.title)
                        .put("path", game.path)
                        .put("engine", game.engine)
                        .apply { game.facts.engineVersion?.let { put("engineVersion", it) } }
                        .put("updatedAtMs", updatedAt(game)),
                )
            }
            return JSONObject().put("ready", true).put("games", array).put("complete", wanted.size <= MAX_GAMES)
        }
    }
}
