package dev.droidtop.pluginhost

import android.content.Context
import dalvik.system.DexClassLoader
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.loader.FlutterLoader
import java.io.File

/**
 * Builds a plugin's `FlutterEngine` the one way both of its hosts need
 * (docs/SPEC.md 12a): [FlutterDroidtopPlugin] for the headless engine that
 * answers droidtop's calls, and [PluginMainActivity] for the full-screen
 * main UI (`ui.main`, docs/plugin-api.md 1.7). Both run in `:pluginhost`,
 * so the Dart VM and its `FlutterLoader` are shared per plugin (one
 * `libapp.so`), but every engine gets a `FlutterJNI` of its own: one JNI
 * object is one native shell. The members below are the ones the headless
 * plugin used to carry privately; their comments are unchanged.
 */
internal object FlutterEngineHost {
    class Built(val engine: FlutterEngine, val appBundlePath: String)

    // One FlutterLoader per libapp.so: initialising the VM again for the same
    // snapshot is what a second engine must not do. Touched on the main thread only.
    private val loaders = HashMap<String, FlutterLoader>()

    /** Builds an engine for the plugin installed at [installDir]; must run on the main thread (FlutterEngine is @UiThread). Throws when the payload or runtime is missing. */
    fun build(appContext: Context, pluginId: String, installDir: File): Built {
        val abi = FlutterRuntimeManager.currentAbi()
        val libapp = File(installDir, "lib/$abi/libapp.so")
        if (!libapp.isFile) throw IllegalStateException("no lib/$abi/libapp.so in this plugin's payload")
        val libflutter = FlutterRuntimeManager.libflutterSoPath(appContext)
            ?: throw IllegalStateException("Flutter runtime is not installed -- download it in Settings > Plugins first")
        loadAssetsIntoEngine(appContext, installDir)
        val flutterJNI = DownloadedFlutterJNI(libflutter)
        val flutterLoader = loaders.getOrPut(libapp.absolutePath) { FlutterLoader(flutterJNI) }
        val dartVmArgs = arrayOf("--aot-shared-library-name=${libapp.absolutePath}")
        // false, not true: automatic registration only looks on :pluginhost's own classloader, which never has the plugin's generated registrant (see registerGeneratedPlugins).
        val engine = FlutterEngine(appContext, flutterLoader, flutterJNI, dartVmArgs, false)
        registerGeneratedPlugins(appContext, pluginId, engine, installDir)
        return Built(engine, flutterLoader.findAppBundlePath())
    }

