package dev.droidtop.pluginhost

import android.os.Build
import android.os.ParcelFileDescriptor
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * A contained plugin's process (docs/plugin-api.md 5.3): `android:isolatedProcess="true"`, so Android gives it a random
 * UID of its own with no permissions, outside the inet group (no sockets) and with no access to droidtop's files or to
 * shared storage. One plugin per process: on API 29 and later :app binds one instance per plugin with
 * `bindIsolatedService`; below that it uses the fixed slots [PluginSandboxSlot0] to [PluginSandboxSlot7] ([PluginProcesses]).
 *
 * The plugin's code arrives as file descriptors :app opened ([ContainedFiles]), and its [PluginContext] reaches
 * nothing but its broker: no folder, no root, no Shizuku. How each kind loads:
 * - `native_bundle`: the dex files of `classes.jar`, read from the descriptor into memory and loaded with
 *   [InMemoryDexClassLoader]. Its native libraries get virtual paths ([SandboxFiles]) that the class loader's library
 *   path names, so the plugin's own `System.loadLibrary` maps them through the guarded hooks and ART binds its JNI
 *   methods as usual.
 * - `python`: the interpreter starts from descriptors ([PythonBridge.initContained]): libpython and its libraries
 *   through `android_dlopen_ext` with `ANDROID_DLEXT_USE_LIBRARY_FD`, the standard library as one zip on `sys.path`,
 *   and each `lib-dynload` module registered as a built-in. `plugin.py` is executed from its text.
 * - `flutter_embed`: a headless FlutterEngine from virtual paths (the downloaded engine, the plugin's `libapp.so`
 *   and its assets zip) answered by the guarded hooks, drawing in software; its `ui.main` draws into a surface droidtop
 *   owns ([PluginScreenActivity]).
 */
open class PluginSandboxService : PluginProcessService() {
    @Volatile private var pythonReport: JSONObject? = null

    /**
     * True for the isolated sandbox, false for an open process ([PluginOpenService]): the same broker-only loader in a
     * process of droidtop's own UID that is NOT `android:isolatedProcess`, the only process an app may run that can open
     * the graphics chip or a socket, each only with its hole granted (docs/plugin-api.md 5.3, "Open processes").
     */
    protected open val requiresIsolation: Boolean get() = true

    /**
     * Whether a Flutter plugin draws in software: always in the isolated sandbox (an isolated process may not open the
     * GPU), and in an open process unless it was given the `gpu.render` hole. Set by the load.
     */
    @Volatile private var softwareRendering: Boolean = true

