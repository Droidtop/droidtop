package dev.droidtop.pluginhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A contained plugin's own full-screen UI (`ui.main`, docs/plugin-api.md 1.7, 5.3). The plugin runs in an isolated
 * process, which has no window, may not reach the window manager (sepolicy: an isolated app finds only the activity
 * and display services) and may not open the GPU. So droidtop owns the screen: this activity, in droidtop's own
 * process holds a [SurfaceView]. An isolated process cannot present into a Surface at all (it may not use the graphics
 * allocator's buffer fds), so for a contained plugin this activity also allocates two frames of shared memory, the
 * plugin's engine draws each frame into them in software ([ScreenBridge]), and this activity paints every finished
 * frame into its surface on a render thread of its own. A `gpu.render` plugin draws into the surface directly. Touch and
 * keys, the pad's included, are passed to the plugin as they arrive. Back pops the plugin's
 * route, and when the plugin closes its last route the screen closes.
 *
 * A full-access plugin keeps [PluginMainActivity] in its own process. Not exported: only [PluginMainUi.open] starts it,
 * after the approval, the per-item grant and the payload's signature were checked.
 */
class PluginScreenActivity : Activity(), SurfaceHolder.Callback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var policy: PluginCrashPolicy? = null

    @Volatile private var runtime: IPluginRuntime? = null
    private var pluginId = ""
    private var entrypoint = ""
    private var library: String? = null
    private lateinit var view: SurfaceView
    private var surfaceReady = false
    private var attached = false
    private var width = 0
    private var height = 0

    /** Whether the plugin draws into shared frames this activity paints ([ScreenBridge]): a contained plugin's screen. */
    private var bridged = false
    @Volatile private var frames: java.nio.ByteBuffer? = null
    private val painter = android.os.HandlerThread("plugin-screen").apply { start() }
    private val painterHandler = android.os.Handler(painter.looper)
    private var bitmap: android.graphics.Bitmap? = null

    /** The newest frame waiting to be painted, as (index shl 32) or-ed with nothing else pending; -1 when none. */
    private val pending = java.util.concurrent.atomic.AtomicLong(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: return finish()
        entrypoint = intent.getStringExtra(EXTRA_ENTRYPOINT) ?: return finish()
        library = intent.getStringExtra(EXTRA_LIBRARY)
        bridged = intent.getBooleanExtra(EXTRA_BRIDGED, false)
        view = SurfaceView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(view)
        view.holder.addCallback(this)
        view.requestFocus()
        open[pluginId] = this
        scope.launch {
            val record = withContext(Dispatchers.IO) { PluginStore.installed(applicationContext).firstOrNull { it.manifest.id == pluginId } }
            if (record == null) return@launch close("This plugin is no longer installed")
            val crashPolicy = PluginCrashPolicy(applicationContext)
            policy = crashPolicy
            val (process, why) = crashPolicy.screenRuntime(record)
            if (process == null) return@launch close(why ?: "${record.manifest.label} did not start")
            runtime = process
            attachIfReady()
        }
    }

    private fun close(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun attachIfReady() {
        val process = runtime ?: return
        if (!surfaceReady || attached) return
        attached = true
        val surface = view.holder.surface
        val density = resources.displayMetrics.density
        // Room for the largest this screen can get, so a resize or a turn never needs new frames: the display's full size.
        val display = resources.displayMetrics
        val capacity = ScreenBridge.capacityFor(maxOf(width, display.widthPixels, display.heightPixels), maxOf(height, display.widthPixels, display.heightPixels))
        scope.launch {
            val why = withContext(Dispatchers.IO) {
                val shared = if (bridged) ScreenBridge.create(capacity) else null
                if (bridged && shared == null) return@withContext "Could not set up this plugin's screen"
                frames = shared?.second
                try {
                    runCatching {
                        process.attachScreen(pluginId, entrypoint, library, surface, shared?.first, if (shared != null) capacity else 0L, width, height, density)
                    }.getOrElse { it.message ?: "the plugin's process did not answer" }
                } finally {
                    // The plugin's process holds its own descriptor now; the mapping here stays until the screen closes.
                    shared?.first?.close()
                }
            }
            if (why != null) close(why)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        this.width = width
        this.height = height
        surfaceReady = true
        val process = runtime
        if (attached && process != null) {
            scope.launch(Dispatchers.IO) { runCatching { process.resizeScreen(pluginId, width, height) } }
        } else {
            attachIfReady()
        }
    }

    // Synchronous on purpose: the plugin must stop drawing before this surface goes away.
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (attached) {
            attached = false
            runCatching { runtime?.detachScreen(pluginId) }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val process = runtime
        if (attached && process != null) {
            val copy = MotionEvent.obtain(event)
            try {
                runCatching { process.screenTouch(pluginId, copy) }
            } finally {
                copy.recycle()
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val process = runtime
        if (attached && process != null) {
            runCatching { process.screenKey(pluginId, event) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Paints frame [index] of the shared frames into the surface, on the render thread. Frames that arrive while one is
     * being painted replace each other, so a slow paint drops frames instead of queueing them.
     */
    private fun paint(index: Int, frameWidth: Int, frameHeight: Int) {
        if (pending.getAndSet((index.toLong() shl 40) or (frameWidth.toLong() shl 20) or frameHeight.toLong()) != -1L) return
        painterHandler.post {
            val packed = pending.getAndSet(-1)
            if (packed == -1L) return@post
            val i = (packed ushr 40).toInt()
            val w = ((packed ushr 20) and 0xFFFFF).toInt()
            val h = (packed and 0xFFFFF).toInt()
            val source = frames ?: return@post
            if (!surfaceReady || w <= 0 || h <= 0) return@post
            val bytes = w.toLong() * h * 4
            if ((i + 1) * bytes > source.capacity()) return@post
            val image = bitmap?.takeIf { it.width == w && it.height == h }
                ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap = it }
            val slice = source.duplicate()
            slice.position((i * bytes).toInt())
            slice.limit(((i + 1) * bytes).toInt())
            image.copyPixelsFromBuffer(slice)
            val canvas = runCatching { view.holder.lockHardwareCanvas() }.getOrNull() ?: return@post
            try {
                canvas.drawBitmap(image, 0f, 0f, null)
            } finally {
                runCatching { view.holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    override fun onDestroy() {
        open.remove(pluginId, this)
        if (attached) {
            attached = false
            runCatching { runtime?.detachScreen(pluginId) }
        }
        painter.quitSafely()
        frames?.let { ScreenBridge.unmap(it) }
        frames = null
        scope.cancel()
        policy?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PLUGIN_ID = "dev.droidtop.pluginhost.screen.PLUGIN_ID"
        private const val EXTRA_ENTRYPOINT = "dev.droidtop.pluginhost.screen.ENTRYPOINT"
        private const val EXTRA_LIBRARY = "dev.droidtop.pluginhost.screen.LIBRARY"
        private const val EXTRA_BRIDGED = "dev.droidtop.pluginhost.screen.BRIDGED"

        private val open = ConcurrentHashMap<String, PluginScreenActivity>()

        /** [bridged]: the plugin runs isolated, so its frames come through [ScreenBridge] (a contained plugin). */
        fun intentFor(context: Context, pluginId: String, entry: PluginMainUi.Entry, bridged: Boolean): Intent =
            Intent(context, PluginScreenActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, pluginId)
                .putExtra(EXTRA_ENTRYPOINT, entry.entrypoint)
                .putExtra(EXTRA_LIBRARY, entry.library)
                .putExtra(EXTRA_BRIDGED, bridged)

        /** The plugin finished a frame in the shared frames: it is painted. Called on a binder thread. */
        fun frame(pluginId: String, index: Int, width: Int, height: Int) {
            open[pluginId]?.paint(index, width, height)
        }

        /** The plugin closed its last route: its screen closes. Called on a binder thread. */
        fun closed(pluginId: String) {
            open[pluginId]?.let { activity -> activity.runOnUiThread { activity.finish() } }
        }
    }
}
