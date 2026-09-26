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
                runtime.setCallback(object : IPluginRuntimeCallback.Stub() {
                    override fun onPluginCrashed(pluginId: String, capability: String, reason: String) {
                        onCrash(pluginId, capability, reason)
                    }

                    override fun onJobProgress(pluginId: String, jobId: String, percent: Int, statusLine: String) {
                        this@NativePluginRunner.onJobProgress(pluginId, jobId, percent, statusLine)
                    }

                    override fun onJobComplete(pluginId: String, jobId: String, resultJson: String) {
                        this@NativePluginRunner.onJobComplete(pluginId, jobId, decode(resultJson))
                    }
                })
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

    /** Starts a long-running job (see [PluginJob]); null means the plugin process rejected it (not loaded, or doesn't implement [DroidtopPlugin.startJob]). Not watchdog-bound, unlike [invoke]. */
    suspend fun startJob(pluginId: String, capability: PluginCapability, args: Map<String, String>): String? {
        val runtime = connection ?: ensureConnected() ?: return null
        val argsJson = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }.toString()
        return runCatching { runtime.startJob(pluginId, capability.id, argsJson) }.getOrNull()
    }

    fun cancelJob(pluginId: String, jobId: String) {
        runCatching { connection?.cancelJob(pluginId, jobId) }
    }

    fun unbind() {
        serviceConnection?.let { runCatching { context.unbindService(it) } }
        serviceConnection = null
        connection = null
    }
}
