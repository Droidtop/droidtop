package dev.droidtop.pluginhost

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.android.AndroidTouchProcessor
import io.flutter.embedding.engine.renderer.FlutterRenderer
import io.flutter.embedding.engine.systemchannels.KeyEventChannel
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.JSONMethodCodec
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
class FlutterDroidtopPlugin internal constructor(
    private val pluginId: String,
    private val appContext: Context,
    private val source: FlutterSource,
    /** The manifest's contract: 2 means the Dart code answers `handle` itself. */
    private val contractVersion: Int,
) : DroidtopPlugin, HostedScreen {
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    @Volatile private var pluginContext: PluginContext? = null
    private val hostCallExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
        pluginContext = context
        // Found on the rig (dq-flutterembed-01): FlutterEngine's constructor,
        // FlutterLoader's init and DartExecutor.executeDartEntrypoint are all
        // @UiThread, and onLoad() runs on a binder thread inside :pluginhost,
        // so the whole build is pushed onto the main Looper and waited on
        // (the same blocking-post-and-latch shape invoke() uses). The build
        // itself (assets, loader, engine, generated plugins) is
        // [FlutterEngineHost.build], shared with the main UI activity; it is
        // real, possibly slow work, so onLoad stays covered by
        // PluginRunner.CALL_TIMEOUT_MS like every DroidtopPlugin.onLoad.
        runOnMainThreadBlocking {
            val built = FlutterEngineHost.build(appContext, pluginId, source)
            val newEngine = built.engine
            val messenger: BinaryMessenger = newEngine.dartExecutor.binaryMessenger
            val newChannel = MethodChannel(messenger, "dev.droidtop.pluginhost/$pluginId")
            newChannel.setMethodCallHandler { call, result -> handleIncomingCall(call, result) }
            engine = newEngine
            channel = newChannel

            // FlutterEngine's constructor does NOT run the plugin's Dart
            // `main()` on its own. The DartEntrypoint is built by hand from
            // the engine's own loader (never DartEntrypoint.createDefault(),
            // which reads the process-wide FlutterInjector singleton that is
            // deliberately never initialised here).
            val latch = CountDownLatch(1)
            readyLatch = latch
            newEngine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint(built.appBundlePath, "main"))
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
        pluginContext = null
        hostCallExecutor.shutdownNow()
    }

    /**
     * Handles calls Dart makes INTO the host over the same channel
     * [invoke] and [startJob] use to call OUT to Dart -- Flutter's
     * [MethodChannel] is bidirectional on one [BinaryMessenger], the
     * same object underlies both directions. The plugin can make broker
     * calls with "hostCall" and report jobs with "jobProgress"/
     * "jobComplete"; other calls (including a mistaken "invoke", which
     * only ever flows host-to-plugin) are [MethodChannel.Result.notImplemented].
     */
    private fun handleIncomingCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "hostCall" -> handleHostCall(call.arguments, result)
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

    /** Broker calls can block, so they run off the main Looper that delivers MethodChannel calls. */
    private fun handleHostCall(arguments: Any?, result: MethodChannel.Result) {
        val request = try {
            HostCallRequest.parse(arguments)
        } catch (e: Exception) {
            result.success(errorReply("INVALID_ARGS", e.message ?: "malformed hostCall argument"))
            return
        }

        val context = pluginContext
        if (context == null) {
            result.success(errorReply("FAILED", "plugin is not loaded"))
            return
        }
        try {
            hostCallExecutor.execute {
                val reply = runCatching { context.call(request.api, request.version, request.op, request.argsJson) }
                    .getOrElse { errorReply("FAILED", it.message ?: "broker call failed") }
                mainHandler.post { result.success(reply) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            result.success(errorReply("FAILED", "plugin is unloading"))
        }
    }

    private fun errorReply(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

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
    private val speaksContract2: Boolean get() = contractVersion >= 2

    // ---------------------------------------------------------------
    // ui.main drawn into a surface droidtop owns (contained, docs/plugin-api.md 1.7)
    // ---------------------------------------------------------------

    private var screenEngine: FlutterEngine? = null
    private var bridged = false
    private var bridgeReport: String? = null
    private var screenTouch: AndroidTouchProcessor? = null
    private var screenKeys: KeyEventChannel? = null

    /**
     * Starts the plugin's main UI on an engine of its own, drawing into [surface], a surface of droidtop's
     * ([PluginScreenActivity]) handed across the binder. A contained process has no window and may not open the GPU,
     * so the engine draws in software into that surface; touch and keys come from droidtop. When the plugin's last route
     * closes (`SystemNavigator.pop`), [onClose] tells droidtop to close the screen.
     */
    override fun attachScreen(entry: PluginMainUi.Entry, target: ScreenTarget, width: Int, height: Int, density: Float, onClose: () -> Unit): String? =
        try {
            runOnMainThreadBlocking {
                detachScreenOnMain()
                val built = FlutterEngineHost.build(appContext, pluginId, source)
                val engine = built.engine
                // The only platform call a screen without a window needs: the last route closed.
                MethodChannel(engine.dartExecutor.binaryMessenger, "flutter/platform", JSONMethodCodec.INSTANCE).setMethodCallHandler { call, result ->
                    if (call.method == "SystemNavigator.pop") {
                        onClose()
                        result.success(null)
                    } else {
                        result.notImplemented()
                    }
                }
                // In an isolated process the engine draws into droidtop's shared frames through a stand-in window
                // (ScreenBridge, docs/plugin-api.md 5.3 "ui.main in the sandbox"); elsewhere into the surface itself.
                val frames = target.frames
                if (frames != null && dev.droidtop.runtime.util.IsolatedProcess.isIsolated()) {
                    if (!ScreenBridge.attach(frames, target.framesCapacity, width, height, target.onFrame)) {
                        throw IllegalStateException("the screen's frames could not be mapped")
                    }
                    bridged = true
                    bridgeReport = bridgeReport ?: SandboxFiles.bridgeScreen("/libflutter.so")
                    android.util.Log.i("droidtop.plugin", "$pluginId: screen ${width}x$height through droidtop's frames: $bridgeReport")
                }
                engine.renderer.startRenderingToSurface(target.surface, false)
                engine.renderer.surfaceChanged(width, height)
                engine.renderer.setViewportMetrics(viewport(width, height, density))
                val entrypoint = if (entry.library == null) {
                    DartExecutor.DartEntrypoint(built.appBundlePath, entry.entrypoint)
                } else {
                    DartExecutor.DartEntrypoint(built.appBundlePath, entry.library, entry.entrypoint)
                }
                engine.dartExecutor.executeDartEntrypoint(entrypoint)
                engine.lifecycleChannel.appIsResumed()
                // Once the framework listens, resumed again (LifecycleChannel drops a repeat, so inactive first): the
                // resumed sent with the entrypoint arrived before the framework's binding and frames stayed disabled
                // (Dart on emulator-5560: framesEnabled=false after runApp, true after this).
                mainHandler.postDelayed({
                    if (screenEngine === engine) {
                        engine.lifecycleChannel.appIsInactive()
                        engine.lifecycleChannel.appIsResumed()
                    }
                }, 250)
                engine.renderer.addIsDisplayingFlutterUiListener(object : io.flutter.embedding.engine.renderer.FlutterUiDisplayListener {
                    override fun onFlutterUiDisplayed() {
                        android.util.Log.i("droidtop.plugin", "$pluginId: screen drew its first frame")
                    }
                    override fun onFlutterUiNoLongerDisplayed() = Unit
                })
                screenEngine = engine
                screenTouch = AndroidTouchProcessor(engine.renderer, false)
                screenKeys = KeyEventChannel(engine.dartExecutor.binaryMessenger)
            }
            null
        } catch (t: Throwable) {
            t.message ?: t::class.java.simpleName
        }

    private var screenDensity = 1f

    private fun viewport(width: Int, height: Int, density: Float): FlutterRenderer.ViewportMetrics {
        screenDensity = density
        return FlutterRenderer.ViewportMetrics().also {
            it.width = width
            it.height = height
            // A fixed-size view, as FlutterView sends it: min and max equal to the size. Left at their default 0 the
            // framework laid the screen out at zero size and every frame was empty (emulator-5560: black in both tiers,
            // while Dart reported its first frame built).
            it.minWidth = width
            it.maxWidth = width
            it.minHeight = height
            it.maxHeight = height
            it.devicePixelRatio = density
        }
    }

    override fun resizeScreen(width: Int, height: Int) {
        mainHandler.post {
            val engine = screenEngine ?: return@post
            if (bridged) ScreenBridge.resize(width, height)
            engine.renderer.surfaceChanged(width, height)
            engine.renderer.setViewportMetrics(viewport(width, height, screenDensity))
        }
    }

    override fun screenTouch(event: android.view.MotionEvent) {
        mainHandler.post {
            screenTouch?.onTouchEvent(event)
            event.recycle()
        }
    }

    /** Keys, the pad's included, as Flutter's key events; Back pops the plugin's route (the last one closes the screen). */
    override fun screenKey(event: android.view.KeyEvent) {
        mainHandler.post {
            val engine = screenEngine ?: return@post
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) engine.navigationChannel.popRoute()
                return@post
            }
            screenKeys?.sendFlutterKeyEvent(KeyEventChannel.FlutterKeyEvent(event, null), event.action == android.view.KeyEvent.ACTION_UP) { }
        }
    }

    override fun detachScreen() {
        runCatching { runOnMainThreadBlocking { detachScreenOnMain() } }
    }

    private fun detachScreenOnMain() {
        val engine = screenEngine ?: return
        screenEngine = null
        screenTouch = null
        screenKeys = null
        runCatching { engine.renderer.stopRenderingToSurface() }
        runCatching { engine.destroy() }
        if (bridged) {
            bridged = false
            ScreenBridge.detach()
        }
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

    companion object {
        fun forInstall(context: Context, pluginId: String, installDir: File): Result<FlutterDroidtopPlugin> {
            if (FlutterRuntimeManager.libflutterSoPath(context) == null) {
                return Result.failure(IllegalStateException("Flutter runtime not installed -- download it in Settings > Plugins first"))
            }
            val contract = runCatching {
                PluginManifest.fromJson(JSONObject(File(installDir, "manifest.json").readText()))?.contractVersion ?: 1
            }.getOrDefault(1)
            return runCatching { FlutterDroidtopPlugin(pluginId, context, FlutterSource.installed(context, pluginId, installDir), contract) }
        }
    }
}

/** Parsed, broker-ready fields for the JSON text a Flutter hostCall or a Python droidtop.host.call hands over. */
internal data class HostCallRequest(val api: String, val version: Int, val op: String, val argsJson: String) {
    companion object {
        fun parse(arguments: Any?): HostCallRequest {
            require(arguments is String) { "hostCall argument must be a JSON string" }
            val json = JSONObject(arguments)
            val api = (json.opt("api") as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("api must be a non-empty string")
            val version = json.opt("version")
            require(version is Int && version > 0) { "version must be a positive integer" }
            val op = (json.opt("op") as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("op must be a non-empty string")
            val args = json.optJSONObject("args")
                ?: throw IllegalArgumentException("args must be a JSON object")
            return HostCallRequest(api, version, op, args.toString())
        }
    }
}
