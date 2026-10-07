package dev.droidtop.stores.util

import android.os.Environment
import android.os.StatFs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/*
 * The pieces GOG's and Epic's chunked downloads share, lifted from GameNative
 * (app.gamenative.utils and app.gamenative.service, GPL-3.0) with them.
 */

/** Free room for a download (GameNative's StorageUtils.downloadSpaceShortfall). */
internal object StoreDiskSpace {
    // The chunk cache normally stays MB-sized (chunks are deleted as they are
    // assembled); this only guards against starting on a nearly full data partition.
    private const val MIN_INTERNAL_CACHE_BYTES = 512L * 1024 * 1024

    /**
     * Free space on the volume that would hold [path], even before [path]
     * exists: [StatFs] needs an existing path, so the nearest existing parent
     * (on the same volume) is asked.
     */
    fun availableFor(path: File): Long {
        var file: File? = path
        while (file != null && !file.exists()) file = file.parentFile
        requireNotNull(file) { "Invalid path: $path" }
        val stat = StatFs(file.path)
        return stat.blockSizeLong * stat.availableBlocksLong
    }

    /** Why the download cannot start for lack of room, or null when it can. */
    fun shortfall(installDir: File, requiredBytes: Long, cacheDir: File): String? {
        val available = availableFor(installDir)
        if (available < requiredBytes) {
            return "Not enough free space: it needs ${gigabytes(requiredBytes)} and ${gigabytes(available)} is free"
        }
        val cacheAvailable = availableFor(cacheDir)
        if (cacheAvailable < MIN_INTERNAL_CACHE_BYTES) {
            return "Not enough internal storage for the download's working space: ${gigabytes(cacheAvailable)} free"
        }
        return null
    }

    /**
     * Whether [path] is on a removable card. Such cards are FAT or exFAT,
     * which have no sparse files, so a download there grows each file as it
     * writes instead of sizing it first (setLength would write the whole file
     * of zeros). GameNative asked this of its own storage setting
     * (ContainerStorageManager.isOnExternalStorage); droidtop asks the volume.
     */
    fun onRemovableVolume(path: File): Boolean {
        var file: File? = path
        while (file != null && !file.exists()) file = file.parentFile
        val existing = file ?: return false
        return runCatching { Environment.isExternalStorageRemovable(existing) }.getOrDefault(false)
    }

    private fun gigabytes(bytes: Long): String = String.format("%.1f GB", bytes / 1_000_000_000.0)
}

/**
 * Orders a store's CDN base addresses by which answers a HEAD request,
 * fastest first (GameNative's CdnRankingUtils).
 */
internal object CdnRankingUtils {
    suspend fun rankBaseUrlsByHeadProbe(
        baseUrls: List<String>,
        httpClient: OkHttpClient,
        userAgent: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val urls = baseUrls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (urls.size <= 1) return@withContext urls
        urls.map { url ->
            val start = System.nanoTime()
            val success = try {
                val request = Request.Builder().url(url).head().header("User-Agent", userAgent).build()
                httpClient.newCall(request).execute().use { response -> response.code in 200..499 }
            } catch (_: Exception) {
                false
            }
            Triple(url, success, (System.nanoTime() - start) / 1_000_000)
        }
            .sortedWith(compareByDescending<Triple<String, Boolean, Long>> { it.second }.thenBy { it.third })
            .map { it.first }
    }
}

/**
 * How many chunks download and how many unpack at once. GameNative let a
 * person pick a speed; droidtop has no such setting, so this is GameNative's
 * default ("fast", its downloadSpeed 24).
 */
internal class DownloadSpeedConfig {
    private val cpuCores: Int get() = Runtime.getRuntime().availableProcessors()

    val maxDownloads: Int get() = (cpuCores * 1.25).toInt().coerceIn(6, 16)

    val maxDecompress: Int get() = (cpuCores * 0.4).toInt().coerceIn(2, 5)
}

/** The order chunks are fetched in and when each can be dropped (GameNative's StreamingAssembly). */
internal object StreamingAssembly {
    /** Every chunk once, in the order the files that use them appear. */
    fun buildOrderedChunkQueue(fileChunkIds: List<List<String>>): List<String> {
        val seen = mutableSetOf<String>()
        val queue = mutableListOf<String>()
        for (chunks in fileChunkIds) for (id in chunks) if (seen.add(id)) queue.add(id)
        return queue
    }

}
