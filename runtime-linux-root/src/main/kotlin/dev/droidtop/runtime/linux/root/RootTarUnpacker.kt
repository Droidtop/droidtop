package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.CraneRootfsPuller
import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootfsUnpacker

/**
 * droidspaces' half of [CraneRootfsPuller]: the rootfs is extracted by
 * root, so the tree keeps the image's real ownership (a namespace container
 * boots it as-is, with no proot faking uids), and everything under it is
 * managed as root. Removal goes through [RootfsDelete], which refuses while
 * anything is still mounted under the tree.
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

    override suspend fun extract(tarPath: String, destinationPath: String) {
        val mkdirResult = RootProcess.run("mkdir", "-p", destinationPath)
        check(mkdirResult.succeeded) { "mkdir -p $destinationPath failed: ${mkdirResult.stderr}" }
        val extractResult = RootProcess.run("tar", "-xf", tarPath, "-C", destinationPath)
        check(extractResult.succeeded) {
            "Extracting $tarPath into $destinationPath failed: ${extractResult.stderr}"
        }
    }

    override suspend fun markComplete(destinationPath: String, digest: String) {
        val marker = "$destinationPath/${CraneRootfsPuller.DIGEST_MARKER}"
        val markResult = RootProcess.run("sh", "-c", "printf %s '$digest' > '$marker'")
        check(markResult.succeeded) { "Writing $marker failed: ${markResult.stderr}" }
    }
}
