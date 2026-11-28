package dev.droidtop.pluginhost

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject

/** The optional rendered-GET capability. A taken request never falls through to a different network stack. */
internal object BrowserDocuments {
    // Joint host/provider review and exact-head gates must precede enabling this new route.
    private const val ENABLED = false
    private const val RELAY = "web.hidden.v1"
    private const val CAPABILITY = "web.document.get.v1"
    private const val ISOLATION = "web.runtime.isolated.v1"
    private val active = AtomicInteger()
    private fun pool(name: String, threads: Int) = ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), { task -> Thread(task, name).apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val rpc = pool("browser-document", 2)
    private val guards = pool("browser-document-policy", 4)

    /** Null means unavailable before acceptance only; errors after acceptance are explicit broker failures. */
    fun fetch(context: Context, environment: BrokerEnvironment, requestRecord: PluginRecord, url: String, headers: Map<String, String>, timeoutMs: Long, maxBytes: Int, authorize: (String, String) -> Unit): WebFetch.Result? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Browser document requests run off the main thread" }
        if (!ENABLED || Build.VERSION.SDK_INT < 30) return null
        val providerRecord = WebViewProviders.provider(context) ?: return null
        require(WebSurfaceEvents.safeUrl(url) && maxBytes in 1..HostNetApis.MAX_BODY)
        require(timeoutMs in 1..HostNetApis.MAX_TIMEOUT_MS)
        // A rendered navigation is not a generic HTTP request. Do not silently lose authentication headers.
        if (headers.keys.any { !it.equals("cookie", true) }) {
            throw BrokerException(PluginErrorCode.UNSUPPORTED, "This browser document route supports site cookies, not custom request headers")
        }
        if (active.incrementAndGet() > 4) { active.decrementAndGet(); throw BrokerException(PluginErrorCode.TIMEOUT, "Four browser document requests are already running; try again shortly") }
        val app = context.applicationContext
        val id = providerRecord.manifest.id
        var operation: Operation? = null
        var lease: AppInterfaceConnections.Lease? = null
        try {
            val uid = app.packageManager.getApplicationInfo(providerRecord.manifest.app!!.packageName, 0).uid
            val cookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value
            require(cookie == null || (cookie.length <= 16384 && cookie.none { it.isISOControl() }))
            val op = Operation(app, environment, requestRecord, providerRecord, uid, url, cookie, timeoutMs, maxBytes, authorize).also { operation = it }
            return runBlocking {
                try {
                    withTimeout(timeoutMs) {
                        val held = AppInterfaceConnections.acquireLease(app, providerRecord) ?: return@withTimeout null
                        lease = held
                        val runtime = held.runtime
                        call {
                            val manifest = java.io.File(PluginStore.payloadDirFor(app, providerRecord), "manifest.json").readText()
                            check(runtime.loadContained(id, manifest, emptyArray(), emptyArray(), PluginBrokers.binderFor(app, id))) { "The browser could not load its document capability" }
                            runtime.attachRelay(id, RELAY, op.host.asBinder())
                        }
                        val provider = withTimeoutOrNull(minOf(5_000L, op.left())) { op.connected.await() } ?: return@withTimeout null
                        op.provider = provider
                        val death = IBinder.DeathRecipient { op.fail("The browser disconnected") }.also { op.death = it }
                        provider.asBinder().linkToDeath(death, 0)
                        call { provider.start(op.token) }
                        val metadata = op.result.await()
                        op.denied.get()?.let { throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it) }
                        check(op.allowed(metadata.finalUrl, "document")) { "The browser document is no longer allowed" }
                        when (metadata.kind) {
                            "permission_denied" -> throw BrokerException(PluginErrorCode.PERMISSION_DENIED, metadata.reason ?: "The browser page needs a network permission this plugin does not have")
                            "failed" -> throw BrokerException(PluginErrorCode.FAILED, metadata.reason ?: "The browser could not read the document")
                            "challenged" -> WebFetch.Result(null, true, metadata.cookies, metadata.userAgent)
                            else -> {
                                val descriptor = call { provider.body(op.token) } ?: error("The browser returned no document body")
                                op.descriptor.set(descriptor)
                                val bytes = read(op, metadata)
                                val type = metadata.contentType.substringBefore(';').trim()
                                WebFetch.Result(HttpAnswer(metadata.status ?: error("The document HTTP status is unknown"), metadata.finalUrl, mapOf("content-type" to "$type; charset=utf-8"), bytes, false), false, metadata.cookies, metadata.userAgent)
                            }
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    op.denied.get()?.let { throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it) }
                    if (op.withdrawUntaken()) null else throw BrokerException(PluginErrorCode.TIMEOUT, "The browser document request timed out; it was not retried in another engine")
                } catch (error: BrokerException) { throw error }
                catch (error: Exception) {
                    op.denied.get()?.let { throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it) }
                    if (op.withdrawUntaken()) null else throw BrokerException(PluginErrorCode.FAILED, "The browser document request failed; reopen its browser check and try again")
                }
            }
        } finally {
            operation?.close()
            lease?.close()
            active.decrementAndGet()
        }
    }

    private class Operation(val app: Context, val environment: BrokerEnvironment, val requestRecord: PluginRecord, val record: PluginRecord, val uid: Int, val url: String, var cookie: String?, timeoutMs: Long, val maxBytes: Int, var guard: ((String, String) -> Unit)?) {
        val token = UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val connected = CompletableDeferred<IWebHiddenProvider?>()
        val result = CompletableDeferred<BrowserDocumentMetadata>()
        val denied = AtomicReference<String?>()
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        @Volatile var taken = false
        @Volatile var closed = false
        @Volatile var provider: IWebHiddenProvider? = null
        var death: IBinder.DeathRecipient? = null
        private val checks = AtomicInteger()
        private val authorizedAuthorities = ConcurrentHashMap.newKeySet<String>()
        private fun authority(target: String): String = URI(target).let { "${it.host.lowercase()}:${if (it.port < 0) 443 else it.port}" }
        private val gateway = BrowserConnectGateway.open(timeoutMs,
            beforeResolve = { target ->
                check(live() && providerAllowed())
                check(authority(target) in authorizedAuthorities) { "No channel authorization for this browser destination" }
                HostNetApis.reachName(environment, requestRecord, target)
            },
            authorize = { target, addresses ->
                check(live() && providerAllowed())
                check(authority(target) in authorizedAuthorities) { "No channel authorization for this browser destination" }
                HostNetApis.reachResolved(environment, requestRecord, target, addresses)
            },
            resolve = environment::addressesOf,
            failed = { reason ->
                val message = when (reason) {
                    BrowserConnectGateway.Failure.PERMISSION -> "The browser destination is outside this plugin's network permissions"
                    BrowserConnectGateway.Failure.LIMIT -> "The browser document exceeded its connection or byte budget"
                    BrowserConnectGateway.Failure.DEADLINE -> "The browser document deadline expired"
                }
                if (reason == BrowserConnectGateway.Failure.PERMISSION) denied.compareAndSet(null, message)
                result.completeExceptionally(BrokerException(
                    if (reason == BrowserConnectGateway.Failure.DEADLINE) PluginErrorCode.TIMEOUT else if (reason == BrowserConnectGateway.Failure.PERMISSION) PluginErrorCode.PERMISSION_DENIED else PluginErrorCode.FAILED,
                    message,
                ))
                runCatching { descriptor.getAndSet(null)?.close() }
            },
        )
        fun left() = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        private fun caller() = Binder.getCallingUid() == uid
        private fun live() = !closed && SystemClock.elapsedRealtime() < deadline

        private fun providerAllowed(): Boolean {
            val requestor = PluginBundleInstaller.readRecord(PluginStore.root(app), requestRecord.manifest.id) ?: return false
            if (!requestor.runnable() || requestor.archiveDigest != requestRecord.archiveDigest) return false
            val current = PluginBundleInstaller.readRecord(PluginStore.root(app), record.manifest.id) ?: return false
            if (!current.runnable() || current.archiveDigest != record.archiveDigest || current.approvedKeySha256 != record.approvedKeySha256) return false
            val grants = PluginGrants.forContext(app).read(current.manifest.id)
            return PluginGrants.pointRefusal(current, grants, WebViewProviders.POINT) == null && AppInterfaces.verifyRecord(app, current) == null
        }

        fun allowed(target: String, kind: String): Boolean {
            if (!live() || denied.get() != null) return false
            if (!WebSurfaceEvents.safeUrl(target) || kind !in setOf("document", "frame", "resource") || checks.incrementAndGet() > 1024) {
                denied.compareAndSet(null, "The browser document exceeded its allowed network scope or request limit")
                return false
            }
            val task = try { guards.submit(Callable {
                check(providerAllowed()) { "The browser permission changed" }
                gateway.revalidate()
                guard?.invoke(target, kind) ?: error("The document request ended")
                true
            }) } catch (_: Exception) { denied.compareAndSet(null, "The browser network policy could not be checked in time"); return false }
            return try {
                val accepted = task.get(minOf(5_000L, left()), TimeUnit.MILLISECONDS) && live()
                if (accepted) authorizedAuthorities += authority(target)
                accepted
            }
            catch (error: Exception) {
                val broker = error.cause as? BrokerException
                denied.compareAndSet(null, broker?.message?.take(512) ?: "The browser page is outside this plugin's allowed network sites")
                task.cancel(true)
                false
            }
        }

        val host = object : IWebHiddenHost.Stub() {
            override fun connected(remote: IWebHiddenProvider?, capabilities: Array<out String>?) {
                if (!caller() || !live() || remote == null || capabilities == null || capabilities.size > 16 || capabilities.any { it.length > 128 }) return
                connected.complete(remote.takeIf { CAPABILITY in capabilities && ISOLATION in capabilities })
            }
            override fun takeRequest(given: String?): String {
                check(caller() && given == token && live()) { "No such browser document request" }
                synchronized(this@Operation) {
                    check(!taken && live() && allowed(url, "document")) { "The browser document request is unavailable" }
                    check(live()) { "The browser document request ended" }
                    taken = true
                    val host = NetScope.hostOf(url)!!
                    val jar = JSONObject().apply { cookie?.let { put(host, it) } }
                    cookie = null
                    val proxy = JSONObject().put("host", gateway.endpoint.host).put("port", gateway.endpoint.port)
                        .put("authorization", gateway.endpoint.authorization).put("isolationKey", token)
                    return JSONObject().put("op", "document").put("url", url).put("cookies", jar).put("maxBytes", maxBytes).put("timeoutMs", left()).put("proxy", proxy).toString()
                }
            }
            override fun authorize(given: String?, target: String?, kind: String?): Boolean =
                caller() && given == token && taken && target != null && kind != null && allowed(target, kind)
            override fun result(given: String?, metadataJson: String?) {
                if (!caller() || given != token || !taken || !live() || metadataJson == null) return
                try { result.complete(BrowserDocumentMetadata.parse(metadataJson, NetScope.hostOf(url)!!, maxBytes, url)) }
                catch (_: Exception) { fail("The browser returned an invalid document result") }
            }
            override fun unavailable(given: String?, reason: String?) {
                if (!caller() || given != token || !live()) return
                if (!taken) connected.complete(null) else fail("The browser document became unavailable")
            }
        }

        fun fail(reason: String) { result.completeExceptionally(IllegalStateException(reason)); runCatching { descriptor.getAndSet(null)?.close() } }
        fun withdrawUntaken(): Boolean = synchronized(this) { if (taken) false else { closed = true; true } }
        fun close() {
            synchronized(this) { closed = true; guard = null; cookie = null }
            gateway.close()
            authorizedAuthorities.clear()
            runCatching { descriptor.getAndSet(null)?.close() }
            provider?.let { p ->
                death?.let { runCatching { p.asBinder().unlinkToDeath(it, 0) } }
                runCatching { rpc.execute { runCatching { p.cancel(token) } } }
            }
            connected.cancel(); result.cancel()
        }
    }

    private suspend fun read(op: Operation, metadata: BrowserDocumentMetadata): ByteArray = withContext(Dispatchers.IO) {
        val fd = op.descriptor.get() ?: error("The browser document was closed")
        val poll = StructPollfd().apply { this.fd = fd.fileDescriptor; events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort() }
        val bytes = ByteArray(16 * 1024)
        val output = ByteArrayOutputStream(metadata.bodyBytes)
        var nextPermissionCheck = 0L
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            while (true) {
                ensureActive()
                check(!op.closed && SystemClock.elapsedRealtime() < op.deadline) { "The browser document deadline expired" }
                if (SystemClock.elapsedRealtime() >= nextPermissionCheck) {
                    if (!op.allowed(metadata.finalUrl, "document")) throw BrokerException(PluginErrorCode.PERMISSION_DENIED, op.denied.get() ?: "The document permission changed during transfer")
                    nextPermissionCheck = SystemClock.elapsedRealtime() + 1_000
                }
                if (Os.poll(arrayOf(poll), 250) == 0) continue
                val count = input.read(bytes)
                if (count < 0) { fd.checkError(); break }
                check(count <= metadata.bodyBytes - output.size()) { "The browser returned more document bytes than declared" }
                output.write(bytes, 0, count)
            }
        }
        check(output.size() == metadata.bodyBytes) { "The browser document ended before all bytes arrived" }
        op.denied.get()?.let { throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it) }
        if (!op.allowed(metadata.finalUrl, "document")) {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, op.denied.get() ?: "The document permission changed before completion")
        }
        output.toByteArray()
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
