package dev.droidtop.pluginhost

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteCallbackList
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * What every plugin process runs (docs/plugin-api.md 5.3 and 8): the one [IPluginRuntime] binder, the plugins loaded in this
 * process, and the crash and job reports back to :app. Two kinds of process extend it, and they differ only in how a plugin's
 * code gets in and what its [PluginContext] can answer locally:
 *
 * - [PluginRuntimeService], full trust: droidtop's own UID, the plugin loaded from its install folder, one process per plugin
 *   from a pool of declared slots. A plugin here can still do anything droidtop's UID can, so this process is crash
 *   containment, not a security boundary.
 * - [PluginSandboxService], contained: an isolated process with a random UID of its own, no permissions, no network and no
 *   access to droidtop's files or shared storage. The plugin's code arrives as file descriptors and everything it does
 *   beyond its own process goes through its broker, where the grant, the quota and the audit apply.
 *
 * Either way a plugin that throws, hangs or native-crashes takes down its own process, never :app.
 */
abstract class PluginProcessService : Service() {
    // Binder threads (a pool) read and write these concurrently: a second load
    // of the same plugin arrives while the first is still starting its engine.
    protected val loaded = ConcurrentHashMap<String, DroidtopPlugin>()

    /** Why each plugin's last load returned false ([IPluginRuntime.lastLoadError]); also logged, so logcat has the reason too. */
    private val loadErrors = ConcurrentHashMap<String, String>()

    /** Serialises loads: a caller that gave up waiting (its own timeout) and retries must join the load still in flight, not start a second engine beside it. */
    private val loadLock = Any()

    /**
     * Every caller currently connected, not just the most recent one: a single
     * nullable field was silently stolen by the second concurrent connection.
     * [RemoteCallbackList] drops a callback by itself once the binder behind it dies.
     */
    private val callbacks = RemoteCallbackList<IPluginRuntimeCallback>()

    // RemoteCallbackList.beginBroadcast cannot be outstanding on two threads at once (throws if it is);
    // the three broadcast senders below share this lock so one job's progress callback never lands
    // mid-broadcast of another one's (confirmed on the rig, two concurrent core downloads).
    private val broadcastLock = Any()

    // One shared pool for every plugin's jobs in this process. A job is expected to run minutes, so it never runs on a binder thread.
    private val jobExecutor = Executors.newCachedThreadPool()

    protected fun failLoad(pluginId: String, reason: String): Boolean {
        loadErrors[pluginId] = reason
        Log.w("droidtop.plugin", "$pluginId did not load: $reason")
        return false
    }

    /** A thrown load is a crash (the plugin is disabled with the reason) and also the reason the caller reads. */
    protected fun loadCrashed(pluginId: String, t: Throwable): Boolean {
        val reason = "load failed: ${t.message ?: t::class.java.simpleName}"
        reportCrash(pluginId, "", reason)
        return failLoad(pluginId, reason)
    }

    /** Every load path ends here: `onLoad` with the plugin's context, then the plugin is callable. A throw is a crash. */
    protected fun start(pluginId: String, plugin: DroidtopPlugin, context: PluginContext): Boolean = try {
        plugin.onLoad(context)
        loaded[pluginId] = plugin
        true
    } catch (t: Throwable) {
        loadCrashed(pluginId, t)
    }

