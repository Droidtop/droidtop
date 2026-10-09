package dev.droidtop.pluginhost

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
    private const val NATIVE_OWNER_ID = "droidtop"
    private const val NATIVE_OWNER_LABEL = "Library"

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
        /** Set for a job droidtop runs itself ([registerNative]); the key of its [NativeJobRunner]. Null for a plugin job. */
        val nativeKind: String? = null,
        /** Why the download policy is holding this job ("Waiting for Wi-Fi"); null when nothing is. A held job is paused. */
        val hold: String? = null,
        /** The person resumed a held job themselves: the policy leaves it alone for the rest of its life. */
        val policyOverride: Boolean = false,
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
    private data class NativeSpec(val kind: String, val args: Map<String, String>)
    private val nativeRunners = ConcurrentHashMap<String, NativeJobRunner>()
    private val nativeSpecs = ConcurrentHashMap<String, NativeSpec>()
    private val nativeJobs = ConcurrentHashMap<String, Job>()
    private val nativeCompletions = ConcurrentHashMap<String, (PluginResult) -> Unit>()
    private val nativeCancelHooks = ConcurrentHashMap<String, (args: Map<String, String>, checkpoint: String?) -> Unit>()
    private val nativeReattachKinds = ConcurrentHashMap.newKeySet<String>()
    private val downloadKinds = ConcurrentHashMap.newKeySet<String>()
    private val nativeScope = CoroutineScope(SupervisorJob())

    /**
     * The download policy's front door (docs/SPEC.md, "Download rules"), installed by [DownloadGate]: the line to
     * hold a new download job with, or null to let it start. Only jobs of a [registerNative] `download` kind ask.
     */
    @Volatile
    var admission: (kind: String, args: Map<String, String>) -> String? = { _, _ -> null }

    /** Where a native job's coroutine runs; a test swaps it. */
    internal var nativeDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistenceContext: Context? = null
    private val persistedKey = stringPreferencesKey("entries")

    /** Connects the registry to app storage and restores interrupted resumable jobs as paused. */
    fun attach(context: Context) {
        persistenceContext = context.applicationContext
        persistenceScope.launch {
            val prefs = context.applicationContext.pluginJobsStore.data.first()
            restore(decodeEntries(prefs[persistedKey].orEmpty()))
        }
    }

    /** Adds interrupted resumable jobs, as paused, to the live list; a job already tracked is left as it is. */
    internal fun restore(restored: List<Entry>) {
        state.update { current -> (restored.filter { old -> current.none { it.jobId == old.jobId } } + current).distinctBy { it.jobId } }
        // A job whose own system service kept going while the process was dead (a DownloadManager
        // download) is not left paused for the person to resume: it re-attaches to its checkpoint.
        restored.filter { it.nativeKind in nativeReattachKinds && it.paused && !it.done && nativeSpecs.containsKey(it.jobId) }.forEach { entry ->
            // A download the policy was holding stays held while the policy still says wait.
            val kind = entry.nativeKind
            if (entry.hold != null && kind != null && kind in downloadKinds && admission(kind, nativeSpecs[entry.jobId]?.args.orEmpty()) != null) return@forEach
            update(entry.jobId) { it.copy(paused = false, hold = null, statusLine = "Reconnecting…") }
            launchNative(entry.jobId, entry.resumePayload)
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
        /** The person started it (a page's button, a context action): it counts as their call while it runs ([PluginBrokers.jobRunning]). */
        userInitiated: Boolean = false,
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
                    PluginBrokers.jobEnded(jobId)
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
        if (userInitiated) PluginBrokers.jobRunning(record.manifest.id, jobId)
        val accepted = runner.startJob(record, capability, args, jobId)
        if (!accepted) {
            PluginBrokers.jobEnded(jobId)
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

    /**
     * Registers how a job droidtop runs itself of [kind] is run (SPEC 12a "Jobs"); called once at
     * process start, before [attach] restores anything. [onCancel] runs (off the main thread) when
     * the person cancels a job of this kind, running or paused or restored, with its arguments and
     * last checkpoint: the place to release whatever the job holds outside this process. With
     * [reattachOnRestart] a restored job of this kind is run again from its checkpoint at once
     * instead of waiting paused for Resume. A [download] kind moves bytes over the network, so the download policy
     * ([DownloadGate]) may hold its jobs (the job's `bytes` and `automatic` arguments tell the policy what it is).
     */
    fun registerNative(
        kind: String,
        onCancel: ((args: Map<String, String>, checkpoint: String?) -> Unit)? = null,
        reattachOnRestart: Boolean = false,
        download: Boolean = false,
        runner: NativeJobRunner,
    ) {
        if (download) downloadKinds.add(kind) else downloadKinds.remove(kind)
        nativeRunners[kind] = runner
        if (onCancel != null) nativeCancelHooks[kind] = onCancel else nativeCancelHooks.remove(kind)
        if (reattachOnRestart) nativeReattachKinds.add(kind) else nativeReattachKinds.remove(kind)
    }

    /**
     * Starts a native job of [kind] and tracks it like any other: pausable,
     * resumable from its last checkpoint, persisted across a restart. A job of
     * the same [kind] and [args] that is already running or paused is the same
     * job: it is returned (and resumed if paused) instead of started twice.
     * Null when no runner is registered for [kind]. [onComplete] is in-process
     * only; a job restored after a restart does not call it.
     */
    fun startNative(
        context: Context?,
        kind: String,
        title: String,
        args: Map<String, String> = emptyMap(),
        onComplete: (PluginResult) -> Unit = {},
        owner: String = NATIVE_OWNER_LABEL,
        pausable: Boolean = true,
    ): String? {
        context?.let(::attach)
        if (!nativeRunners.containsKey(kind)) return null
        nativeSpecs.entries.firstOrNull { (id, spec) -> spec.kind == kind && spec.args == args && find(id)?.done == false }?.let { (id, _) ->
            nativeCompletions[id] = onComplete
            if (find(id)?.paused == true) resume(id)
            return id
        }
        val jobId = UUID.randomUUID().toString()
        nativeSpecs[jobId] = NativeSpec(kind, args)
        nativeCompletions[jobId] = onComplete
        state.update { current ->
            listOf(
                Entry(
                    jobId = jobId, pluginId = NATIVE_OWNER_ID, pluginLabel = owner, capability = null, title = title,
                    startedAtMs = System.currentTimeMillis(), kind = "native", pausable = pausable, resumable = true, nativeKind = kind,
                ),
            ) + current
        }
        persist()
        // The policy decides before a byte moves: a download that may not run now starts paused, saying why.
        if (kind in downloadKinds) {
            admission(kind, args)?.let { reason ->
                update(jobId) { it.copy(paused = true, hold = reason, statusLine = reason) }
                return jobId
            }
        }
        launchNative(jobId, null)
        return jobId
    }

    /** Whether jobs of [kind] are downloads the policy governs. */
    fun isDownloadKind(kind: String?): Boolean = kind != null && kind in downloadKinds

    /** A native job's arguments (its size and whether it is automatic ride in them), or null for any other job. */
    fun argsOf(jobId: String): Map<String, String>? = nativeSpecs[jobId]?.args

    /**
     * The download policy holds [jobId] for [reason]: a running job is paused (it resumes from what it has),
     * a job already held only changes its reason. A job the person paused is theirs and is left alone, as is one
     * that cannot pause. Returns whether the job is now held.
     */
    fun holdByPolicy(jobId: String, reason: String): Boolean {
        val entry = find(jobId) ?: return false
        if (entry.done) return false
        if (entry.paused) {
            if (entry.hold == null) return false
        } else if (!pause(jobId)) {
            return false
        }
        update(jobId) { it.copy(hold = reason, statusLine = reason) }
        return true
    }

    /** The policy lets a held job go: it resumes. A job paused by the person stays paused. */
    fun releaseHold(jobId: String): Boolean {
        val entry = find(jobId) ?: return false
        if (entry.hold == null || !entry.paused || entry.done) return false
        return resume(jobId, byPolicy = true)
    }

    private fun launchNative(jobId: String, checkpoint: String?) {
        val spec = nativeSpecs[jobId] ?: return
        val runner = nativeRunners[spec.kind]
        if (runner == null) {
            update(jobId) { it.copy(paused = true, statusLine = "Unavailable in this version") }
            return
        }
        val previous = nativeJobs[jobId]
        nativeJobs[jobId] = nativeScope.launch(nativeDispatcher) {
            // A paused run may still be reaching its next boundary; never two at once.
            previous?.join()
            try {
                val summary = runner(spec.args, checkpoint) { percent, status, newCheckpoint ->
                    // Progress that arrives after a pause belongs to the run being stopped.
                    if (find(jobId)?.paused == false) {
                        update(jobId) { it.copy(percent = percent, statusLine = status, resumePayload = newCheckpoint ?: it.resumePayload) }
                    }
                }
                finishNative(jobId, PluginResult.success(mapOf("summary" to summary)))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                finishNative(jobId, PluginResult.failure(t.message ?: "job failed"))
            }
        }
    }

    private fun finishNative(jobId: String, result: PluginResult) {
        update(jobId) {
            it.copy(
                done = true, result = result, percent = 100,
                statusLine = if (result.ok) result.values["summary"] ?: "Done" else (result.error ?: "Failed"),
            )
        }
        nativeCompletions.remove(jobId)?.invoke(result)
        nativeSpecs.remove(jobId)
        nativeJobs.remove(jobId)
        prune()
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
    fun startBrokered(callerId: String, callerLabel: String, providerLabel: String, title: String, block: suspend (jobId: String) -> PluginReply): String {
        val jobId = UUID.randomUUID().toString()
        state.update { current ->
            listOf(Entry(jobId, callerId, callerLabel, null, title, System.currentTimeMillis(), via = providerLabel)) + current
        }
        brokeredJobs[jobId] = brokeredScope.launch {
            val reply = try {
                block(jobId)
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

    /** How far a brokered job is, for one that can tell (a host download, docs/plugin-api.md 3 D2); [percent] is -1 when it cannot. */
    fun progressBrokered(jobId: String, percent: Int, statusLine: String) {
        update(jobId) { it.copy(percent = percent, statusLine = statusLine) }
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
        nativeJobs.remove(jobId)?.cancel()
        nativeSpecs.remove(jobId)?.let { spec ->
            nativeCancelHooks[spec.kind]?.let { hook ->
                val checkpoint = entry.resumePayload
                resumeScope.launch { runCatching { hook(spec.args, checkpoint) } }
            }
        }
        nativeCompletions.remove(jobId)
        if (entry.resumable) {
            state.update { list -> list.filterNot { it.jobId == jobId } }
            resumeSpecs.remove(jobId)
            persist()
        }
    }

    /** Pauses a declared pausable job and stores its caller-supplied opaque checkpoint. */
    fun pause(jobId: String, resumePayload: String? = find(jobId)?.resumePayload): Boolean {
        val entry = find(jobId) ?: return false
        if (nativeSpecs.containsKey(jobId)) {
            // No checkpoint yet only means a resume starts from the beginning.
            if (entry.done || entry.paused || !entry.pausable) return false
            update(jobId) { it.copy(paused = true, statusLine = "Paused") }
            nativeJobs[jobId]?.cancel()
            return true
        }
        val checkpoint = resumePayload ?: return false
        if (!entry.pausable || !entry.resumable || entry.done || entry.paused) return false
        val runner = runners[jobId] ?: return false
        if (!runner.pauseJob(entry.pluginId, jobId, checkpoint)) return false
        var changed = false
        update(jobId) { current -> if (!current.done) { changed = true; current.copy(paused = true, resumePayload = checkpoint, statusLine = "Paused") } else current }
        if (changed) persist()
        return changed
    }

    /**
     * Marks a restored or paused job ready for its owner to resume. When the person resumes a job the download policy
     * holds ([byPolicy] false), that is their answer to the hold: the policy leaves the job alone from then on.
     */
    fun resume(jobId: String, byPolicy: Boolean = false): Boolean {
        val entry = find(jobId) ?: return false
        if (nativeSpecs.containsKey(jobId)) {
            if (!entry.paused || entry.done) return false
            update(jobId) { it.copy(paused = false, hold = null, policyOverride = it.policyOverride || !byPolicy, statusLine = "Resuming…") }
            launchNative(jobId, entry.resumePayload)
            return true
        }
        val spec = resumeSpecs[jobId] ?: return false
        val checkpoint = entry.resumePayload ?: return false
        if (!entry.paused || entry.done) return false
        update(jobId) { it.copy(paused = false, hold = null, policyOverride = it.policyOverride || !byPolicy, statusLine = "Resuming…") }
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
            val nativeSpec = nativeSpecs[e.jobId]
            put(JSONObject().put("id", e.jobId).put("plugin", e.pluginId).put("label", e.pluginLabel).put("title", e.title)
                .put("started", e.startedAtMs).put("percent", e.percent).put("status", e.statusLine).put("kind", e.kind)
                .put("pausable", e.pausable).put("resumable", e.resumable).put("payload", e.resumePayload).put("paused", true)
                .put("native", e.nativeKind).put("hold", e.hold).put("capability", spec?.capability?.id ?: e.capability?.id)
                .put("args", JSONObject().apply { (spec?.args ?: nativeSpec?.args)?.forEach { (k, v) -> put(k, v) } }))
        }
    }.toString()

    internal fun decodeEntries(encoded: String): List<Entry> = try {
        val array = JSONArray(encoded)
        (0 until array.length()).map { i -> array.getJSONObject(i).let { o -> Entry(
            jobId = o.getString("id"), pluginId = o.getString("plugin"), pluginLabel = o.getString("label"), capability = null,
            title = o.getString("title"), startedAtMs = o.getLong("started"), percent = o.optInt("percent", -1),
            statusLine = o.optString("hold").takeIf { it.isNotEmpty() && it != "null" } ?: "Paused", kind = o.optString("kind", "plugin"), pausable = o.optBoolean("pausable"),
            resumable = o.optBoolean("resumable"), resumePayload = o.optString("payload").takeIf { it.isNotEmpty() && it != "null" }, paused = true,
            nativeKind = o.optString("native").takeIf { it.isNotEmpty() && it != "null" },
            hold = o.optString("hold").takeIf { it.isNotEmpty() && it != "null" },
        ).also { entry ->
            val cap = PluginCapability.fromId(o.optString("capability"))
            val args = buildMap { o.optJSONObject("args")?.let { a -> a.keys().forEach { put(it, a.optString(it)) } } }
            if (entry.nativeKind != null) nativeSpecs[entry.jobId] = NativeSpec(entry.nativeKind, args)
            else if (entry.pausable && entry.resumable && cap != null) resumeSpecs[entry.jobId] = ResumeSpec(null, cap, args)
        } } }
    } catch (_: Exception) { emptyList() }
}