    override fun loadFromFolder(pluginId: String, dir: File, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean =
        failLoad(pluginId, "a contained plugin is loaded from the files droidtop hands it, never from a folder")

    /** What the system-call filter reported in this process ([PluginSyscallFilter]), for the containment check. */
    @Volatile private var filter: String? = null

    override fun loadFromFiles(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, broker: IPluginHostBroker): Boolean {
        if (requiresIsolation && !dev.droidtop.runtime.util.IsolatedProcess.isIsolated()) return failLoad(pluginId, "the contained process is not isolated; refusing to run the plugin here")
        // The same seccomp filter for both kinds of plugin process, before any plugin code (docs/plugin-api.md 5.3). In a
        // graphics process it is the only network wall; in the isolated sandbox Android's own rules already refuse sockets,
        // except where a device does not enforce them (the BlueStacks rig runs with SELinux disabled and let an isolated UID
        // open a socket, rig, v0.2.0-dev.1649). In both it is also what answers the plugin's own files at their virtual
        // paths and turns droidtop's private folder into the plugin's own ([SandboxBroker]), so a process that cannot take
        // it does not run the plugin. Where it cannot be installed nothing is silently best-effort (Droidtop/tracker#481):
        // for the isolated sandbox droidtop asks the person in red whether to run it this time anyway.
        // The first native call of the process, which loads droidtop's own libdroidtoppy.so; timed in logcat because on the
        // BlueStacks rig that first library load waits 10 s inside its native bridge (docs/plugin-api.md 5.3, "The rigs").
        val before = android.os.SystemClock.uptimeMillis()
        val brokerFd = files[ContainedFiles.BROKER]?.detachFd() ?: return failLoad(pluginId, "droidtop did not hand over its file broker")
        val claims = if (requiresIsolation) SandboxBroker.claims(applicationInfo) else null
        // An isolated process takes the network hole alone, its sockets made by droidtop's broker (5.6); never the GPU.
        val granted = if (requiresIsolation) holes[pluginId].orEmpty().filterTo(HashSet()) { it == PluginTiers.NET_SOCKETS } else holes[pluginId].orEmpty()
        val gpu = PluginTiers.GPU_RENDER in granted
        softwareRendering = !gpu
        val report = PluginSyscallFilter.install(brokerFd, claims, gpu = gpu, network = PluginTiers.NET_SOCKETS in granted).also { filter = it }
        android.util.Log.i("droidtop.plugin", "$pluginId: system-call filter ${report.substringBefore(':')} in ${android.os.SystemClock.uptimeMillis() - before} ms (first native call)")
        if (requiresIsolation && !PluginSyscallFilter.isOn(report) && pluginId !in runUnprotected) {
            return failLoad(pluginId, PluginDangers.PROTECTION_FAILED + report)
        }
        if (!requiresIsolation && !PluginSyscallFilter.isOn(report)) {
            return failLoad(pluginId, "its process could not take droidtop's system-call filter ($report), so it is not run outside the sandbox")
        }
        // The plugin's folder is droidtop's private-folder path, which this process's broker answers with the plugin's own.
        val context = BrokerPluginContext(broker, applicationInfo.dataDir)
        return when (manifest.kind) {
            PluginKind.NATIVE_BUNDLE -> loadDex(pluginId, manifest, files, context)
            PluginKind.PYTHON -> loadPython(pluginId, manifest, files, context)
            PluginKind.FLUTTER_EMBED -> loadFlutter(pluginId, manifest, files, context)
            // Its code is its own app, bound by AppInterfaces; it never loads in a droidtop process.
            PluginKind.APP_INTERFACE -> failLoad(pluginId, "an app's plugin runs in its own app, not in a droidtop process")
        }
    }

    private fun loadDex(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, context: PluginContext): Boolean {
        val jar = files[ContainedFiles.CLASSES] ?: return failLoad(pluginId, "its classes.jar was not handed over")
        val entryClass = manifest.entryClass ?: return failLoad(pluginId, "it names no entry class")
        val libraries = files.filterKeys { it.startsWith(ContainedFiles.NATIVE_PREFIX) && it.endsWith(".so") }
        val libraryPath = if (libraries.isEmpty()) {
            null
        } else {
            // docs/plugin-api.md 5.3, "Guarded hooks": each library at a virtual path the class loader's library path names.
            val abi = libraries.keys.first().removePrefix(ContainedFiles.NATIVE_PREFIX).substringBefore('/')
            libraries.forEach { (name, fd) -> SandboxFiles.register(SandboxFiles.PLUGIN + name, fd) }
            hooks = SandboxFiles.ensureHooks()
            SandboxFiles.PLUGIN + ContainedFiles.NATIVE_PREFIX + abi
        }
        return try {
            val loader = ContainedDex.loader(ContainedDex.read(jar), javaClass.classLoader!!, libraryPath)
            libraries.keys.forEach { name -> ContainedDex.reportLibrary(pluginId, loader, SandboxFiles.PLUGIN + name) }
            val plugin = loader.loadClass(entryClass).getDeclaredConstructor().newInstance() as? DroidtopPlugin
                ?: return failLoad(pluginId, "its entry class does not implement the plugin interface")
            start(pluginId, plugin, context)
        } catch (t: Throwable) {
            loadCrashed(pluginId, t)
        }
    }

    private fun loadPython(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, context: PluginContext): Boolean {
        val script = files[ContainedFiles.PLUGIN_PY] ?: return failLoad(pluginId, "its plugin.py was not handed over")
        val source = try {
            FileInputStream(script.fileDescriptor).bufferedReader().readText()
        } catch (t: Throwable) {
            return failLoad(pluginId, "its plugin.py could not be read: ${t.message}")
        }
        val report = PythonBridge.initContained(files)
        pythonReport = report
        if (!report.optBoolean("ok")) {
            return failLoad(pluginId, "the Python runtime did not start in the contained process: ${report.optString("error")}")
        }
        val plugin = PythonDroidtopPlugin(pluginId, scriptPath = "", dataDir = "", contractVersion = manifest.contractVersion, source = source)
        return start(pluginId, plugin, context)
    }

    private fun loadFlutter(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, context: PluginContext): Boolean {
        val libflutter = files[ContainedFiles.FLUTTER_ENGINE] ?: return failLoad(pluginId, "the Flutter engine was not handed over")
        val libapp = files[ContainedFiles.FLUTTER_APP] ?: return failLoad(pluginId, "its libapp.so was not handed over")
        val assets = files[ContainedFiles.FLUTTER_ASSETS] ?: return failLoad(pluginId, "its flutter_assets were not handed over")
        return try {
            // docs/plugin-api.md 5.3, "Flutter assets": AssetManager opens a path itself, which the guarded hooks do not
            // reach, so from API 30 the assets are served from a memfd through a ResourcesLoader. Read before any
            // SandboxFiles.register, which takes the descriptor over.
            val served = if (Build.VERSION.SDK_INT >= 30) {
                val served = ContainedAssets.from(assets)
                served.installInto(applicationContext)
                assetsNote = "served from memory to AssetManager (${served.count} files)"
                true
            } else {
                SandboxFiles.register(SandboxFiles.PLUGIN + "flutter_assets.zip", assets)
                assetsNote = "added by its virtual path (Android 10 and older)"
                false
            }
            SandboxFiles.register(SandboxFiles.RUNTIME + "libflutter.so", libflutter)
            SandboxFiles.register(SandboxFiles.PLUGIN + "libapp.so", libapp)
            hooks = SandboxFiles.ensureHooks()
            val registrant = files.filterKeys { it.startsWith(ContainedFiles.FLUTTER_DEX) }.toSortedMap().values.map { fd ->
                java.nio.ByteBuffer.wrap(FileInputStream(fd.fileDescriptor).readBytes())
            }
            start(pluginId, FlutterDroidtopPlugin(pluginId, applicationContext, FlutterSource.contained(registrant, softwareRendering, served), manifest.contractVersion), context)
        } catch (t: Throwable) {
            loadCrashed(pluginId, t)
        }
    }

    /** What the guarded hooks rewrote, for the containment check. */
    @Volatile private var hooks: String? = null

    /** How a Flutter plugin's assets reached AssetManager, for the containment check. */
    @Volatile private var assetsNote: String? = null

    override fun loadNotes(): JSONObject = JSONObject().apply {
        pythonReport?.let { put("python", it) }
        hooks?.let { put("hooks", it) }
        assetsNote?.let { put("assets", it) }
        filter?.let { put("syscallFilter", it) }
    }
}

// Contained slots for API 26 to 28, where bindIsolatedService does not exist (docs/plugin-api.md 5.3): each is the same
// isolated service in a process of its own, `:plugin_sandbox0` to `:plugin_sandbox7` in this module's manifest. Like an
// open process, one serves one plugin and ends with it (5.6): the filter and the broker it installed are that plugin's
// for good, so the next plugin in the slot must start in a fresh process.
open class PluginSandboxSlotService : PluginSandboxService() {
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        android.util.Log.i("droidtop.plugin", "contained slot process ${android.os.Process.myPid()}: unbound, ending with its plugin")
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }
}
class PluginSandboxSlot0 : PluginSandboxSlotService()
class PluginSandboxSlot1 : PluginSandboxSlotService()
class PluginSandboxSlot2 : PluginSandboxSlotService()
class PluginSandboxSlot3 : PluginSandboxSlotService()
class PluginSandboxSlot4 : PluginSandboxSlotService()
class PluginSandboxSlot5 : PluginSandboxSlotService()
class PluginSandboxSlot6 : PluginSandboxSlotService()
class PluginSandboxSlot7 : PluginSandboxSlotService()

