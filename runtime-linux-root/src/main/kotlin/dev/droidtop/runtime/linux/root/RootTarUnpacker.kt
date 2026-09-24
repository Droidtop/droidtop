package dev.droidtop.runtime.linux.root

import android.util.Log
import dev.droidtop.runtime.CraneRootfsPuller
import dev.droidtop.runtime.OciFlattener
import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootfsContent
import dev.droidtop.runtime.RootfsUnpacker
import dev.droidtop.runtime.TarStreamSink

/**
 * droidspaces' half of [CraneRootfsPuller]: the rootfs is written by
 * root, so the tree keeps the image's real ownership and setuid bits (a
 * namespace container boots it as-is, with no proot faking uids), and
 * everything under it is managed as root. Removal goes through
 * [RootfsDelete], which refuses while anything is still mounted under the
 * tree.
 *
 * Root never reads the image. The app flattens it ([OciFlattener], which
 * refuses `..`, anything beneath a symlink, hard links out of the image)
 * and streams the result into `tar -x` on root's standard input, so what
 * root's `tar` extracts is only ever that checked stream, into a directory
 * that is empty. Which `tar` `su` finds (toybox, or a root manager's
 * busybox) and how it treats hostile names no longer matters: it is never
 * given one. That closes finding 7 of
 * docs/security/2026-09-24-droidtop-intents-updater.md.
 */
class RootTarUnpacker : RootfsUnpacker {
    override suspend fun isCurrent(destinationPath: String, digest: String): Boolean {
        val existing = RootProcess.run("cat", "$destinationPath/${CraneRootfsPuller.DIGEST_MARKER}")
        return existing.succeeded && existing.stdout.trim() == digest
    }

    override suspend fun wipe(destinationPath: String) {
        val wiped = RootfsDelete.delete(destinationPath)
        check(wiped.succeeded) {
            "refusing to reuse or wipe the stale rootfs at $destinationPath: ${wiped.stderr.ifBlank { wiped.stdout }}"
        }
    }

    override suspend fun extract(content: RootfsContent, destinationPath: String) {
        val mkdirResult = RootProcess.run("mkdir", "-p", destinationPath)
        check(mkdirResult.succeeded) { "mkdir -p $destinationPath failed: ${mkdirResult.stderr}" }
        var flattened: OciFlattener.Result? = null
        val extractResult = RootProcess.run("tar", "-xf", "-", "-C", destinationPath) { stdin ->
            flattened = TarStreamSink(stdin).use { sink -> content.writeTo(sink) }
        }
        check(extractResult.succeeded) { "Extracting into $destinationPath failed: ${extractResult.stderr}" }
        flattened?.let { result ->
            Log.i(TAG, "extracted ${result.written} entries into $destinationPath, skipped ${result.skippedCount}")
            result.skipped.forEach { Log.i(TAG, "skipped $it") }
        }
    }

    override suspend fun markComplete(destinationPath: String, digest: String) {
        val marker = "$destinationPath/${CraneRootfsPuller.DIGEST_MARKER}"
        // Values as positional parameters, not spliced into root's script.
        val markResult = RootProcess.run("sh", "-c", "printf %s \"\$1\" > \"\$2\"", "sh", digest, marker)
        check(markResult.succeeded) { "Writing $marker failed: ${markResult.stderr}" }
    }

    private companion object {
        const val TAG = "droidtop.droidspaces"
    }
}
