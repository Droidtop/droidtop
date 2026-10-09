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
 * process, holds a [SurfaceView] and hands its surface to the plugin's process, where the plugin's engine draws into it
 * in software; touch and keys, the pad's included, are passed to the plugin as they arrive. Back pops the plugin's
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: return finish()
        entrypoint = intent.getStringExtra(EXTRA_ENTRYPOINT) ?: return finish()
        library = intent.getStringExtra(EXTRA_LIBRARY)
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
        scope.launch {
            val why = withContext(Dispatchers.IO) {
                runCatching { process.attachScreen(pluginId, entrypoint, library, surface, width, height, density) }.getOrElse { it.message ?: "the plugin's process did not answer" }
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

    override fun onDestroy() {
        open.remove(pluginId, this)
        if (attached) {
            attached = false
            runCatching { runtime?.detachScreen(pluginId) }
        }
        scope.cancel()
        policy?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PLUGIN_ID = "dev.droidtop.pluginhost.screen.PLUGIN_ID"
        private const val EXTRA_ENTRYPOINT = "dev.droidtop.pluginhost.screen.ENTRYPOINT"
        private const val EXTRA_LIBRARY = "dev.droidtop.pluginhost.screen.LIBRARY"

        private val open = ConcurrentHashMap<String, PluginScreenActivity>()

        fun intentFor(context: Context, pluginId: String, entry: PluginMainUi.Entry): Intent =
            Intent(context, PluginScreenActivity::class.java)
                .putExtra(EXTRA_PLUGIN_ID, pluginId)
                .putExtra(EXTRA_ENTRYPOINT, entry.entrypoint)
                .putExtra(EXTRA_LIBRARY, entry.library)

        /** The plugin closed its last route: its screen closes. Called on a binder thread. */
        fun closed(pluginId: String) {
            open[pluginId]?.let { activity -> activity.runOnUiThread { activity.finish() } }
        }
    }
}
