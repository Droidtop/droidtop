package dev.droidtop.runtime.windows

import android.content.Context
import dev.droidtop.runtime.windows.utils.ComponentCatalog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import timber.log.Timber

/**
 * The Windows runtime's one downloader: [fetchUrl] for a file at a URL (a
 * catalog item, a pinned release asset, a Wine build a person added by
 * link), [fetch] for a file of the runtime's base system (the imagefs
 * archive, the bundled Proton 9 builds, prefix templates, driver packages,
 * Windows components) by the path the runtime asks for, found through
 * droidtop's component catalog ([ComponentCatalog.file]), and [bytes] for the
 * catalog itself. Every download that has a SHA-256 is checked against it,
 * and a file with several locations ([fetchUrls]) is tried at each in turn.
 * Adapted from GameNative's SteamService.fetchFile (GPL-3.0); its download
 * hosts are no longer used (docs/SPEC.md 5a).
 */
internal object RuntimeDownloads {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    /** Downloads [url] into [dest], checked against [sha256] when given; [onProgress] gets 0 to 1. */
    suspend fun fetchUrl(url: String, dest: File, sha256: String? = null, onProgress: (Float) -> Unit) =
        fetchUrls(listOf(url), dest, sha256, onProgress)

    /**
     * Downloads the first of [urls] that answers with the right bytes into
     * [dest]: the catalog's download locations for one file, in its order
     * (droidtop's copy, then its makers' copies). A location that fails or
     * serves bytes that do not match [sha256] is skipped for the next.
     */
    suspend fun fetchUrls(urls: List<String>, dest: File, sha256: String? = null, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) { firstThatWorks(urls) { url -> fetchFrom(url, dest, sha256, onProgress) } }

    /** Downloads the base-system file [fileName] into [dest]; [onProgress] gets 0 to 1. */
    suspend fun fetch(context: Context, fileName: String, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val file = ComponentCatalog.file(context, fileName)
        try {
            firstThatWorks(file.downloadUrls()) { url -> fetchFrom(url, dest, file.sha256, onProgress) }
        } catch (e: Exception) {
            dest.delete()
            throw IOException("Failed to download $fileName. Check the network connection and try again.", e)
        }
    }

    /** A small file at [url] (the catalog, its signature, its certificate), exactly as served. */
    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.bytes() ?: throw IOException("empty answer from $url")
        }
    }

    /** [attempt] on each of [urls] in order until one returns; the last failure when none does. */
    internal fun <T> firstThatWorks(urls: List<String>, attempt: (String) -> T): T {
        var last: Exception? = null
        for (url in urls) {
            try {
                return attempt(url)
            } catch (e: Exception) {
                if (urls.size > 1) Timber.w("RuntimeDownloads: %s failed (%s); trying the next location", url, e.message)
                last = e
            }
        }
        throw last ?: IOException("no download location")
    }

    private fun fetchFrom(url: String, dest: File, sha256: String?, onProgress: (Float) -> Unit) {
        val partial = File(dest.absolutePath + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("empty body")
                val total = body.contentLength()
                partial.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            read += count
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
                if (total > 0 && partial.length() != total) error("incomplete download")
            }
            if (sha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(sha256, ignoreCase = true)) {
                    throw IOException("${dest.name} did not match its published checksum")
                }
            }
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }
}