// An open process (docs/plugin-api.md 5.3, "Open processes: one hole per permission"): the same broker-only loader as the
// sandbox, but in a process of droidtop's own UID that is NOT isolated, so the holes the person granted can be opened:
// the graphics chip for gpu.render, internet sockets for net.sockets, and nothing else. Eight slots `:plugin_open0` to
// `:plugin_open7` in this module's manifest; there is no API 29 per-instance form because bindIsolatedService is for
// isolated processes only. Before any of the plugin's code loads, the process takes droidtop's system-call filter
// ([PluginSyscallFilter]) with exactly those holes; a process that cannot take the filter does not run the plugin.
//
// One process serves one plugin and ends with it: its filter, its broker and its holes are that plugin's for good
// (nothing can lift a seccomp filter), so a slot is never shared ([PluginProcesses]) and the process exits when :app
// unbinds it, so the next plugin in the slot starts in a fresh process.
open class PluginOpenService : PluginSandboxService() {
    override val requiresIsolation: Boolean get() = false

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        android.util.Log.i("droidtop.plugin", "open process ${android.os.Process.myPid()}: unbound, ending with its plugin")
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }
}
class PluginOpenSlot0 : PluginOpenService()
class PluginOpenSlot1 : PluginOpenService()
class PluginOpenSlot2 : PluginOpenService()
class PluginOpenSlot3 : PluginOpenService()
class PluginOpenSlot4 : PluginOpenService()
class PluginOpenSlot5 : PluginOpenService()
class PluginOpenSlot6 : PluginOpenService()
class PluginOpenSlot7 : PluginOpenService()

