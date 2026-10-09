package dev.droidtop.pluginhost

import android.content.Context
import dalvik.system.DexClassLoader
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.loader.FlutterLoader
import java.io.File
import java.nio.ByteBuffer

/**
 * Where one Flutter plugin's files come from (docs/SPEC.md 12a, docs/plugin-api.md 5.3).
 *
 * - [installed]: with full access, its install folder and the downloaded runtime, by their real paths.
 * - [contained]: the plugin's code arrives as descriptors :app handed over, at virtual paths under [SandboxFiles.ROOT].
 *   The guarded hooks answer them: `System.load` of the engine, the engine's own `dlopen` of `libapp.so` (hooked once
 *   the engine is loaded) and the asset manager opening the asset zip. The plugin's generated plugin registrant, when
 *   it has one, is dex loaded from memory. [softwareRendering] is set for the isolated sandbox, which may not open the
 *   GPU driver, and cleared for a `gpu.render` process, which may.
 */
internal class FlutterSource private constructor(
    /** Absolute path `System.load` loads the engine from. */
    val libflutter: String,
    /** Absolute path of the plugin's AOT snapshot, `--aot-shared-library-name`. */
    val libapp: String,
    /** A zip with a `flutter_assets/` tree, added to the engine's AssetManager. */
    val assetsZip: String,
    /** The dex files with the plugin's generated plugin registrant, or none. */
    private val registrant: () -> ClassLoader?,
    val contained: Boolean,
    val softwareRendering: Boolean,
) {
    fun registrantLoader(): ClassLoader? = registrant()

    companion object {
        /** A full-access plugin's own folder. Throws when the payload or runtime is missing. */
        fun installed(appContext: Context, pluginId: String, installDir: File): FlutterSource {
            val abi = FlutterRuntimeManager.currentAbi()
            val libapp = File(installDir, "lib/$abi/libapp.so")
            if (!libapp.isFile) throw IllegalStateException("no lib/$abi/libapp.so in this plugin's payload")
            val libflutter = FlutterRuntimeManager.libflutterSoPath(appContext)
                ?: throw IllegalStateException("Flutter runtime is not installed -- download it in Settings > Plugins first")
            val zip = FlutterAssets.repack(installDir, File(installDir, "data").apply { mkdirs() }.resolve("flutter_assets.repack.zip"))
            return FlutterSource(libflutter.absolutePath, libapp.absolutePath, zip.absolutePath, {
                val dexFiles = File(installDir, "dex").listFiles { f -> f.isFile && f.extension == "dex" }?.sortedBy { it.name }.orEmpty()
                if (dexFiles.isEmpty()) {
                    null
                } else {
                    val optimizedDir = File(appContext.cacheDir, "flutter-plugin-dex-opt/$pluginId").apply { mkdirs() }
                    DexClassLoader(dexFiles.joinToString(File.pathSeparator) { it.absolutePath }, optimizedDir.absolutePath, null, FlutterEngineHost::class.java.classLoader)
                }
            }, contained = false, softwareRendering = false)
        }

        /**
         * A contained plugin's files, already registered with [SandboxFiles] under these names ([ContainedFiles]).
         * [softwareRendering] is true in the isolated sandbox and false in a `gpu.render` process.
         */
        fun contained(registrantDex: List<ByteBuffer>, softwareRendering: Boolean): FlutterSource =
            FlutterSource(
                libflutter = SandboxFiles.RUNTIME + "libflutter.so",
                libapp = SandboxFiles.PLUGIN + "libapp.so",
                assetsZip = SandboxFiles.PLUGIN + "flutter_assets.zip",
                registrant = { if (registrantDex.isEmpty()) null else ContainedDex.loader(registrantDex, FlutterEngineHost::class.java.classLoader!!, null) },
                contained = true,
                softwareRendering = softwareRendering,
            )
    }
}

/**
 * Builds a plugin's `FlutterEngine` the one way all of its hosts need (docs/SPEC.md 12a): the headless engine that
 * answers droidtop's calls, and the full-screen main UI (`ui.main`, docs/plugin-api.md 1.7) drawn either by
 * [PluginMainActivity] (full access) or into a surface droidtop owns ([PluginScreenActivity], contained). Every engine
 * gets a `FlutterJNI` of its own (one JNI object is one native shell); the `FlutterLoader`, which starts the Dart VM, is
 * one per snapshot.
 */
