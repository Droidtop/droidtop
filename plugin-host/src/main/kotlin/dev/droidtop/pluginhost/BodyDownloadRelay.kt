package dev.droidtop.pluginhost

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** API 26+ body capability for a browser-owned Activity, independent of embedded SurfacePackage interfaces. */
internal object BodyDownloadRelay {
    internal const val CAPABILITY = "web.download.body.v1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, Connection>()
    private val rpc = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), { task -> Thread(task, "browser-download-relay").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    fun prepare(context: Context, record: PluginRecord, token: String, ready: () -> Unit, unavailable: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val held = AtomicBoolean()
            val release = { if (held.compareAndSet(true, false)) AppInterfaceConnections.release(app, record.manifest.id); Unit }
            try {
                withTimeout(10_000) {
                    check(allowed(app, record))
                    held.set(true)
                    val runtime = AppInterfaceConnections.acquire(app, record) ?: error("The browser could not be reached")
                    if (!WebViewProviders.keepBodyProvider(token, release)) { release(); return@withTimeout }
                    val connection = synchronized(connections) {
                        connections[record.manifest.id]?.takeIf { it.runtime === runtime.asBinder() && it.record.archiveDigest == record.archiveDigest }
                            ?: Connection(app, record, runtime).also { fresh ->
                                connections[record.manifest.id] = fresh
                                scope.launch { fresh.connect() }
                            }
                    }
                    val transport = connection.ready.await() ?: error("The browser needs its response capability update")
                    check(allowed(app, record))
                    if (!WebViewProviders.bodyReady(token, transport)) return@withTimeout
                    withContext(Dispatchers.Main) { ready() }
                }
            } catch (_: Exception) {
                release()
                withContext(Dispatchers.Main) { unavailable() }
            }
        }
    }

    private fun allowed(app: Context, record: PluginRecord): Boolean {
        val current = PluginBundleInstaller.readRecord(PluginStore.root(app), record.manifest.id) ?: return false
        if (!current.runnable() || current.archiveDigest != record.archiveDigest || current.approvedKeySha256 != record.approvedKeySha256) return false
        return PluginGrants.pointRefusal(current, PluginGrants.forContext(app).read(current.manifest.id), WebViewProviders.POINT) == null && AppInterfaces.verifyRecord(app, current) == null
    }

    private class Connection(val app: Context, val record: PluginRecord, private val plugin: IPluginRuntime) {
        val runtime: IBinder = plugin.asBinder()
        val ready = CompletableDeferred<BrowserBodyTransport?>()
        private val uid = app.packageManager.getApplicationInfo(record.manifest.app!!.packageName, 0).uid
        @Volatile private var transport: BrowserBodyTransport? = null

        suspend fun connect() {
            try {
                call {
                    val manifest = java.io.File(PluginStore.payloadDirFor(app, record), "manifest.json").readText()
                    check(plugin.loadContained(record.manifest.id, manifest, emptyArray(), emptyArray(), PluginBrokers.binderFor(app, record.manifest.id)))
                    plugin.attachRelay(record.manifest.id, CAPABILITY, host.asBinder())
                }
                withTimeout(5_000) { ready.await() }
            } catch (_: Exception) {
                ready.complete(null)
                connections.remove(record.manifest.id, this)
            }
        }

        private val host = object : IWebDownloadHost.Stub() {
            override fun connected(provider: IWebDownloadProvider?, capabilities: Array<out String>?) {
                if (Binder.getCallingUid() != uid || provider == null || capabilities == null || capabilities.size > 16 || capabilities.any { it.length > 128 }) return
                if (CAPABILITY !in capabilities) { ready.complete(null); return }
                val endpoint = DownloadBodyTransport(provider)
                try {
                    endpoint.binder.linkToDeath({ connections.remove(record.manifest.id, this@Connection) }, 0)
                    transport = endpoint
                    ready.complete(endpoint)
                } catch (_: Exception) { ready.complete(null) }
            }
            override fun download(token: String?, offerId: String?, metadataJson: String?) {
                if (Binder.getCallingUid() != uid || token == null || offerId == null || offerId.length !in 1..128 || metadataJson == null) return
                val metadata = BrowserBodyMetadata.parse(metadataJson)
                val endpoint = transport ?: return
                scope.launch {
                    val accepted = metadata != null && allowed(app, record) && WebViewProviders.bodyOffer(app, token, offerId, endpoint, metadata)
                    runCatching { call { endpoint.acknowledge(token, offerId, accepted) } }
                }
            }
        }
    }

    private suspend fun <T> call(block: () -> T): T {
        val result = CompletableDeferred<T>()
        val unclaimed = AtomicReference<ParcelFileDescriptor?>()
        rpc.execute {
            try {
                val value = block()
                if (value is ParcelFileDescriptor) unclaimed.set(value)
                if (!result.complete(value)) unclaimed.getAndSet(null)?.close()
            } catch (error: Exception) { result.completeExceptionally(error) }
        }
        return try { withTimeout(5_000) { result.await() }.also { unclaimed.set(null) } }
        finally { result.cancel(); unclaimed.getAndSet(null)?.close() }
    }
}
