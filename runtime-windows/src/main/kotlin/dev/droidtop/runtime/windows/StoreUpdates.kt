package dev.droidtop.runtime.windows

import android.content.Context
import android.util.Log
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreLibraries
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * What the stores say about a newer build of an installed game (docs/SPEC.md
 * 7g, "Where an update comes from"), kept as a small file so a library scan
 * can read it without a network call and without a session.
 *
 * Every store answers through its own
 * [dev.droidtop.library.stores.StoreLibrary.checkUpdate] (Steam compares
 * each installed depot's build with the one it serves now, Amazon the
 * installed version id with the live one, GOG the build id the install was
 * made from with the newest, itch.io the upload's stamp). A store with
 * no check stays [StoreUpdate.UNKNOWN] and the game page says so; nothing
 * here ever claims "up to date" for a store that was not asked. A check runs off the main thread, at most every few
 * hours, never inside a scan, and only for a store that is signed in; its
 * answer reaches the library on the next walk because the file is part of
 * the store part's change stamp ([PcGameProvider]).
 */
internal object StoreUpdates {
    /** A store's answer for one game; [latest] is null when the store names no version. */
    data class Result(val update: StoreUpdate, val latest: String?)

    private const val TAG = "droidtop.StoreUpdates"
    private const val FILE_NAME = "store-updates.json"
    private const val RECHECK_MS = 6L * 60 * 60 * 1000
    private const val ASK_TIMEOUT_MS = 20_000L

    @Volatile
    private var known: Map<String, Result> = emptyMap()

    @Volatile
    private var checkedAt = 0L

    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** The last stored answer for a store id ("steam:440"); null when none was ever recorded. */
    fun resultFor(id: String): Result? = known[id]

    /** Reads the file once per scan; a missing or unreadable file is simply "nothing known". */
    fun load(context: Context) {
        val file = file(context)
        if (!file.isFile) return
        runCatching {
            val json = JSONObject(file.readText())
            val games = json.optJSONObject("games") ?: JSONObject()
            val read = buildMap<String, Result> {
                games.keys().forEach { id ->
                    val row = games.optJSONObject(id) ?: return@forEach
                    val update = if (row.optString("u") == "A") StoreUpdate.AVAILABLE else StoreUpdate.CURRENT
                    put(id, Result(update, row.optString("v").ifBlank { null }))
                }
            }
            known = read
            checkedAt = json.optLong("checkedAt", 0L)
        }.onFailure { Log.w(TAG, "Could not read the store update answers", it) }
    }

    /**
     * Asks the stores about [installedIds], in the background and at most
     * every [RECHECK_MS]. Returns at once; a second call while one runs does
     * nothing.
     */
    fun refreshInBackground(context: Context, installedIds: List<String>) {
        if (System.currentTimeMillis() - checkedAt < RECHECK_MS) return
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                refresh(app, installedIds)
            } catch (failure: Exception) {
                Log.w(TAG, "Asking the stores about updates failed", failure)
            } finally {
                running.set(false)
            }
        }
    }

    /** One game's answer after its download ended, so an update that just finished stops being offered. */
    fun recheck(context: Context, id: String) {
        val app = context.applicationContext
        scope.launch {
            val answer = runCatching { ask(app, id) }.getOrNull() ?: return@launch
            val next = known.toMutableMap()
            if (answer.update == StoreUpdate.AVAILABLE || answer.update == StoreUpdate.CURRENT) next[id] = answer else next.remove(id)
            persist(app, next, checkedAt)
        }
    }

    private suspend fun refresh(context: Context, installedIds: List<String>) {
        val next = known.filterKeys { it in installedIds }.toMutableMap()
        var answered = 0
        for (id in installedIds) {
            // No answer (not signed in, offline, no check for this store)
            // keeps what was known; it is never turned into "current".
            val answer = withTimeoutOrNull(ASK_TIMEOUT_MS) { runCatching { ask(context, id) }.getOrNull() } ?: continue
            answered++
            if (answer.update == StoreUpdate.UNKNOWN) next.remove(id) else next[id] = answer
        }
        if (answered > 0 || next != known) persist(context, next, if (answered > 0) System.currentTimeMillis() else checkedAt)
    }

    /** The store's answer, or null when it cannot be asked right now or has no check at all. */
    private suspend fun ask(context: Context, id: String): Result? {
        val store = StoreLibraries.forKey(id) ?: return null
        return store.checkUpdate(context, id.substringAfter(':'))?.let { Result(it.update, it.latest) }
    }

    private fun persist(context: Context, next: Map<String, Result>, at: Long) {
        runCatching {
            val games = JSONObject()
            next.forEach { (id, result) ->
                games.put(id, JSONObject().put("u", if (result.update == StoreUpdate.AVAILABLE) "A" else "C").put("v", result.latest ?: ""))
            }
            file(context).writeText(JSONObject().put("checkedAt", at).put("games", games).toString())
            known = next
            checkedAt = at
        }.onFailure { Log.w(TAG, "Could not save the store update answers", it) }
    }
}

/**
 * Asks [StoreUpdates] again about a game whose download just ended, so an
 * update that finished stops being offered (docs/SPEC.md 7i). It follows
 * [StoreDownloads], which the store install jobs publish (docs/SPEC.md 7g,
 * "Stores"): a key that leaves it is a download that ended. Memory only; the
 * answer is asked off the main thread.
 */
internal object StoreDownloadWatch {
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var before = emptySet<String>()
            StoreDownloads.active.collect { now ->
                (before - now.keys).forEach { id -> StoreUpdates.recheck(app, id) }
                before = now.keys
            }
        }
    }
}
