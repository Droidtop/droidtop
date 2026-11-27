package dev.droidtop.pluginhost

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Android transfers input, IME and accessibility with the remote view's SurfacePackage. */
@TargetApi(30)
class BrowserSurfaceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Session? = null
    private var provider: IWebViewSurfaceProvider? = null
    private var boundPluginId: String? = null
    private var death: IBinder.DeathRecipient? = null
    private var opened = false
    private var bodyCapable = false
    private var receivedSurface = false
    private var canGoBack = false
    private val events = kotlinx.coroutines.channels.Channel<WebSurfaceEvents.Event>(32)
    private val eventOverflow = java.util.concurrent.atomic.AtomicBoolean()
    private lateinit var surfaceView: SurfaceView
    private lateinit var location: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val token = intent.getStringExtra(WebViewProviders.EXTRA_TOKEN) ?: return finish()
        val asked = sessions[token] ?: return finish()
        if (asked.activity != null) return finish()
        asked.activity = this
        session = asked
        location = TextView(this).apply { text = asked.label; setPadding(16, 12, 16, 12) }
        surfaceView = SurfaceView(this).apply { setZOrderOnTop(true) }
        val close = Button(this).apply { text = "Close"; setOnClickListener { end() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(location)
            addView(surfaceView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(close)
        })
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { openSurface() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!opened) openSurface()
                else provider?.let { p -> scope.launch {
                    try { remote { p.resize(asked.token, width, height, resources.displayMetrics.densityDpi) } }
                    catch (_: Exception) { failed() }
                } }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
        scope.launch {
            for (event in events) {
                when (event.kind) {
                    "navigation" -> {
                        location.text = event.url
                        canGoBack = event.back
                        event.url?.let { WebViewProviders.surfacePage(asked.token, it) }
                    }
                    "loading" -> location.contentDescription = if (event.loading) "Loading ${location.text}" else location.text
                    "error" -> location.text = event.message?.takeIf { it.isNotBlank() } ?: "The page could not load"
                }
            }
        }
        scope.launch {
            try {
                val record = withContext(Dispatchers.IO) { WebViewProviders.provider(this@BrowserSurfaceActivity) }
                    ?.takeIf { it.manifest.id == asked.pluginId } ?: error("The browser permission changed")
                val pkg = record.manifest.app?.packageName ?: error("The browser is unavailable")
                asked.uid = withContext(Dispatchers.IO) { packageManager.getApplicationInfo(pkg, 0).uid }
                boundPluginId = asked.pluginId
                val runtime = withTimeout(5_000) { AppInterfaceConnections.acquire(this@BrowserSurfaceActivity, record) }
                    ?: error("The browser is unavailable")
                remote {
                    val manifest = java.io.File(PluginStore.payloadDirFor(this@BrowserSurfaceActivity, record), "manifest.json").readText()
                    check(runtime.loadContained(asked.pluginId, manifest, emptyArray(), emptyArray(), PluginBrokers.binderFor(this@BrowserSurfaceActivity, asked.pluginId))) { "The browser could not start" }
                    runtime.attachRelay(asked.pluginId, RELAY, host.asBinder())
                }
                delay(3_000)
                if (provider == null) remote { runtime.attachRelay(asked.pluginId, LEGACY_RELAY, host.asBinder()) }
                delay(10_000)
                if (!receivedSurface) failed()
                while (isActive && !isFinishing) {
                    delay(5_000)
                    val valid = withContext(Dispatchers.IO) { WebViewProviders.provider(this@BrowserSurfaceActivity)?.manifest?.id == asked.pluginId }
                    if (!valid) { failed(); break }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed() }
        }
    }

    private fun caller(): Boolean = session?.uid?.let { it >= 0 && Binder.getCallingUid() == it } == true
    private fun connect(remoteProvider: IWebViewSurfaceProvider, bodies: Boolean, isolatedBrowse: Boolean = false) {
        scope.launch {
            if (provider != null || isFinishing) return@launch
            if (session?.browse == true && !isolatedBrowse) { failed(); return@launch }
            bodyCapable = bodies
            provider = remoteProvider
            val recipient = IBinder.DeathRecipient { scope.launch { failed() } }.also { death = it }
            try { remoteProvider.asBinder().linkToDeath(recipient, 0); openSurface() }
            catch (_: Exception) { failed() }
        }
    }
    private val host = object : IWebViewSurfaceHost.Stub() {
        override fun connected(remoteProvider: IWebViewSurfaceProvider?) {
            if (!caller() || remoteProvider == null) return
            connect(remoteProvider, false)
        }
        override fun connectedCapabilities(remoteProvider: IWebViewSurfaceProvider?, capabilities: Array<out String>?) {
            if (!caller() || remoteProvider == null || capabilities == null || capabilities.size > 16 || capabilities.any { it.length > 128 }) return
            connect(remoteProvider, "web.download.body.v1" in capabilities, "web.browse.isolated.v1" in capabilities)
        }
        override fun surfaceReady(token: String?) {
            if (!caller() || token != session?.token) return
            scope.launch {
                if (receivedSurface || isFinishing) return@launch
                receivedSurface = true
                try {
                    val p = provider ?: error("The browser disconnected")
                    val surface = remote { p.surface(token) } ?: error("The browser returned no view")
                    // setChildSurfacePackage takes ownership (Android SurfaceView API contract).
                    if (isFinishing) surface.release() else surfaceView.setChildSurfacePackage(surface)
                } catch (_: Exception) { failed() }
            }
        }
        override fun unavailable(token: String?, reason: String?) {
            if (caller() && token == session?.token) scope.launch { failed() }
        }
        override fun event(token: String?, eventJson: String?) {
            if (!caller() || token != session?.token || eventJson == null) return
            val event = WebSurfaceEvents.parse(eventJson) ?: return
            if (events.trySend(event).isFailure && eventOverflow.compareAndSet(false, true)) scope.launch { failed() }
        }
        override fun download(token: String?, offerId: String?, metadataJson: String?) {
            if (!caller() || token != session?.token || offerId == null || offerId.length !in 1..128 || metadataJson == null) return
            val metadata = BrowserBodyMetadata.parse(metadataJson)
            scope.launch {
                val p = provider ?: return@launch
                val accepted = bodyCapable && metadata != null && withContext(Dispatchers.IO) { WebViewProviders.bodyOffer(this@BrowserSurfaceActivity, token!!, offerId, p, metadata) }
                try { remote { p.offerResult(token, offerId, accepted) } }
                catch (_: Exception) { failed() }
            }
        }
    }

    private fun openSurface() {
        val asked = session ?: return
        val p = provider ?: return
        val hostToken = surfaceView.hostToken ?: return
        if (opened || !surfaceView.holder.surface.isValid || surfaceView.width <= 0 || surfaceView.height <= 0) return
        opened = true
        scope.launch {
            try { remote { p.open(asked.token, hostToken, display?.displayId ?: 0, surfaceView.width, surfaceView.height, resources.displayMetrics.densityDpi) } }
            catch (_: Exception) { failed() }
        }
    }

    override fun onBackPressed() {
        val p = provider
        val asked = session
        if (canGoBack && p != null && asked != null) scope.launch { try { remote { p.back(asked.token) } } catch (_: Exception) { failed() } }
        else end()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= 33) surfaceView.childSurfacePackage?.let { surface ->
            scope.launch { try { remote { surface.notifyConfigurationChanged(Configuration(newConfig)) } } catch (_: Exception) { failed() } }
        }
        session?.let { asked -> provider?.let { p -> scope.launch {
            try { remote { p.resize(asked.token, surfaceView.width, surfaceView.height, newConfig.densityDpi) } }
            catch (_: Exception) { failed() }
        } } }
    }

    private fun end() { session?.let { asked -> provider?.let { p -> runCatching { executor.execute { runCatching { p.close(asked.token) } } } }; WebViewProviders.cancel(asked.token) }; finish() }
    private fun failed() {
        val asked = session ?: return finish()
        if (WebViewProviders.withdrawUntaken(asked.token)) asked.fallback()
        else WebViewProviders.cancel(asked.token)
        finish()
    }

    override fun onDestroy() {
        session?.let { asked ->
            if (asked.activity === this) {
                asked.activity = null
                sessions.remove(asked.token, asked)
                WebViewProviders.cancel(asked.token)
            }
            provider?.let { p ->
                death?.let { runCatching { p.asBinder().unlinkToDeath(it, 0) } }
                runCatching { executor.execute { runCatching { p.close(asked.token) } } }
            }
        }
        boundPluginId?.let { AppInterfaceConnections.release(this, it) }
        events.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val RELAY = "web.view.surface.v2"
        const val LEGACY_RELAY = "web.view.surface.v1"
        private class Session(val token: String, val pluginId: String, val label: String, val browse: Boolean, val fallback: () -> Unit) {
            var activity: BrowserSurfaceActivity? = null
            @Volatile var uid = -1
        }
        private val sessions = ConcurrentHashMap<String, Session>()
        private val executor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), { task -> Thread(task, "browser-surface").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        // A timed-out Binder call cannot stall main or hold a coroutine waiting indefinitely.
        private suspend fun <T> remote(block: () -> T): T {
            val result = CompletableDeferred<T>()
            val unclaimed = java.util.concurrent.atomic.AtomicReference<android.view.SurfaceControlViewHost.SurfacePackage?>()
            executor.execute {
                try {
                    val value = block()
                    if (value is android.view.SurfaceControlViewHost.SurfacePackage) unclaimed.set(value)
                    if (!result.complete(value)) unclaimed.getAndSet(null)?.release()
                } catch (error: Exception) { result.completeExceptionally(error) }
            }
            return try {
                withTimeout(5_000) { result.await() }.also { unclaimed.set(null) }
            } finally {
                result.cancel()
                unclaimed.getAndSet(null)?.release()
            }
        }
        fun open(context: Context, record: PluginRecord, token: String, label: String, browse: Boolean, fallback: () -> Unit): Boolean {
            sessions[token] = Session(token, record.manifest.id, label, browse, fallback)
            return runCatching {
                context.startActivity(Intent(context, BrowserSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(WebViewProviders.EXTRA_TOKEN, token))
                true
            }.getOrElse { sessions.remove(token); false }
        }
        fun completed(token: String) { sessions.remove(token)?.activity?.finish() }
    }
}
