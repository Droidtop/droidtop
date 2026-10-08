package dev.droidtop.runtime.windows

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * The Windows runtime's one downloader: [fetchUrl] for a file at a URL (a
 * component-list entry, a pinned release asset), [fetch] for a file of the
 * runtime's base system (the imagefs archive, the bundled Proton 9 builds,
 * container files, drivers) from GameNative's download host with its mirror
 * as the fallback. GameNative's SteamService.fetchFile and
 * fetchFileWithFallback (GPL-3.0), which had nothing to do with Steam but
 * lived in its Steam service. The hosts are GameNative's: they serve the
 * builds, the code that fetches them is droidtop's (docs/SPEC.md 5b).
 */
internal object RuntimeDownloads {
    private const val PRIMARY = "https://downloads.gamenative.app/"
    private const val MIRROR = "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    /** Downloads [url] into [dest]; [onProgress] gets 0 to 1. */
    suspend fun fetchUrl(url: String, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        fetchFrom(url, dest, onProgress)
    }

    /** Downloads [fileName] into [dest], from the primary host and then the mirror; [onProgress] gets 0 to 1. */
    suspend fun fetch(fileName: String, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        try {
            fetchFrom(PRIMARY + fileName, dest, onProgress)
        } catch (primary: Exception) {
            try {
                fetchFrom(MIRROR + fileName, dest, onProgress)
            } catch (mirror: Exception) {
                dest.delete()
                throw IOException("Failed to download $fileName. Check the network connection and try again.", mirror)
            }
        }
    }

    private fun fetchFrom(url: String, dest: File, onProgress: (Float) -> Unit) {
        val partial = File(dest.absolutePath + ".part")
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("empty body")
                val total = body.contentLength()
                partial.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8 * 1024)
                        var read = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            read += count
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
                if (total > 0 && partial.length() != total) {
                    partial.delete()
                    error("incomplete download")
                }
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }
}
