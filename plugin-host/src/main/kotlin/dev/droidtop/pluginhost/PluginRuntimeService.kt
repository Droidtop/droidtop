package dev.droidtop.pluginhost

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import dalvik.system.DexClassLoader
import java.io.File
import org.json.JSONObject

/**
 * A full-trust plugin process (docs/plugin-api.md 5.3): droidtop's own UID, the plugin loaded from its install folder.
 * Only a plugin the person allowed `host.full_trust`, and every contract 1 plugin, runs here. Each runs in a process of
 * its own, one of the declared slots ([PluginRuntimeService] is slot 0, `:pluginhost`; [FullTrustSlot1] to
 * [FullTrustSlot7] the rest, [PluginProcesses] hands them out), so no plugin can reach another one's class loader or
 * objects. That is crash containment and nothing more: a plugin here can do anything droidtop's UID can, including open
 * sockets, read droidtop's files and use Shizuku, and what it does that way never reaches the broker or the activity log.
 */
open class PluginRuntimeService : PluginProcessService() {
    override fun onCreate() {
        super.onCreate()
        // Shizuku hands its binder to `:pluginhost` (this module's manifest); a plugin in another slot asks for it there.
        ShizukuTransport.ensureStarted()
    }

    override fun loadFromFiles(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, broker: IPluginHostBroker): Boolean =
        failLoad(pluginId, "a full-access process loads plugins from their install folder")

