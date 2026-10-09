package dev.droidtop.pluginhost

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dev.droidtop.net.Conditions
import dev.droidtop.net.DownloadPolicy
import dev.droidtop.net.DownloadSettings
import dev.droidtop.net.JobFacts
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
 * the person resumes themselves is left alone for its life ([PluginJobsCenter.Entry.policyOverride]); a job the
 * person paused is never resumed by the policy. Nothing here polls fast: changes arrive from the system's network
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

    /**
     * What to do with [entries] under [settings] and [conditions]. A running download that gets a hold is held; a
     * held one whose verdict is go is released; a held one that is still held takes the new reason; a job the person
     * paused, resumed or finished is left alone. Pure.
     */
    fun plan(
        entries: List<PluginJobsCenter.Entry>,
        facts: (PluginJobsCenter.Entry) -> JobFacts,
        settings: DownloadSettings,
        conditions: Conditions,
    ): List<Move> = entries.mapNotNull { entry ->
        if (entry.done || entry.policyOverride || !PluginJobsCenter.isDownloadKind(entry.nativeKind)) return@mapNotNull null
        // A download that cannot pause (a single file Android's download service has) is held only before it starts;
        // once queued, Android's own network rule, set from the policy at enqueue, makes it wait for Wi-Fi.
        if (!entry.paused && !entry.pausable) return@mapNotNull null
        val verdict = DownloadPolicy.decide(settings, conditions, facts(entry))
        when {
            !entry.paused && verdict is Verdict.Hold -> Move.Hold(entry.jobId, verdict.reason)
            entry.paused && entry.hold != null && verdict is Verdict.Hold -> Move.Hold(entry.jobId, verdict.reason).takeIf { entry.hold != verdict.reason }
            entry.paused && entry.hold != null && verdict == Verdict.Go -> Move.Release(entry.jobId)
            else -> null
        }
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
            PluginJobsCenter.admission = { _, args ->
                (DownloadPolicy.decide(DownloadPolicy.settings.value, conditions(app, gameRunning), factsOf(args)) as? Verdict.Hold)?.reason
            }
            merge(networkChanges(app), DownloadPolicy.settings.map { }, runningDownloads(), ticks()).conflate().collect {
                val entries = PluginJobsCenter.entries().value
                plan(entries, { factsOf(PluginJobsCenter.argsOf(it.jobId)) }, DownloadPolicy.settings.value, conditions(app, gameRunning)).forEach { move ->
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
        return Conditions(DownloadPolicy.networkNow(app), now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), gameRunning())
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
