package dev.droidtop.pluginhost

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Runs in the isolated `:pluginhost` process (this module's own
 * AndroidManifest.xml declares it there). Everything in this class runs
 * OUTSIDE :app's process: a plugin native-crashing, deadlocking, or
 * throwing an uncaught exception on a binder thread takes this process
 * down, not :app's. That is the entire point (docs/SPEC.md 12a: "for
 * COMPATIBILITY and STABILITY, not security") -- there is no
 * [SecurityManager], no permission drop, no separate UID here; a plugin
 * in this process can do anything :app's own UID can do. The isolation
 * this buys is crash containment and a clean binder boundary, nothing
 * more, and the class-level doc comments on [IPluginRuntime] and
 * [DroidtopPlugin] say so again so nobody mistakes what this is for.
 */
class PluginRuntimeService : Service() {
    // Binder threads (a pool) read and write these concurrently: a second load
    // of the same plugin arrives while the first is still starting its engine.
    private val loaded = ConcurrentHashMap<String, DroidtopPlugin>()

    /** Why each plugin's last load returned false ([IPluginRuntime.lastLoadError]); also logged, so logcat has the reason too. */
    private val loadErrors = ConcurrentHashMap<String, String>()

    /** Serialises [IPluginRuntime.loadPlugin]: a caller that gave up waiting (its own timeout) and retries must join the load still in flight, not start a second engine beside it. */
    private val loadLock = Any()

    private fun failLoad(pluginId: String, reason: String): Boolean {
        loadErrors[pluginId] = reason
        Log.w("droidtop.plugin", "$pluginId did not load: $reason")
        return false
    }

    /**
     * Every caller currently connected, not just the most recent one --
     * see [IPluginRuntime.registerCallback]'s own doc comment for the
     * bug this replaced (a single nullable field, silently stolen by
     * the second concurrent connection). [RemoteCallbackList] is the
     * standard AIDL idiom for this: thread-safe register/unregister,
     * and it drops a registered callback on its own once the binder
     * behind it dies, so a :app-side connection that vanishes without
     * calling [IPluginRuntime.unregisterCallback] (a process kill, not
     * just an unbind) can never leave a stale entry broadcasts keep
     * paying for.
     */
    private val callbacks = RemoteCallbackList<IPluginRuntimeCallback>()
    // RemoteCallbackList.beginBroadcast cannot be outstanding on two threads at once (throws if it is);
    // the three broadcast senders below share this lock so one job's progress callback never lands
    // mid-broadcast of another one's -- confirmed live on the rig, two concurrent core downloads.
    private val broadcastLock = Any()

    // One shared pool for every plugin's jobs in this process. A job is
    // expected to run minutes (docs/SPEC.md 12a's job shape), so it must
    // never run on a binder thread; this is that "somewhere else".
    private val jobExecutor = Executors.newCachedThreadPool()

