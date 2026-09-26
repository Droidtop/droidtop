package dev.droidtop.pluginhost

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * The `flutter_embed`-kind [DroidtopPlugin] adapter (docs/SPEC.md 12a):
 * hosts a real `FlutterEngine` inside `:pluginhost` and bridges droidtop's
 * `invoke`/`startJob` calls to the plugin's own Dart code over one
 * [MethodChannel], the same "JSON in, JSON out at the binder boundary"
 * shape [PythonDroidtopPlugin] already gives a `plugin.py` file.
 *
 * Feasibility (docs/SPEC.md 12a's flutter_embed section has the full
 * citation trail) rests on three confirmed-from-source Flutter engine
 * facts, none of them undocumented/hidden API:
 *
 * 1. `FlutterJNI` is a plain, non-final, `dlsym`-free-of-tricks public
 *    class whose `loadLibrary(Context)` is the ONLY place the engine
 *    calls `ReLinker.loadLibrary(context, "flutter")` (i.e. plain
 *    `System.loadLibrary("flutter")`, which only searches this
 *    process's OWN installed native library directory -- never a
 *    downloaded one). [DownloadedFlutterJNI] below overrides that one
 *    method to `System.load()` [FlutterRuntimeManager]'s own downloaded
 *    absolute path instead.
 * 2. `FlutterEngine`'s
 *    `(Context, FlutterLoader, FlutterJNI, String[] dartVmArgs, boolean)`
 *    constructor takes an explicit `FlutterLoader` (built here from our
 *    [DownloadedFlutterJNI], never touching the process-wide
 *    `FlutterInjector` singleton at all) AND explicit `dartVmArgs`.
 *    `FlutterLoader.ensureInitializationComplete`'s own source adds
 *    manifest-metadata flags first, then defaults, then these
 *    caller-supplied args LAST -- "last occurrence wins" for a repeated
 *    flag -- so passing our own `--aot-shared-library-name=<plugin's own
 *    absolute lib/<abi>/libapp.so path>` here overrides the internally
 *    computed (and wrong, since :pluginhost's real ApplicationInfo
 *    points at ITS OWN installed native lib dir, not any one plugin's)
 *    default.
 * 3. Flutter's own `FlutterLoader` source already adds
 *    `--aot-shared-library-name` TWICE for exactly this "the bare name
 *    doesn't always resolve" reason -- once bare, once as a full path --
 *    confirming a fully-qualified path argument is an intended,
 *    supported shape for this flag, not a hack.
 *
 * The one part of this NOT yet confirmed the same way -- flagged in
 * docs/SPEC.md 12a as the specific thing dq-flutterembed-01 must check --
 * is `flutter_assets`: those come from the plugin's own payload
 * directory, not the APK, and Android's `AssetManager` only reads a
 * `flutter_assets/` tree that way via the semi-public
 * `AssetManager.addAssetPath(String)` (public through API 28, reflective
 * since -- a long-standing technique real Android plugin-hosting
 * frameworks still use, not something this project invented). If that
 * reflection call is refused on the rig's Android build,
 * [loadAssetsIntoEngine] is the one thing here that needs a different
 * approach (repackaging `flutter_assets` as a tiny per-plugin split APK
 * is the documented fallback, not built).
 */
class FlutterDroidtopPlugin(
    private val pluginId: String,
    private val appContext: Context,
    private val installDir: File,
) : DroidtopPlugin {
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null

    // Job callbacks the host is waiting on, keyed by the SAME jobId
    // PluginRuntimeService.startJob generated and handed to startJob()
    // below. Dart reports progress/completion by calling back INTO this
    // channel (methods "jobProgress"/"jobComplete", handled in onLoad's
    // setMethodCallHandler) rather than over a return value, since a real
    // job outlives the single invokeMethod call that started it.
    private val activeJobs = ConcurrentHashMap<String, PluginJobProgress>()

    override fun onLoad(context: PluginContext) {
        val libapp = File(installDir, "lib/${FlutterRuntimeManager.currentAbi()}/libapp.so")
        if (!libapp.isFile) {
            throw IllegalStateException("no lib/${FlutterRuntimeManager.currentAbi()}/libapp.so in this plugin's payload")
        }
        val libflutter = FlutterRuntimeManager.libflutterSoPath(appContext)
            ?: throw IllegalStateException("Flutter runtime is not installed -- download it in Settings > Plugins first")

        val flutterJNI = DownloadedFlutterJNI(libflutter)
        val flutterLoader = FlutterLoader(flutterJNI)
        val dartVmArgs = arrayOf("--aot-shared-library-name=${libapp.absolutePath}")

        // FlutterEngine's constructor itself calls flutterLoader.startInitialization()/
        // ensureInitializationComplete() synchronously the first time -- this is real,
        // possibly slow (asset extraction, JNI init) work, which is why onLoad (like every
        // DroidtopPlugin.onLoad) is still covered by PluginRunner.CALL_TIMEOUT_MS, same
        // constraint PythonDroidtopPlugin.forInstall's Py_InitializeEx call already has.
        val newEngine = FlutterEngine(appContext, flutterLoader, flutterJNI, dartVmArgs, true)
        loadAssetsIntoEngine(newEngine, installDir)

        val messenger: BinaryMessenger = newEngine.dartExecutor.binaryMessenger
        val newChannel = MethodChannel(messenger, "dev.droidtop.pluginhost/$pluginId")
        newChannel.setMethodCallHandler { call, result -> handleIncomingCall(call, result) }
        engine = newEngine
        channel = newChannel

        // FlutterEngine's constructor sets everything up but does NOT run
        // the plugin's Dart `main()` on its own -- every real embedding
        // (FlutterActivity/FlutterFragment included) calls
        // executeDartEntrypoint itself. createDefault() runs `main` from
        // the asset bundle's own kernel/AOT data, which after
        // loadAssetsIntoEngine above is this plugin's own flutter_assets.
        newEngine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
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
    private fun loadAssetsIntoEngine(engine: FlutterEngine, installDir: File) {
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

    override fun onUnload() {
        channel?.setMethodCallHandler(null)
        channel = null
        engine?.destroy()
        engine = null
        activeJobs.clear()
    }

    /**
     * Handles calls Dart makes INTO the host over the same channel
     * [invoke] and [startJob] use to call OUT to Dart -- Flutter's
     * [MethodChannel] is bidirectional on one [BinaryMessenger], the
     * same object underlies both directions. Only "jobProgress" and
     * "jobComplete" are expected here; anything else (including a
     * mistaken "invoke", which only ever flows host-to-plugin) is
     * [MethodChannel.Result.notImplemented].
     */
    private fun handleIncomingCall(call: MethodChannel.MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "jobProgress" -> {
                val obj = JSONObject(call.arguments as String)
                val jobId = obj.optString("jobId")
                val progress = activeJobs[jobId]
                if (progress != null) {
                    progress.report(obj.optInt("percent"), obj.optString("statusLine"))
                }
                result.success(null)
            }
            "jobComplete" -> {
                val obj = JSONObject(call.arguments as String)
                val jobId = obj.optString("jobId")
                val progress = activeJobs.remove(jobId)
                if (progress != null) {
                    progress.complete(decode(obj.optString("result")))
                }
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult {
        val ch = channel ?: return PluginResult.failure("flutter engine not loaded")
        val argsJson = JSONObject().apply { args.keys().forEach { put(it, args.string(it)) } }
        val payload = JSONObject().put("capability", capability.id).put("args", argsJson).toString()

        // MethodChannel calls are async and must run on the engine's own
        // platform thread; PluginRuntimeService.invoke already runs off
        // droidtop's own main thread (a binder thread), so blocking here
        // with a latch -- bounded by the SAME PluginRunner.CALL_TIMEOUT_MS
        // the whole call is already wrapped in -- is safe and keeps this
        // adapter's public shape identical to PythonDroidtopPlugin's
        // synchronous invoke().
        val latch = CountDownLatch(1)
        var resultJson: String? = null
        var errorMessage: String? = null
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            ch.invokeMethod(
                "invoke",
                payload,
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        resultJson = result as? String
                        latch.countDown()
                    }

                    override fun error(errorCode: String, errorMessage2: String?, errorDetails: Any?) {
                        errorMessage = errorMessage2 ?: errorCode
                        latch.countDown()
                    }

                    override fun notImplemented() {
                        errorMessage = "plugin's Dart code has no MethodChannel handler for 'invoke'"
                        latch.countDown()
                    }
                },
            )
        }
        if (!latch.await(PluginRunner.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return PluginResult.failure("timed out waiting for the Dart side")
        }
        errorMessage?.let { return PluginResult.failure(it) }
        val raw = resultJson ?: return PluginResult.failure("plugin returned no result")
        return decode(raw)
    }

    private fun decode(resultJson: String): PluginResult {
        return try {
            val obj = JSONObject(resultJson)
            val ok = obj.optBoolean("ok", false)
            if (!ok) return PluginResult.failure(obj.optString("error", "flutter plugin call failed"))
            val values = obj.optJSONObject("values") ?: JSONObject()
            PluginResult.success(buildMap { values.keys().forEach { k -> put(k, values.optString(k)) } })
        } catch (e: Exception) {
            PluginResult.failure("malformed result from flutter plugin: ${e.message}")
        }
    }

    /**
     * Bridges [DroidtopPlugin.startJob] to Dart over the same
     * per-plugin [MethodChannel] [invoke] uses, mirroring the
     * request/response shape but fire-and-forget on the way out: unlike
     * [invoke] there is no [PluginRunner.CALL_TIMEOUT_MS] watchdog here
     * (the job itself is explicitly not bounded by it, same as every
     * other kind's [DroidtopPlugin.startJob] contract), so this posts
     * "startJob" to the engine's platform thread and returns immediately
     * -- [progress] is registered under [jobId] BEFORE that post so a
     * Dart callback racing ahead of this method's own return still finds
     * it. Dart's own `main.dart` is expected to call back "jobProgress"/
     * "jobComplete" (handled by [handleIncomingCall]) rather than reply
     * to the "startJob" invocation itself, since the job's real answer
     * arrives later, not synchronously.
     */
    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        val ch = channel ?: run {
            progress.complete(PluginResult.failure("flutter engine not loaded"))
            return
        }
        activeJobs[jobId] = progress
        val argsJson = JSONObject().apply { args.keys().forEach { put(it, args.string(it)) } }
        val payload = JSONObject()
            .put("jobId", jobId)
            .put("capability", capability.id)
            .put("args", argsJson)
            .toString()

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            ch.invokeMethod(
                "startJob",
                payload,
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        // Dart accepted the job; the real answer comes
                        // later via "jobComplete", not here.
                    }

                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        activeJobs.remove(jobId)?.complete(PluginResult.failure(errorMessage ?: errorCode))
                    }

                    override fun notImplemented() {
                        activeJobs.remove(jobId)?.complete(
                            PluginResult.failure("plugin's Dart code has no MethodChannel handler for 'startJob'"),
                        )
                    }
                },
            )
        }
    }

    /**
     * Best-effort, same contract as [DroidtopPlugin.cancelJob] generally:
     * forwards [jobId] to Dart so it can stop whatever it's doing, but
     * does not itself wait for or force completion -- Dart's own
     * "jobComplete" callback (or never calling it, if the process is
     * torn down first) is still what resolves [activeJobs].
     */
    override fun cancelJob(jobId: String) {
        val ch = channel ?: return
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            ch.invokeMethod("cancelJob", JSONObject().put("jobId", jobId).toString())
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

    companion object {
        fun forInstall(context: Context, pluginId: String, installDir: File): Result<FlutterDroidtopPlugin> {
            if (FlutterRuntimeManager.libflutterSoPath(context) == null) {
                return Result.failure(IllegalStateException("Flutter runtime not installed -- download it in Settings > Plugins first"))
            }
            return Result.success(FlutterDroidtopPlugin(pluginId, context, installDir))
        }
    }
}
