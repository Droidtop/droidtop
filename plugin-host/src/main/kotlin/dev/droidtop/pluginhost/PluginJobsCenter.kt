package dev.droidtop.pluginhost

import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The one shared "a plugin job is running, here is its progress" registry
 * (docs/SPEC.md 12a "Jobs"). Before this, [dev.droidtop.library.integrations.AcquireContentSources]
 * held its own private [PluginCrashPolicy]/callback pair per download and
 * had no way to be seen from anywhere but the screen that started it --
 * "one mechanism per job" means a plugin job started from ANY surface
 * (a Get-games download, an app_status action, a [PluginEvent] reacting
 * on its own) shows up in the SAME place: a live [entries] list any
 * screen can render (the dedicated Jobs screen, or a progress row shown
 * in the context that started the job), and [start]'s own optional
 * `onProgress`/`onComplete` let a starting screen keep its own inline
 * "show status right here" behavior without a second tracking mechanism
 * underneath it.
 *
 * **Routing (rewritten 2026-09-27, dq-pluginui-01).** [start] creates a
 * dedicated [PluginJobRunner] (the real [PluginCrashPolicy] in
 * production) per job, but [PluginRuntimeService] now broadcasts every
 * job's progress/completion to EVERY currently registered connection --
 * fixing a callback-theft bug meant no connection can assume an incoming
 * event is its own anymore. [jobId] IS the id [start] hands to the
 * runner AND the [Entry.jobId] key this object tracks under -- one id,
 * chosen by THIS object before the runner is ever called (never by
 * :pluginhost, which used to generate and return one, the other half of
 * the original bug: a fast job's own completion could arrive before that
 * return trip finished, so the id being compared against did not exist
 * yet). Every `onJobProgress`/`onJobComplete` closure [start] builds
 * filters on `eventJobId == jobId` -- a plain `val` comparison, no race,
 * since [jobId] is fixed before any call that could produce a callback.
 *
 * Process-lifetime only, deliberately -- a job is already bounded by
 * :pluginhost's own process lifetime (docs/SPEC.md 12a: a job's plugin
 * process dying takes the job with it), so nothing here claims to
 * survive :app's own process death either. A finished job's [Entry]
 * stays in [entries] until the next call touches this object (another
 * job starting or finishing), then anything older than
 * [FINISHED_RETENTION_MS] is dropped -- no background timer for a
 * launcher-scale job count; a Jobs screen already re-reads on every
 * (re)entry per [dev.droidtop.library.settings.CatalogScreen]'s own
 * contract, which is enough to keep this feeling live.
 */
object PluginJobsCenter {
    private const val FINISHED_RETENTION_MS = 15_000L

    data class Entry(
        val jobId: String,
        val pluginId: String,
        val pluginLabel: String,
        val capability: PluginCapability,
        val title: String,
        val startedAtMs: Long,
        val percent: Int = -1,
        val statusLine: String = "Starting…",
        val done: Boolean = false,
        val result: PluginResult? = null,
    )

    /**
     * How a job's own [PluginJobRunner] is built. Production code never
     * touches this -- the default builds a real [PluginCrashPolicy].
     * `PluginJobsCenterTest.kt` swaps it for a fake that lets a test
     * control exactly when progress/completion callbacks fire (including
     * "before [start] itself has returned", the precise ordering
     * dq-pluginui-01 found broken) without any real Android Service,
     * RemoteCallbackList, or binder connection. [context] is nullable
     * only so a test can omit one entirely; every real call site already
     * passes a real [Context].
     */
    internal var runnerFactory: (
        context: Context?,
        onJobProgress: (pluginId: String, jobId: String, percent: Int, statusLine: String) -> Unit,
        onJobComplete: (pluginId: String, jobId: String, result: PluginResult) -> Unit,
    ) -> PluginJobRunner = { context, onProgress, onComplete ->
        PluginCrashPolicy(
            requireNotNull(context) { "a real Context is required outside tests" }.applicationContext,
            onJobProgress = onProgress,
            onJobComplete = onComplete,
        )
    }

    private val state = MutableStateFlow<List<Entry>>(emptyList())
    private val deferreds = ConcurrentHashMap<String, CompletableDeferred<PluginResult>>()
    private val runners = ConcurrentHashMap<String, PluginJobRunner>()

