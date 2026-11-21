package dev.droidtop.library.computers

import android.content.Context
import android.util.Log
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryKinds
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When the library is exchanged with the paired computers by itself (docs/SPEC.md 7o, "When the library syncs"; owner
 * 2026-11-21: "desktop libraries should sync on a cycle, and update dynamically"):
 * - while droidtop's own screen is in front, every [CYCLE_MS], and at once on coming back when the last sync is older
 *   than [FRESH_MS];
 * - [SETTLE_MS] after this device's games or their marks stop changing (an install, a favourite, a session played).
 * One exchange at a time, the same [ComputerLibrary.sync] the "Sync the library now" button runs, and the library is
 * read again only when one brought something ([ComputerLibrary.Synced.arrived]), so what came from a computer shows
 * without a visit to Settings.
 */
object ComputerLibraryCycle {
    const val CYCLE_MS = 15 * 60_000L
    const val FRESH_MS = 5 * 60_000L
    const val SETTLE_MS = 30_000L
    private const val TAG = "droidtop.computers"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile private var library: ((Context) -> Library)? = null
    @Volatile private var cycle: Job? = null
    @Volatile private var watching: Job? = null

    /** Once, at start: how to reach the process's library. Starts watching this device's games for changes. */
    @OptIn(FlowPreview::class)
    fun install(context: Context, library: (Context) -> Library) {
        val app = context.applicationContext
        this.library = library
        if (watching != null) return
        watching = scope.launch {
            library(app).backgroundScanState(LibraryKinds.GAMES)
                .filterNotNull()
                .map(::fingerprint)
                .distinctUntilChanged()
                .drop(1)
                .debounce(SETTLE_MS)
                .collect { syncAll(app, olderThanMs = 0L) }
        }
    }

    /** droidtop's own screen came to the front: the cycle runs while it stays there. */
    fun shown(context: Context) {
        if (cycle?.isActive == true) return
        val app = context.applicationContext
        cycle = scope.launch {
            var olderThan = FRESH_MS
            while (isActive) {
                // Its own job: leaving droidtop's screen stops the cycle, never an exchange halfway through its writes.
                val due = olderThan
                scope.launch { syncAll(app, due) }.join()
                olderThan = 0L
                delay(CYCLE_MS)
            }
        }
    }

    /** droidtop's own screen left the front (a game, another app, the screen off): the cycle stops. */
    fun hidden() {
        cycle?.cancel()
        cycle = null
    }

    /**
     * One exchange with every paired computer whose last sync is at least [olderThanMs] old, then the library read
     * again if any brought something. Skipped while the library has not been scanned yet: an empty list would tell the
     * computers this device has no games.
     */
    suspend fun syncAll(context: Context, olderThanMs: Long) {
        val lib = library?.invoke(context) ?: return
        lock.withLock {
            val now = System.currentTimeMillis()
            val due = Computers.list(context).filter { now - it.lastSyncMs >= olderThanMs }
            if (due.isEmpty()) return
            val games = lib.backgroundScanState(LibraryKinds.GAMES).value ?: return
            var arrived = false
            due.forEach { computer -> if (syncOne(context, computer, lib, games)) arrived = true }
            if (arrived) {
                lib.scanInBackground(LibraryKinds.GAMES, restart = true)
                lib.scanInBackground(LibraryKinds.APPS, restart = true)
            }
        }
    }

    private suspend fun syncOne(context: Context, computer: Computer, lib: Library, games: List<LibraryEntry>): Boolean =
        runCatching { ComputerLibrary.sync(context, computer, lib, games) }
            .onSuccess { Log.i(TAG, it.line) }
            .onFailure { Log.w(TAG, "library sync with ${computer.name} failed", it) }
            .getOrNull()?.arrived == true

    /**
     * What a sync would send about this device's games, as one number: a change in it is a change a computer should
     * hear about. Collections live outside the entries, so a collection change alone waits for the next cycle.
     */
    internal fun fingerprint(games: List<LibraryEntry>): Int {
        var hash = games.size
        games.forEach { e ->
            hash = 31 * hash + listOf(
                ComputerLibrary.keyOf(e), e.favorite, e.hidden, e.completed, e.rating, e.gameName, e.sortName, e.kidGame,
                e.playtimeSeconds, e.lastPlayedEpochMs, e.pcInfo?.installPath,
            ).hashCode()
        }
        return hash
    }
}
