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
     * True for the isolated sandbox, false for a `gpu.render` process ([PluginGpuService]). A GPU process is the same
     * broker-only loader in a process of droidtop's own UID that is NOT `android:isolatedProcess`, the only process an
     * app may run that can open the graphics chip (docs/plugin-api.md 5.3).
     */
    protected open val requiresIsolation: Boolean get() = true

    /** The contained default draws in software (an isolated process may not open the GPU); a `gpu.render` process draws with hardware. */
    protected open val softwareRendering: Boolean get() = true

    override fun loadFromFolder(pluginId: String, dir: File, entryClass: String, rootApproved: Boolean, broker: IPluginHostBroker): Boolean =
        failLoad(pluginId, "a contained plugin is loaded from the files droidtop hands it, never from a folder")

    /** What the system-call filter reported in this process ([PluginSyscallFilter]), for the containment check. */
    @Volatile private var filter: String? = null

    override fun loadFromFiles(pluginId: String, manifest: PluginManifest, files: Map<String, ParcelFileDescriptor>, broker: IPluginHostBroker): Boolean {
        if (requiresIsolation && !dev.droidtop.runtime.util.IsolatedProcess.isIsolated()) return failLoad(pluginId, "the contained process is not isolated; refusing to run the plugin here")
        // The same seccomp filter for both kinds of plugin process, before any plugin code (docs/plugin-api.md 5.3). In a
        // graphics process it is the only network wall, so a process that cannot take it does not run the plugin. In the
        // isolated sandbox Android's own rules already refuse sockets, except where a device does not enforce them: the
        // BlueStacks rig runs with SELinux disabled and let an isolated UID open a socket (rig, v0.2.0-dev.1649). There the
        // filter is the wall; where it cannot be installed, the isolated process still stands, so the plugin runs.
        // The first native call of the process, which loads droidtop's own libdroidtoppy.so; timed in logcat because on the
        // BlueStacks rig that first library load waits 10 s inside its native bridge (docs/plugin-api.md 5.3, "The rigs").
        val before = android.os.SystemClock.uptimeMillis()
        val report = PluginSyscallFilter.install().also { filter = it }
        android.util.Log.i("droidtop.plugin", "$pluginId: system-call filter ${report.substringBefore(':')} in ${android.os.SystemClock.uptimeMillis() - before} ms (first native call)")
        if (!requiresIsolation && !PluginSyscallFilter.isOn(report)) {
            return failLoad(pluginId, "its process could not take droidtop's system-call filter ($report), so it is not run with the graphics chip")
        }
        val context = BrokerPluginContext(broker)
        return when (manifest.kind) {
            PluginKind.NATIVE_BUNDLE -> loadDex(pluginId, manifest, files, context)
            PluginKind.PYTHON -> loadPython(pluginId, manifest, files, context)
            PluginKind.FLUTTER_EMBED -> loadFlutter(pluginId, manifest, files, context)
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
// isolated service in a process of its own, `:plugin_sandbox0` to `:plugin_sandbox7` in this module's manifest.
class PluginSandboxSlot0 : PluginSandboxService()
class PluginSandboxSlot1 : PluginSandboxService()
class PluginSandboxSlot2 : PluginSandboxService()
class PluginSandboxSlot3 : PluginSandboxService()
class PluginSandboxSlot4 : PluginSandboxService()
class PluginSandboxSlot5 : PluginSandboxService()
class PluginSandboxSlot6 : PluginSandboxService()
class PluginSandboxSlot7 : PluginSandboxService()

// A `gpu.render` plugin's process (docs/plugin-api.md 5.3): the same broker-only loader as the sandbox, but in a process
// of droidtop's own UID that is NOT isolated, so the graphics chip is reachable and Flutter draws with hardware. Eight
// slots `:plugin_gpu0` to `:plugin_gpu7` in this module's manifest; there is no API 29 per-instance form because
// bindIsolatedService is for isolated processes only. Before any of the plugin's code loads, the process takes
// droidtop's system-call filter ([PluginSyscallFilter]), which takes away the network droidtop's UID would otherwise give
// it; a process that cannot take the filter does not run the plugin.
open class PluginGpuService : PluginSandboxService() {
    override val requiresIsolation: Boolean get() = false
    override val softwareRendering: Boolean get() = false
}
class PluginGpuSlot0 : PluginGpuService()
class PluginGpuSlot1 : PluginGpuService()
class PluginGpuSlot2 : PluginGpuService()
class PluginGpuSlot3 : PluginGpuService()
class PluginGpuSlot4 : PluginGpuService()
class PluginGpuSlot5 : PluginGpuService()
class PluginGpuSlot6 : PluginGpuService()
class PluginGpuSlot7 : PluginGpuService()

/**
 * The seccomp-bpf filter of every plugin process droidtop loads from descriptors, the isolated sandbox and a
 * `gpu.render` process alike (docs/plugin-api.md 5.3; `native/src/plugin_filter.c`, the sandbox library's lockdown in
 * `vendor/sandbox`): no socket but a local datagram one (so no internet and no DNS), no programs, no io_uring, no ptrace,
 * no namespaces and no signals to other processes, on every thread and for good. An app may add such a filter to its own process (Chrome does for its renderers); it
 * cannot filter by path or look inside a binder call, which is what the permission's warning is about.
 */
internal object PluginSyscallFilter {
    init {
        System.loadLibrary("droidtoppy")
    }

    @JvmStatic private external fun nativeInstall(): String

    /** Installs the filter once per process; returns what happened, starting "on:" or "error:". */
    @Synchronized
    fun install(): String = nativeInstall()

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
