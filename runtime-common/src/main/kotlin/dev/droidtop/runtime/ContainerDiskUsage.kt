package dev.droidtop.runtime

import java.io.File
import java.nio.file.Files

/**
 * Real size on disk of a container's rootfs tree, for
 * [ContainerRuntime.diskUsageBytes] (docs/SPEC.md 3d "Storage used").
 * Iterative, not recursive: a rootfs is an untrusted, arbitrarily deep
 * tree (a distro's own package database, node_modules-style dependency
 * trees inside it), and Kotlin has no tail-call guarantee for a recursive
 * walk. Symlinks are never followed -- a rootfs image that happens to
 * symlink outside itself must not inflate this or loop forever on a
 * cycle.
 */
object ContainerDiskUsage {
    fun bytesUnder(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (Files.isSymbolicLink(file.toPath())) continue
            if (file.isDirectory) {
                file.listFiles()?.forEach { stack.addLast(it) }
            } else {
                total += file.length()
            }
        }
        return total
    }
}
