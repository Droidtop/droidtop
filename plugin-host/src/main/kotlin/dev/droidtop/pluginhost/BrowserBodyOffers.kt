package dev.droidtop.pluginhost

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** One-use bodies stay in memory; the persisted marker forbids ever refetching their provenance URL. */
internal object BrowserBodyOffers {
    const val TOKEN = "X-Droidtop-Browser-Body-Token"
    const val REQUIRED = "X-Droidtop-Browser-Body-Required"
    private class Offer(val context: Context, val pluginId: String, val token: String, val providerId: String, val provider: IWebViewSurfaceProvider, val metadata: BrowserBodyMetadata) {
        val claimed = AtomicBoolean()
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        lateinit var death: IBinder.DeathRecipient
    }
    private val offers = ConcurrentHashMap<String, Offer>()
    private val active = AtomicInteger()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val rpc = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64), { task -> Thread(task, "browser-body").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    @Synchronized
    fun offer(context: Context, pluginId: String, token: String, providerId: String, provider: IWebViewSurfaceProvider, metadata: BrowserBodyMetadata): WebSessionDownload? {
        if (providerId.length !in 1..128 || providerId.any { !it.isLetterOrDigit() && it !in "-_." }) return null
        if (offers.size >= 32 || offers.values.count { it.token == token } >= DownloadJobs.MAX_FILES) return null
        if (offers.values.any { it.token == token && it.providerId == providerId }) return null
        if (!AppInterfaceConnections.retain(pluginId)) return null
        val id = UUID.randomUUID().toString()
        val entry = Offer(context.applicationContext, pluginId, token, providerId, provider, metadata)
        entry.death = IBinder.DeathRecipient { revoke(id) }
        offers[id] = entry
        try { provider.asBinder().linkToDeath(entry.death, 0) }
        catch (_: Exception) { revoke(id); return null }
        main.postDelayed({ if (!entry.claimed.get()) revoke(id) }, 60_000)
        return WebSessionDownload.direct(metadata.sourceUrl, metadata.fileName, metadata.size.takeIf { it >= 0 }, mapOf(TOKEN to id, REQUIRED to "1")).copy(mimeType = metadata.mimeType)
    }

    fun revoke(headers: Map<String, String>) { headers[TOKEN]?.let(::revoke) }
    private fun revoke(id: String) {
        val entry = offers.remove(id) ?: return
        runCatching { entry.descriptor.getAndSet(null)?.close() }
        runCatching { entry.provider.asBinder().unlinkToDeath(entry.death, 0) }
        runCatching { rpc.execute { runCatching { entry.provider.cancelDownload(entry.token, entry.providerId) } } }
        AppInterfaceConnections.release(entry.context, entry.pluginId)
    }

    private suspend fun descriptor(entry: Offer): ParcelFileDescriptor {
        val answer = CompletableDeferred<ParcelFileDescriptor?>()
        val unclaimed = AtomicReference<ParcelFileDescriptor?>()
        rpc.execute {
            try {
                val fd = entry.provider.download(entry.token, entry.providerId)
                unclaimed.set(fd)
                if (!answer.complete(fd)) unclaimed.getAndSet(null)?.close()
            } catch (error: Exception) { answer.completeExceptionally(error) }
        }
        return try { (withTimeout(5_000) { answer.await() } ?: error("The browser no longer has this download")).also { unclaimed.set(null) } }
        finally { answer.cancel(); unclaimed.getAndSet(null)?.close() }
    }

    suspend fun transfer(headers: Map<String, String>, destination: File, maxBytes: Long, numbers: DownloadJobs.TransferNumbers, report: (Int, String, String?) -> Unit): Boolean {
        if (REQUIRED !in headers && TOKEN !in headers) return false
        val id = headers[TOKEN] ?: error("This browser download cannot resume after a restart; open the page and start it again")
        val entry = offers[id] ?: error("This browser download expired; open the page and start it again")
        check(entry.claimed.compareAndSet(false, true)) { "This browser download was already used" }
        if (active.incrementAndGet() > 4) { active.decrementAndGet(); revoke(id); error("Four browser downloads are already transferring; try again after one finishes") }
        try {
            val fd = descriptor(entry)
            entry.descriptor.set(fd)
            check(offers[id] === entry) { "The browser disconnected" }
            withContext(Dispatchers.IO) {
                val cap = minOf(BrowserBodyMetadata.MAX_BYTES, maxBytes.takeIf { it > 0 } ?: BrowserBodyMetadata.MAX_BYTES)
                check(entry.metadata.size <= cap) { "The browser download exceeds this job's size limit" }
                destination.parentFile?.mkdirs()
                val free = destination.parentFile?.usableSpace ?: 0L
                check(entry.metadata.size < 0 || entry.metadata.size <= free) { "There is not enough space for this download" }
                val started = SystemClock.elapsedRealtime()
                var lastRead = started
                val coroutine = currentCoroutineContext()
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                        destination.outputStream().use { output ->
                            val poll = StructPollfd().apply { this.fd = fd.fileDescriptor; events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort() }
                            BrowserBodyCopy.receive(input, output, entry.metadata.size, cap,
                                beforeRead = {
                                    do {
                                        coroutine.ensureActive()
                                        val now = SystemClock.elapsedRealtime()
                                        check(now - lastRead < 60_000 && now - started < 3_600_000) { "The browser download stopped sending data" }
                                    } while (Os.poll(arrayOf(poll), 250) == 0)
                                },
                                beforeWrite = { count -> check(destination.parentFile!!.usableSpace >= count) { "There is not enough space for this download" } },
                                progress = { done ->
                                    lastRead = SystemClock.elapsedRealtime()
                                    numbers.bytes(done, entry.metadata.size, if (lastRead > started) done * 1000 / (lastRead - started) else 0)
                                    report(if (entry.metadata.size > 0) (done * 100 / entry.metadata.size).toInt().coerceAtMost(99) else -1, "Receiving from the browser…", null)
                                },
                            )
                            fd.checkError()
                            output.fd.sync()
                        }
                    }
                } catch (error: Throwable) { destination.delete(); throw error }
            }
            return true
        } finally {
            active.decrementAndGet()
            runCatching { entry.descriptor.getAndSet(null)?.close() }
            revoke(id)
        }
    }
}