    private val binder = object : IPluginRuntime.Stub() {
        override fun registerCallback(cb: IPluginRuntimeCallback?) {
            cb?.let { callbacks.register(it) }
        }

        override fun unregisterCallback(cb: IPluginRuntimeCallback?) {
            cb?.let { callbacks.unregister(it) }
        }

        override fun loadPlugin(pluginId: String, installDir: String, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean {
            // Found while fixing the flutter_embed channel-error race
            // (2026-09-26): PluginCrashPolicy.invoke()/startJob() call
            // load() before EVERY capability call, unconditionally --
            // and this method used to rebuild the plugin from scratch
            // every single time, directly violating DroidtopPlugin.onLoad's
            // own documented contract ("Called once after loading, before
            // any invoke"). Harmless for native_bundle/python's cheap,
            // idempotent setup, but for flutter_embed this reconstructed a
            // brand new FlutterEngine (a full Dart isolate boot) on every
            // call -- confirmed on the rig: the SECOND such construction in
            // one :pluginhost process logged "FlutterJNI.init called more
            // than once" and left the freshly-built engine's own
            // MethodChannel unable to reach its already-registered Dart
            // handler (PlatformException(channel-error, ...)), even though
            // the readiness handshake had just succeeded on THAT SAME
            // engine moments earlier. A plugin already in [loaded] is
            // already loaded; returning true here without touching it is
            // what onLoad's own contract always said this should do.
            return synchronized(loadLock) {
            loaded[pluginId]?.let { return true }
            loadErrors.remove(pluginId)
            val dir = File(installDir)
            // The manifest on disk (written by PluginBundleInstaller,
            // re-verified before every activation by PluginCrashPolicy)
            // is the one place this process can tell a native_bundle load
            // from a python one apart -- IPluginRuntime.loadPlugin's own
            // signature is unchanged; entryClass is simply unused on the
            // python path (that kind's entry point is always its own
            // payload's plugin.py, found straight off installDir).
            val manifestFile = File(dir, "manifest.json")
            val kind = runCatching {
                PluginManifest.fromJson(JSONObject(manifestFile.readText()))?.kind
            }.getOrNull()
            when (kind) {
                PluginKind.PYTHON -> loadPythonPlugin(pluginId, dir, rootApproved, broker)
                PluginKind.FLUTTER_EMBED -> loadFlutterPlugin(pluginId, dir, rootApproved, broker)
                else -> loadNativeBundlePlugin(pluginId, dir, entryClass, rootApproved, broker)
            }
            }
        }

        override fun lastLoadError(pluginId: String): String = loadErrors[pluginId].orEmpty()

        private fun loadNativeBundlePlugin(pluginId: String, dir: File, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean {
            return try {
                val jar = File(dir, "classes.jar")
                if (!jar.isFile) return failLoad(pluginId, "its classes.jar is missing from the installed bundle")
                val nativeDir = nativeLibraryDirFor(dir)
                val optimizedDir = File(cacheDir, "dex-opt/$pluginId").apply { mkdirs() }
                val loader = DexClassLoader(jar.absolutePath, optimizedDir.absolutePath, nativeDir, javaClass.classLoader)
                val instance = loader.loadClass(entryClass).getDeclaredConstructor().newInstance()
                val plugin = instance as? DroidtopPlugin ?: return failLoad(pluginId, "its entry class does not implement the plugin interface")
                plugin.onLoad(pluginContextFor(pluginId, dir, rootApproved, broker))
                loaded[pluginId] = plugin
                true
            } catch (t: Throwable) {
                loadCrashed(pluginId, t)
            }
        }

        /** A thrown load is a crash (the plugin is disabled with the reason) and also the reason the caller reads. */
        private fun loadCrashed(pluginId: String, t: Throwable): Boolean {
            val reason = "load failed: ${t.message ?: t::class.java.simpleName}"
            reportCrash(pluginId, "", reason)
            return failLoad(pluginId, reason)
        }

        /**
         * [PluginKind.PYTHON]'s load path (docs/SPEC.md 12a): runs
         * `plugin.py` inside [PythonBridge]'s process-wide interpreter
         * rather than a DexClassLoader. A missing/not-yet-downloaded
         * [PythonRuntimeManager] runtime is reported as an ordinary load
         * failure (return false, no [reportCrash]) -- that is an
         * expected, non-crash state (the runtime download is its own
         * explicit settings action, never triggered implicitly from
         * here), exactly like [NativePluginRunner.load] returning false
         * for "the process never connected" does not disable the plugin.
         */
        private fun loadPythonPlugin(pluginId: String, dir: File, rootApproved: Boolean, broker: IPluginHostBroker): Boolean {
            val built = PythonDroidtopPlugin.forInstall(applicationContext, pluginId, dir)
            val plugin = built.getOrElse { e ->
                if (e.message?.contains("runtime not installed", ignoreCase = true) == true) {
                    return failLoad(pluginId, "the Python runtime is not installed")
                }
                return loadCrashed(pluginId, e)
            }
            return try {
                plugin.onLoad(pluginContextFor(pluginId, dir, rootApproved, broker))
                loaded[pluginId] = plugin
                true
            } catch (t: Throwable) {
                loadCrashed(pluginId, t)
            }
        }

        /**
         * [PluginKind.FLUTTER_EMBED]'s load path (docs/SPEC.md 12a):
         * hosts a real FlutterEngine via [FlutterDroidtopPlugin] rather
         * than a DexClassLoader or the python interpreter. Two
         * preconditions are checked BEFORE any engine construction is
         * attempted, both reported as an ordinary "not ready" load
         * failure (return false, no [reportCrash]) rather than a crash --
         * same shape [loadPythonPlugin] already uses for "runtime not
         * installed":
         *
         *   1. the shared Flutter runtime (libflutter.so,
         *      [FlutterRuntimeManager]) is actually downloaded, and
         *   2. this plugin's own [PluginManifest.runtimeVersion] matches
         *      that runtime EXACTLY -- a Dart AOT snapshot only runs
         *      against the exact engine build it was compiled for
         *      (docs/SPEC.md 12a's flutter_embed section has the
         *      citation), so a version drift here is refused up front
         *      rather than left to fail confusingly deep inside
         *      FlutterEngine's own native init.
         */
        private fun loadFlutterPlugin(pluginId: String, dir: File, rootApproved: Boolean, broker: IPluginHostBroker): Boolean {
            val manifestFile = File(dir, "manifest.json")
            val runtimeVersion = runCatching {
                PluginManifest.fromJson(JSONObject(manifestFile.readText()))?.runtimeVersion
            }.getOrNull()
            val pinnedVersion = FlutterRuntimeManager.pinnedVersion(applicationContext)
            if (pinnedVersion == null || FlutterRuntimeManager.libflutterSoPath(applicationContext) == null) {
                return failLoad(pluginId, "the Flutter runtime is not installed")
            }
            if (runtimeVersion != pinnedVersion) {
                val reason = "plugin's runtimeVersion ($runtimeVersion) does not match the installed Flutter runtime ($pinnedVersion)"
                reportCrash(pluginId, "", reason)
                return failLoad(pluginId, reason)
            }
            val built = FlutterDroidtopPlugin.forInstall(applicationContext, pluginId, dir)
            val plugin = built.getOrElse { e -> return loadCrashed(pluginId, e) }
            return try {
                plugin.onLoad(pluginContextFor(pluginId, dir, rootApproved, broker))
                loaded[pluginId] = plugin
                true
            } catch (t: Throwable) {
                loadCrashed(pluginId, t)
            }
        }

        override fun unloadPlugin(pluginId: String) {
            runCatching { loaded.remove(pluginId)?.onUnload() }
            loaded.remove(pluginId)
            loadErrors.remove(pluginId)
        }

        override fun invoke(pluginId: String, capability: String, argsJson: String): String? {
            val plugin = loaded[pluginId] ?: return null
            // PluginEvent ids and PluginCapability ids share this one
            // AIDL string slot on purpose (docs/SPEC.md 12a "Event
            // hooks"): both are "JSON in, JSON out, one watchdog-bound
            // call" already, and events are droidtop calling OUT rather
            // than a plugin capability droidtop calls INTO, so they need
            // no new binder method -- only a different id namespace and
            // a different plugin-side handler ([DroidtopPlugin.onEvent]
            // instead of [DroidtopPlugin.invoke]). Checked first since
            // the two id sets are disjoint by construction (PluginEvent's
            // ids are never also PluginCapability ids).
            val event = PluginEvent.fromId(capability)
            if (event != null) {
                return try {
                    val argsMap = buildMap<String, String> {
                        val obj = JSONObject(argsJson)
                        obj.keys().forEach { key -> put(key, obj.optString(key)) }
                    }
                    encode(plugin.onEvent(event, PluginArgs(argsMap)))
                } catch (t: Throwable) {
                    reportCrash(pluginId, capability, t.message ?: t::class.java.simpleName)
                    null
                }
            }
            val cap = PluginCapability.fromId(capability) ?: return null
            return try {
                val argsMap = buildMap<String, String> {
                    val obj = JSONObject(argsJson)
                    obj.keys().forEach { key -> put(key, obj.optString(key)) }
                }
                val result = plugin.invoke(cap, PluginArgs(argsMap))
                encode(result)
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
                buildMap<String, String> {
                    val obj = JSONObject(argsJson)
                    obj.keys().forEach { key -> put(key, obj.optString(key)) }
                }
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
                    // A plugin that never overrode startJob() -- an
                    // ordinary, expected shape, not a crash: report it as
                    // a normal job failure and leave the plugin running.
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

    /** Releases every still-registered callback's binder reference -- the process is going away with them regardless, but [RemoteCallbackList.kill] is the documented, clean way to say so rather than just dropping the field. */
    override fun onDestroy() {
        callbacks.kill()
        super.onDestroy()
    }

    private fun encode(result: PluginResult): String {
        val json = JSONObject()
        json.put("ok", result.ok)
        result.error?.let { json.put("error", it) }
        val values = JSONObject()
        result.values.forEach { (k, v) -> values.put(k, v) }
        json.put("values", values)
        val text = json.toString()
        // The other half of the size cap: even a plugin that ran fine
        // cannot hand back more than PluginRunner.MAX_RESULT_BYTES across
        // the binder (12a point 5).
        return if (text.toByteArray(Charsets.UTF_8).size > PluginRunner.MAX_RESULT_BYTES) {
            JSONObject().put("ok", false).put("error", "result exceeds size cap").put("values", JSONObject()).toString()
        } else {
            text
        }
    }

    private fun reportCrash(pluginId: String, capability: String, reason: String) {
        broadcastPluginCrashed(pluginId, capability, reason)
        loaded.remove(pluginId)
    }

    /**
     * Fan-out to every currently registered [IPluginRuntimeCallback]
     * (see [callbacks]' own doc comment) -- the standard
     * begin/get/finishBroadcast dance [RemoteCallbackList] requires. One
     * dead or misbehaving callback (`runCatching` per item) never stops
     * the rest from being delivered.
     */
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

    private fun nativeLibraryDirFor(installDir: File): String? {
        // x86_64 wins whenever it is present -- see FlutterRuntimeManager.currentAbi's
        // own doc comment for the real device this was confirmed broken on.
        val abi = if (Build.SUPPORTED_64_BIT_ABIS.contains("x86_64")) {
            "x86_64"
        } else {
            "arm64-v8a"
        }
        val dir = File(installDir, "lib/$abi")
        return if (dir.isDirectory) dir.absolutePath else null
    }

    private fun pluginContextFor(pluginId: String, installDir: File, rootApproved: Boolean, broker: IPluginHostBroker): PluginContext = object : PluginContext {
        override fun call(api: String, version: Int, op: String, argsJson: String): String = try {
            broker.call(
                JSONObject().put("api", api).put("version", version).put("op", op)
                    .put("args", runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())).toString(),
            ) ?: PluginReply.error(PluginErrorCode.FAILED, "droidtop returned no reply").encode()
        } catch (t: Throwable) {
            PluginReply.error(PluginErrorCode.FAILED, "droidtop could not be reached: ${t.message ?: t::class.java.simpleName}").encode()
        }

        /** The contract 1 methods below are the broker calls they became (docs/plugin-api.md 6); a refused or failed call reads as false, as before. */
        private fun flag(api: String, op: String, args: JSONObject, key: String): Boolean = runCatching {
            val reply = JSONObject(call(api, 1, op, args.toString()))
            reply.optBoolean("ok") && reply.optJSONObject("data")?.optBoolean(key) == true
        }.getOrDefault(false)

        override fun privateDataDir(): String = File(installDir, "data").apply { mkdirs() }.absolutePath
        // The library-folder question needs :app's own configured state
        // (the systems database); this process never reads it directly
        // -- it is resolved by :app BEFORE the call reaches here and
        // passed down through argsJson/PluginArgs by NativePluginRunner
        // instead. Root approval, by contrast, IS threaded down to this
        // process now: [rootApproved] is [PluginRecord.rootApproved] as
        // it stood at the moment :app issued this load (NativePluginRunner.load
        // passes the whole record's own flag across loadPlugin's AIDL
        // call), re-sent on every load rather than cached here, since a
        // load happens again on every [PluginCrashPolicy] call and picks
        // up any approval change made on the settings screen since the
        // last one.
        override fun libraryFolderPath(systemId: String): String? = null
        override fun hasRootApproval(): Boolean = rootApproved && deviceHasRoot()
        override fun hasShizukuAccess(): Boolean = checkShizukuAccess(applicationContext)
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

    private fun checkPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun checkShizukuAccess(context: Context): Boolean {
        val installed = checkPackageInstalled(context, SHIZUKU_MANAGER_PACKAGE)
        if (!installed) return false
        // Shizuku's own client answers once its binder has arrived (the provider in this module's
        // manifest receives it): the server is running and droidtop was allowed in Shizuku.
        val viaBinder = runCatching {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrNull()
        if (viaBinder != null) return viaBinder
        return runCatching {
            context.checkSelfPermission(SHIZUKU_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * Whether THIS DEVICE has a root solution present at all, independent
     * of any plugin's own approval -- [PluginContext.hasRootApproval]
     * folds this together with [PluginRecord.rootApproved] so a plugin
     * never has to make two divergent checks itself (12a point 2 of the
     * root section). The same lightweight probe a root-aware app
     * commonly uses ("su -c id", exit code 0 means a su binary answered
     * and granted the call): cheap enough to run per load, cached for
     * this process's lifetime since a device does not gain or lose root
     * between one plugin call and the next.
     */
    private val deviceHasRootCached: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("sh", "-c", "su -c id").start()
            process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun deviceHasRoot(): Boolean = deviceHasRootCached

    companion object {
        private const val MAX_RESUME_PAYLOAD_CHARS = 4096
        fun bindIntent(context: Context): Intent = Intent(context, PluginRuntimeService::class.java)

        // The lightweight, no-client-library Shizuku check (PluginApi.kt's
        // hasShizukuAccess doc comment): the permission Shizuku's manager
        // grants once the user pairs and approves it there.
        private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    }
}
