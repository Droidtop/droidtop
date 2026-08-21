package dev.droidtop.runtime.linux.root

import android.content.Context
import dev.droidtop.runtime.ImageCache
import dev.droidtop.runtime.ImageCachePolicy
import java.io.File

/**
 * Filesystem-backed [ImageCache]: pulled/exported image tarballs keyed by
 * digest, moved into `context.filesDir/image-cache/<digest>.tar` on [put].
 * Callers (e.g. [CraneRootfsPuller]) pull into their own scratch location
 * first and hand it off here — this class owns where a cached blob actually
 * lives, not the caller.
 */
class FileImageCache(context: Context) : ImageCache {
    private val cacheDir = File(context.filesDir, "image-cache")

    override suspend fun get(digest: String): String? {
        val path = File(cacheDir, "$digest.tar")
        return if (path.exists()) path.absolutePath else null
    }

    override suspend fun put(digest: String, blobPath: String) {
        cacheDir.mkdirs()
        val dest = File(cacheDir, "$digest.tar")
        val source = File(blobPath)
        if (!source.renameTo(dest)) {
            // renameTo can fail across filesystems/volumes; fall back to a
            // real copy.
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
    }

    override suspend fun evictToFit(policy: ImageCachePolicy) {
        val cap = policy.maxCacheBytes ?: return
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= cap) break
            total -= file.length()
            file.delete()
        }
    }

    override suspend fun clear() {
        cacheDir.deleteRecursively()
    }
}