/**
 * The seccomp-bpf filter of every plugin process droidtop loads from descriptors, the isolated sandbox and an open
 * process alike (docs/plugin-api.md 5.3; `native/src/plugin_filter.c`, the sandbox library's lockdown in
 * `vendor/sandbox`): no socket but a local datagram one (so no internet and no DNS; an open process with the
 * `net.sockets` hole gets internet sockets instead, and then no other socket), no programs, no io_uring, no ptrace,
 * no namespaces and no signals to other processes, on every thread and for good. Every call that names a path traps:
 * the plugin's own files answer at their virtual paths ([SandboxFiles]), droidtop's private folder goes to its broker in
 * :app ([SandboxBroker]), which makes it the plugin's own, and in an open process every other path goes there too. An
 * app may add such a filter to its own process (Chrome does for its renderers); it cannot look inside a binder call or
 * read a socket address, which is what the hole permissions' warnings are about.
 */
internal object PluginSyscallFilter {
    init {
        System.loadLibrary("droidtoppy")
    }

    @JvmStatic private external fun nativeInstall(brokerFd: Int, ownOpens: Array<String>, claims: Array<String>?, network: Boolean, fileLimit: Long): String

    /**
     * Device files a process with the `gpu.render` hole opens itself before the lockdown: the kernel ties binder and some GPU drivers'
     * state to the process that opened them, so the broker's descriptor would not do (Droidtop/tracker#26, the
     * Enginehost lockdown on emulator-5560: a broker-opened /dev/hwbinder failed to map).
     */
    private val OWN_OPENS = arrayOf("/dev/hwbinder", "/dev/kgsl-3d0", "/dev/mali0", "/dev/dri/renderD128")

    /**
     * Installs the filter once per process; returns what happened, starting "on:" or "error:". [brokerFd] is the
     * process's end of its broker's socket (taken). [claims] are the folders the broker answers for in the isolated
     * sandbox ([SandboxBroker.claims]), whose other paths stay the kernel's; null in an open process, whose every path
     * goes to the broker. [gpu] and [network] are an open process's granted holes: the graphics devices opened first,
     * and internet sockets. A contained process's files are also capped at the data store's quota each, and its
     * [network] is the broker's: the sockets are made, connected and accepted there (docs/plugin-api.md 5.6).
     */
    @Synchronized
    fun install(brokerFd: Int, claims: Array<String>?, gpu: Boolean = false, network: Boolean = false): String =
        nativeInstall(
            brokerFd, if (claims == null && gpu) OWN_OPENS else emptyArray(), claims, network,
            PluginDataStore.LIMIT_BYTES,
        )