    override fun loadFromFolder(pluginId: String, dir: File, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean {
        // The manifest on disk (written by PluginBundleInstaller, re-verified before every activation by
        // PluginCrashPolicy) says which kind this is; entryClass is unused on the python and flutter paths.
        val kind = runCatching { PluginManifest.fromJson(JSONObject(File(dir, "manifest.json").readText()))?.kind }.getOrNull()
        val context = FullTrustContext(broker, dir, rootApproved)
        return when (kind) {
            PluginKind.PYTHON -> loadPython(pluginId, dir, context)
            PluginKind.FLUTTER_EMBED -> loadFlutter(pluginId, dir, context)
            else -> loadNativeBundle(pluginId, dir, entryClass, context)
        }
    }

    private fun loadNativeBundle(pluginId: String, dir: File, entryClass: String, context: PluginContext): Boolean {
        return try {
            val jar = File(dir, "classes.jar")
            if (!jar.isFile) return failLoad(pluginId, "its classes.jar is missing from the installed bundle")
            val optimizedDir = File(cacheDir, "dex-opt/$pluginId").apply { mkdirs() }
            val loader = DexClassLoader(jar.absolutePath, optimizedDir.absolutePath, nativeLibraryDirFor(dir), javaClass.classLoader)
            val plugin = loader.loadClass(entryClass).getDeclaredConstructor().newInstance() as? DroidtopPlugin
                ?: return failLoad(pluginId, "its entry class does not implement the plugin interface")
            start(pluginId, plugin, context)
        } catch (t: Throwable) {
            loadCrashed(pluginId, t)
        }
    }

    /**
     * The python kind (docs/SPEC.md 12a): `plugin.py` inside [PythonBridge]'s interpreter. A runtime that is not
     * downloaded is an ordinary "not ready" failure, never a crash: the download is its own settings action.
     */
    private fun loadPython(pluginId: String, dir: File, context: PluginContext): Boolean {
        val plugin = PythonDroidtopPlugin.forInstall(applicationContext, pluginId, dir).getOrElse { e ->
            if (e.message?.contains("runtime not installed", ignoreCase = true) == true) {
                return failLoad(pluginId, "the Python runtime is not installed")
            }
            return loadCrashed(pluginId, e)
        }
        return start(pluginId, plugin, context)
    }

    /**
     * The flutter_embed kind (docs/SPEC.md 12a): a real FlutterEngine. Two preconditions are checked before any engine is
     * built, both "not ready" rather than a crash: the shared runtime is downloaded, and the plugin's own
     * [PluginManifest.runtimeVersion] matches it exactly (a Dart AOT snapshot runs only on the engine it was built for).
     */
    private fun loadFlutter(pluginId: String, dir: File, context: PluginContext): Boolean {
        val runtimeVersion = runCatching { PluginManifest.fromJson(JSONObject(File(dir, "manifest.json").readText()))?.runtimeVersion }.getOrNull()
        val pinnedVersion = FlutterRuntimeManager.pinnedVersion(applicationContext)
        if (pinnedVersion == null || FlutterRuntimeManager.libflutterSoPath(applicationContext) == null) {
            return failLoad(pluginId, "the Flutter runtime is not installed")
        }
        if (runtimeVersion != pinnedVersion) {
            val reason = "plugin's runtimeVersion ($runtimeVersion) does not match the installed Flutter runtime ($pinnedVersion)"
            reportCrash(pluginId, "", reason)
            return failLoad(pluginId, reason)
        }
        val plugin = FlutterDroidtopPlugin.forInstall(applicationContext, pluginId, dir).getOrElse { e -> return loadCrashed(pluginId, e) }
        return start(pluginId, plugin, context)
    }

    private fun nativeLibraryDirFor(installDir: File): String? {
        // x86_64 wins whenever it is present: see FlutterRuntimeManager.currentAbi's own doc comment.
        val abi = if (Build.SUPPORTED_64_BIT_ABIS.contains("x86_64")) "x86_64" else "arm64-v8a"
        val dir = File(installDir, "lib/$abi")
        return if (dir.isDirectory) dir.absolutePath else null
    }

    /**
     * A full-trust plugin's context: the broker for everything droidtop does on its behalf, plus the local answers only
     * droidtop's UID can give, its own folder, root approval and Shizuku. [rootApproved] is [PluginRecord.rootApproved]
     * as it stood when :app issued this load, re-sent with every load.
     */
    private inner class FullTrustContext(broker: IPluginHostBroker, installDir: File, private val rootApproved: Boolean) :
        BrokerPluginContext(broker) {
        private val dataDir = File(installDir, "data")

        override fun privateDataDir(): String = dataDir.apply { mkdirs() }.absolutePath
        override fun hasRootApproval(): Boolean = rootApproved && rootProviderAvailable()

        /**
         * Whether a running provider plugin offers `priv.shell` at root level (Shizuku running as root, Sui, or a Magisk
         * module provider), asked through the broker's `plugins.available`. droidtop itself never runs `su` (owner rule):
         * root is reached only through such a provider, so its presence is the root check. Not cached: a provider can be
         * started or stopped between calls.
         */
        private fun rootProviderAvailable(): Boolean = runCatching {
            val reply = JSONObject(call("plugins", 1, "available", JSONObject().put("api", "priv.shell").put("minLevel", "root").toString()))
            reply.optBoolean("ok") && reply.optJSONObject("data")?.optBoolean("available") == true
        }.getOrDefault(false)
        override fun hasShizukuAccess(): Boolean = checkShizukuAccess(applicationContext)
    }

    private fun checkShizukuAccess(context: Context): Boolean {
        val installed = runCatching { context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0); true }.getOrDefault(false)
        if (!installed) return false
        // Shizuku's own client answers once its binder has arrived: the server is running and droidtop was allowed in Shizuku.
        val viaBinder = runCatching {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrNull()
        if (viaBinder != null) return viaBinder
        return runCatching {
            context.checkSelfPermission(SHIZUKU_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    companion object {
        // The permission Shizuku's manager grants once the user pairs and approves droidtop there.
        private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
    }
}

// Full-trust slots 1 to 7 (docs/plugin-api.md 5.3): the same service in processes of their own, `:pluginhost1` to
// `:pluginhost7` in this module's manifest. Android names a process per declared component, so one process per plugin
// takes one declaration per slot.
class FullTrustSlot1 : PluginRuntimeService()
class FullTrustSlot2 : PluginRuntimeService()
class FullTrustSlot3 : PluginRuntimeService()
class FullTrustSlot4 : PluginRuntimeService()
class FullTrustSlot5 : PluginRuntimeService()
class FullTrustSlot6 : PluginRuntimeService()
class FullTrustSlot7 : PluginRuntimeService()