internal object FlutterEngineHost {
    class Built(val engine: FlutterEngine, val appBundlePath: String)

    // One FlutterLoader per libapp.so: initialising the VM again for the same
    // snapshot is what a second engine must not do. Touched on the main thread only.
    private val loaders = HashMap<String, FlutterLoader>()

    /** Builds an engine for [source]; must run on the main thread (FlutterEngine is @UiThread). Throws when a file is missing. */
    fun build(appContext: Context, pluginId: String, source: FlutterSource): Built {
        addAssets(appContext, source.assetsZip)
        val flutterJNI = DownloadedFlutterJNI(source.libflutter, source.contained)
        val flutterLoader = loaders.getOrPut(source.libapp) { FlutterLoader(flutterJNI) }
        val dartVmArgs = buildList {
            add("--aot-shared-library-name=${source.libapp}")
            // An isolated process may not open the GPU driver (sepolicy isolated_app_all.te): Flutter draws in software
            // there. A gpu.render process is not isolated and draws with hardware (docs/plugin-api.md 5.3).
            if (source.softwareRendering) add("--enable-software-rendering")
        }.toTypedArray()
        // false, not true: automatic registration only looks on this process's own classloader, which never has the plugin's generated registrant (see registerGeneratedPlugins).
        val engine = FlutterEngine(appContext, flutterLoader, flutterJNI, dartVmArgs, false)
        registerGeneratedPlugins(source, engine)
        return Built(engine, flutterLoader.findAppBundlePath())
    }

    /**
     * Adds the plugin's `flutter_assets` zip to the engine's AssetManager through the reflective `addAssetPath(String)`
     * (public through API 28, reflective since; rig-verified for the full-access path, dq-flutterembed-01). Contained, the
     * path is virtual and the asset manager's own zip reader is answered by the guarded hooks.
     */
    private fun addAssets(appContext: Context, zip: String) {
        val assetManager = appContext.assets
        val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assetManager, zip) as? Int
        if (cookie == null || cookie == 0) {
            throw IllegalStateException("AssetManager.addAssetPath refused this plugin's flutter_assets (cookie=$cookie)")
        }
    }

    /**
     * Registers the plugin's own Flutter plugin dependencies (sqflite, path_provider, ...) with [engine]: the
     * `GeneratedPluginRegistrant` a normal `FlutterActivity` calls, found in the dex files the plugin's build copied into
     * its bundle's `dex/` folder (found missing 2026-09-26: without it the first channel call of a real plugin failed).
     * A plugin whose Dart code needs no plugin package has no such class, which is normal. A dependency that needs a
     * manifest entry this process does not declare, or files (contained plugins have none), can still fail at use.
     */
    private fun registerGeneratedPlugins(source: FlutterSource, engine: FlutterEngine) {
        val loader = source.registrantLoader() ?: return
        val registrantClass = try {
            Class.forName("io.flutter.plugins.GeneratedPluginRegistrant", true, loader)
        } catch (e: ClassNotFoundException) {
            return
        }
        registrantClass.getMethod("registerWith", FlutterEngine::class.java).invoke(null, engine)
    }

    /**
     * Overrides the one method [FlutterJNI] uses to load `libflutter.so`, so it loads the downloaded runtime instead of a
     * library in droidtop's own APK. Contained, the path is virtual; once the engine is in, its own imports are hooked
     * too, because the engine opens the plugin's `libapp.so` itself.
     */
    private class DownloadedFlutterJNI(private val libflutterSo: String, private val contained: Boolean) : FlutterJNI() {
        override fun loadLibrary(context: Context) {
            System.load(libflutterSo)
            if (contained) SandboxFiles.hook("/libflutter.so")
        }
    }
}

/** A plugin's `flutter_assets/` tree as the zip AssetManager reads (it only reads zip central directories, never a folder). */
internal object FlutterAssets {
    /** Writes [out] from [installDir]'s `flutter_assets/` and returns it. Throws when there is no such tree. */
    fun repack(installDir: File, out: File): File {
        val assetsDir = File(installDir, "flutter_assets")
        if (!assetsDir.isDirectory) throw IllegalStateException("no flutter_assets/ in this plugin's payload")
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
            assetsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = "flutter_assets/" + file.relativeTo(assetsDir).path.replace(File.separatorChar, '/')
                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw IllegalStateException("could not write ${out.name}")
        }
        return out
    }
}
