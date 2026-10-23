package dev.droidtop.runtime.windows

import android.content.Context
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.runtime.windows.utils.ComponentCatalog
import java.io.File
import java.io.IOException
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

    /**
     * Downloads [url] into [dest], checked against [sha256] when given; [onProgress] gets 0 to 1. [title] names the job in
     * Downloads; by default it is the file's own name.
     */
    suspend fun fetchUrl(context: Context, url: String, dest: File, sha256: String? = null, title: String? = null, onProgress: (Float) -> Unit) =
        firstThatWorks(listOf(url)) { fetchFrom(context, it, dest, sha256, title, onProgress) }

    /**
     * Downloads the first of [urls] that answers with the right bytes into
     * [dest]: the catalog's download locations for one file, in its order
     * (droidtop's copy, then its makers' copies). A location that fails or
     * serves bytes that do not match [sha256] is skipped for the next.
     */
    suspend fun fetchUrls(context: Context, urls: List<String>, dest: File, sha256: String? = null, onProgress: (Float) -> Unit) =
        firstThatWorks(urls) { url -> fetchFrom(context, url, dest, sha256, null, onProgress) }

    /** Downloads the base-system file [fileName] into [dest]; [onProgress] gets 0 to 1. */
    suspend fun fetch(context: Context, fileName: String, dest: File, onProgress: (Float) -> Unit) {
        val file = withContext(Dispatchers.IO) { ComponentCatalog.file(context, fileName) }
        try {
            fetchUrls(context, file.downloadUrls(), dest, file.sha256, onProgress)
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
    internal inline fun <T> firstThatWorks(urls: List<String>, attempt: (String) -> T): T {
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

    /**
     * One location as a Downloads job (docs/SPEC.md 12a, "One download engine"): droidtop's one download path fetches
     * it, checks it against [sha256] and moves it to [dest] ([DownloadJobs.POST_MOVE_TO]), so the Windows runtime's
     * files are listed in Downloads, follow the Download rules and move to the download engine with every other file.
     */
    private suspend fun fetchFrom(context: Context, url: String, dest: File, sha256: String?, title: String?, onProgress: (Float) -> Unit) {
        val result = DownloadJobs.run(
            context = context,
            title = title ?: "Windows support: ${dest.name}",
            post = DownloadJobs.POST_MOVE_TO,
            url = url,
            name = areaName(dest.name),
            sha256 = sha256,
            extra = mapOf(DownloadJobs.ARG_TARGET_PATH to dest.absolutePath),
            onPercent = { if (it in 0..100) onProgress(it / 100f) },
        )
        if (!result.ok) {
            throw IOException(if (result.error == DownloadJobs.DIGEST_MISMATCH) "${dest.name} did not match its published checksum" else result.error ?: "the download failed")
        }
    }

    /** The downloads area's name for [fileName]: a bare, unique name, whatever the component is called. */
    internal fun areaName(fileName: String): String =
        "runtime_${System.nanoTime()}_" + fileName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trimStart('.', '_', '-').take(80).ifEmpty { "file" }
}
