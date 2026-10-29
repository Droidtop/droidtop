package dev.droidtop.pluginhost

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "A plugin failure is shown to the user and disables that plugin. The
 * launcher keeps working." (docs/SPEC.md 12a). This is the one place
 * that rule is enforced, so every call site -- a status tile refresh, an
 * acquire_content search, a metadata pass -- goes through [guard]
 * instead of calling a [PluginRunner] directly and re-implementing this.
 *
 * Every plugin process ([PluginProcessService]) and [NativePluginRunner]
 * funnel failures (an uncaught exception in plugin code, a timeout) into
 * the crash callback given to [NativePluginRunner]'s constructor, and
 * [PluginProcesses] reports a process dying to [processDied]; this class
 * holds both real implementations.
 */
class PluginCrashPolicy(
    private val context: Context,
    private val onJobProgress: (pluginId: String, jobId: String, percent: Int, statusLine: String, resumePayload: String?) -> Unit = { _, _, _, _, _ -> },
    private val onJobComplete: (pluginId: String, jobId: String, result: PluginResult) -> Unit = { _, _, _ -> },
) : PluginJobRunner {
    override val supportsCheckpointResume: Boolean get() = true
    private val runner: NativePluginRunner = NativePluginRunner(context, ::onCrash, onJobProgress, onJobComplete)

    private fun onCrash(pluginId: String, capability: String, reason: String) {
        if (pluginId.isEmpty()) return
        Log.w("droidtop.plugin", "$pluginId disabled: $reason")
        PluginStore.disableWithReason(context, pluginId, PluginLoadErrorMessage.userMessage(reason), detail = reason)
    }

    /**
     * The tier [record] runs in for this call (docs/plugin-api.md 5.3), or why it cannot run: a plugin that can only run
     * with full access and has not been allowed it. During a call the person started, an unanswered `host.full_trust`
     * is asked about on the first-use sheet, like any other critical permission; from the background it is not.
     */
    private suspend fun tierFor(record: PluginRecord, userInitiated: Boolean): Pair<PluginTier?, String?> = withContext(Dispatchers.IO) {
        val store = PluginGrants.forContext(context)
        var grants = store.read(record.manifest.id)
        val refusal = PluginTiers.refusal(record, grants) ?: return@withContext PluginTiers.of(record, grants) to null
        val state = PluginGrants.stateOf(record, grants, PluginTiers.FULL_TRUST)
        if (state == GrantState.ASK && userInitiated) {
            // The red dialog, not the first-use sheet (Droidtop/tracker#481): Cancel leaves it unanswered, to be asked again.
            val update = PluginTiers.FULL_TRUST in grants.fresh
            if (PluginDangers.askFullAccess(record, update)) store.set(record.manifest.id, PluginTiers.FULL_TRUST, GrantState.GRANTED)
            grants = store.read(record.manifest.id)
            PluginTiers.refusal(record, grants)?.let { return@withContext null to it }
            return@withContext PluginTiers.of(record, grants) to null
        }
        if (state == GrantState.ASK) store.noteWanted(record.manifest.id, PluginTiers.FULL_TRUST)
        null to refusal
    }

    /** Loads [record] in the tier it may run in; false with the reason in [loadFailure] when it cannot. */
    private suspend fun loadFor(record: PluginRecord, userInitiated: Boolean): String? {
        val (tier, refusal) = tierFor(record, userInitiated)
        if (tier == null) return refusal ?: "plugin failed to load"
        val id = record.manifest.id
        val dir = PluginStore.payloadDirFor(context, record)
        if (runner.load(record, dir.absolutePath, tier)) {
            PluginDangers.loaded(id)
            return null
        }
        // Protection did not start (Droidtop/tracker#481): during a call the person started, ask in red whether to run it
        // this time anyway; from the background it waits for the Plugins page, which asks for every such plugin at once.
        val report = PluginDangers.protectionReport(runner.loadFailure(id)) ?: return loadFailure(record)
        if (userInitiated && PluginDangers.askRunUnprotected(id, record.manifest.label, report)) {
            if (runner.load(record, dir.absolutePath, tier)) {
                PluginDangers.loaded(id)
                return null
            }
            return loadFailure(record)
        }
        PluginDangers.recordFailure(PluginDangers.Failure(id, record.manifest.label, report))
        return "${record.manifest.label} did not run: its protection did not start"
    }

    /**
     * Connects an app interface plugin's app (docs/plugin-api.md 1.3, the door): checks it as a call would and loads it,
     * which hands its app its broker. Null when it is connected, else why not.
     */
    suspend fun connectApp(record: PluginRecord): String? {
        if (record.manifest.kind != PluginKind.APP_INTERFACE) return "not an app's plugin"
        if (!record.runnable()) return "${record.manifest.label} is not approved and turned on"
        gateOnVerification(record)?.let { return it.error ?: "${record.manifest.label} failed its check" }
        return loadFor(record, userInitiated = false)
    }

    /**
     * Loads [record] for its own screen (a contained `ui.main`, [PluginScreenActivity]) the way a call the person started
     * would, and returns the process it runs in, or why it could not.
     */
    suspend fun screenRuntime(record: PluginRecord): Pair<IPluginRuntime?, String?> {
        if (!record.runnable()) return null to "${record.manifest.label} is not approved and turned on"
        gateOnVerification(record)?.let { return null to (it.error ?: "${record.manifest.label} failed its check") }
        missingRuntime(record)?.let { return null to "${record.manifest.label} ${it.message}" }
        loadFor(record, userInitiated = true)?.let { return null to it }
        return runner.runtimeOf(record.manifest.id) to null
    }

    /**
     * The containment check (docs/plugin-api.md 5.3): loads [record] the way a call would and reports, in plain lines,
     * which tier and process it runs in and what that process reached when it tried the network, droidtop's files and
     * shared storage, plus how a contained python runtime loaded. For the rig and for anyone who wants to see the wall.
     */
    suspend fun containmentReport(record: PluginRecord): String {
        if (!record.runnable()) return "${record.manifest.label} is not approved and turned on"
        gateOnVerification(record)?.let { return it.error ?: "failed its check" }
        missingRuntime(record)?.let { return it.message }
        loadFor(record, userInitiated = true)?.let { return "Did not load: $it" }
        val report = runner.reachability(record.manifest.id) ?: return "Loaded, but its process did not answer"
        report.optString("error").takeIf { it.isNotEmpty() }?.let { return "Loaded, but its report failed: $it" }
        val grants = withContext(Dispatchers.IO) { PluginGrants.forContext(context).read(record.manifest.id) }
        return buildString {
            append(
                when (PluginTiers.of(record, grants)) {
                    PluginTier.CONTAINED -> "Sealed in a process of its own: no network, no files but a folder of its own, nothing but what droidtop does for it"
                    PluginTier.OPEN -> {
                        val holes = PluginTiers.holes(record, grants)
                        val opened = listOfNotNull(
                            "graphics access".takeIf { PluginTiers.GPU_RENDER in holes },
                            "its own internet connections".takeIf { PluginTiers.NET_SOCKETS in holes },
                        ).joinToString(" and ")
                        "A process of its own with $opened. droidtop's system-call filter takes away everything else" +
                            (if (PluginTiers.NET_SOCKETS in holes) "" else ", the network included,") +
                            " and sends every file it opens through droidtop's checks, but it shares droidtop's user id, so its own code could still ask Android's services for things as droidtop; everything it does through droidtop is still listed and limited"
                    }
                    PluginTier.FULL_TRUST -> "Runs with droidtop's own access: it can do anything droidtop can"
                },
            )
            append(" (uid ").append(report.optInt("uid")).append(", Android API ").append(report.optInt("sdk")).append(")")
            PluginProcesses.describe(record.manifest.id)?.let { append("\nProcess: ").append(it) }
            append("\nNetwork: ").append(report.optString("network"))
            append("\ndroidtop's files: ").append(report.optString("droidtopFiles"))
            append("\nShared storage: ").append(report.optString("sharedStorage"))
            report.optJSONObject("notes")?.optString("syscallFilter")?.takeIf { it.isNotEmpty() }?.let { append("\nSystem-call filter: ").append(it) }
            report.optJSONObject("notes")?.optString("hooks")?.takeIf { it.isNotEmpty() }?.let { append("\nGuarded hooks: ").append(it) }
            report.optJSONObject("notes")?.optString("assets")?.takeIf { it.isNotEmpty() }?.let { append("\nFlutter assets: ").append(it) }
            report.optJSONObject("notes")?.optJSONObject("python")?.let { python ->
                append("\nPython: ").append(if (python.optBoolean("ok")) "started" else "did not start: ${python.optString("error")}")
                append("\n").append(python.toString(1))
            }
        }
    }

    /**
     * Runs [block] (a load or an invoke) and, on ANY failure -- the
     * runner already reported the crash via the callback above by the
     * time this returns -- makes sure the record is disabled even if the
     * failure path didn't already do it (belt and braces for a case the
     * callback missed, e.g. the process never starting).
     */
    suspend fun invoke(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, userInitiated: Boolean = true): PluginResult {
        if (!record.runnable()) return PluginResult.failure("plugin is not approved/enabled")
        waitingReason(record)?.let { return PluginResult.failure(it) }
        if (record.manifest.kind !in RUNNABLE_KINDS) {
            return PluginResult.failure("no runner for kind ${record.manifest.kind.id} yet")
        }
        LegacyManifest.CAPABILITY_POINTS[capability]?.let { point ->
            pointGate(record, point, userInitiated)?.let { return PluginResult.failure(it) }
        }
        gateOnVerification(record)?.let { return it }
        missingRuntime(record)?.let { return PluginResult.failure(it.message) }
        loadFor(record, userInitiated)?.let { return PluginResult.failure(it) }
        // A returned PluginResult.failure (ok=false, no exception) is a
        // PLUGIN reporting its own ordinary failure -- "no network",
        // "nothing found" -- and is not a crash: it must not disable the
        // plugin, or every transient failure would permanently kill it.
        // Only a thrown exception, a timeout or the process dying counts
        // as a crash, and those already went through onCrash() by the
        // time this call returns (PluginRuntimeService.invoke catches
        // Throwable and reports it there instead of encoding a normal
        // failure result; NativePluginRunner's timeout/exception catches
        // do the same).
        return PluginBrokers.during(record.manifest.id, userInitiated, PluginRunner.CALL_TIMEOUT_MS) {
            runner.invoke(record.manifest.id, capability, args)
        }
    }

    /**
     * Sends one v2 call to [record]'s `handle` (docs/plugin-api.md 1.3), the
     * one path for extension points that have no contract 1 capability
     * (quick tiles, `api:<id>` calls for a provider) and for the ones that
     * do. The same runnable and re-verify gates as [invoke]; a plugin that
     * is Waiting, or whose `provide:` grant for [PluginCall.point] is not
     * given, is refused. [timeoutMs] is the budget of docs/plugin-api.md 8:
     * a miss is a crash only for the default 15 s budget ([crashOnTimeout]),
     * because a shorter one is the UI declining to wait. Never throws.
     */
    suspend fun handle(
        record: PluginRecord,
        call: PluginCall,
        timeoutMs: Long = PluginRunner.CALL_TIMEOUT_MS,
        crashOnTimeout: Boolean = timeoutMs >= PluginRunner.CALL_TIMEOUT_MS,
        userInitiated: Boolean = true,
    ): PluginReply {
        if (!record.runnable()) return PluginReply.error(PluginErrorCode.PROVIDER_UNAVAILABLE, "plugin is not approved/enabled")
        waitingReason(record)?.let { return PluginReply.error(PluginErrorCode.PROVIDER_UNAVAILABLE, it) }
        if (record.manifest.kind !in RUNNABLE_KINDS) {
            return PluginReply.error(PluginErrorCode.UNSUPPORTED, "no runner for kind ${record.manifest.kind.id} yet")
        }
        pointGate(record, call.point, userInitiated)?.let { return PluginReply.error(PluginErrorCode.PERMISSION_DENIED, it) }
        gateOnVerification(record)?.let { return PluginReply.error(PluginErrorCode.FAILED, it.error ?: "plugin failed verification") }
        missingRuntime(record)?.let { return PluginReply.error(PluginErrorCode.FAILED, it.message) }
        loadFor(record, userInitiated)?.let { return PluginReply.error(PluginErrorCode.FAILED, it) }
        return PluginBrokers.during(record.manifest.id, userInitiated, timeoutMs) {
            runner.handle(record.manifest.id, call, timeoutMs, crashOnTimeout)
        }
    }

    /**
     * Why a call to [point] must not be made ([PluginGrants.pointRefusal]), or null when it may. A point set to Ask first
     * (or not answered yet) is asked about on the first-use sheet during a call the person started, and the answer is
     * kept: Allow turns it on, Never blocks it, Not now leaves it to be asked again (Droidtop/tracker#270). From the
     * background it stays refused, with a note that the plugin wanted it.
     */
    private suspend fun pointGate(record: PluginRecord, point: String, userInitiated: Boolean): String? = withContext(Dispatchers.IO) {
        val id = record.manifest.id
        val store = PluginGrants.forContext(context)
        val snapshot = store.read(id)
        val refusal = PluginGrants.pointRefusal(record, snapshot, point) ?: return@withContext null
        if (!ExtensionPoints.declares(record.manifest, point)) return@withContext refusal
        if (PluginGrants.provideState(record, snapshot, point) != GrantState.ASK) return@withContext refusal
        if (!userInitiated) {
            store.noteWanted(id, PluginPermissions.PROVIDE_PREFIX + point)
            return@withContext refusal
        }
        if (PluginGrants.askPoint(context, record, point)) PluginGrants.pointRefusal(record, store.read(id), point) else refusal
    }

    /** Why [record] cannot be called right now although it is approved and enabled: a required API of another plugin has no provider (docs/plugin-api.md 2.3). Null when it can. */
    private fun waitingReason(record: PluginRecord): String? {
        val missing = PluginApiResolver.current(context).waiting[record.manifest.id] ?: return null
        return "waiting for " + missing.joinToString { it.api }
    }

    /** Starts a long-running job for [record] under the given [jobId] (see [PluginJob], and [IPluginRuntime.startJob]'s own doc comment for why the caller -- [PluginJobsCenter] -- chooses this id); same runnable/re-verify gates as [invoke], false on any refusal. */
    override suspend fun startJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String): Boolean {
        if (!record.runnable() || waitingReason(record) != null) return false
        if (record.manifest.kind !in RUNNABLE_KINDS) return false
        PluginGrants.jobRefusal(record, PluginGrants.forContext(context).read(record.manifest.id), capability, args)?.let { return false }
        gateOnVerification(record)?.let { return false }
        missingRuntime(record)?.let {
            Log.w("droidtop.plugin", "${record.manifest.id} job not started: ${it.message}")
            return false
        }
        loadFor(record, userInitiated = false)?.let {
            Log.w("droidtop.plugin", "${record.manifest.id} job not started: $it")
            return false
        }
        return runner.startJob(record.manifest.id, capability, args, jobId)
    }

    override fun pauseJob(pluginId: String, jobId: String, resumePayload: String): Boolean {
        runner.cancelJob(pluginId, jobId)
        return true
    }

    override suspend fun resumeJob(record: PluginRecord, capability: PluginCapability, args: Map<String, String>, jobId: String, resumePayload: String): Boolean =
        startJob(record, capability, args + (RESUME_PAYLOAD_ARG to resumePayload), jobId)

    /**
     * Fires [event] at [record] and returns its answer, or null when
     * [record] never subscribed ([PluginManifest.subscribedEvents]) --
     * the check happens here, BEFORE any load/bind, so a plugin that
     * ignores every event costs nothing (no process spin-up, no binder
     * call) on droidtop's own state changes. Same runnable/re-verify
     * gates as [invoke]: a disabled, denied or tampered-with plugin
     * never receives an event either.
     */
    suspend fun notifyEvent(record: PluginRecord, event: dev.droidtop.pluginhost.PluginEvent, args: Map<String, String>): PluginResult? {
        if (!record.manifest.subscribesTo(event)) return null
        if (!record.runnable() || waitingReason(record) != null) return null
        if (record.manifest.kind !in RUNNABLE_KINDS) return null
        gateOnVerification(record)?.let { return null }
        if (missingRuntime(record) != null) return null
        if (loadFor(record, userInitiated = false) != null) return null
        return runner.notifyEvent(record.manifest.id, event, args)
    }

    /**
     * The shared re-verification gate of [invoke]/[startJob]/
     * [notifyEvent]: re-checks the installed files AND the signature
     * (against the official pinned key first, then the user-trusted
     * keys, [UserOriginKeys]) before every activation, disabling the
     * plugin with the REAL problem as the reason -- "signature no
     * longer verifies" for a plugin whose origin's key was removed
     * from "Keys you trust", "<file> changed on disk since approval"
     * for a tampered payload -- rather than one generic sentence for
     * all of them. Returns the failure result [invoke] should return,
     * or null when the plugin is fit to run.
     */
    private suspend fun gateOnVerification(record: PluginRecord): PluginResult? = withContext(Dispatchers.IO) {
        // Hashes every payload file: disk work, so never on the caller's (possibly main) thread.
        val userKeys = UserOriginKeys.loadBase64(UserOriginKeys.storeFile(context))
        val root = PluginStore.root(context)
        val problem = PluginBundleInstaller.verifyInstalled(root, record, userKeys) ?: return@withContext null
        // The record this call was given may be one an update has replaced since: only the version the plugin is on
        // now decides whether it is disabled (docs/SPEC.md 12a, "Trust over updates").
        val current = PluginBundleInstaller.readRecord(root, record.manifest.id)
        if (current != null && current.archiveDigest != record.archiveDigest && PluginBundleInstaller.verifyInstalled(root, current, userKeys) == null) {
            return@withContext PluginResult.failure("${record.manifest.label} was updated during this call; try again")
        }
        PluginStore.disableWithReason(context, record.manifest.id, problem.reason)
        PluginResult.failure(problem.reason)
    }

    /** The runtime [record] needs and the device lacks, or null. Answered before any load, so a plugin that cannot run is never started and never disabled for it. */
    private suspend fun missingRuntime(record: PluginRecord): RuntimeNeed? =
        withContext(Dispatchers.IO) { PluginRuntimeNeeds.missing(context, record.manifest) }

    /** The reason [runner]'s last load of [record] failed (docs/SPEC.md 12a); never the bare "failed to load" when the host knows more. */
    private fun loadFailure(record: PluginRecord): String {
        val raw = runner.loadFailure(record.manifest.id) ?: "plugin failed to load"
        return PluginLoadErrorMessage.userMessage(raw)
    }

    override fun cancelJob(pluginId: String, jobId: String) {
        runner.cancelJob(pluginId, jobId)
    }

    override fun shutdown() {
        runner.unbind()
    }

    companion object {
        internal fun disableInFlight(pluginIds: Set<String>, disable: (String) -> Unit) {
            pluginIds.forEach(disable)
        }

        /**
         * A plugin process died ([PluginProcesses]), holding [pluginIds]. A death during a call is evidence against the
         * plugin the call was for; an idle death is a system kill and must reconnect on the next call without disabling.
         */
        internal fun processDied(context: Context, pluginIds: Set<String>, reason: String) {
            disableInFlight(pluginIds intersect PluginBrokers.inFlightPluginIds()) { affectedId ->
                Log.w("droidtop.plugin", "$affectedId disabled: $reason")
                PluginStore.disableWithReason(context, affectedId, PluginLoadErrorMessage.userMessage(reason), detail = reason)
            }
        }

        // Kinds with a real runner behind NativePluginRunner. Found stale
        // 2026-09-26: this list still named only NATIVE_BUNDLE/PYTHON after
        // flutter_embed's runner landed (63627015) -- PluginRuntimeService's
        // own dispatch handled FLUTTER_EMBED fine, but every call from the
        // Settings UI goes through THIS class first (PluginsCatalog.kt),
        // so a flutter_embed plugin could install, approve and show
        // "Running", but never actually be called: every invoke/startJob
        // failed here with "no runner for kind flutter_embed yet" before
        // ever reaching the runner that would have worked. One list instead
        // of two separate != chains so a future kind only needs one edit.
        private val RUNNABLE_KINDS = setOf(PluginKind.NATIVE_BUNDLE, PluginKind.PYTHON, PluginKind.FLUTTER_EMBED, PluginKind.APP_INTERFACE)

        // Carries a paused job's checkpoint into resumeJob's startJob call.
        const val RESUME_PAYLOAD_ARG = "droidtop.resume_payload"
    }
}
