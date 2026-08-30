package dev.droidtop.runtime.linux.root

import android.content.Context
import dev.droidtop.runtime.ImageCache
import dev.droidtop.runtime.ImageCacheEntry
import dev.droidtop.runtime.ImageCachePolicy
import java.io.File

/**
 * Filesystem-backed [ImageCache]: pulled/exported image tarballs keyed by
 * digest, moved into `context.filesDir/image-cache/<digest>.tar` on [put].
 * Callers (e.g. [CraneRootfsPuller]) pull into their own scratch location
 * first and hand it off here — this class owns where a cached blob actually
 * lives, not the caller.
 *
 * This is the VERSIONED image store (per direction): one cached tar per
 * digest, and every container created from that image extracts its own
 * rootfs copy from it — a second/third container off the same image is a
 * local extract, never a re-download. [put]'s label lands in a
 * `<digest>.ref` sidecar so [entries] (the §3 cache-management setting's
 * data source) can show a human-readable `reference:tag` instead of an
 * opaque digest.
 */
class FileImageCache(context: Context) : ImageCache {
    private val cacheDir = File(context.filesDir, "image-cache")

    override suspend fun get(digest: String): String? {
        val path = File(cacheDir, "$digest.tar")
        return if (path.exists()) path.absolutePath else null
    }

    override suspend fun put(digest: String, blobPath: String, label: String?) {
        cacheDir.mkdirs()
        val dest = File(cacheDir, "$digest.tar")
        val source = File(blobPath)
        if (!source.renameTo(dest)) {
            // renameTo can fail across filesystems/volumes; fall back to a
            // real copy.
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
        label?.let { File(cacheDir, "$digest.ref").writeText(it) }
    }

    override suspend fun entries(): List<ImageCacheEntry> =
        cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".tar") }.orEmpty().map { tar ->
            val digest = tar.name.removeSuffix(".tar")
            ImageCacheEntry(
                digest = digest,
                sizeBytes = tar.length(),
                label = File(cacheDir, "$digest.ref").takeIf { it.exists() }?.readText()?.trim(),
            )
        }

    override suspend fun evictToFit(policy: ImageCachePolicy) {
        val cap = policy.maxCacheBytes ?: return
        val tars = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".tar") }
            ?.sortedBy { it.lastModified() } ?: return
        var total = tars.sumOf { it.length() }
        for (file in tars) {
            if (total <= cap) break
            total -= file.length()
            File(cacheDir, file.name.removeSuffix(".tar") + ".ref").delete()
            file.delete()
        }
    }

    override suspend fun clear() {
        cacheDir.deleteRecursively()
    }
}
