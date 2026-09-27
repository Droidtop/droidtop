package dev.droidtop.pluginhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * [PluginRunner] for [PluginKind.NATIVE_BUNDLE]: binds
 * [PluginRuntimeService] (a separate process, this module's manifest) and
 * drives it over [IPluginRuntime]. Owns the crash-containment plumbing on
 * the :app side -- a [android.os.IBinder.DeathRecipient] for the process
 * dying outright, and [IPluginRuntimeCallback] for a plugin that threw
 * but left the process alive -- and reports both the same way, through
 * [onCrash], so a caller (SPI 5: [PluginCrashPolicy]) never has to know
 * which kind of failure it was.
 */
class NativePluginRunner(
    private val context: Context,
    private val onCrash: (pluginId: String, capability: String, reason: String) -> Unit,
    private val onJobProgress: (pluginId: String, jobId: String, percent: Int, statusLine: String) -> Unit = { _, _, _, _ -> },
    private val onJobComplete: (pluginId: String, jobId: String, result: PluginResult) -> Unit = { _, _, _ -> },
) : PluginRunner {
    private var connection: IPluginRuntime? = null
    private var serviceConnection: ServiceConnection? = null
    private var connectDeferred: CompletableDeferred<IPluginRuntime?>? = null

    // Kept so [unbind] can call [IPluginRuntime.unregisterCallback] with
    // the EXACT same stub instance [registerCallback] was given --
    // PluginRuntimeService.kt now fans every event out to every
    // registered caller (docs/SPEC.md 12a "Jobs": found and fixed
    // 2026-09-27, a single shared callback field meant a second
    // concurrent connection silently stole delivery from the first), so
    // a connection that never unregisters keeps costing every future
    // broadcast a wasted (though harmless, `runCatching`-guarded)
    // delivery attempt for as long as :pluginhost stays alive.
    private var callbackStub: IPluginRuntimeCallback.Stub? = null

    private suspend fun ensureConnected(): IPluginRuntime? {
        connection?.let { return it }
        val deferred = CompletableDeferred<IPluginRuntime?>()
        connectDeferred = deferred
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val runtime = IPluginRuntime.Stub.asInterface(binder)
                binder?.linkToDeath(
                    {
                        // The process died outright -- every plugin this
                        // runner had loaded is gone with it. onCrash is
                        // called per still-tracked plugin by the caller
                        // (PluginCrashPolicy holds that list, this class
                        // only knows about the connection).
                        connection = null
                        onCrash("", "", "the plugin process died")
                    },
                    0,
                )
                val stub = object : IPluginRuntimeCallback.Stub() {
                    override fun onPluginCrashed(pluginId: String, capability: String, reason: String) {
                        onCrash(pluginId, capability, reason)
                    }

                    override fun onJobProgress(pluginId: String, jobId: String, percent: Int, statusLine: String) {
                        this@NativePluginRunner.onJobProgress(pluginId, jobId, percent, statusLine)
                    }

                    override fun onJobComplete(pluginId: String, jobId: String, resultJson: String) {
                        this@NativePluginRunner.onJobComplete(pluginId, jobId, decode(resultJson))
                    }
                }
                callbackStub = stub
                runtime.registerCallback(stub)
                connection = runtime
                deferred.complete(runtime)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connection = null
            }
        }
        serviceConnection = conn
        val bound = context.bindService(PluginRuntimeService.bindIntent(context), conn, Context.BIND_AUTO_CREATE)
        if (!bound) {
            deferred.complete(null)
        }
        return deferred.await()
    }

    override suspend fun load(record: PluginRecord, installDir: String): Boolean {
        // entryClass is genuinely null for anything but NATIVE_BUNDLE --
        // PluginManifest.structuralProblems() only requires it for that
        // kind, and PluginRuntimeService.loadPlugin's own doc comment
        // says the python path never reads it (its entry point is always
        // installDir's own plugin.py). The AIDL parameter itself is a
        // non-null String, so this used to bail out here with `?: return
        // false` before ever binding the service -- a python plugin's
        // "Call ... status tile" failed with "plugin failed to load" and
        // no process, no exception and nothing in logcat (rig,
        // dq-pyplugin-01 follow-up), because load() never got far enough
        // to try. An empty string is the same "unused" placeholder
        // PluginRuntimeService already documents.
        val entryClass = record.manifest.entryClass ?: ""
        val runtime = ensureConnected() ?: return false
        return try {
            withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                runtime.loadPlugin(record.manifest.id, installDir, entryClass, record.rootApproved)
            }
        } catch (e: TimeoutCancellationException) {
            onCrash(record.manifest.id, "", "load timed out")
            false
        } catch (e: Exception) {
            onCrash(record.manifest.id, "", e.message ?: "load failed across the binder")
            false
        }
    }

    override fun unload(pluginId: String) {
        runCatching { connection?.unloadPlugin(pluginId) }
    }

    override suspend fun invoke(pluginId: String, capability: PluginCapability, args: Map<String, String>): PluginResult {
        val runtime = connection ?: ensureConnected() ?: return PluginResult.failure("plugin process is not running")
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        return try {
            val resultJson = withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                runtime.invoke(pluginId, capability.id, argsJson)
            } ?: return PluginResult.failure("plugin returned no result")
            decode(resultJson)
        } catch (e: TimeoutCancellationException) {
            onCrash(pluginId, capability.id, "call timed out after ${PluginRunner.CALL_TIMEOUT_MS}ms")
            PluginResult.failure("timed out")
        } catch (e: Exception) {
            onCrash(pluginId, capability.id, e.message ?: "call failed across the binder")
            PluginResult.failure(e.message ?: "call failed")
        }
    }

    /**
     * [PluginEvent]'s own analogue of [invoke] -- same watchdog, same
     * crash-containment path, sent over the same [IPluginRuntime.invoke]
     * call with the event's id in the capability slot (see
     * [PluginRuntimeService.invoke]'s own doc comment for why this
     * shares the one AIDL method rather than adding a second).
     */
    suspend fun notifyEvent(pluginId: String, event: PluginEvent, args: Map<String, String>): PluginResult {
        val runtime = connection ?: ensureConnected() ?: return PluginResult.failure("plugin process is not running")
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        return try {
            val resultJson = withTimeout(PluginRunner.CALL_TIMEOUT_MS) {
                runtime.invoke(pluginId, event.id, argsJson)
            } ?: return PluginResult.failure("plugin returned no result")
            decode(resultJson)
        } catch (e: TimeoutCancellationException) {
            onCrash(pluginId, event.id, "event call timed out after ${PluginRunner.CALL_TIMEOUT_MS}ms")
            PluginResult.failure("timed out")
        } catch (e: Exception) {
            onCrash(pluginId, event.id, e.message ?: "event call failed across the binder")
            PluginResult.failure(e.message ?: "call failed")
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
     * Starts a long-running job (see [PluginJob]) under the CALLER-chosen
     * [jobId] -- see [IPluginRuntime.startJob]'s own doc comment for why
     * this process no longer generates and returns one. Returns false
     * when the plugin process rejected it outright (not loaded); does
     * NOT mean the plugin doesn't support jobs, which is reported later
     * as an ordinary job failure over the callback. Not watchdog-bound,
     * unlike [invoke].
     */
    suspend fun startJob(pluginId: String, capability: PluginCapability, args: Map<String, String>, jobId: String): Boolean {
        val runtime = connection ?: ensureConnected() ?: return false
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        return runCatching { runtime.startJob(pluginId, capability.id, argsJson, jobId) }.getOrDefault(false)
    }

    fun cancelJob(pluginId: String, jobId: String) {
        runCatching { connection?.cancelJob(pluginId, jobId) }
    }

    fun unbind() {
        runCatching { callbackStub?.let { stub -> connection?.unregisterCallback(stub) } }
        serviceConnection?.let { runCatching { context.unbindService(it) } }
        serviceConnection = null
        connection = null
        callbackStub = null
    }
}
