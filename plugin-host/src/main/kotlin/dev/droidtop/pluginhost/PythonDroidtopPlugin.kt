package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * The `python`-kind [DroidtopPlugin] adapter (docs/SPEC.md 12a):
 * everything [PluginRuntimeService] otherwise gets from a `native_bundle`
 * plugin's compiled class, this gets from a single `plugin.py` file run
 * inside the process-wide interpreter [PythonBridge] owns. Loaded into
 * [PluginRuntimeService]'s own `loaded` map exactly like a
 * `native_bundle` plugin -- `invoke`/`startJob`/`unload` call sites there
 * never need to know which kind they're holding.
 *
 * [uniqueName] (the plugin id) is what keeps two python-kind plugins'
 * module globals from colliding inside the one shared interpreter (see
 * `native/src/droidtoppy_jni.c`'s header comment).
 */
class PythonDroidtopPlugin(
    private val uniqueName: String,
    private val scriptPath: String,
    private val dataDir: String,
    /** The manifest's contract: 2 means `plugin.py` answers `handle` itself. */
    private val contractVersion: Int = 1,
) : DroidtopPlugin {
    override fun onLoad(context: PluginContext) {
        // Throws PythonCallException on any failure -- caught by
        // PluginRuntimeService.loadPlugin's existing catch(Throwable),
        // same as a native_bundle plugin's onLoad throwing.
        PythonBridge.nativeLoadModule(uniqueName, scriptPath, dataDir)
    }

    override fun onUnload() {
        PythonBridge.nativeUnloadModule(uniqueName)
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult {
        val argsJson = JSONObject().apply { args.keys().forEach { put(it, args.string(it)) } }.toString()
        val payload = JSONObject().apply {
            put("capability", capability.id)
            put("args", JSONObject(argsJson))
        }
        // Throws PythonCallException on an unhandled Python-side
        // exception -- the normal "this call failed" path (PluginApi.kt's
        // own doc comment on DroidtopPlugin.invoke), not swallowed here.
        val resultJson = PythonBridge.nativeCallFunction(uniqueName, "invoke", payload.toString())
        return try {
            val obj = JSONObject(resultJson)
            val ok = obj.optBoolean("ok", false)
            if (!ok) return PluginResult.failure(obj.optString("error", "python plugin call failed"))
            val values = obj.optJSONObject("values") ?: JSONObject()
            PluginResult.success(buildMap { values.keys().forEach { k -> put(k, values.optString(k)) } })
        } catch (e: Exception) {
            PluginResult.failure("malformed result from python plugin: ${e.message}")
        }
    }

    /**
     * The contract 2 envelope (docs/plugin-api.md 1.3, 1.6): a contract 2 `plugin.py`
     * defines `handle(call_json) -> reply_json`, JSON text both ways like `invoke`. A
     * contract 1 plugin keeps the translation onto its capabilities. An unhandled
     * Python exception propagates as a crash, the same as from `invoke`.
     */
    override fun onEvent(event: PluginEvent, args: PluginArgs): PluginResult {
        val payload = JSONObject().apply {
            put("event", event.id)
            put("args", JSONObject().apply { args.keys().forEach { put(it, args.string(it)) } })
        }.toString()
        val resultJson = try {
            PythonBridge.nativeCallFunction(uniqueName, "on_event", payload)
        } catch (e: PythonCallException) {
            if (e.message?.contains("on_event", ignoreCase = true) == true) {
                return PluginResult.success()
            }
            return PluginResult.failure(e.message ?: "python plugin event call failed")
        }
        return try {
            val obj = JSONObject(resultJson)
            val ok = obj.optBoolean("ok", false)
            if (!ok) return PluginResult.failure(obj.optString("error", "python plugin event call failed"))
            val values = obj.optJSONObject("values") ?: JSONObject()
            PluginResult.success(buildMap { values.keys().forEach { k -> put(k, values.optString(k)) } })
        } catch (e: Exception) {
            PluginResult.failure("malformed result from python plugin event: ${e.message}")
        }
    }

    override fun handle(call: PluginCall): PluginReply {
        if (contractVersion < 2) return LegacyHandle.translate(this, call)
        return PluginReply.parse(PythonBridge.nativeCallFunction(uniqueName, "handle", call.toJson().toString()))
    }

    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        val payload = JSONObject().apply {
            put("job_id", jobId)
            put("call", JSONObject().apply { args.keys().forEach { put(it, args.string(it)) } })
        }.toString()
        val result = JSONObject(PythonBridge.nativeCallFunction(uniqueName, "start_job", payload))
        val reports = result.optJSONArray("progress")
        if (reports != null) {
            for (index in 0 until reports.length()) {
                val report = reports.optJSONObject(index) ?: continue
                progress.report(report.optInt("percent", -1), report.optString("status", ""))
            }
        }
        val ok = result.optBoolean("ok", false)
        val values = result.optJSONObject("values") ?: JSONObject()
        progress.complete(if (ok) {
            PluginResult.success(buildMap { values.keys().forEach { key -> put(key, values.optString(key)) } })
        } else PluginResult.failure(result.optString("error", "python plugin job failed")))
    }

    override fun cancelJob(jobId: String) {
        PythonBridge.nativeCallFunction(uniqueName, "cancel_job", JSONObject().put("job_id", jobId).toString())
    }

    companion object {
        /**
         * Builds the [PythonDroidtopPlugin] for [pluginId] if this
         * device has both the runtime installed and the plugin's own
         * `plugin.py` payload -- returns a reason string instead of
         * throwing when either is missing, since "runtime not
         * downloaded yet" is an ordinary, expected state (the runtime is
         * a separate, explicit download -- [PythonRuntimeManager] -- not
         * something a plugin load triggers on its own), not a crash.
         */
        fun forInstall(context: Context, pluginId: String, installDir: File): Result<PythonDroidtopPlugin> {
            val script = File(installDir, "plugin.py")
            if (!script.isFile) {
                return Result.failure(IllegalStateException("plugin payload has no plugin.py"))
            }
            val pythonHome = PythonRuntimeManager.pythonHomeDir(context)
                ?: return Result.failure(IllegalStateException("Python runtime not installed -- download it in Settings > Plugins first"))
            val libpython = PythonRuntimeManager.libpythonSoPath(context)
                ?: return Result.failure(IllegalStateException("Python runtime is installed but its libpython is missing"))
            if (!PythonBridge.nativeInit(pythonHome.absolutePath, libpython.absolutePath)) {
                return Result.failure(IllegalStateException("failed to initialize the Python interpreter"))
            }
            val dataDir = File(installDir, "data").apply { mkdirs() }
            val contract = runCatching {
                PluginManifest.fromJson(JSONObject(File(installDir, "manifest.json").readText()))?.contractVersion ?: 1
            }.getOrDefault(1)
            return Result.success(PythonDroidtopPlugin(pluginId, script.absolutePath, dataDir.absolutePath, contract))
        }
    }
}
