package dev.droidtop.pluginhost

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// A damaged jobs file starts the list empty: an unreadable record of past jobs must never stop droidtop from starting.
private val Context.pluginJobsStore by preferencesDataStore(
    name = "plugin_jobs",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w("PluginJobsCenter", "The saved jobs file could not be read; starting with no saved jobs", it)
        emptyPreferences()
    },
)

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
 * Kept across restarts ([encodeEntries]): an unfinished resumable job comes back paused (a reattaching kind runs
 * again), and the newest [FINISHED_KEPT] finished jobs come back with their results and reports, so what a job did
 * stays readable after its toast (docs/SPEC.md "Jobs and their results", Droidtop/tracker#577) until the person
 * clears it ([clear], [clearFinished]).
 */
object PluginJobsCenter {
    /** How many finished jobs are kept, newest first, with their reports, across restarts. */
    internal const val FINISHED_KEPT = 200
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
        /**
         * A restored job the person had paused themselves. Every restored job comes back paused; one that was
         * running when droidtop stopped (a restart, a self-update) is not this, and a reattaching kind resumes it.
         */
        val pausedByPerson: Boolean = false,
        /** The job's numbers ([JobNumbers]); null when it reports only a percent and a line. */
        val numbers: JobNumbers? = null,
        /** What the job did, line by line, kept after it ends ([JobReport]). */
        val report: JobReport? = null,
        /** Where Open on the job goes: the game it installed, the folder it filled. */
        val open: OpenTarget? = null,
        /** When it ended; null while it runs. */
        val finishedAtMs: Long? = null,
        /** A finished native job can be run again with its arguments ([retry]). */
        val retryable: Boolean = false,
        /** Why a running job is not moving right now; null while it moves ([JobWait]). */
        val wait: JobWait? = null,
    ) {
        /** Where the job stands, from its flags. */
        val state: JobState
            get() = when {
                done && result?.ok == false -> JobState.FAILED
                done -> JobState.DONE
                paused && hold != null -> JobState.HELD
                paused -> JobState.PAUSED
                wait?.reason == WaitReason.NETWORK -> JobState.WAITING_FOR_NETWORK
                wait?.reason == WaitReason.RETRY -> JobState.WAITING_TO_RETRY
                else -> JobState.RUNNING
            }
    }

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
    // A finished native job's kind and arguments, for Retry; kept as long as its row is.
    private val finishedSpecs = ConcurrentHashMap<String, NativeSpec>()
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

    /**
     * Runs on the restore thread after [attach] has restored the interrupted jobs; :app uses it to drop what
     * a previous run left that should not carry on (the self-update's stale downloads).
     */
    @Volatile
    var onRestored: () -> Unit = {}

    /** Connects the registry to app storage and restores interrupted resumable jobs as paused. */
    fun attach(context: Context) {
        persistenceContext = context.applicationContext
        persistenceScope.launch {
            val prefs = context.applicationContext.pluginJobsStore.data.first()
            restore(decodeEntries(prefs[persistedKey].orEmpty()))
            runCatching { onRestored() }
        }
    }

    /**
     * Adds interrupted resumable jobs, as paused, to the live list; a job already tracked is left as it is. A job that
     * cannot be paused comes back ended ([stoppedByRestart]): paused, it had no Resume, and whoever asked for its kind
     * again joined it and waited for ever. That was the update check after a self-update, so Check now did nothing
     * (Droidtop/tracker#837, console dev.2758: "PAUSED (1): Check for updates").
     */
    internal fun restore(restored: List<Entry>) {
        val now = System.currentTimeMillis()
        val back = restored.map { if (!it.done && !it.pausable && it.nativeKind !in nativeReattachKinds) stoppedByRestart(it, now) else it }
        state.update { current -> (back.filter { old -> current.none { it.jobId == old.jobId } } + current).distinctBy { it.jobId } }
        // A job that carries on from what it left on disk (a download's partial file) is not left paused for
        // the person to resume: it runs again from its checkpoint.
        // One the person paused stays paused: only the restart stopped the others (console, 0.2.0-dev.2129: "Get details
        // for PC games" came back Paused after the self-update and waited for Resume).
        restored.filter { it.nativeKind?.let { k -> k in nativeReattachKinds } == true && it.paused && !it.pausedByPerson && !it.done && nativeSpecs.containsKey(it.jobId) }.forEach { entry ->
            // A download the policy was holding stays held while the policy still says wait.
            val kind = entry.nativeKind
            if (entry.hold != null && kind != null && kind in downloadKinds && admission(kind, nativeSpecs[entry.jobId]?.args.orEmpty()) != null) return@forEach
            update(entry.jobId) { it.copy(paused = false, hold = null, statusLine = "Reconnecting…") }
            launchNative(entry.jobId, entry.resumePayload)
        }
    }

    /** [entry], which could not be paused, as the restart left it: ended, saying so. Pure. */
    internal fun stoppedByRestart(entry: Entry, nowMs: Long): Entry = entry.copy(
        done = true,
        paused = false,
        pausedByPerson = false,
        result = PluginResult.failure(STOPPED_BY_RESTART),
        statusLine = STOPPED_BY_RESTART,
        finishedAtMs = nowMs,
    )

    internal const val STOPPED_BY_RESTART = "Stopped when droidtop restarted"

    private val writeQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Writes the list at most once per [PERSIST_INTERVAL_MS]: with finished jobs and their reports kept, encoding it
     * on every progress tick would bring back the lag the progress throttle removed. The last state always lands.
     */
    private fun persist() {
        val context = persistenceContext ?: return
        if (!writeQueued.compareAndSet(false, true)) return
        persistenceScope.launch {
            delay(PERSIST_INTERVAL_MS)
            writeQueued.set(false)
            val snapshot = encodeEntries(state.value)
            context.pluginJobsStore.edit { it[persistedKey] = snapshot }
        }
    }

    internal const val PERSIST_INTERVAL_MS = 1_000L

    /** Live, ordered (newest first) view of every tracked job, running or recently finished -- what the Jobs screen and any inline progress row both read. */
    fun entries(): StateFlow<List<Entry>> = state

    fun find(jobId: String): Entry? = state.value.firstOrNull { it.jobId == jobId }

    /**
     * Follows [jobId] until it ends, telling [onStatus] each new line: the entry it ended as, or null when it went
     * (cancelled, or cleared). For the row that started a job and says its outcome where the person asked; the job
     * itself shows under Downloads and installs and in Home's activity spot like any other.
     */
    suspend fun follow(jobId: String, onStatus: (String) -> Unit): Entry? = kotlinx.coroutines.coroutineScope {
        val follower = launch {
            state.map { list -> list.firstOrNull { it.jobId == jobId }?.statusLine }
                .distinctUntilChanged()
                .collect { line -> line?.let(onStatus) }
        }
        val end = state.first { list -> list.none { it.jobId == jobId && !it.done } }.firstOrNull { it.jobId == jobId }
        follower.cancel()
        end
    }

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
                    update(jobId) { ended(it, result).copy(done = true, result = result, percent = 100, finishedAtMs = System.currentTimeMillis()) }
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

    /**
     * When each running native job last reported (Droidtop/tracker#842): a run that says nothing for [stallMs] has stopped
     * making progress (a step waiting on something that will not come back), and is ended with a plain reason and Retry
     * instead of staying "current" for hours.
     */
    private val lastHeard = ConcurrentHashMap<String, Long>()

    /** How long a running native job may report nothing before it is stopped; tests shorten it. */
    @Volatile
    internal var stallMs: Long = 30 * 60_000L

    /** How often a run's silence is checked. */
    @Volatile
    internal var stallCheckMs: Long = 60_000L

    /** What a stopped run says; Retry carries on from what is on disk (a download's partial file, the archive). */
    internal const val STALLED = "It made no progress for 30 minutes, so it was stopped. Retry carries on from where it got to"

    private class Stalled : Exception(STALLED)

    private fun heard(jobId: String) {
        lastHeard[jobId] = System.currentTimeMillis()
    }

    /** Sets a running job's bar and line from inside one of its steps ([JobHandle.progress]); a paused or finished job keeps its own. */
    internal fun setProgress(jobId: String, percent: Int, line: String) {
        val now = find(jobId) ?: return
        if (now.done || now.paused) return
        heard(jobId)
        update(jobId) { it.copy(percent = percent, statusLine = line) }
    }

    /**
     * Writes the jobs list now, not within [PERSIST_INTERVAL_MS] (Droidtop/tracker#842): before droidtop installs its own
     * update, so the restart finds every running job with its last checkpoint and runs it again from there.
     */
    suspend fun flushNow() {
        val context = persistenceContext ?: return
        val snapshot = encodeEntries(state.value)
        context.pluginJobsStore.edit { it[persistedKey] = snapshot }
    }

    private fun launchNative(jobId: String, checkpoint: String?) {
        val spec = nativeSpecs[jobId] ?: return
        val runner = nativeRunners[spec.kind]
        if (runner == null) {
            update(jobId) { it.copy(paused = true, statusLine = "Unavailable in this version") }
            return
        }
        val previous = nativeJobs[jobId]
        // A download reports for every chunk and file; each report used to rebuild the jobs list, persist it and wake
        // every screen that reads it (the Home strip recomputed the whole library), which was the lag while a Steam
        // game downloaded (console, 0.2.0-dev.2127). The row now moves at most [PROGRESS_INTERVAL_MS] apart, with
        // the latest report always shown in the end and a new checkpoint kept at once, so a pause resumes from it.
        val throttle = ProgressThrottle(PROGRESS_INTERVAL_MS) { System.currentTimeMillis() }
        val trailing = java.util.concurrent.atomic.AtomicBoolean(false)
        fun publish(report: ProgressThrottle.Report) {
            // Progress that arrives after a pause belongs to the run being stopped, and a finished job keeps its end.
            val now = find(jobId) ?: return
            if (now.paused || now.done) return
            update(jobId) { it.copy(percent = report.percent, statusLine = report.status, resumePayload = report.checkpoint ?: it.resumePayload) }
        }
        nativeJobs[jobId] = nativeScope.launch(nativeDispatcher + JobHandle(jobId)) {
            // A paused run may still be reaching its next boundary; never two at once.
            previous?.join()
            try {
                heard(jobId)
                val summary = coroutineScope {
                    val work = async {
                        runner(spec.args, checkpoint) { percent, status, newCheckpoint ->
                            heard(jobId)
                            val report = ProgressThrottle.Report(percent, status, newCheckpoint)
                            if (throttle.offer(report)) {
                                publish(report)
                            } else if (trailing.compareAndSet(false, true)) {
                                nativeScope.launch(nativeDispatcher) {
                                    delay(throttle.waitMs())
                                    trailing.set(false)
                                    throttle.takePending()?.let(::publish)
                                }
                            }
                        }
                    }
                    // A run that reports nothing for [stallMs] is stopped (Droidtop/tracker#842), so nothing stays
                    // "current" with no work behind it.
                    var stalled = false
                    val watch = launch {
                        while (true) {
                            delay(stallCheckMs)
                            if (isStalled(lastHeard[jobId], System.currentTimeMillis(), stallMs)) {
                                stalled = true
                                work.cancel()
                                break
                            }
                        }
                    }
                    try {
                        work.await()
                    } catch (e: CancellationException) {
                        if (stalled) throw Stalled() else throw e
                    } finally {
                        watch.cancel()
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

    /** How many Retries in a row [followed] walks; a page is never left that many runs behind. */
    private const val MAX_RETRY_CHAIN = 32

    /** The least time between two progress updates of one native job's row: about four a second. */
    internal const val PROGRESS_INTERVAL_MS = 250L

    /** Whether a run last heard at [lastHeardMs] has been silent longer than [limitMs] at [now]. Pure. */
    internal fun isStalled(lastHeardMs: Long?, now: Long, limitMs: Long): Boolean = lastHeardMs != null && now - lastHeardMs > limitMs

    private fun finishNative(jobId: String, result: PluginResult) {
        lastHeard.remove(jobId)
        nativeSpecs[jobId]?.let { finishedSpecs[jobId] = it }
        update(jobId) {
            it.copy(
                done = true, result = result, percent = 100, finishedAtMs = System.currentTimeMillis(), retryable = true, wait = null,
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
            update(jobId) { ended(it, result).copy(done = true, result = result, percent = 100, finishedAtMs = System.currentTimeMillis()) }
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
            update(jobId) { it.copy(paused = true, wait = null, statusLine = "Paused") }
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
            }, { _, _, result -> update(jobId) { ended(it, result).copy(done = true, paused = false, result = result, finishedAtMs = System.currentTimeMillis()) } }).also { runners[jobId] = it }
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

    /** Keeps the newest [FINISHED_KEPT] finished jobs; the rest are forgotten. */
    private fun prune() {
        state.update { current -> keepFinished(current, FINISHED_KEPT) }
        persist()
    }

    /** [entries] with only the newest [keep] finished jobs among them, running and paused ones untouched. Pure. */
    internal fun keepFinished(entries: List<Entry>, keep: Int): List<Entry> {
        val finished = entries.filter { it.done }.sortedByDescending { it.finishedAtMs ?: it.startedAtMs }.take(keep).map { it.jobId }.toSet()
        return entries.filter { !it.done || it.jobId in finished }
    }

    /** The job a Retry started in place of each retried one, so a page left open on the old one follows the new. */
    private val successors = ConcurrentHashMap<String, String>()

    /** Jobs the person cleared this run, so a page left open on one says so instead of showing what it last saw. */
    private val clearedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The job [jobId] is now: itself, or the run a Retry started in its place, followed through repeated Retries. */
    fun followed(jobId: String): String {
        var id = jobId
        repeat(MAX_RETRY_CHAIN) { id = successors[id] ?: return id }
        return id
    }

    /** Whether the person cleared [jobId] (Clear, or Clear finished) while droidtop ran. */
    fun wasCleared(jobId: String): Boolean = jobId in clearedIds

    /**
     * A plugin job's last line (Droidtop/tracker#790): "Done", or its failure as one plain sentence ([JobFailureLine]) with
     * a runtime's own words kept as the report's Details, never as the line.
     */
    private fun ended(entry: Entry, result: PluginResult): Entry {
        if (result.ok) return entry.copy(statusLine = "Done")
        val line = JobFailureLine.of(entry.pluginLabel, result.error)
        val report = line.detail?.let { detail ->
            android.util.Log.w("droidtop.plugin", "${entry.pluginLabel}: ${entry.title} failed: $detail")
            JobReport((entry.report?.items.orEmpty() + ReportItem("Details", Outcome.FAILED, detail)).takeLast(JobReport.MAX_ITEMS))
        } ?: entry.report
        return entry.copy(statusLine = line.sentence, report = report)
    }

    /** What a Retry of a finished plugin job does, given by whoever starts that job again ([offerRetry]). */
    private val retryHandlers = ConcurrentHashMap<String, () -> Unit>()

    /**
     * Offers Retry on [jobId], a finished plugin job: [again] starts its work afresh (a plugin service is started again,
     * Droidtop/tracker#790), and the row gives way to the new run. Nothing when the job is gone or still running.
     */
    fun offerRetry(jobId: String, again: () -> Unit) {
        val entry = find(jobId) ?: return
        if (!entry.done) return
        retryHandlers[jobId] = again
        update(jobId) { it.copy(retryable = true) }
    }

    /** Forgets a finished job and its report (the row's Clear). False for a job still running or paused. */
    fun clear(jobId: String): Boolean {
        val entry = find(jobId) ?: return false
        if (!entry.done) return false
        clearedIds += jobId
        state.update { list -> list.filterNot { it.jobId == jobId } }
        finishedSpecs.remove(jobId)
        retryHandlers.remove(jobId)
        persist()
        return true
    }

    /** Forgets every finished job (Clear finished). */
    fun clearFinished() {
        state.value.filter { it.done }.forEach { clearedIds += it.jobId }
        state.update { list -> list.filterNot { it.done } }
        finishedSpecs.clear()
        retryHandlers.clear()
        persist()
    }

    /**
     * Runs a finished native job again with the arguments it had (Retry). Its own row is replaced by the new run.
     * Null when the job is not one droidtop can run again (a plugin's job, or one from before this was kept).
     */
    fun retry(context: Context?, jobId: String): String? {
        val entry = find(jobId) ?: return null
        if (!entry.done) return null
        // A plugin job whose starter offered Retry: that starter runs it again, and its new run takes this row's place.
        retryHandlers.remove(jobId)?.let { again ->
            clear(jobId)
            again()
            return jobId
        }
        val spec = finishedSpecs[jobId] ?: return null
        val started = startNative(context, spec.kind, entry.title, spec.args, owner = entry.pluginLabel, pausable = entry.pausable) ?: return null
        successors[jobId] = started
        clear(jobId)
        return started
    }

    internal fun setNumbers(jobId: String, numbers: JobNumbers) {
        val now = find(jobId) ?: return
        if (now.done || now.paused) return
        state.update { current -> current.map { if (it.jobId == jobId) it.copy(numbers = numbers) else it } }
    }

    internal fun addReportItem(jobId: String, item: ReportItem) {
        state.update { current ->
            current.map { entry ->
                if (entry.jobId != jobId) return@map entry
                val items = (entry.report?.items.orEmpty() + item).takeLast(JobReport.MAX_ITEMS)
                entry.copy(report = JobReport(items))
            }
        }
    }

    internal fun setWait(jobId: String, wait: JobWait?) {
        val now = find(jobId) ?: return
        if (now.done || now.wait == wait) return
        state.update { current -> current.map { if (it.jobId == jobId) it.copy(wait = wait) else it } }
    }

    internal fun setOpen(jobId: String, target: OpenTarget?) {
        update(jobId) { it.copy(open = target) }
    }

    /**
     * What survives a restart: every unfinished resumable job, restored paused, and the newest [FINISHED_KEPT]
     * finished jobs with their results and reports, so what a job did is still there after the toast and the restart.
     */
    internal fun encodeEntries(entries: List<Entry>): String = JSONArray().apply {
        entries.filter { (it.resumable && !it.done) || it.done }.forEach { e ->
            val spec = resumeSpecs[e.jobId]
            val nativeSpec = nativeSpecs[e.jobId] ?: finishedSpecs[e.jobId]
            put(JSONObject().put("id", e.jobId).put("plugin", e.pluginId).put("label", e.pluginLabel).put("title", e.title)
                .put("started", e.startedAtMs).put("percent", e.percent).put("status", e.statusLine).put("kind", e.kind)
                .put("pausable", e.pausable).put("resumable", e.resumable).put("payload", e.resumePayload).put("paused", !e.done)
                .put("pausedByPerson", e.paused && e.hold == null)
                .put("native", e.nativeKind).put("hold", e.hold).put("capability", spec?.capability?.id ?: e.capability?.id)
                .put("args", JSONObject().apply { (spec?.args ?: nativeSpec?.args)?.forEach { (k, v) -> put(k, v) } })
                .apply {
                    if (e.done) {
                        put("done", true).put("ok", e.result?.ok != false).put("error", e.result?.error)
                            .put("summary", e.result?.values?.get("summary")).put("finished", e.finishedAtMs ?: e.startedAtMs)
                            .put("retryable", e.retryable)
                    }
                    e.report?.let { put("report", it.encode()) }
                    put("open", OpenTarget.encode(e.open))
                })
        }
    }.toString()

    internal fun decodeEntries(encoded: String): List<Entry> = try {
        val array = JSONArray(encoded)
        fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotEmpty() && it != "null" }
        (0 until array.length()).map { i -> array.getJSONObject(i).let { o ->
            val done = o.optBoolean("done", false)
            val result = if (!done) null else if (o.optBoolean("ok", true)) {
                PluginResult.success(o.text("summary")?.let { mapOf("summary" to it) }.orEmpty())
            } else {
                PluginResult.failure(o.text("error") ?: "Failed")
            }
            Entry(
                jobId = o.getString("id"), pluginId = o.getString("plugin"), pluginLabel = o.getString("label"), capability = null,
                title = o.getString("title"), startedAtMs = o.getLong("started"), percent = o.optInt("percent", -1),
                statusLine = if (done) o.text("status") ?: "Done" else o.text("hold") ?: "Paused",
                kind = o.optString("kind", "plugin"), pausable = o.optBoolean("pausable"),
                resumable = o.optBoolean("resumable"), resumePayload = o.text("payload"), paused = !done,
                nativeKind = o.text("native"),
                hold = if (done) null else o.text("hold"),
                // An entry saved before this was recorded reads as running when droidtop stopped, as restore treated it.
                pausedByPerson = !done && o.optBoolean("pausedByPerson", false),
                done = done, result = result,
                finishedAtMs = if (done) o.optLong("finished") else null,
                retryable = done && o.optBoolean("retryable", false),
                report = JobReport.decode(o.text("report")),
                open = OpenTarget.decode(o.text("open")),
            ).also { entry ->
                val cap = PluginCapability.fromId(o.optString("capability"))
                val args = buildMap { o.optJSONObject("args")?.let { a -> a.keys().forEach { put(it, a.optString(it)) } } }
                when {
                    entry.nativeKind != null && done -> finishedSpecs[entry.jobId] = NativeSpec(entry.nativeKind, args)
                    entry.nativeKind != null -> nativeSpecs[entry.jobId] = NativeSpec(entry.nativeKind, args)
                    !done && entry.pausable && entry.resumable && cap != null -> resumeSpecs[entry.jobId] = ResumeSpec(null, cap, args)
                }
            }
        } }
    } catch (_: Exception) { emptyList() }
}
