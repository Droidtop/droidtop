package dev.droidtop.pluginhost

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.droidtop.net.Conditions
import dev.droidtop.net.DownloadCategory
import dev.droidtop.net.DownloadPools
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.net.DownloadSettings
import dev.droidtop.net.JobFacts
import dev.droidtop.net.MeteredGrants
import dev.droidtop.net.Verdict
import dev.droidtop.runtime.tasks.LaunchLedger
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Where the download policy ([DownloadPolicy]) meets the jobs (docs/SPEC.md, "Download rules"). Every download job
 * kind registered with `download = true` in [PluginJobsCenter] is held at its start ([PluginJobsCenter.admission])
 * and, while it runs, whenever the network, the clock, a game or the person's rules change: a job the policy will
 * not run now is paused with the reason as its status line, and resumes by itself when the verdict turns to go. A job
 * the person resumes themselves is left alone for its life ([PluginJobsCenter.Entry.policyOverride]), except by the
 * connection: no job is started past a network hold, the person allows the connection instead ([MeteredGrants]). A job
 * the person paused is never resumed by the policy. Nothing here polls fast: changes arrive from the system's network
 * callback and the settings, and a slow tick covers the clock.
 */
object DownloadGate {
    /** Job argument: the download's size in bytes when known. Absent or 0 reads as large. */
    const val ARG_BYTES = "bytes"

    /** Job argument: "1" for a download nobody just asked for (an automatic update, a font pack). */
    const val ARG_AUTOMATIC = "automatic"

    private const val TICK_MS = 60_000L

    /**
     * A launch older than this is not counted as "a game is running": Android gives droidtop no liveness for another
     * app, so a game closed from inside itself would otherwise hold automatic downloads for as long as the ledger
     * remembers it (docs/SPEC.md, "The Game tab").
     */
    const val SESSION_MAX_MS = 4L * 60 * 60 * 1000

    sealed interface Move {
        val jobId: String

        data class Hold(override val jobId: String, val reason: String) : Move

        data class Release(override val jobId: String) : Move
    }

    fun factsOf(args: Map<String, String>?): JobFacts =
        JobFacts(args?.get(ARG_BYTES)?.toLongOrNull() ?: 0L, args?.get(ARG_AUTOMATIC) == "1")

    /** A download's category ([DownloadPools.ARG_CATEGORY]); a job from before categories is a game's, as source downloads were. */
    fun categoryOf(args: Map<String, String>?): DownloadCategory =
        DownloadCategory.fromKey(args?.get(DownloadPools.ARG_CATEGORY)) ?: DownloadCategory.GAMES

    /**
     * What to do with [entries] under [settings] and [conditions]: the download policy first, then the pools
     * ([DownloadPools], docs/SPEC.md 12a). A running download the policy holds is held; one the policy lets run takes a
     * slot of its category's pool, or waits queued when the pool is full, and a queued one starts when a slot frees, in
     * the order they were started. A download the person resumed themselves is left alone and keeps its slot; one the
     * person paused, or that finished, is left alone and takes none. Pure.
     */
    fun plan(
        entries: List<PluginJobsCenter.Entry>,
        facts: (PluginJobsCenter.Entry) -> JobFacts,
        settings: DownloadSettings,
        conditions: Conditions,
        category: (PluginJobsCenter.Entry) -> DownloadCategory = { DownloadCategory.GAMES },
    ): List<Move> {
        val moves = ArrayList<Move>()
        val pooled = ArrayList<DownloadPools.Entry>()
        for (entry in entries) {
            if (entry.done || !PluginJobsCenter.isDownloadKind(entry.nativeKind)) continue
            if (entry.paused && entry.hold == null) continue
            val verdict = DownloadPolicy.decide(settings, conditions, facts(entry))
            // The person's word wins over the update window and a running game, never over the connection.
            if (entry.policyOverride && !(verdict is Verdict.Hold && DownloadPolicy.isNetworkHold(verdict.reason))) {
                if (!entry.paused) pooled += DownloadPools.Entry(entry.jobId, category(entry), entry.startedAtMs, running = true, pinned = true)
                continue
            }
            if (verdict is Verdict.Hold) {
                if (!entry.paused || entry.hold != verdict.reason) moves += Move.Hold(entry.jobId, verdict.reason)
                continue
            }
            pooled += DownloadPools.Entry(entry.jobId, category(entry), entry.startedAtMs, running = !entry.paused)
        }
        val holds = entries.associate { it.jobId to it.hold }
        DownloadPools.plan(pooled, { DownloadPools.limit(settings, it, conditions) }, { holds[it] }).forEach { move ->
            moves += when (move) {
                is DownloadPools.Move.Queue -> Move.Hold(move.id, move.line)
                is DownloadPools.Move.Start -> Move.Release(move.id)
            }
        }
        return moves
    }

