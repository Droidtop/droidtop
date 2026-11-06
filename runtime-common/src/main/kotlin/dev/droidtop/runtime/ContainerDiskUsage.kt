package dev.droidtop.runtime

import dev.droidtop.library.format.ValueFormat
import java.io.File
import java.nio.file.Files

/**
 * A container's size on disk, for [ContainerRuntime.diskUsage] (docs/SPEC.md 3d "Storage used"): the one
 * measurement the container's page and its row in the Containers list both show (Droidtop/tracker#911).
 * [complete] is false when part of the tree could not be read, so [bytes] is a floor ("at least 1.2 GB").
 */
data class DiskUsage(val bytes: Long, val complete: Boolean = true) {
    /** "1.2 GB", or "at least 1.2 GB" when part of it could not be read. */
    val label: String get() = if (complete) ValueFormat.size(bytes) else "at least ${ValueFormat.size(bytes)}"
}

/**
 * The app-side walk of a rootfs tree (proot's, which is the app's own) and the reading of a root `du` (droidspaces',
 * whose tree is root's). Iterative, not recursive: a rootfs is an untrusted, arbitrarily deep tree (a distro's own
 * package database, node_modules-style dependency trees inside it), and Kotlin has no tail-call guarantee for a
 * recursive walk. Symlinks are never followed -- a rootfs image that happens to symlink outside itself must not
 * inflate this or loop forever on a cycle.
 */
object ContainerDiskUsage {
    /**
     * What the files under [root] take. A folder droidtop cannot list is left out and the result marked incomplete,
     * so the row says "at least" rather than a short total as if it were the whole.
     */
    fun measure(root: File): DiskUsage {
        if (!root.exists()) return DiskUsage(0L)
        var total = 0L
        var complete = true
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (Files.isSymbolicLink(file.toPath())) continue
            if (file.isDirectory) {
                val children = file.listFiles()
                if (children == null) complete = false else children.forEach { stack.addLast(it) }
            } else {
                total += file.length()
            }
        }
        return DiskUsage(total, complete)
    }

    /**
     * The bytes in `du -sk`'s answer ("12345<TAB>/path", kilobytes of 1024 bytes): its last line's first field, or
     * null when there is none. [succeeded] false (du met files it could not read, or that went away while it counted)
     * makes the total a floor.
     */
    fun fromDu(stdout: String, succeeded: Boolean): DiskUsage? {
        val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        val kilobytes = line.split(Regex("\\s+"), limit = 2).first().toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return DiskUsage(kilobytes * 1024, complete = succeeded)
    }
}