    /**
     * NOT YET RIG-VERIFIED (docs/SPEC.md 12a, dq-flutterembed-01): adds
     * this plugin's own `installDir/flutter_assets` tree to the engine's
     * `AssetManager` via the reflective `addAssetPath(String)` call
     * described in this class's own header comment. `flutter_assets`
     * must be handed to Android's `AssetManager` as a zip/apk-shaped
     * file, not a bare directory -- `addAssetPath` only ever reads zip
     * central directories -- so this repackages the plugin's own
     * extracted `flutter_assets/` tree into a small on-disk zip once per
     * load (`installDir/data/flutter_assets.repack.zip`) and adds THAT.
     */
    private fun loadAssetsIntoEngine(appContext: Context, installDir: File) {
        val assetsDir = File(installDir, "flutter_assets")
        if (!assetsDir.isDirectory) {
            throw IllegalStateException("no flutter_assets/ in this plugin's payload")
        }
        val repacked = File(installDir, "data").apply { mkdirs() }.resolve("flutter_assets.repack.zip")
        repackAssetsAsZip(assetsDir, repacked)
        val assetManager = appContext.assets
        val addAssetPath = assetManager.javaClass.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assetManager, repacked.absolutePath) as? Int
        if (cookie == null || cookie == 0) {
            throw IllegalStateException("AssetManager.addAssetPath refused this plugin's flutter_assets (cookie=$cookie) -- see FlutterDroidtopPlugin's header comment")
        }
    }

    /**
     * Registers this plugin's OWN real Flutter plugin dependencies
     * (sqflite, path_provider, ...) with [engine] -- found missing
     * 2026-09-26 while getting the acquire_content UI's first real
     * search call to work against a real flutter_embed plugin: the
     * readiness handshake and the load-once fix above (both real, both
     * necessary) got `invoke()` all the way to that plugin's own Dart
     * code, which then failed immediately with
     * `PlatformException(channel-error, Unable to establish connection on
     * channel: "dev.flutter.pigeon.
     * path_provider_android.PathProviderApi.getApplicationDocumentsPath"...)`
     * -- the plugin's own database layer calls `path_provider` to find its
     * sqlite file, and nothing had ever registered that plugin's ANDROID
     * side with this engine.
     *
     * The reason: `build.sh` only ever extracted `libapp.so` (the Dart AOT
     * snapshot) and `flutter_assets` from the plugin's own built APK --
     * never `classes.dex`/`classesN.dex`, which is where a normal Flutter
     * build compiles BOTH the plugin's own Dart-independent Android glue
     * (`PathProviderPlugin`, `SqflitePlugin`, ...) AND the generated
     * `io.flutter.plugins.GeneratedPluginRegistrant` that a real
     * `FlutterActivity` calls to wire them all up. `FlutterEngine`'s own
     * automatic-registration reflection (`automaticallyRegisterPlugins`)
     * looks for that class on :pluginhost's OWN classloader -- which never
     * has it, since :pluginhost's own compiled code has no idea what
     * plugins any given flutter_embed plugin bundles -- so it silently
     * finds nothing (`GeneratedPluginsRegister: could not find or invoke
     * the GeneratedPluginRegistrant`, logged on every single load, always
     * dismissed as harmless because the trivial sample never used a real
     * plugin package to notice).
     *
     * Fixed generically, not per-plugin-name: `build.sh` now also copies
     * the built APK's `classes.dex`/`classesN.dex` into the bundle's own
     * `dex/` folder (present only when the Dart code actually needed real
     * plugin packages; absent -- as for the trivial sample -- is a normal,
     * silent no-op here). When present, they are loaded with a
     * [DexClassLoader] parented to THIS class's own classloader (so the
     * loaded code can resolve `io.flutter.embedding.engine.plugins.
     * FlutterPlugin` and friends from :pluginhost's existing
     * `flutter_embedding_release` dependency), and `GeneratedPluginRegistrant
     * .registerWith(FlutterEngine)` is invoked reflectively -- the exact
     * same call a normal `FlutterActivity.configureFlutterEngine` makes,
     * just against a dex path this engine's own installer chose instead of
     * the app's default one. `DexClassLoader`'s own `dexPath` accepts
     * multiple entries separated by [File.pathSeparator] (colon on
     * Android, a real ART-supported multidex-loading path, not a hack),
     * which is why a plugin needing `classes2.dex`+ still works.
     *
     * A plugin whose own dependencies need Android manifest entries this
     * process's manifest does not declare (a content provider, a
     * broadcast receiver, a runtime permission) can still fail at actual
     * use -- registering the plugin class is necessary, not sufficient,
     * for every possible real-world Flutter package. That is a real,
     * separate limit of hosting arbitrary third-party plugins headlessly,
     * not something this method can paper over, and is called out here so
     * a future plugin author hitting it does not mistake it for this same
     * bug.
     */
    private fun registerGeneratedPlugins(appContext: Context, pluginId: String, engine: FlutterEngine, installDir: File) {
        val dexDir = File(installDir, "dex")
        val dexFiles = dexDir.listFiles { f -> f.isFile && f.extension == "dex" }
            ?.sortedBy { it.name }
            ?: return
        if (dexFiles.isEmpty()) return
        val dexPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
        val optimizedDir = File(appContext.cacheDir, "flutter-plugin-dex-opt/$pluginId").apply { mkdirs() }
        val loader = DexClassLoader(dexPath, optimizedDir.absolutePath, null, FlutterEngineHost::class.java.classLoader)
        val registrantClass = try {
            Class.forName("io.flutter.plugins.GeneratedPluginRegistrant", true, loader)
        } catch (e: ClassNotFoundException) {
            // This plugin's Dart code never needed a real Flutter plugin
            // package -- nothing generated this class, and that is a
            // normal, expected shape (the trivial sample is exactly this).
            return
        }
        registrantClass.getMethod("registerWith", FlutterEngine::class.java).invoke(null, engine)
    }

    private fun repackAssetsAsZip(assetsDir: File, out: File) {
        out.delete()
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            assetsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = "flutter_assets/" + file.relativeTo(assetsDir).path.replace(File.separatorChar, '/')
                zip.putNextEntry(java.util.zip.ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Overrides the one method [FlutterJNI] uses to load `libflutter.so`
     * (this class's own header comment has the full citation). Every
     * other `FlutterJNI` method is untouched -- this is a targeted
     * override, not a reimplementation, the same "hand-resolve only the
     * small stable subset" restraint [PythonBridge]'s native file
     * documents for CPython's C API.
     */
    private class DownloadedFlutterJNI(private val libflutterSo: File) : FlutterJNI() {
        override fun loadLibrary(context: Context) {
            System.load(libflutterSo.absolutePath)
        }
    }
}
