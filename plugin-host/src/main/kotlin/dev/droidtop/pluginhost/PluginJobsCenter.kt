package dev.droidtop.pluginhost

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

private val Context.pluginJobsStore by preferencesDataStore(name = "plugin_jobs")

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
        /** Null for a job a provider runs for another plugin (docs/plugin-api.md 2.4): those have no contract 1 capability. */
        val capability: PluginCapability?,
        val title: String,
        val startedAtMs: Long,
        val percent: Int = -1,
        val statusLine: String = "Starting…",
        val done: Boolean = false,
        val result: PluginResult? = null,
        /** The provider plugin's label when this job is brokered: the job is owned by the caller and shown "via" the provider. */
        val via: String? = null,
        val kind: String = "plugin",
        val pausable: Boolean = false,
        val resumable: Boolean = false,
        val resumePayload: String? = null,
        val paused: Boolean = false,
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
        onJobProgress: (pluginId: String, jobId: String, percent: Int, statusLine: String, resumePayload: String?) -> Unit,
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
    private data class ResumeSpec(val record: PluginRecord?, val capability: PluginCapability?, val args: Map<String, String>)
    private val resumeSpecs = ConcurrentHashMap<String, ResumeSpec>()
    private val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistenceContext: Context? = null
    private val persistedKey = stringPreferencesKey("entries")

    /** Connects the registry to app storage and restores interrupted resumable jobs as paused. */
    fun attach(context: Context) {
        persistenceContext = context.applicationContext
        persistenceScope.launch {
            val prefs = context.applicationContext.pluginJobsStore.data.first()
            val restored = decodeEntries(prefs[persistedKey].orEmpty())
            state.update { current -> (restored.filter { old -> current.none { it.jobId == old.jobId } } + current).distinctBy { it.jobId } }
        }
    }

    private fun persist() {
        val context = persistenceContext ?: return
        val snapshot = encodeEntries(state.value)
        persistenceScope.launch { context.pluginJobsStore.edit { it[persistedKey] = snapshot } }
    }

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
        kind: String = "plugin",
        pausable: Boolean = false,
        resumable: Boolean = false,
        resumePayload: String? = null,
    ): String? {
        context?.let(::attach)
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
            { _, eventJobId, percent, statusLine, checkpoint ->
                if (eventJobId == jobId) {
                    update(jobId) { it.copy(percent = percent, statusLine = statusLine, resumePayload = checkpoint ?: it.resumePayload) }
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
        if (pausable && resumable && runner.supportsCheckpointResume) resumeSpecs[jobId] = ResumeSpec(record, capability, args)
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
                    kind = kind,
                    pausable = pausable && runner.supportsCheckpointResume,
                    resumable = resumable && runner.supportsCheckpointResume,
                    resumePayload = resumePayload,
                ),
            ) + current
        }
        val accepted = runner.startJob(record, capability, args, jobId)
        if (!accepted) {
            runner.shutdown()
            runners.remove(jobId)
            resumeSpecs.remove(jobId)
            deferreds.remove(jobId)
            state.update { current -> current.filter { it.jobId != jobId } }
            return null
        }
        persist()
        return jobId
    }

    private val brokeredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val brokeredJobs = ConcurrentHashMap<String, Job>()
    private val brokeredReplies = ConcurrentHashMap<String, PluginReply>()

    /**
     * Tracks a provider's job op for its CALLER (docs/plugin-api.md 2.4): the
     * entry belongs to [callerId] and names [providerLabel] as "via". [block]
     * runs the provider's `handle` with no per-call bound; the job ends with
     * its reply, which [brokeredReply] returns to the caller that started it.
     */
    fun startBrokered(callerId: String, callerLabel: String, providerLabel: String, title: String, block: suspend () -> PluginReply): String {
        val jobId = UUID.randomUUID().toString()
        state.update { current ->
            listOf(Entry(jobId, callerId, callerLabel, null, title, System.currentTimeMillis(), via = providerLabel)) + current
        }
        brokeredJobs[jobId] = brokeredScope.launch {
            val reply = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                PluginReply.error(PluginErrorCode.CANCELLED, "cancelled")
            } catch (t: Throwable) {
                PluginReply.error(PluginErrorCode.FAILED, t.message ?: "job failed")
            }
            brokeredReplies[jobId] = reply
            val result = if (reply.ok) {
                PluginResult.success(buildMap { reply.data.keys().forEach { put(it, reply.data.optString(it)) } })
            } else {
                PluginResult.failure(reply.message ?: "failed")
            }
            update(jobId) { it.copy(done = true, result = result, statusLine = if (result.ok) "Done" else (result.error ?: "Failed"), percent = 100) }
            brokeredJobs.remove(jobId)
            prune()
        }
        return jobId
    }

    /** The reply of a brokered job, or null while it still runs or when [jobId] is not one of [callerId]'s. */
    fun brokeredReply(callerId: String, jobId: String): PluginReply? =
        if (find(jobId)?.pluginId == callerId) brokeredReplies[jobId] else null

    /** Awaits [jobId]'s completion -- for a caller (a Jobs-screen row re-attaching to a job it didn't itself start) that needs the final result, not just the live [entries] view. Returns null when [jobId] is unknown. */
    suspend fun await(jobId: String): PluginResult? = deferreds[jobId]?.await()

    /** Best-effort cancel (docs/SPEC.md 12a: [DroidtopPlugin.cancelJob] itself is best-effort -- a plugin that ignores it keeps running until it finishes on its own). No-op for an unknown or already-finished [jobId]. */
    fun cancel(jobId: String) {
        val entry = find(jobId) ?: return
        brokeredJobs[jobId]?.cancel()
        runners[jobId]?.cancelJob(entry.pluginId, jobId)
        if (entry.resumable) {
            state.update { list -> list.filterNot { it.jobId == jobId } }
            resumeSpecs.remove(jobId)
            persist()
        }
    }

    /** Pauses a declared pausable job and stores its caller-supplied opaque checkpoint. */
    fun pause(jobId: String, resumePayload: String? = find(jobId)?.resumePayload): Boolean {
        val entry = find(jobId) ?: return false
        val checkpoint = resumePayload ?: return false
        if (!entry.pausable || !entry.resumable || entry.done || entry.paused) return false
        val runner = runners[jobId] ?: return false
        if (!runner.pauseJob(entry.pluginId, jobId, checkpoint)) return false
        var changed = false
        update(jobId) { current -> if (!current.done) { changed = true; current.copy(paused = true, resumePayload = checkpoint, statusLine = "Paused") } else current }
        if (changed) persist()
        return changed
    }

    /** Marks a restored or paused job ready for its owner to resume. */
    fun resume(jobId: String): Boolean {
        val entry = find(jobId) ?: return false
        val spec = resumeSpecs[jobId] ?: return false
        val checkpoint = entry.resumePayload ?: return false
        if (!entry.paused || entry.done) return false
        update(jobId) { it.copy(paused = false, statusLine = "Resuming…") }
        resumeScope.launch {
            val context = persistenceContext
            val record = spec.record ?: context?.let { PluginStore.installed(it).firstOrNull { r -> r.manifest.id == entry.pluginId } }
            val capability = spec.capability ?: entry.capability
            if (record == null || capability == null) {
                update(jobId) { it.copy(paused = true, statusLine = "Plugin is unavailable") }
                return@launch
            }
            val runner = runners[jobId] ?: runnerFactory(context, { _, _, _, _, payload ->
                if (payload != null) update(jobId) { it.copy(resumePayload = payload) }
            }, { _, _, result -> update(jobId) { it.copy(done = true, paused = false, result = result, statusLine = if (result.ok) "Done" else (result.error ?: "Failed")) } }).also { runners[jobId] = it }
            val accepted = runner.resumeJob(record, capability, spec.args, jobId, checkpoint)
            if (!accepted) update(jobId) { it.copy(paused = true, statusLine = "Resume failed") }
        }
        var changed = false
        update(jobId) { current -> changed = true; current }
        if (changed) persist()
        return changed
    }

    private fun update(jobId: String, transform: (Entry) -> Entry) {
        state.update { current -> current.map { if (it.jobId == jobId) transform(it) else it } }
        persist()
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - FINISHED_RETENTION_MS
        state.update { current -> current.filter { !it.done || it.startedAtMs > cutoff } }
        persist()
    }

    internal fun encodeEntries(entries: List<Entry>): String = JSONArray().apply {
        entries.filter { it.resumable && !it.done }.forEach { e ->
            val spec = resumeSpecs[e.jobId]
            put(JSONObject().put("id", e.jobId).put("plugin", e.pluginId).put("label", e.pluginLabel).put("title", e.title)
                .put("started", e.startedAtMs).put("percent", e.percent).put("status", e.statusLine).put("kind", e.kind)
                .put("pausable", e.pausable).put("resumable", e.resumable).put("payload", e.resumePayload).put("paused", true)
                .put("capability", spec?.capability?.id ?: e.capability?.id).put("args", JSONObject().apply { spec?.args?.forEach { (k, v) -> put(k, v) } }))
        }
    }.toString()

    internal fun decodeEntries(encoded: String): List<Entry> = try {
        val array = JSONArray(encoded)
        (0 until array.length()).map { i -> array.getJSONObject(i).let { o -> Entry(
            jobId = o.getString("id"), pluginId = o.getString("plugin"), pluginLabel = o.getString("label"), capability = null,
            title = o.getString("title"), startedAtMs = o.getLong("started"), percent = o.optInt("percent", -1),
            statusLine = "Paused", kind = o.optString("kind", "plugin"), pausable = o.optBoolean("pausable"),
            resumable = o.optBoolean("resumable"), resumePayload = o.optString("payload").takeIf { it.isNotEmpty() && it != "null" }, paused = true,
        ).also { entry ->
            val cap = PluginCapability.fromId(o.optString("capability"))
            val args = buildMap { o.optJSONObject("args")?.let { a -> a.keys().forEach { put(it, a.optString(it)) } } }
            if (entry.pausable && entry.resumable && cap != null) resumeSpecs[entry.jobId] = ResumeSpec(null, cap, args)
        } } }
    } catch (_: Exception) { emptyList() }
}
