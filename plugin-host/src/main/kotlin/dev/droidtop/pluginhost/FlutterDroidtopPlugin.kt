package dev.droidtop.pluginhost

import android.content.Context
import dalvik.system.DexClassLoader
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterJNI
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
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

    // The readiness handshake (found needed on the rig, 2026-09-26): a
    // Dart plugin's own `main()` calling `setMethodCallHandler` is not
    // synchronous with `executeDartEntrypoint` returning below --
    // `executeDartEntrypoint` only starts the isolate, it does not wait
    // for that isolate's own main() body to run. The very first real
    // `invoke()` call posted right after onLoad returned used to race
    // Dart's own startup and fail with a channel-not-yet-registered
    // PlatformException ("channel-error, Unable to establish
    // connection") -- confirmed on BlueStacks against a real
    // flutter_embed plugin, the acquire_content UI's own first real
    // caller. Every flutter_embed plugin's own Dart entrypoint MUST call
    // `_channel.invokeMethod("ready")` as the very first thing it does
    // after `setMethodCallHandler` (see samples/plugin-sample-flutter-
    // statustile's own main.dart) -- this is now part of the
    // flutter_embed contract, documented in docs/SPEC.md 12a. No sleep,
    // no polling: [onLoad] blocks the CALLING thread (never the main
    // thread, which must stay free to deliver that very "ready" call)
    // on this latch until it counts down or [PluginRunner.CALL_TIMEOUT_MS]
    // elapses.
    private var readyLatch: CountDownLatch? = null

    override fun onLoad(context: PluginContext) {
        val libapp = File(installDir, "lib/${FlutterRuntimeManager.currentAbi()}/libapp.so")
        if (!libapp.isFile) {
            throw IllegalStateException("no lib/${FlutterRuntimeManager.currentAbi()}/libapp.so in this plugin's payload")
        }
        val libflutter = FlutterRuntimeManager.libflutterSoPath(appContext)
            ?: throw IllegalStateException("Flutter runtime is not installed -- download it in Settings > Plugins first")

        // Found on the rig (dq-flutterembed-01): FlutterEngine's constructor,
        // FlutterLoader's init and DartExecutor.executeDartEntrypoint are all
        // @UiThread -- Flutter enforces this itself ("Methods marked with
        // @UiThread must be executed on the main thread"). onLoad() runs on
        // whatever thread NativePluginRunner's AIDL call arrives on inside
        // :pluginhost (a binder thread), never the main thread, so every one
        // of these calls has to be pushed onto the main Looper and waited on
        // -- the same blocking-post-and-latch shape invoke() below already
        // uses for MethodChannel calls, for the same underlying reason.
        runOnMainThreadBlocking {
            val flutterJNI = DownloadedFlutterJNI(libflutter)
            val flutterLoader = FlutterLoader(flutterJNI)
            val dartVmArgs = arrayOf("--aot-shared-library-name=${libapp.absolutePath}")

            // FlutterEngine's constructor itself calls flutterLoader.startInitialization()/
            // ensureInitializationComplete() synchronously the first time -- this is real,
            // possibly slow (asset extraction, JNI init) work, which is why onLoad (like every
            // DroidtopPlugin.onLoad) is still covered by PluginRunner.CALL_TIMEOUT_MS, same
            // constraint PythonDroidtopPlugin.forInstall's Py_InitializeEx call already has.
            // false, not true: FlutterEngine's own automatic registration only
            // ever looks on :pluginhost's OWN restricted classloader, which
            // never has a plugin's generated registrant or its real plugin
            // classes (sqflite, path_provider, ...) on it -- see
            // registerGeneratedPlugins below, which does the same job with
            // the RIGHT classloader instead.
            val newEngine = FlutterEngine(appContext, flutterLoader, flutterJNI, dartVmArgs, false)
            loadAssetsIntoEngine(newEngine, installDir)
            registerGeneratedPlugins(newEngine, installDir)

            val messenger: BinaryMessenger = newEngine.dartExecutor.binaryMessenger
            val newChannel = MethodChannel(messenger, "dev.droidtop.pluginhost/$pluginId")
            newChannel.setMethodCallHandler { call, result -> handleIncomingCall(call, result) }
            engine = newEngine
            channel = newChannel

            // FlutterEngine's constructor sets everything up but does NOT run
            // the plugin's Dart `main()` on its own -- every real embedding
            // (FlutterActivity/FlutterFragment included) calls
            // executeDartEntrypoint itself. NOT DartEntrypoint.createDefault():
            // found on the rig -- that factory reads
            // FlutterInjector.instance().flutterLoader(), the PROCESS-WIDE
            // singleton, and throws ("DartEntrypoints can only be created
            // once a FlutterEngine is created") if IT was never initialized --
            // which it never is here, deliberately (this class's own header
            // comment: "never touching the process-wide FlutterInjector
            // singleton at all"). [flutterLoader] above, OUR OWN instance,
            // was already initialized by the FlutterEngine constructor
            // that just ran; its own findAppBundlePath() is what
            // createDefault() would have used, so calling it directly and
            // building the DartEntrypoint by hand skips the singleton
            // entirely.
            val latch = CountDownLatch(1)
            readyLatch = latch
            val entrypoint = DartExecutor.DartEntrypoint(flutterLoader.findAppBundlePath(), "main")
            newEngine.dartExecutor.executeDartEntrypoint(entrypoint)
        }

        // Deliberately OUTSIDE runOnMainThreadBlocking's own post: that
        // block already returned (executeDartEntrypoint only starts the
        // isolate), and the "ready" call Dart sends back arrives as an
        // ordinary MethodChannel message on the main Looper -- waiting
        // for it FROM the main thread would deadlock the very thread
        // that has to deliver it. This await happens on onLoad's own
        // calling thread (a binder thread inside :pluginhost), which is
        // exactly what leaves the main thread free.
        val latch = readyLatch
        if (latch != null && !latch.await(PluginRunner.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException(
                "plugin's Dart entrypoint never signaled ready -- " +
                    "every flutter_embed plugin must call _channel.invokeMethod('ready') " +
                    "right after setMethodCallHandler (docs/SPEC.md 12a)",
            )
        }
    }

    /**
     * Runs [block] on the main thread and blocks the CALLING thread until
     * it finishes, rethrowing whatever [block] threw on the caller's own
     * thread so [onLoad]'s normal "throwing is how you report failure"
     * contract (PluginRuntimeService.loadPlugin's catch(Throwable)) still
     * works unchanged -- the exception just now genuinely happened on the
     * main thread, not the calling one.
     */
    private fun runOnMainThreadBlocking(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(PluginRunner.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        error?.let { throw it }
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
    private fun registerGeneratedPlugins(engine: FlutterEngine, installDir: File) {
        val dexDir = File(installDir, "dex")
        val dexFiles = dexDir.listFiles { f -> f.isFile && f.extension == "dex" }
            ?.sortedBy { it.name }
            ?: return
        if (dexFiles.isEmpty()) return
        val dexPath = dexFiles.joinToString(File.pathSeparator) { it.absolutePath }
        val optimizedDir = File(appContext.cacheDir, "flutter-plugin-dex-opt/$pluginId").apply { mkdirs() }
        val loader = DexClassLoader(dexPath, optimizedDir.absolutePath, null, this::class.java.classLoader)
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

    override fun onUnload() {
        channel?.setMethodCallHandler(null)
        channel = null
        // FlutterEngine.destroy() is @UiThread too -- post it, but
        // onUnload itself is documented best-effort ("the process may
        // already be dying"), so this does not block waiting for it the
        // way onLoad's runOnMainThreadBlocking does.
        val toDestroy = engine
        engine = null
        if (toDestroy != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { toDestroy.destroy() }
        }
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
    private fun handleIncomingCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "ready" -> {
                // The readiness handshake's other half -- see [readyLatch]'s
                // own doc comment. Counting down more than once (a plugin
                // that calls this twice) is harmless; CountDownLatch ignores it.
                readyLatch?.countDown()
                result.success(null)
            }
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
        val argsJson = JSONObject().apply { args.keys().forEach { put(it, args.string(it)) } }
        val payload = JSONObject().put("capability", capability.id).put("args", argsJson).toString()
        val (resultJson, errorMessage) = callDart("invoke", payload)
        errorMessage?.let { return PluginResult.failure(it) }
        val raw = resultJson ?: return PluginResult.failure("plugin returned no result")
        return decode(raw)
    }

    /**
     * The contract 2 envelope (docs/plugin-api.md 1.3, 1.6): a contract 2 plugin's
     * Dart code answers the channel method `handle` with the envelope in and the
     * reply out, both JSON text. A contract 1 plugin keeps the translation onto its
     * capabilities, so nothing changes for a bundle built before this existed.
     */
    override fun onEvent(event: PluginEvent, args: PluginArgs): PluginResult {
        val argsJson = JSONObject().apply { args.keys().forEach { put(it, args.string(it)) } }
        val payload = JSONObject().put("event", event.id).put("args", argsJson).toString()
        val (resultJson, errorMessage) = callDart("onEvent", payload)
        if (errorMessage != null && errorMessage.contains("plugin's Dart code has no MethodChannel handler for 'onEvent'")) {
            return PluginResult.success()
        }
        errorMessage?.let { return PluginResult.failure(it) }
        val raw = resultJson ?: return PluginResult.failure("plugin returned no result")
        return decode(raw)
    }

    override fun handle(call: PluginCall): PluginReply {
        if (!speaksContract2) return LegacyHandle.translate(this, call)
        val (replyJson, errorMessage) = callDart("handle", call.toJson().toString())
        errorMessage?.let { return PluginReply.error(PluginErrorCode.FAILED, it) }
        return PluginReply.parse(replyJson)
    }

    /** Whether this plugin's own manifest is contract 2, read once from its installed payload. */
    private val speaksContract2: Boolean by lazy {
        runCatching {
            PluginManifest.fromJson(JSONObject(File(installDir, "manifest.json").readText()))?.contractVersion ?: 1
        }.getOrDefault(1) >= 2
    }

    /**
     * One request/response call into Dart: the JSON text it answered, or why it did not.
     * MethodChannel calls are async and must run on the engine's own platform thread;
     * every caller here already runs off droidtop's main thread (a binder thread), so
     * blocking on a latch -- bounded by the same [PluginRunner.CALL_TIMEOUT_MS] the whole
     * call is already wrapped in -- is safe and keeps this adapter synchronous like
     * [PythonDroidtopPlugin].
     */
    private fun callDart(method: String, payload: String): Pair<String?, String?> {
        val ch = channel ?: return null to "flutter engine not loaded"
        val latch = CountDownLatch(1)
        var resultJson: String? = null
        var errorMessage: String? = null
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            ch.invokeMethod(
                method,
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
                        errorMessage = "plugin's Dart code has no MethodChannel handler for '$method'"
                        latch.countDown()
                    }
                },
            )
        }
        if (!latch.await(PluginRunner.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return null to "timed out waiting for the Dart side"
        }
        return resultJson to errorMessage
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
