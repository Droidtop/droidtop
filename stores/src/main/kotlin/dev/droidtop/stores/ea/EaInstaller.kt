package dev.droidtop.stores.ea

// Adapted from DroidDeck stores/ea/EaInstaller.kt at cc63b403872b (GPL-3.0; NOTICE.md).
import android.content.Context
import com.droiddeck.launcher.stores.ea.EaNative
import dev.droidtop.library.stores.StoreInstallJob
import dev.droidtop.library.stores.StoreProgress
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import org.json.JSONObject

/** Runs on the store's IO dispatcher. Cancellation asks Rust to stop, then keeps the handle alive until it answers. */
internal object EaInstaller {
    fun request(url: String, folder: File): JSONObject = JSONObject()
        .put("url", url).put("gameDir", folder.absolutePath).put("maxWorkers", 8).put("processWorkers", 2).put("caBundle", "")

    suspend fun download(context: Context, row: EaInstall, url: String, progress: StoreProgress): JSONObject {
        check(EaNative.ready()) { "The EA store engine is not available in this build" }
        val folder = EaInstalls.ownedFolder(row)
        val done = CountDownLatch(1)
        data class Outcome(val success: Boolean, val cancelled: Boolean, val error: String, val result: String)
        val outcome = AtomicReference<Outcome?>()
        val failure = AtomicReference<String?>()
        val listener = object : EaNative.InstallListener {
            override fun onStatus(text: String) { progress.report(-1f, text) }
            override fun onCheck(doneBytes: Long, totalBytes: Long, files: Int, filesTotal: Int) {
                progress.report(-1f, "Checking files ($files/$filesTotal)")
            }
            override fun onSizes(pendingBytes: Long, totalBytes: Long) {
                if (pendingBytes > 0 && folder.usableSpace < pendingBytes) failure.set("Not enough free space for ${row.title}")
            }
            override fun onProgress(done: Long, total: Long, files: Int, filesTotal: Int) {
                progress.report(if (total > 0) (done.toDouble() / total).toFloat() else -1f, "Downloading ($files/$filesTotal files)")
            }
            override fun onLog(line: String) = Unit // the engine's signed download URLs never enter the UI log
            override fun beforeReplace(): Boolean = runCatching {
                check(failure.get() == null) { failure.get().orEmpty() }
                EaInstalls.put(context, row.copy(complete = false))
                File(folder, StoreInstallJob.IN_PROGRESS_MARKER).writeText(row.id)
                true
            }.getOrDefault(false)
            override fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String) {
                outcome.set(Outcome(success, cancelled, error, result))
                done.countDown()
            }
        }
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val handle = EaNative.nativeInstall(request(url, folder).toString(), listener)
        if (handle == 0L) error(outcome.get()?.error?.takeIf { it.isNotBlank() } ?: "The EA install could not start")
        var stopped = false
        var forwarded = false
        try {
            while (true) {
                val finished = try { done.await(250, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { stopped = true; false }
                if (finished) break
                if (!forwarded && (!coroutine.isActive || stopped || failure.get() != null)) {
                    EaNative.nativeCancel(handle)
                    forwarded = true
                }
            }
        } finally {
            EaNative.nativeRelease(handle)
        }
        coroutine.ensureActive()
        failure.get()?.let { error(it) }
        val result = outcome.get() ?: error("The EA install ended without a result")
        if (stopped || result.cancelled) throw CancellationException("The EA install stopped")
        check(result.success) { result.error.ifBlank { "The EA install failed" } }
        return result.result.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
    }
}
