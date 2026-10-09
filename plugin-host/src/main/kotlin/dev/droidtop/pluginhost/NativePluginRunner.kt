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

    /** The processes this runner registered [callback] with, so [unbind] can unregister the very same stub. */
    private val registered = ConcurrentHashMap<IBinder, IPluginRuntime>()

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
     */
    private val callback = object : IPluginRuntimeCallback.Stub() {
        override fun onPluginCrashed(pluginId: String, capability: String, reason: String) {
            onCrash(pluginId, capability, reason)
        }

        override fun onJobProgress(pluginId: String, jobId: String, percent: Int, statusLine: String, resumePayload: String?) {
            this@NativePluginRunner.onJobProgress(pluginId, jobId, percent, statusLine, resumePayload)
        }

        override fun onJobComplete(pluginId: String, jobId: String, resultJson: String) {
            this@NativePluginRunner.onJobComplete(pluginId, jobId, decode(resultJson))
        }

        override fun onScreenClosed(pluginId: String) {
            PluginScreenActivity.closed(pluginId)
        }

        override fun onScreenFrame(pluginId: String, index: Int, width: Int, height: Int) {
            PluginScreenActivity.frame(pluginId, index, width, height)
        }
    }

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
        val runtime = PluginProcesses.acquire(appContext, id, tier)
            ?: return failLoad(id, if (tier == PluginTier.CONTAINED) "its contained process could not be started" else "the plugin process could not be started")
        if (runtimes.put(id, runtime) != null) PluginProcesses.release(appContext, id)
        val binder = runtime.asBinder()
        if (registered.putIfAbsent(binder, runtime) == null) runCatching { runtime.registerCallback(callback) }
        return try {
            // A blocking binder call: on the IO dispatcher so the budget below can fire and a caller on the main thread is
            // never held for a cold start (rig, 2026-09-30: 15 s of skipped frames while a Flutter plugin started).
            val loaded = withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    when (tier) {
                        PluginTier.FULL_TRUST ->
                            // entryClass is null for any kind but native_bundle; the AIDL parameter is a non-null placeholder there.
                            runtime.loadPlugin(id, installDir, record.manifest.entryClass ?: "", record.rootApproved, PluginBrokers.binderFor(appContext, id))
                        // A gpu.render process loads the plugin from the same descriptors as a contained one; it differs only
                        // in being a non-isolated, GPU-capable process (docs/plugin-api.md 5.3, "The graphics tier").
                        PluginTier.CONTAINED, PluginTier.GPU_RENDER -> loadContained(runtime, record)
                    }
                }
            }
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

    /** Opens what the contained plugin needs, hands it over and closes droidtop's own copies of the descriptors. */
    private fun loadContained(runtime: IPluginRuntime, record: PluginRecord): Boolean {
        val id = record.manifest.id
        val files = when (val result = ContainedFiles.forRecord(appContext, record)) {
            is ContainedFiles.Result.Missing -> {
                loadFailures[id] = result.reason
                return false
            }
            is ContainedFiles.Result.Files -> result.files
        }
        val opened = ArrayList<ParcelFileDescriptor>(files.size)
        try {
            files.forEach { (_, file) -> opened += ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }
            val manifest = JSONObject(java.io.File(PluginStore.payloadDirFor(appContext, id), "manifest.json").readText()).toString()
            return runtime.loadContained(id, manifest, opened.toTypedArray(), files.map { it.first }.toTypedArray(), PluginBrokers.binderFor(appContext, id))
        } finally {
            opened.forEach { runCatching { it.close() } }
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
        runCatching { runtimes[pluginId]?.unloadPlugin(pluginId) }
    }

    override suspend fun invoke(pluginId: String, capability: PluginCapability, args: Map<String, String>): PluginResult {
        val runtime = runtimes[pluginId] ?: return PluginResult.failure("plugin process is not running")
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        return try {
            val resultJson = withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { runtime.invoke(pluginId, capability.id, argsJson) }
            } ?: return PluginResult.failure("plugin returned no result")
            decode(resultJson)
        } catch (e: TimeoutCancellationException) {
            onCrash(pluginId, capability.id, "call timed out after ${PluginRunner.CALL_TIMEOUT_MS}ms")
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
        return try {
            val text = withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) { runtime.handle(pluginId, call.toJson().toString()) }
            }
            PluginReply.parse(text)
        } catch (e: TimeoutCancellationException) {
            if (crashOnTimeout) onCrash(pluginId, call.point, "call timed out after ${timeoutMs}ms")
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
        registered.values.forEach { runtime -> runCatching { runtime.unregisterCallback(callback) } }
        registered.clear()
        runtimes.keys.toList().forEach { PluginProcesses.release(appContext, it) }
        runtimes.clear()
    }
}
