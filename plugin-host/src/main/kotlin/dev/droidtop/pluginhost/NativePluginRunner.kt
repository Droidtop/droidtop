package dev.droidtop.pluginhost

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * The [PluginRunner] behind every kind: it gets each plugin's own process from [PluginProcesses] (contained or full
 * trust, docs/plugin-api.md 5.3), loads the plugin there and drives it over [IPluginRuntime]. A plugin that threw but
 * left its process alive is reported through [IPluginRuntimeCallback] to [onCrash]; a process that died is reported by
 * [PluginProcesses] itself, once, to [PluginCrashPolicy.processDied]. One runner may use several processes; it holds
 * each one it loaded a plugin in until [unbind].
 */
class NativePluginRunner(
    context: Context,
    private val onCrash: (pluginId: String, capability: String, reason: String) -> Unit,
    private val onJobProgress: (pluginId: String, jobId: String, percent: Int, statusLine: String, resumePayload: String?) -> Unit = { _, _, _, _, _ -> },
    private val onJobComplete: (pluginId: String, jobId: String, result: PluginResult) -> Unit = { _, _, _ -> },
) : PluginRunner {
    private val appContext = context.applicationContext

    /** The runtime each plugin was loaded on by this runner. */
    private val runtimes = ConcurrentHashMap<String, IPluginRuntime>()

    /** The processes this runner registered a callback with ([callbacks]). */
    private val registered = ConcurrentHashMap<IBinder, IPluginRuntime>()

    /** The process each plugin last answered a call in: a call on any other runtime of it is the first since that process started. */
    private val answeredOn = ConcurrentHashMap<String, IBinder>()

    private fun isCold(pluginId: String, runtime: IPluginRuntime): Boolean = answeredOn[pluginId] !== runtime.asBinder()

    // Why each plugin's last [load] returned false, for the caller to show and for logcat (see [loadFailure]).
    private val loadFailures = ConcurrentHashMap<String, String>()

    /** Why the last [load] of [pluginId] returned false, in words an end user can read; null when it has not failed. */
    fun loadFailure(pluginId: String): String? = loadFailures[pluginId]

    private fun failLoad(pluginId: String, reason: String): Boolean {
        loadFailures[pluginId] = reason
        Log.w("droidtop.plugin", "$pluginId did not load: $reason")
        return false
    }

    /**
     * Every process broadcasts every crash and job report to every registered callback, and each runner filters by the
     * plugin and job ids it knows (a single callback field was silently stolen by the second connection, 2026-09-27).
     * Each process gets a callback of its own, which believes a report only about a plugin this runner loaded in that
     * very process (docs/plugin-api.md 5.6): code in one plugin process can no longer disable another plugin by
     * reporting its crash, or draw into another plugin's screen.
     */
    private inner class ProcessCallback(private val process: IBinder) : IPluginRuntimeCallback.Stub() {
        private fun ours(pluginId: String): Boolean {
            val ok = PluginProcessOwnership.reports(runtimes[pluginId]?.asBinder(), process)
            if (!ok) Log.w("droidtop.plugin", "ignored a report about $pluginId from a process it does not run in")
            return ok
        }

        override fun onPluginCrashed(pluginId: String, capability: String, reason: String) {
            if (ours(pluginId)) onCrash(pluginId, capability, reason)
        }

        override fun onJobProgress(pluginId: String, jobId: String, percent: Int, statusLine: String, resumePayload: String?) {
            if (ours(pluginId)) this@NativePluginRunner.onJobProgress(pluginId, jobId, percent, statusLine, resumePayload)
        }

        override fun onJobComplete(pluginId: String, jobId: String, resultJson: String) {
            if (ours(pluginId)) this@NativePluginRunner.onJobComplete(pluginId, jobId, decode(resultJson))
        }

        override fun onScreenClosed(pluginId: String) {
            if (ours(pluginId)) PluginScreenActivity.closed(pluginId)
        }

        override fun onScreenFrame(pluginId: String, index: Int, width: Int, height: Int) {
            if (ours(pluginId)) PluginScreenActivity.frame(pluginId, index, width, height)
        }
    }

    /** The plugins handed a Shizuku relay, handed a new one whenever Shizuku sends droidtop a new binder. */
    private val relayed = ConcurrentHashMap<String, PluginRecord>()

    private val listening = java.util.concurrent.atomic.AtomicBoolean()

    /** Listens for Shizuku's binder from the first relay on, so a runner that never hands one (a rig test's) never listens. */
    private fun listenForShizuku() {
        if (!listening.compareAndSet(false, true)) return
        runCatching {
            rikka.shizuku.Shizuku.addBinderReceivedListener {
                relayed.forEach { (id, record) -> runtimes[id]?.let { runtime -> runCatching { ShizukuRelays.attach(appContext, runtime, record) } } }
            }
        }
    }

    /** The callback this runner registered with each process, so [unbind] can unregister the very same stub. */
    private val callbacks = ConcurrentHashMap<IBinder, ProcessCallback>()

    override suspend fun load(record: PluginRecord, installDir: String): Boolean {
        val grants = withContext(Dispatchers.IO) { PluginGrants.forContext(appContext).read(record.manifest.id) }
        return load(record, installDir, PluginTiers.of(record, grants))
    }

    /**
     * Loads [record] in its own process for [tier]: from its install folder with full trust, or, contained, from the
     * descriptors of the files it needs ([ContainedFiles]), which :app opens because the isolated process cannot.
     */
    suspend fun load(record: PluginRecord, installDir: String, tier: PluginTier): Boolean {
        val id = record.manifest.id
        loadFailures.remove(id)
        if (record.manifest.kind == PluginKind.APP_INTERFACE) return loadApp(record)
        // An open plugin with only the network hole runs isolated, its sockets the broker's (docs/plugin-api.md 5.6).
        val grants = withContext(Dispatchers.IO) { PluginGrants.forContext(appContext).read(id) }
        val isolatedNet = tier == PluginTier.OPEN && PluginTiers.runsIsolated(PluginTiers.holes(record, grants))
        // Full access isolated, behind its switch until every plugin has passed (5.6, "Every plugin is isolated, full
        // access included"): the contained loader with the network, and droidtop's relays for the system services it was allowed.
        val isolatedFull = tier == PluginTier.FULL_TRUST && PluginTiers.fullAccessRunsIsolated(PluginTiers.fullAccessIsolated())
        val isolated = isolatedNet || isolatedFull
        // Each start's phases on one line (Droidtop/tracker#848): the process, then the plugin's own load in it.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val runtime = PluginProcesses.acquire(appContext, id, if (isolated) PluginTier.CONTAINED else tier, network = isolated)
            ?: return failLoad(id, if (tier == PluginTier.CONTAINED) "its contained process could not be started" else "the plugin process could not be started")
        if (runtimes.put(id, runtime) != null) PluginProcesses.release(appContext, id)
        val binder = runtime.asBinder()
        if (registered.putIfAbsent(binder, runtime) == null) {
            val callback = ProcessCallback(binder).also { callbacks[binder] = it }
            runCatching { runtime.registerCallback(callback) }
        }
        val processAt = android.os.SystemClock.elapsedRealtime()
        return try {
            // A blocking binder call: on the IO dispatcher so the budget below can fire and a caller on the main thread is
            // never held for a cold start (rig, 2026-09-30: 15 s of skipped frames while a Flutter plugin started).
            val loaded = withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    when (tier) {
                        PluginTier.FULL_TRUST -> if (isolatedFull) {
                            loadContained(runtime, record, tier)
                        } else {
                            // entryClass is null for any kind but native_bundle; the AIDL parameter is a non-null placeholder there.
                            runtime.loadPlugin(id, installDir, record.manifest.entryClass ?: "", record.rootApproved, PluginBrokers.binderFor(appContext, id))
                        }
                        // An open process loads the plugin from the same descriptors as a contained one; it differs only in
                        // being non-isolated, with the holes the person granted (docs/plugin-api.md 5.3, "Open processes").
                        PluginTier.CONTAINED, PluginTier.OPEN -> loadContained(runtime, record, tier)
                    }
                }
            }
            if (loaded && isolatedFull && ShizukuRelays.allowed(record, grants)) {
                relayed[id] = record
                listenForShizuku()
                withContext(Dispatchers.IO) { runCatching { ShizukuRelays.attach(appContext, runtime, record) } }
            }
            val doneAt = android.os.SystemClock.elapsedRealtime()
            Log.i("droidtop.plugin", "$id: start ${record.manifest.kind.id} ${tier.name.lowercase()}: process ${processAt - startedAt} ms, load ${doneAt - processAt} ms, loaded=$loaded")
            if (!loaded) {
                // The reason is the process's own (runtime missing, version mismatch, a refusal); a crash it also reported already disabled the plugin.
                failLoad(id, runCatching { runtime.lastLoadError(id) }.getOrNull().orEmpty().ifBlank { loadFailures[id] ?: "plugin failed to load" })
            }
            loaded
        } catch (e: TimeoutCancellationException) {
            // A slow first start (a runtime's cold start) is "not ready yet", not a crash: the load keeps going in the plugin process and the next call joins it.
            failLoad(id, "still starting up (the first start can take a while); try again in a moment")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: "load failed across the binder"
            onCrash(id, "", reason)
            failLoad(id, reason)
        }
    }

    /**
     * Opens what the contained plugin needs, hands it over and closes droidtop's own copies of the descriptors, with the
     * socket to a file broker started for its process here ([SandboxBroker]).
     */
    private fun loadContained(runtime: IPluginRuntime, record: PluginRecord, tier: PluginTier): Boolean {
        val id = record.manifest.id
        val files = when (val result = ContainedFiles.forRecord(appContext, record)) {
            is ContainedFiles.Result.Missing -> {
                loadFailures[id] = result.reason
                return false
            }
            is ContainedFiles.Result.Files -> result.files
        }
        val opened = ArrayList<ParcelFileDescriptor>(files.size + 1)
        val names = files.map { it.first }.toMutableList()
        try {
            files.forEach { (_, file) -> opened += ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
            // Full access in an isolated process has the network and a contained process's files: the rest is the API's.
            val holes = when (tier) {
                PluginTier.OPEN -> PluginTiers.holes(record, PluginGrants.forContext(appContext).read(id))
                PluginTier.FULL_TRUST -> setOf(PluginTiers.NET_SOCKETS)
                PluginTier.CONTAINED -> emptySet()
            }
            val broker = SandboxBroker.serve(appContext, id, if (tier == PluginTier.FULL_TRUST) PluginTier.CONTAINED else tier, holes)
            if (broker == null) {
                loadFailures[id] = "its file broker could not be started"
                return false
            }
            opened += broker
            names += ContainedFiles.BROKER
            // The run-anyway mark and the holes are droidtop's alone: a plugin's own manifest can never carry them.
            val manifest = JSONObject(java.io.File(PluginStore.payloadDirFor(appContext, record), "manifest.json").readText()).apply {
                remove(PluginDangers.RUN_UNPROTECTED_KEY)
                if (PluginDangers.allowedThisRun(id)) put(PluginDangers.RUN_UNPROTECTED_KEY, true)
                remove(PluginTiers.HOLES_KEY)
                if (holes.isNotEmpty()) put(PluginTiers.HOLES_KEY, org.json.JSONArray(holes.toList()))
            }.toString()
            return runtime.loadContained(id, manifest, opened.toTypedArray(), names.toTypedArray(), PluginBrokers.binderFor(appContext, id))
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    /**
     * An app interface plugin (docs/plugin-api.md 1.3): its app's own service, bound by [AppInterfaceConnections], loaded
     * the contained way with no files and the manifest droidtop verified itself, never one the app gives, and handed its
     * broker. The app is in its own process with its own permissions; droidtop opens nothing for it.
     */
    private suspend fun loadApp(record: PluginRecord): Boolean {
        val id = record.manifest.id
        val runtime = AppInterfaceConnections.acquire(appContext, record)
            ?: return failLoad(id, "its app's service could not be reached")
        runtimes.put(id, runtime)?.let { if (it.asBinder() !== runtime.asBinder()) AppInterfaceConnections.release(appContext, id) }
        val binder = runtime.asBinder()
        if (registered.putIfAbsent(binder, runtime) == null) {
            val callback = ProcessCallback(binder).also { callbacks[binder] = it }
            runCatching { runtime.registerCallback(callback) }
        }
        return try {
            val loaded = withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val manifest = java.io.File(PluginStore.payloadDirFor(appContext, record), "manifest.json").readText()
                    runtime.loadContained(id, manifest, emptyArray(), emptyArray(), PluginBrokers.binderFor(appContext, id))
                }
            }
            if (!loaded) failLoad(id, runCatching { runtime.lastLoadError(id) }.getOrNull().orEmpty().ifBlank { "its app did not take the plugin" })
            loaded
        } catch (e: TimeoutCancellationException) {
            failLoad(id, "its app did not answer in time; try again in a moment")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Its app is not droidtop's process: a failure there is reported, never counted as a droidtop plugin crash.
            failLoad(id, e.message ?: "its app could not be reached")
        }
    }

    /** The process [pluginId] was loaded in by this runner, for the calls that are not plugin calls (its screen). */
    fun runtimeOf(pluginId: String): IPluginRuntime? = runtimes[pluginId]

    /** What [pluginId]'s process reports it can reach (the containment check, docs/plugin-api.md 5.3); null when it is not loaded here. */
    suspend fun reachability(pluginId: String): JSONObject? {
        val runtime = runtimes[pluginId] ?: return null
        return withContext(Dispatchers.IO) { runCatching { JSONObject(runtime.reachability()) }.getOrNull() }
    }

    override fun unload(pluginId: String) {
        relayed.remove(pluginId)
        runCatching { runtimes[pluginId]?.unloadPlugin(pluginId) }
    }

    override suspend fun invoke(pluginId: String, capability: PluginCapability, args: Map<String, String>): PluginResult {
        val runtime = runtimes[pluginId] ?: return PluginResult.failure("plugin process is not running")
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        val cold = isCold(pluginId, runtime)
        val budget = PluginRunner.CALL_TIMEOUT_MS + if (cold) PluginRunner.COLD_START_GRACE_MS else 0L
        return try {
            val resultJson = withTimeout(budget) {
                withContext(Dispatchers.IO) { runtime.invoke(pluginId, capability.id, argsJson) }
            } ?: return PluginResult.failure("plugin returned no result")
            answeredOn[pluginId] = runtime.asBinder()
            decode(resultJson)
        } catch (e: TimeoutCancellationException) {
            if (cold) return PluginResult.failure("still starting up (the first call can take a while); try again in a moment")
            onCrash(pluginId, capability.id, "call timed out after ${budget}ms")
            PluginResult.failure("timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onCrash(pluginId, capability.id, e.message ?: "call failed across the binder")
            PluginResult.failure(e.message ?: "call failed")
        }
    }

    /** [PluginEvent]'s analogue of [invoke]: the same watchdog and the same AIDL method, with the event's id in the capability slot. */
    suspend fun notifyEvent(pluginId: String, event: PluginEvent, args: Map<String, String>): PluginResult {
        val runtime = runtimes[pluginId] ?: return PluginResult.failure("plugin process is not running")
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        return try {
            val resultJson = withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { runtime.invoke(pluginId, event.id, argsJson) }
            } ?: return PluginResult.failure("plugin returned no result")
            decode(resultJson)
        } catch (e: TimeoutCancellationException) {
            onCrash(pluginId, event.id, "event call timed out after ${PluginRunner.CALL_TIMEOUT_MS}ms")
            PluginResult.failure("timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onCrash(pluginId, event.id, e.message ?: "event call failed across the binder")
            PluginResult.failure(e.message ?: "call failed")
        }
    }

    /**
     * Sends one v2 envelope ([PluginCall]) and waits at most [timeoutMs]. The binder call runs on the IO dispatcher so
     * the timeout does not wait for a hung plugin. A timeout is a crash only when [crashOnTimeout] (the default 15 s
     * budget, a provider's deadline); the shorter budgets of docs/plugin-api.md 8 are the UI choosing not to wait. An
     * exception or the process dying is always a crash.
     */
    suspend fun handle(pluginId: String, call: PluginCall, timeoutMs: Long, crashOnTimeout: Boolean): PluginReply {
        val runtime = runtimes[pluginId] ?: return PluginReply.error(PluginErrorCode.FAILED, "plugin process is not running")
        // Only the default budget is extended: a shorter one is the UI choosing not to wait, and stays short.
        val cold = crashOnTimeout && isCold(pluginId, runtime)
        val budget = timeoutMs + if (cold) PluginRunner.COLD_START_GRACE_MS else 0L
        val askedAt = android.os.SystemClock.elapsedRealtime()
        return try {
            val text = withTimeout(budget) {
                withContext(Dispatchers.IO) { runtime.handle(pluginId, call.toJson().toString()) }
            }
            if (answeredOn.put(pluginId, runtime.asBinder()) !== runtime.asBinder()) {
                Log.i("droidtop.plugin", "$pluginId: first call ${call.point} ${call.op} answered in ${android.os.SystemClock.elapsedRealtime() - askedAt} ms")
            }
            PluginReply.parse(text)
        } catch (e: TimeoutCancellationException) {
            if (cold) return PluginReply.error(PluginErrorCode.TIMEOUT, "still starting up (the first call can take a while); try again in a moment")
            if (crashOnTimeout) onCrash(pluginId, call.point, "call timed out after ${budget}ms")
            PluginReply.error(PluginErrorCode.TIMEOUT, "timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onCrash(pluginId, call.point, e.message ?: "call failed across the binder")
            PluginReply.error(PluginErrorCode.FAILED, e.message ?: "call failed")
        }
    }

    private fun decode(resultJson: String): PluginResult {
        return try {
            val obj = JSONObject(resultJson)
            val ok = obj.optBoolean("ok", false)
            if (!ok) return PluginResult.failure(obj.optString("error", "plugin call failed"))
            val values = obj.optJSONObject("values") ?: JSONObject()
            val map = buildMap { values.keys().forEach { k -> put(k, values.optString(k)) } }
            PluginResult.success(map)
        } catch (e: Exception) {
            PluginResult.failure("malformed result from plugin")
        }
    }

    /**
     * Starts a long-running job (see [PluginJob]) under the caller-chosen [jobId]. False when the plugin's process
     * rejected it outright (not loaded); a plugin without jobs is reported later as an ordinary job failure. Not
     * watchdog-bound, unlike [invoke].
     */
    suspend fun startJob(pluginId: String, capability: PluginCapability, args: Map<String, String>, jobId: String): Boolean {
        val runtime = runtimes[pluginId] ?: return false
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        return withContext(Dispatchers.IO) { runCatching { runtime.startJob(pluginId, capability.id, argsJson, jobId) }.getOrDefault(false) }
    }

    fun cancelJob(pluginId: String, jobId: String) {
        runCatching { runtimes[pluginId]?.cancelJob(pluginId, jobId) }
    }

    /** Lets go of every process this runner used: its callback is unregistered and each process starts its idle countdown. */
    fun unbind() {
        registered.forEach { (binder, runtime) -> callbacks[binder]?.let { runCatching { runtime.unregisterCallback(it) } } }
        registered.clear()
        callbacks.clear()
        runtimes.keys.toList().forEach { id ->
            if (AppInterfaceConnections.holds(id)) AppInterfaceConnections.release(appContext, id) else PluginProcesses.release(appContext, id)
        }
        runtimes.clear()
    }
}