    fun isOn(report: String): Boolean = report.startsWith("on:")
}

/** Dex code from a descriptor, for a process that can open no file (docs/plugin-api.md 5.3). */
internal object ContainedDex {
    private val DEX = Regex("^classes(\\d*)\\.dex$")

    /** The dex files inside the `classes.jar` zip behind [jar], in the order ART expects (`classes.dex`, `classes2.dex`, ...). */
    fun read(jar: ParcelFileDescriptor): List<ByteBuffer> {
        val found = sortedMapOf<Int, ByteBuffer>()
        ZipInputStream(FileInputStream(jar.fileDescriptor).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val match = DEX.matchEntire(entry.name) ?: continue
                val index = match.groupValues[1].toIntOrNull() ?: 1
                found[index] = ByteBuffer.wrap(zip.readBytes())
            }
        }
        check(found.isNotEmpty()) { "classes.jar holds no classes.dex" }
        return found.values.toList()
    }

    /**
     * Whether [loader] finds the native library at [path] as the plugin's `System.loadLibrary` will (Droidtop/tracker#790):
     * on the console Syncthing's `loadLibrary("syncthing")` found no `libsyncthing.so` in the virtual folder its class
     * loader names. Logged under `droidtop.plugin` with what an access and a stat of the virtual path answer in this
     * process, so a device's log says which step failed. Nothing changes for the plugin.
     */
    fun reportLibrary(pluginId: String, loader: ClassLoader, path: String) {
        val base = path.substringAfterLast('/')
        val name = base.removePrefix("lib").removeSuffix(".so")
        val found = runCatching { (loader as? dalvik.system.BaseDexClassLoader)?.findLibrary(name) }.getOrElse { "error: ${it.message}" }
        if (found != null) {
            android.util.Log.i("droidtop.plugin", "$pluginId: $base is found at $found")
            return
        }
        val access = runCatching { android.system.Os.access(path, android.system.OsConstants.R_OK); "ok" }.getOrElse { it.message ?: "failed" }
        val stat = runCatching { "${android.system.Os.stat(path).st_size} bytes" }.getOrElse { it.message ?: "failed" }
        android.util.Log.w("droidtop.plugin", "$pluginId: its class loader does not find $base; $path: access $access, stat $stat")
    }

    /**
     * One class loader over every dex, parented to droidtop's own so the plugin sees the plugin API. [libraryPath] is the
     * virtual folder of the plugin's native libraries, or null: API 29 takes it in the constructor, API 28 through the
     * loader's own `addNativePath` (the call Android's ApplicationLoaders makes); before that a contained plugin cannot
     * have native libraries. API 26 takes a single dex only.
     */
    fun loader(dexes: List<ByteBuffer>, parent: ClassLoader, libraryPath: String?): ClassLoader {
        if (libraryPath != null) {
            if (Build.VERSION.SDK_INT >= 29) return InMemoryDexClassLoader(dexes.toTypedArray(), libraryPath, parent)
            if (Build.VERSION.SDK_INT >= 28) {
                val loader = InMemoryDexClassLoader(dexes.toTypedArray(), parent)
                dalvik.system.BaseDexClassLoader::class.java.getDeclaredMethod("addNativePath", Collection::class.java)
                    .apply { isAccessible = true }
                    .invoke(loader, listOf(libraryPath))
                return loader
            }
            throw IllegalStateException("a plugin with native libraries needs Android 9 or later to run contained")
        }
        return when {
            Build.VERSION.SDK_INT >= 27 -> InMemoryDexClassLoader(dexes.toTypedArray(), parent)
            dexes.size == 1 -> InMemoryDexClassLoader(dexes[0], parent)
            else -> throw IllegalStateException("a plugin with ${dexes.size} dex files needs Android 8.1 or later to run contained")
        }
    }
}
