package dev.droidtop.runtime.linux.noroot

import android.util.Log
import dev.droidtop.runtime.CraneRootfsPuller
import dev.droidtop.runtime.RootfsContent
import dev.droidtop.runtime.RootfsUnpacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The proot backend's half of [CraneRootfsPuller]: an app-owned tree,
 * written in-process by [RootfsTarExtractor] straight from the flattener's
 * entries (no tar stream in between) and removed by [TreeDelete],
 * both of which refuse to follow a symlink out of it. [containersDir] is
 * the fence every wipe stays inside.
 */
internal class ProotRootfsUnpacker(private val containersDir: File) : RootfsUnpacker {
    override suspend fun isCurrent(destinationPath: String, digest: String): Boolean = withContext(Dispatchers.IO) {
        val marker = File(destinationPath, CraneRootfsPuller.DIGEST_MARKER)
        marker.isFile && marker.readText().trim() == digest
    }

    override suspend fun wipe(destinationPath: String) = withContext(Dispatchers.IO) {
        TreeDelete.delete(File(destinationPath), containersDir)
    }

    override suspend fun extract(content: RootfsContent, destinationPath: String) {
        val (flattened, result) = withContext(Dispatchers.IO) {
            val extractor = RootfsTarExtractor(File(destinationPath))
            val flattened = content.writeTo(extractor)
            flattened to extractor.finish()
        }
        Log.i(TAG, "extracted ${result.written} entries into $destinationPath, skipped ${flattened.skippedCount} flattening and ${result.skipped.size} writing")
        (flattened.skipped + result.skipped).take(MAX_SKIPPED_LOGGED).forEach { Log.i(TAG, "skipped $it") }
        check(result.written > 0) { "the image extracted to nothing in $destinationPath" }
    }

    override suspend fun markComplete(destinationPath: String, digest: String) = withContext(Dispatchers.IO) {
        File(destinationPath, CraneRootfsPuller.DIGEST_MARKER).writeText(digest)
    }

    private companion object {
        const val TAG = "droidtop.proot"
        const val MAX_SKIPPED_LOGGED = 50
    }
}
