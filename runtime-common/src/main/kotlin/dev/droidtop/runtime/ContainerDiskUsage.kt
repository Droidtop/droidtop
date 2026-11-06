package dev.droidtop.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Real size on disk of a container's rootfs tree, for
 * [ContainerRuntime.diskUsageBytes] (docs/SPEC.md 3d "Storage used"), the
 * one measurement the container's page and its row in the Containers list
 * both show. Iterative, not recursive: a rootfs is an untrusted, arbitrarily
 * deep tree (a distro's own package database, node_modules-style dependency
 * trees inside it), and Kotlin has no tail-call guarantee for a recursive
 * walk. Symlinks are never followed -- a rootfs image that happens to
 * symlink outside itself must not inflate this or loop forever on a
 * cycle.
 *
 * A folder droidtop cannot list (droidspaces' tree belongs to root, and its
 * private folders such as /root are closed to the app) makes the walk fail
 * with [Unreadable] rather than return a total that leaves it out: no size
 * is shown rather than a wrong one (Droidtop/tracker#911).
 */
object ContainerDiskUsage {
    /** Part of the tree could not be read as droidtop, so its size is not known. */
    class Unreadable(path: String) : IOException("droidtop cannot read all of it ($path)")

    fun bytesUnder(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (Files.isSymbolicLink(file.toPath())) continue
            if (file.isDirectory) {
                val children = file.listFiles() ?: throw Unreadable(file.path)
                children.forEach { stack.addLast(it) }
            } else {
                total += file.length()
            }
        }
        return total
    }
}
