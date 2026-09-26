package dev.droidtop.pluginhost

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dalvik.system.DexClassLoader
import java.io.File
import java.util.UUID
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
    private val loaded = mutableMapOf<String, DroidtopPlugin>()
    private var callback: IPluginRuntimeCallback? = null

    // One shared pool for every plugin's jobs in this process. A job is
    // expected to run minutes (docs/SPEC.md 12a's job shape), so it must
    // never run on a binder thread; this is that "somewhere else".
    private val jobExecutor = Executors.newCachedThreadPool()

    private val binder = object : IPluginRuntime.Stub() {
        override fun setCallback(cb: IPluginRuntimeCallback?) {
            callback = cb
        }

        override fun loadPlugin(pluginId: String, installDir: String, entryClass: String, rootApproved: Boolean): Boolean {
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
            return when (kind) {
                PluginKind.PYTHON -> loadPythonPlugin(pluginId, dir, rootApproved)
                PluginKind.FLUTTER_EMBED -> loadFlutterPlugin(pluginId, dir, rootApproved)
                else -> loadNativeBundlePlugin(pluginId, dir, entryClass, rootApproved)
            }
        }

        private fun loadNativeBundlePlugin(pluginId: String, dir: File, entryClass: String, rootApproved: Boolean): Boolean {
            return try {
                val jar = File(dir, "classes.jar")
                if (!jar.isFile) return false
                val nativeDir = nativeLibraryDirFor(dir)
                val optimizedDir = File(cacheDir, "dex-opt/$pluginId").apply { mkdirs() }
                val loader = DexClassLoader(jar.absolutePath, optimizedDir.absolutePath, nativeDir, javaClass.classLoader)
                val instance = loader.loadClass(entryClass).getDeclaredConstructor().newInstance()
                val plugin = instance as? DroidtopPlugin ?: return false
                plugin.onLoad(pluginContextFor(pluginId, dir, rootApproved))
                loaded[pluginId] = plugin
                true
            } catch (t: Throwable) {
                reportCrash(pluginId, "", "load failed: ${t.message ?: t::class.java.simpleName}")
                false
            }
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
        private fun loadPythonPlugin(pluginId: String, dir: File, rootApproved: Boolean): Boolean {
            val built = PythonDroidtopPlugin.forInstall(applicationContext, pluginId, dir)
            val plugin = built.getOrElse { e ->
                if (e.message?.contains("runtime not installed", ignoreCase = true) == true) return false
                reportCrash(pluginId, "", "load failed: ${e.message ?: e::class.java.simpleName}")
                return false
            }
            return try {
                plugin.onLoad(pluginContextFor(pluginId, dir, rootApproved))
                loaded[pluginId] = plugin
                true
            } catch (t: Throwable) {
                reportCrash(pluginId, "", "load failed: ${t.message ?: t::class.java.simpleName}")
                false
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
        private fun loadFlutterPlugin(pluginId: String, dir: File, rootApproved: Boolean): Boolean {
            val manifestFile = File(dir, "manifest.json")
            val runtimeVersion = runCatching {
                PluginManifest.fromJson(JSONObject(manifestFile.readText()))?.runtimeVersion
            }.getOrNull()
            val pinnedVersion = FlutterRuntimeManager.pinnedVersion(applicationContext)
            if (pinnedVersion == null || FlutterRuntimeManager.libflutterSoPath(applicationContext) == null) {
                return false
            }
            if (runtimeVersion != pinnedVersion) {
                reportCrash(pluginId, "", "plugin's runtimeVersion ($runtimeVersion) does not match the installed Flutter runtime ($pinnedVersion)")
                return false
            }
            val built = FlutterDroidtopPlugin.forInstall(applicationContext, pluginId, dir)
            val plugin = built.getOrElse { e ->
                reportCrash(pluginId, "", "load failed: ${e.message ?: e::class.java.simpleName}")
                return false
            }
            return try {
                plugin.onLoad(pluginContextFor(pluginId, dir, rootApproved))
                loaded[pluginId] = plugin
                true
            } catch (t: Throwable) {
                reportCrash(pluginId, "", "load failed: ${t.message ?: t::class.java.simpleName}")
                false
            }
        }

        override fun unloadPlugin(pluginId: String) {
            runCatching { loaded.remove(pluginId)?.onUnload() }
            loaded.remove(pluginId)
        }

        override fun invoke(pluginId: String, capability: String, argsJson: String): String? {
            val plugin = loaded[pluginId] ?: return null
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

        override fun startJob(pluginId: String, capability: String, argsJson: String): String? {
            val plugin = loaded[pluginId] ?: return null
            val cap = PluginCapability.fromId(capability) ?: return null
            val jobId = UUID.randomUUID().toString()
            val argsMap = try {
                buildMap<String, String> {
                    val obj = JSONObject(argsJson)
                    obj.keys().forEach { key -> put(key, obj.optString(key)) }
                }
            } catch (t: Throwable) {
                return null
            }
            val progress = object : PluginJobProgress {
                override fun report(percent: Int, statusLine: String) {
                    runCatching { callback?.onJobProgress(pluginId, jobId, percent, statusLine) }
                }

                override fun complete(result: PluginResult) {
                    runCatching { callback?.onJobComplete(pluginId, jobId, encode(result)) }
                }
            }
            jobExecutor.execute {
                try {
                    plugin.startJob(jobId, cap, PluginArgs(argsMap), progress)
                } catch (e: UnsupportedOperationException) {
                    // A plugin that never overrode startJob() -- an
                    // ordinary, expected shape, not a crash: report it as
                    // a normal job failure and leave the plugin running.
                    runCatching { callback?.onJobComplete(pluginId, jobId, encode(PluginResult.failure("this plugin does not support jobs"))) }
                } catch (t: Throwable) {
                    reportCrash(pluginId, capability, t.message ?: t::class.java.simpleName)
                    runCatching { callback?.onJobComplete(pluginId, jobId, encode(PluginResult.failure(t.message ?: "job failed"))) }
                }
            }
            return jobId
        }

        override fun cancelJob(pluginId: String, jobId: String) {
            runCatching { loaded[pluginId]?.cancelJob(jobId) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

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
        runCatching { callback?.onPluginCrashed(pluginId, capability, reason) }
        loaded.remove(pluginId)
    }

    private fun nativeLibraryDirFor(installDir: File): String? {
        val abi = if (Build.SUPPORTED_64_BIT_ABIS.contains("x86_64") && !Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) {
            "x86_64"
        } else {
            "arm64-v8a"
        }
        val dir = File(installDir, "lib/$abi")
        return if (dir.isDirectory) dir.absolutePath else null
    }

    private fun pluginContextFor(pluginId: String, installDir: File, rootApproved: Boolean): PluginContext = object : PluginContext {
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
        override fun isAppInstalled(packageName: String): Boolean = checkPackageInstalled(applicationContext, packageName)
        override fun launchApp(packageName: String): Boolean = runCatching {
            val intent = applicationContext.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun checkPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun checkShizukuAccess(context: Context): Boolean {
        val installed = checkPackageInstalled(context, SHIZUKU_MANAGER_PACKAGE)
        if (!installed) return false
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
        fun bindIntent(context: Context): Intent = Intent(context, PluginRuntimeService::class.java)

        // The lightweight, no-client-library Shizuku check (PluginApi.kt's
        // hasShizukuAccess doc comment): the permission Shizuku's manager
        // grants once the user pairs and approves it there.
        private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PERMISSION = "moe.shizuku.privileged.api.permission.API_V23"
    }
}