    /** Full trust: loads [pluginId] from its install folder [dir]. */
    protected abstract fun loadFromFolder(pluginId: String, dir: File, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean

    /** Contained: loads [pluginId] from [files], the descriptors :app opened for it, by name. Called with the files still open; they are closed after it returns, so a loader keeps what it needs by detaching it. */
    protected abstract fun loadFromFiles(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, broker: IPluginHostBroker): Boolean

    /** Anything this process knows about how its plugins loaded, added to [reachabilityReport]. */
    protected open fun loadNotes(): JSONObject = JSONObject()

    private val binder = object : IPluginRuntime.Stub() {
        override fun registerCallback(cb: IPluginRuntimeCallback?) {
            cb?.let { callbacks.register(it) }
        }

        override fun unregisterCallback(cb: IPluginRuntimeCallback?) {
            cb?.let { callbacks.unregister(it) }
        }

        // A plugin already loaded is already loaded: onLoad runs once (a second FlutterEngine in one process
        // broke its channel on the rig, 2026-09-26), so a repeated load returns true without touching it.
        override fun loadPlugin(pluginId: String, installDir: String, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean {
            synchronized(loadLock) {
                if (loaded.containsKey(pluginId)) return true
                loadErrors.remove(pluginId)
                return loadFromFolder(pluginId, File(installDir), entryClass, rootApproved, broker)
            }
        }

        override fun loadContained(pluginId: String, manifestJson: String, files: Array<ParcelFileDescriptor>?, names: Array<String>?, broker: IPluginHostBroker): Boolean {
            synchronized(loadLock) {
                try {
                    if (loaded.containsKey(pluginId)) return true
                    loadErrors.remove(pluginId)
                    val manifest = runCatching { PluginManifest.fromJson(JSONObject(manifestJson)) }.getOrNull()
                        ?: return failLoad(pluginId, "its manifest could not be read")
                    val fds = files.orEmpty()
                    val labels = names.orEmpty()
                    if (fds.size != labels.size) return failLoad(pluginId, "its files arrived without names")
                    return loadFromFiles(pluginId, manifest, labels.zip(fds).toMap(), broker)
                } finally {
                    files?.forEach { runCatching { it.close() } }
                }
            }
        }

        override fun lastLoadError(pluginId: String): String = loadErrors[pluginId].orEmpty()

        override fun reachability(): String = reachabilityReport().toString()

        override fun unloadPlugin(pluginId: String) {
            runCatching { loaded.remove(pluginId)?.onUnload() }
            loaded.remove(pluginId)
            loadErrors.remove(pluginId)
        }

        override fun invoke(pluginId: String, capability: String, argsJson: String): String? {
            val plugin = loaded[pluginId] ?: return null
            // Event ids and capability ids share this one string slot on purpose (docs/SPEC.md 12a "Event hooks"):
            // both are one JSON-in, JSON-out, watchdog-bound call. The two id sets are disjoint by construction.
            val event = PluginEvent.fromId(capability)
            if (event != null) {
                return try {
                    encode(plugin.onEvent(event, PluginArgs(flatArgs(argsJson))))
                } catch (t: Throwable) {
                    reportCrash(pluginId, capability, t.message ?: t::class.java.simpleName)
                    null
                }
            }
            val cap = PluginCapability.fromId(capability) ?: return null
            return try {
                encode(plugin.invoke(cap, PluginArgs(flatArgs(argsJson))))
            } catch (t: Throwable) {
                reportCrash(pluginId, capability, t.message ?: t::class.java.simpleName)
                null
            }
        }

        override fun handle(pluginId: String, envelopeJson: String): String? {
            val plugin = loaded[pluginId] ?: return null
            val call = PluginCall.fromJson(envelopeJson)
                ?: return PluginReply.error(PluginErrorCode.INVALID_ARGS, "the call envelope is not valid").encode()
            return try {
                LegacyHandle.dispatch(plugin, call).encode()
            } catch (t: Throwable) {
                reportCrash(pluginId, call.point, t.message ?: t::class.java.simpleName)
                null
            }
        }

        override fun startJob(pluginId: String, capability: String, argsJson: String, jobId: String): Boolean {
            val plugin = loaded[pluginId] ?: return false
            val cap = PluginCapability.fromId(capability) ?: return false
            val argsMap = try {
                flatArgs(argsJson)
            } catch (t: Throwable) {
                return false
            }
            val progress = object : PluginJobProgress {
                override fun report(percent: Int, statusLine: String) {
                    broadcastJobProgress(pluginId, jobId, percent, statusLine, null)
                }

                override fun checkpoint(percent: Int, statusLine: String, resumePayload: String?) {
                    broadcastJobProgress(pluginId, jobId, percent, statusLine, resumePayload?.takeIf { it.length <= MAX_RESUME_PAYLOAD_CHARS })
                }

                override fun complete(result: PluginResult) {
                    broadcastJobComplete(pluginId, jobId, result)
                }
            }
            jobExecutor.execute {
                try {
                    plugin.startJob(jobId, cap, PluginArgs(argsMap), progress)
                } catch (e: UnsupportedOperationException) {
                    // A plugin that never overrode startJob(): an ordinary shape, not a crash.
                    broadcastJobComplete(pluginId, jobId, PluginResult.failure("this plugin does not support jobs"))
                } catch (t: Throwable) {
                    reportCrash(pluginId, capability, t.message ?: t::class.java.simpleName)
                    broadcastJobComplete(pluginId, jobId, PluginResult.failure(t.message ?: "job failed"))
                }
            }
            return true
        }

        override fun cancelJob(pluginId: String, jobId: String) {
            runCatching { loaded[pluginId]?.cancelJob(jobId) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Releases every still-registered callback's binder reference. */
    override fun onDestroy() {
        callbacks.kill()
        super.onDestroy()
    }

    private fun flatArgs(argsJson: String): Map<String, String> = buildMap {
        val obj = JSONObject(argsJson)
        obj.keys().forEach { key -> put(key, obj.optString(key)) }
    }

    private fun encode(result: PluginResult): String {
        val json = JSONObject()
        json.put("ok", result.ok)
        result.error?.let { json.put("error", it) }
        val values = JSONObject()
        result.values.forEach { (k, v) -> values.put(k, v) }
        json.put("values", values)
        val text = json.toString()
        // Even a plugin that ran fine cannot hand back more than PluginRunner.MAX_RESULT_BYTES across the binder (12a point 5).
        return if (text.toByteArray(Charsets.UTF_8).size > PluginRunner.MAX_RESULT_BYTES) {
            JSONObject().put("ok", false).put("error", "result exceeds size cap").put("values", JSONObject()).toString()
        } else {
            text
        }
    }

    protected fun reportCrash(pluginId: String, capability: String, reason: String) {
        broadcastPluginCrashed(pluginId, capability, reason)
        loaded.remove(pluginId)
    }

    /**
     * What this process can reach (docs/plugin-api.md 5.3), measured from inside it: a contained process must report no
     * network, no listing of droidtop's files and none of shared storage. Opening a socket sends nothing: an isolated UID
     * is refused at socket() already, before any address is involved.
     */
    private fun reachabilityReport(): JSONObject {
        val out = JSONObject()
        out.put("isolated", dev.droidtop.runtime.util.IsolatedProcess.isIsolated())
        out.put("uid", Process.myUid())
        out.put("sdk", Build.VERSION.SDK_INT)
        out.put(
            "network",
            runCatching {
                val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
                Os.close(fd)
                "a socket opened"
            }.getOrElse { "refused (${it.message})" },
        )
        out.put("droidtopFiles", listing(File(applicationInfo.dataDir, "files")))
        @Suppress("DEPRECATION")
        val shared = Environment.getExternalStorageDirectory()
        out.put("sharedStorage", listing(shared))
        out.put("loaded", org.json.JSONArray(loaded.keys.toList()))
        out.put("notes", loadNotes())
        return out
    }

    private fun listing(dir: File): String = runCatching {
        val names = dir.list()
        if (names == null) "refused" else "readable (${names.size} entries)"
    }.getOrElse { "refused (${it.message})" }

    private fun broadcastJobProgress(pluginId: String, jobId: String, percent: Int, statusLine: String, resumePayload: String?) = synchronized(broadcastLock) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { callbacks.getBroadcastItem(i).onJobProgress(pluginId, jobId, percent, statusLine, resumePayload) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastJobComplete(pluginId: String, jobId: String, result: PluginResult) = synchronized(broadcastLock) {
        val json = encode(result)
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { callbacks.getBroadcastItem(i).onJobComplete(pluginId, jobId, json) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastPluginCrashed(pluginId: String, capability: String, reason: String) = synchronized(broadcastLock) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) {
                runCatching { callbacks.getBroadcastItem(i).onPluginCrashed(pluginId, capability, reason) }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private companion object {
        const val MAX_RESUME_PAYLOAD_CHARS = 4096
    }
}

/**
 * The broker half of every plugin's [PluginContext] (docs/plugin-api.md 1.4): each call goes to this plugin's own broker
 * object, which names the caller. The local answers are a contained plugin's: no folder, no root, no Shizuku. A full-trust
 * process overrides them ([PluginRuntimeService]).
 */
internal open class BrokerPluginContext(private val broker: IPluginHostBroker) : PluginContext {
    private fun request(api: String, version: Int, op: String, argsJson: String): String =
        JSONObject().put("api", api).put("version", version).put("op", op)
            .put("args", runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())).toString()

    override fun call(api: String, version: Int, op: String, argsJson: String): String = try {
        broker.call(request(api, version, op, argsJson))
            ?: PluginReply.error(PluginErrorCode.FAILED, "droidtop returned no reply").encode()
    } catch (t: Throwable) {
        PluginReply.error(PluginErrorCode.FAILED, "droidtop could not be reached: ${t.message ?: t::class.java.simpleName}").encode()
    }

    override fun openFile(api: String, version: Int, op: String, argsJson: String): PluginFileReply = try {
        val reply = arrayOfNulls<String>(1)
        val fd = broker.open(request(api, version, op, argsJson), reply)
        PluginFileReply(reply[0] ?: PluginReply.error(PluginErrorCode.FAILED, "droidtop returned no reply").encode(), fd)
    } catch (t: Throwable) {
        PluginFileReply(PluginReply.error(PluginErrorCode.FAILED, "droidtop could not be reached: ${t.message ?: t::class.java.simpleName}").encode(), null)
    }

    /** The contract 1 methods below are the broker calls they became (docs/plugin-api.md 6); a refused or failed call reads as false, as before. */
    private fun flag(api: String, op: String, args: JSONObject, key: String): Boolean = runCatching {
        val reply = JSONObject(call(api, 1, op, args.toString()))
        reply.optBoolean("ok") && reply.optJSONObject("data")?.optBoolean(key) == true
    }.getOrDefault(false)

    /** A contained plugin has no folder: its files live behind the `data` API (docs/plugin-api.md 3 H1). */
    override fun privateDataDir(): String = ""

    // The library folder is resolved by :app before a call and passed in its arguments; this process never reads it.
    override fun libraryFolderPath(systemId: String): String? = null
    override fun hasRootApproval(): Boolean = false
    override fun hasShizukuAccess(): Boolean = false

    override fun isAppInstalled(packageName: String): Boolean = runCatching {
        // The reply carries one boolean per package: read it by name.
        val reply = JSONObject(call("apps", 1, "check", JSONObject().put("packages", org.json.JSONArray(listOf(packageName))).toString()))
        reply.optBoolean("ok") && reply.optJSONObject("data")?.optJSONObject("installed")?.optBoolean(packageName) == true
    }.getOrDefault(false)

    override fun launchApp(packageName: String): Boolean =
        flag("apps", "launch", JSONObject().put("package", packageName), "launched")

    override fun launchAppWithExtras(packageName: String, extras: Map<String, String>, action: String?): Boolean =
        flag(
            "apps", "intent",
            JSONObject().put("package", packageName).put("extras", JSONObject(extras as Map<*, *>)).also { if (action != null) it.put("action", action) },
            "launched",
        )
}
