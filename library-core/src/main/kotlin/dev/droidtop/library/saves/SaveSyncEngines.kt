package dev.droidtop.library.saves

import android.content.Context
import android.util.Log
import dev.droidtop.library.stores.EnginehostSaves
import dev.droidtop.library.stores.StoreSaves
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/**
 * A game's saves that another app reported (docs/plugin-api.md 3 A7a, `saves report_changed`): which game, the folder it
 * saved in, and when. [storeKey] is the store whose cloud keeps the game's saves, when one does.
 */
data class ReportedSaves(
    val gameId: String,
    val title: String,
    val gameFolder: String,
    val saveFolder: String,
    val storeKey: String?,
    val reportedAtMs: Long,
    /** The plugin that reported it. */
    val by: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("gameId", gameId).put("title", title).put("gameFolder", gameFolder).put("saveFolder", saveFolder)
        .put("storeKey", storeKey ?: JSONObject.NULL).put("reportedAtMs", reportedAtMs).put("by", by)

    companion object {
        fun fromJson(json: JSONObject): ReportedSaves? = runCatching {
            ReportedSaves(
                gameId = json.getString("gameId"),
                title = json.optString("title"),
                gameFolder = json.getString("gameFolder"),
                saveFolder = json.getString("saveFolder"),
                storeKey = json.optString("storeKey").takeIf { !json.isNull("storeKey") && it.isNotEmpty() },
                reportedAtMs = json.getLong("reportedAtMs"),
                by = json.optString("by"),
            )
        }.getOrNull()
    }
}

/** What one engine did with a report, in the one line the reply and the Saves row show. */
data class SaveHandoff(val engine: String, val line: String, val started: Boolean)

/**
 * One way droidtop syncs a game's saves somewhere else (docs/SPEC.md 7o "Saves"): a store's cloud today; paired
 * computers and handhelds once they sync from a folder outside a Wine prefix (Droidtop/tracker#546); a cloud or network
 * share (Droidtop/tracker#573); a `saves.sync` plugin (Droidtop/tracker#284). Each says for itself whether it covers a game.
 */
interface SaveSyncEngine {
    val id: String

    /**
     * Takes [saves] now (the owner: right away), starting its sync in the background; null when it does not sync this
     * game, so nothing is copied for it. Called off the main thread; it must not wait on the network.
     */
    suspend fun saved(context: Context, saves: ReportedSaves): SaveHandoff?
}

/**
 * Every [SaveSyncEngine], and the hand-off of a report to each: the owner, 2026-10-23: "there's multiple sync engines.
 * If we don't sync somewhere, there's nowhere to copy it to, and that's fine". One engine's failure never stops the others.
 */
object SaveSyncEngines {
    private const val TAG = "droidtop.SaveSyncEngines"

    private val engines = CopyOnWriteArrayList<SaveSyncEngine>(listOf(StoreCloudSaveEngine))

    /** Adds [engine], or replaces the one with its id. */
    fun register(engine: SaveSyncEngine) {
        engines.removeIf { it.id == engine.id }
        engines += engine
    }

    fun all(): List<SaveSyncEngine> = engines.toList()

    /** Hands [saves] to every engine at once; what each that covers the game did. Empty when none does. */
    suspend fun saved(context: Context, saves: ReportedSaves): List<SaveHandoff> = engines.mapNotNull { engine ->
        runCatching { engine.saved(context, saves) }
            .onFailure { Log.w(TAG, "${engine.id} could not take ${saves.gameId}'s saves", it) }
            .getOrElse { SaveHandoff(engine.id, "${engine.id}: ${it.message ?: it.javaClass.simpleName}", started = false) }
    }
}

/**
 * Where each game last saved, as an app reported it: one per game, the newest kept. What the Saves row shows and what a
 * sync engine reads when it needs the folder later. Preferences: off the main thread.
 */
object ReportedSaveFolders {
    private const val PREFS = "droidtop_reported_saves"

    fun record(context: Context, saves: ReportedSaves) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val older = last(context, saves.gameId)
        if (older != null && older.reportedAtMs > saves.reportedAtMs) return
        prefs.edit().putString(saves.gameId, saves.toJson().toString()).apply()
    }

    fun last(context: Context, gameId: String): ReportedSaves? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(gameId, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let(ReportedSaves::fromJson)
}

/**
 * A store's cloud (Steam Cloud and the like) as an engine: the save model of a store game Enginehost runs
 * ([EnginehostSaves]), the same steps as after a game droidtop launched. Repeats are harmless: adopting copies only newer
 * files, and a second upload joins the job already running.
 */
object StoreCloudSaveEngine : SaveSyncEngine {
    override val id = "store_cloud"

    override suspend fun saved(context: Context, saves: ReportedSaves): SaveHandoff? {
        val key = saves.storeKey ?: return null
        if (!EnginehostSaves.prepare(context, key, File(saves.gameFolder))) {
            return SaveHandoff(id, "Cloud saves: droidtop could not make its folder for this game", started = false)
        }
        val upload = EnginehostSaves.afterGameEnded(context, key, saves.gameFolder, saves.saveFolder)
        if (upload) StoreSaves.afterExit(context, key, saves.title)
        return SaveHandoff(id, if (upload) "Cloud saves: uploading" else "Cloud saves: nothing to upload", started = upload)
    }
}