    /** Live, ordered (newest first) view of every tracked job, running or recently finished -- what the Jobs screen and any inline progress row both read. */
    fun entries(): StateFlow<List<Entry>> = state

    fun find(jobId: String): Entry? = state.value.firstOrNull { it.jobId == jobId }

    /**
     * Starts [capability]'s job for [record] and tracks it under the id
     * this function returns (also the id it hands to the runner --
     * [Entry.jobId] IS the real job id now, see this object's own doc
     * comment for why unifying the two removed the original race).
     * Returns null when the plugin refused outright (not loaded, or no
     * [DroidtopPlugin.startJob] override) -- same "null means refused"
     * shape [PluginCrashPolicy.startJob] already has. [onProgress]/
     * [onComplete] are this call's OWN inline callbacks (e.g. an
     * [dev.droidtop.library.settings.AsyncActionItem]'s `onStatus`),
     * fired in addition to the shared [entries] update, not instead of
     * it.
     */
    suspend fun start(
        context: Context?,
        record: PluginRecord,
        capability: PluginCapability,
        args: Map<String, String>,
        title: String,
        onProgress: (percent: Int, statusLine: String) -> Unit = { _, _ -> },
        onComplete: (PluginResult) -> Unit = {},
    ): String? {
        val jobId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<PluginResult>()
        // Every registered connection now hears every job's events
        // (PluginRuntimeService.kt's own doc comment on registerCallback
        // has the full story) -- these closures are what turns that
        // broadcast back into "only MY job", by a plain equality check
        // against [jobId], fixed above before any call that could
        // produce an event.
        val runner = runnerFactory(
            context,
            { _, eventJobId, percent, statusLine ->
                if (eventJobId == jobId) {
                    update(jobId) { it.copy(percent = percent, statusLine = statusLine) }
                    onProgress(percent, statusLine)
                }
            },
            { _, eventJobId, result ->
                if (eventJobId == jobId) {
                    update(jobId) { it.copy(done = true, result = result, statusLine = if (result.ok) "Done" else (result.error ?: "Failed"), percent = 100) }
                    if (!deferred.isCompleted) deferred.complete(result)
                    onComplete(result)
                    runners.remove(jobId)?.shutdown()
                    prune()
                }
            },
        )
        runners[jobId] = runner
        deferreds[jobId] = deferred
        // Added to [state] BEFORE runner.startJob() is even called: a
        // fast job's own completion callback can arrive while that call
        // is still technically "returning" on this thread (:pluginhost
        // dispatches to its own job executor the instant the AIDL call
        // arrives on its side, not after it returns) -- an Entry that
        // doesn't exist in [state] yet would make [update] below a
        // silent no-op. An Entry that turns out to have no real job
        // behind it (startJob refused) is removed again just below.
        state.update { current ->
            listOf(
                Entry(
                    jobId = jobId,
                    pluginId = record.manifest.id,
                    pluginLabel = record.manifest.label,
                    capability = capability,
                    title = title,
                    startedAtMs = System.currentTimeMillis(),
                ),
            ) + current
        }
        val accepted = runner.startJob(record, capability, args, jobId)
        if (!accepted) {
            runner.shutdown()
            runners.remove(jobId)
            deferreds.remove(jobId)
            state.update { current -> current.filter { it.jobId != jobId } }
            return null
        }
        return jobId
    }

    /** Awaits [jobId]'s completion -- for a caller (a Jobs-screen row re-attaching to a job it didn't itself start) that needs the final result, not just the live [entries] view. Returns null when [jobId] is unknown. */
    suspend fun await(jobId: String): PluginResult? = deferreds[jobId]?.await()

    /** Best-effort cancel (docs/SPEC.md 12a: [DroidtopPlugin.cancelJob] itself is best-effort -- a plugin that ignores it keeps running until it finishes on its own). No-op for an unknown or already-finished [jobId]. */
    fun cancel(jobId: String) {
        val entry = find(jobId) ?: return
        runners[jobId]?.cancelJob(entry.pluginId, jobId)
    }

    private fun update(jobId: String, transform: (Entry) -> Entry) {
        state.update { current -> current.map { if (it.jobId == jobId) transform(it) else it } }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - FINISHED_RETENTION_MS
        state.update { current -> current.filter { !it.done || it.startedAtMs > cutoff } }
    }
}
