package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A `native_bundle` plugin's host without a device (docs/plugin-api.md 3 I2, Droidtop/tracker#71). The plugin's class
 * runs in this JVM; its [PluginContext] calls go through droidtop's own broker ([BrokerCore]) over [FakeEnv]'s
 * in-memory droidtop, so a grant, a refusal or an op's checks are the real ones. The test sets grants on [env].
 */
class FakeHost(
    val manifest: PluginManifest,
    val plugin: DroidtopPlugin,
    val env: FakeEnv = FakeEnv(TestPlugins.record(manifest)),
) {
    private val broker = BrokerCore(manifest.id, env)

    val context: PluginContext = object : PluginContext {
        override fun call(api: String, version: Int, op: String, argsJson: String): String {
            val args = runCatching { JSONObject(argsJson) }.getOrElse { JSONObject() }
            return broker.call(JSONObject().put("api", api).put("version", version).put("op", op).put("args", args).toString())
        }
        override fun privateDataDir() = ""
        override fun libraryFolderPath(systemId: String): String? = null
        override fun hasRootApproval() = false
        override fun hasShizukuAccess() = false
        override fun isAppInstalled(packageName: String) = packageName in env.installed
        override fun launchApp(packageName: String) = env.launchApp(packageName)
        override fun launchAppWithExtras(packageName: String, extras: Map<String, String>, action: String?) =
            env.launchAppWithExtras(packageName, extras, action)
    }

    init {
        plugin.onLoad(context)
    }

    fun grant(permission: String, state: GrantState) = env.setGrant(manifest.id, permission, state)

    /** One call, as the runtime makes it; a throw is the plugin's crash and is the caller's to see. */
    fun handle(call: PluginCall): PluginReply = plugin.handle(call)

    /**
     * One job under [call], as the runtime starts a contract 2 job (the envelope in `args["call"]`), waited for at most
     * [timeoutMs]. The outcome is `{progress: [{percent, status}], ok, values, error}`, the same shape the python fake
     * host gives, so the conformance cases read both alike.
     */
    fun runJob(call: PluginCall, jobId: String = "job-1", timeoutMs: Long = 10_000): JSONObject {
        val progress = JSONArray()
        val finished = AtomicReference<PluginResult?>(null)
        val done = CountDownLatch(1)
        val args = PluginArgs(mapOf("call" to call.toJson().toString()))
        plugin.startJob(jobId, LegacyManifest.jobCapabilityFor(call.point), args, object : PluginJobProgress {
            override fun report(percent: Int, statusLine: String) {
                synchronized(progress) { progress.put(JSONObject().put("percent", percent).put("status", statusLine)) }
            }
            override fun complete(result: PluginResult) {
                // Exactly once (PluginJobProgress): a second completion is ignored, as the runtime ignores it.
                if (finished.compareAndSet(null, result)) done.countDown()
            }
        })
        val outcome = JSONObject().put("progress", JSONArray())
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            plugin.cancelJob(jobId)
            return outcome.put("ok", false).put("error", "the job did not complete in ${timeoutMs}ms")
        }
        val r = finished.get()!!
        synchronized(progress) { outcome.put("progress", JSONArray(progress.toString())) }
        outcome.put("ok", r.ok)
        return if (r.ok) outcome.put("values", JSONObject(r.values)) else outcome.put("error", r.error ?: "")
    }

    fun unload() = plugin.onUnload()
}
