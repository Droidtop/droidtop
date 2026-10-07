package dev.droidtop.runtime.windows

import android.content.Context
import android.util.Log
import app.gamenative.data.DownloadInfo
import app.gamenative.service.SteamService
import app.gamenative.service.gog.GOGService
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreLibraries
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * What the stores say about a newer build of an installed game (docs/SPEC.md
 * 7g, "Where an update comes from"), kept as a small file so a library scan
 * can read it without a network call and without a session.
 *
 * Steam answers through the vendored service (a manifest comparison,
 * [SteamService.isUpdatePending]); a store droidtop runs itself answers
 * through its own [dev.droidtop.library.stores.StoreLibrary.checkUpdate]
 * (Amazon compares the installed version id with the live one). A store with
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
        val native = id.substringAfter(':')
        // A store droidtop runs itself answers through its own interface.
        StoreLibraries.forKey(id)?.let { store ->
            return store.checkUpdate(context, native)?.let { Result(it.update, it.latest) }
        }
        return when (id.substringBefore(':')) {
            "steam" -> {
                val appId = native.toIntOrNull() ?: return null
                if (!SteamService.isConnected || !SteamService.isLoggedIn) return null
                Result(if (SteamService.isUpdatePending(appId)) StoreUpdate.AVAILABLE else StoreUpdate.CURRENT, null)
            }
            else -> null
        }
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
 * Publishes the downloads the vendored store services are running to
 * [StoreDownloads] (docs/SPEC.md 7i), so a capsule and the primary button
 * can say Downloading. It reads the services' own in-memory download maps
 * (no disk, no network), slowly while nothing runs and once a second while
 * something does, and asks [StoreUpdates] again about a game whose download
 * just ended. Epic is not mapped: its download map is keyed by a row number
 * the library does not carry.
 */
internal object StoreDownloadWatch {
    private const val IDLE_MS = 3_000L
    private const val BUSY_MS = 1_000L

    private val started = AtomicBoolean(false)

    /** This watch's share of [StoreDownloads]; the store install jobs publish their own. */
    private const val PUBLISHER = "gamenative"

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var before = emptySet<String>()
            while (true) {
                val now = snapshot()
                StoreDownloads.publish(PUBLISHER, now)
                (before - now.keys).forEach { id -> StoreUpdates.recheck(app, id) }
                before = now.keys
                delay(if (now.isEmpty()) IDLE_MS else BUSY_MS)
            }
        }
    }

    private fun snapshot(): Map<String, StoreDownloads.Progress> {
        val out = HashMap<String, StoreDownloads.Progress>()

        fun add(store: String, downloads: Map<*, DownloadInfo>) {
            downloads.forEach { (key, info) ->
                val fraction = info.getProgress()
                // A finished job can linger in the service's map at 100 percent.
                if (fraction < 1f) out["$store:$key"] = StoreDownloads.Progress(fraction, paused = !info.isActive())
            }
        }
        runCatching { add("steam", SteamService.getActiveDownloads()) }
        runCatching { add("gog", GOGService.getActiveDownloads()) }
        return out
    }
}
