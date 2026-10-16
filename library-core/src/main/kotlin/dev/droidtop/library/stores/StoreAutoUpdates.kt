package dev.droidtop.library.stores

import android.content.Context
import android.util.Log
import dev.droidtop.library.StoreUpdate
import dev.droidtop.net.DownloadPolicy
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps installed store games up to date by themselves (docs/SPEC.md, "Download rules"): the games whose own choice
 * or the default says so ([DownloadPolicy.keepsUpToDate]) are asked whether their store has a newer build, and each
 * that does becomes an automatic [StoreInstallJob], which the download policy then holds or lets run (network, update
 * window, a game running). Sweeping is linear in the installed games of signed-in stores, never in the library, runs
 * off the main thread, and happens at most once every [MIN_INTERVAL_MS] however often it is asked for.
 */
object StoreAutoUpdates {
    private const val TAG = "droidtop.AutoUpdates"
    private const val PREFS = "store_auto_updates"
    private const val KEY_LAST = "last_sweep"
    private const val START_DELAY_MS = 2L * 60 * 1000
    const val MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sweeping = AtomicBoolean(false)

    /** Asks for a sweep soon after start: the stores need a moment, and so does everything else starting. */
    fun requestLater(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(START_DELAY_MS)
            sweep(app)
        }
    }

    /** Asks for a sweep now (after a library sync); it does nothing inside [MIN_INTERVAL_MS] of the last. */
    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch { sweep(app) }
    }

    /** Whether [now] is soon enough after [lastMs] that a sweep would only repeat it. Pure. */
    internal fun tooSoon(lastMs: Long, now: Long): Boolean = lastMs > 0 && now - lastMs < MIN_INTERVAL_MS

    /**
     * Starts updating an installed store game in place: the one starter, for this sweep and for an Update pressed on
     * the Updates page (Droidtop/tracker#570). A store installs into "<its folder in a game folder>/<game>", so the
     * folder above the game is the root. False when the job could not start (no such store in this build, no folder).
     */
    fun startUpdate(context: Context, key: String, title: String, installPath: String, sizeBytes: Long, automatic: Boolean = false): Boolean {
        val root = File(installPath).parentFile ?: return false
        return StoreInstallJob.start(context.applicationContext, key, title, root, sizeBytes = sizeBytes, automatic = automatic) != null
    }

    /** Returns how many update jobs it started. */
    suspend fun sweep(context: Context, now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (tooSoon(prefs.getLong(KEY_LAST, 0L), now) || !sweeping.compareAndSet(false, true)) return@withContext 0
        try {
            DownloadPolicy.load(app)
            var started = 0
            for (store in StoreLibraries.all()) {
                if (!store.signedIn(app)) continue
                val games = runCatching { store.games(app) }.getOrDefault(emptyList())
                for (game in games) {
                    val installedAt = game.installPath
                    if (!game.installed || installedAt == null || !DownloadPolicy.keepsUpToDate(game.key)) continue
                    if (StoreInstallJob.jobFor(game.key) != null) continue
                    val check = runCatching { store.checkUpdate(app, game.gameId) }.getOrNull() ?: continue
                    if (check.update != StoreUpdate.AVAILABLE) continue
                    if (startUpdate(app, game.key, game.title, installedAt, game.sizeBytes, automatic = true)) started++
                }
            }
            prefs.edit().putLong(KEY_LAST, now).apply()
            if (started > 0) Log.i(TAG, "started $started automatic update(s)")
            started
        } finally {
            sweeping.set(false)
        }
    }
}