    /**
     * The line to hold a new download with, or null to start it: the policy's, else its pool's when the pool is full
     * of downloads already running.
     */
    fun admit(
        args: Map<String, String>,
        entries: List<PluginJobsCenter.Entry>,
        categoryOfEntry: (PluginJobsCenter.Entry) -> DownloadCategory,
        settings: DownloadSettings,
        conditions: Conditions,
    ): String? {
        (DownloadPolicy.decide(settings, conditions, factsOf(args)) as? Verdict.Hold)?.let { return it.reason }
        val category = categoryOf(args)
        val limit = DownloadPools.limit(settings, category, conditions)
        val running = entries.count { !it.done && !it.paused && PluginJobsCenter.isDownloadKind(it.nativeKind) && categoryOfEntry(it) == category }
        return if (running >= limit) DownloadPools.queuedLine(category, limit) else null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts the gate: loads the person's rules, installs the admission check and watches for changes. [gameRunning]
     * says whether a game is believed to be running. Once, at process start of the main process.
     */
    fun install(context: Context, gameRunning: () -> Boolean = ::ledgerSaysPlaying) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            DownloadPolicy.load(app)
            MeteredGrants.load(app)
            val categoryOfEntry = { e: PluginJobsCenter.Entry -> categoryOf(PluginJobsCenter.argsOf(e.jobId)) }
            PluginJobsCenter.admission = { _, args ->
                admit(args, PluginJobsCenter.entries().value, categoryOfEntry, DownloadPolicy.settings.value, conditions(app, gameRunning))
            }
            merge(networkChanges(app), DownloadPolicy.settings.map { }, MeteredGrants.always.map { }, MeteredGrants.once.map { }, runningDownloads(), ticks()).conflate().collect {
                val entries = PluginJobsCenter.entries().value
                plan(entries, { factsOf(PluginJobsCenter.argsOf(it.jobId)) }, DownloadPolicy.settings.value, conditions(app, gameRunning), categoryOfEntry).forEach { move ->
                    when (move) {
                        is Move.Hold -> PluginJobsCenter.holdByPolicy(move.jobId, move.reason)
                        is Move.Release -> PluginJobsCenter.releaseHold(move.jobId)
                    }
                }
            }
        }
    }

    private fun ledgerSaysPlaying(): Boolean =
        LaunchLedger.last?.let { System.currentTimeMillis() - it.atMs < SESSION_MAX_MS } ?: false

    private fun conditions(app: Context, gameRunning: () -> Boolean): Conditions {
        val now = Calendar.getInstance()
        val saver = runCatching { app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
        return Conditions(
            DownloadPolicy.networkNow(app), now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), gameRunning(), saver,
            meteredAllowed = MeteredGrants.allows(MeteredGrants.connectionNow(app)),
        )
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    /** Fires when the set of live download jobs changes (a restored job, a new one), not on every progress step. */
    private fun runningDownloads(): Flow<Unit> = PluginJobsCenter.entries()
        .map { list -> list.filter { !it.done && PluginJobsCenter.isDownloadKind(it.nativeKind) }.map { it.jobId to it.paused }.toSet() }
        .distinctUntilChanged()
        .map { }

    private fun networkChanges(app: Context): Flow<Unit> = callbackFlow {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }

            override fun onLost(network: Network) { trySend(Unit) }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: android.net.NetworkCapabilities) { trySend(Unit) }
        }
        val registered = runCatching { connectivity?.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) } }
    }
}
