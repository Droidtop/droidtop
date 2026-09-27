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

    private val state = MutableStateFlow<List<Entry>>(emptyList())
    private val deferreds = ConcurrentHashMap<String, CompletableDeferred<PluginResult>>()
    private val policies = ConcurrentHashMap<String, PluginCrashPolicy>()

    /** Live, ordered (newest first) view of every tracked job, running or recently finished -- what the Jobs screen and any inline progress row both read. */
    fun entries(): StateFlow<List<Entry>> = state

    fun find(jobId: String): Entry? = state.value.firstOrNull { it.jobId == jobId }

    /**
     * Starts [capability]'s job for [record] and tracks it here under a
     * tracking id this function returns (deliberately NOT the same
     * string [PluginRuntimeService.startJob] generated -- that id is an
     * implementation detail of one plugin connection; this one is what
     * every other function on this object, including [cancel], takes).
     * Returns null when the plugin refused outright (not loaded, or no
     * [DroidtopPlugin.startJob] override) -- same "null means refused"
     * shape [PluginCrashPolicy.startJob] already has. [onProgress]/
     * [onComplete] are this call's OWN inline callbacks (e.g. an
     * [dev.droidtop.library.settings.AsyncActionItem]'s `onStatus`),
     * fired in addition to the shared [entries] update, not instead of
     * it.
     */
    suspend fun start(
        context: Context,
        record: PluginRecord,
        capability: PluginCapability,
        args: Map<String, String>,
        title: String,
        onProgress: (percent: Int, statusLine: String) -> Unit = { _, _ -> },
        onComplete: (PluginResult) -> Unit = {},
    ): String? {
        val trackingKey = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<PluginResult>()
        var realJobId: String? = null
        val policy = PluginCrashPolicy(
            context.applicationContext,
            onJobProgress = { _, jobId, percent, statusLine ->
                if (jobId == realJobId) {
                    update(trackingKey) { it.copy(percent = percent, statusLine = statusLine) }
                    onProgress(percent, statusLine)
                }
            },
            onJobComplete = { _, jobId, result ->
                if (jobId == realJobId) {
                    update(trackingKey) { it.copy(done = true, result = result, statusLine = if (result.ok) "Done" else (result.error ?: "Failed"), percent = 100) }
                    if (!deferred.isCompleted) deferred.complete(result)
                    onComplete(result)
                    policies.remove(trackingKey)?.shutdown()
                    prune()
                }
            },
        )
        policies[trackingKey] = policy
        val jobId = policy.startJob(record, capability, args)
        if (jobId == null) {
            policy.shutdown()
            policies.remove(trackingKey)
            return null
        }
        realJobId = jobId
        realJobIds[trackingKey] = ReferenceKey(record.manifest.id, jobId)
        deferreds[trackingKey] = deferred
        state.update { current ->
            listOf(
                Entry(
                    jobId = trackingKey,
                    pluginId = record.manifest.id,
                    pluginLabel = record.manifest.label,
                    capability = capability,
                    title = title,
                    startedAtMs = System.currentTimeMillis(),
                ),
            ) + current
        }
        return trackingKey
    }

    /** Awaits [trackingKey]'s completion -- for a caller (a Jobs-screen row re-attaching to a job it didn't itself start) that needs the final result, not just the live [entries] view. Returns null when [trackingKey] is unknown. */
    suspend fun await(trackingKey: String): PluginResult? = deferreds[trackingKey]?.await()

    /** Best-effort cancel (docs/SPEC.md 12a: [DroidtopPlugin.cancelJob] itself is best-effort -- a plugin that ignores it keeps running until it finishes on its own). No-op for an unknown or already-finished [trackingKey]. */
    fun cancel(trackingKey: String) {
        val ref = realJobIds[trackingKey] ?: return
        policies[trackingKey]?.cancelJob(ref.pluginId, ref.jobId)
    }

    private data class ReferenceKey(val pluginId: String, val jobId: String)
    private val realJobIds = ConcurrentHashMap<String, ReferenceKey>()

    private fun update(trackingKey: String, transform: (Entry) -> Entry) {
        state.update { current -> current.map { if (it.jobId == trackingKey) transform(it) else it } }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - FINISHED_RETENTION_MS
        state.update { current -> current.filter { !it.done || it.startedAtMs > cutoff } }
    }
}
