package dev.droidtop.runtime.windows

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletion that cannot leave the directory it was pointed at.
 *
 * Exists because of a real event, not a hypothetical: on 2026-09-02 a
 * cleanup pointed Kotlin's `deleteRecursively` at a half-made Wine
 * prefix. A prefix contains `dosdevices/z: -> /` and one symlink per
 * drive letter; `deleteRecursively` follows symlinked directories, so
 * the walk left the prefix and emptied the shared area of the test
 * device's internal storage before it was stopped. gamenative's
 * `FileUtils.delete` declines to descend into symlinks, but a helper
 * that refuses is still trust -- anything aimed at a path that can hold
 * a Wine prefix gets containment verified here instead.
 *
 * Two guarantees, independently enforced:
 *  - the target is proven to live inside the stated boundary, with the
 *    parent resolved to its real path first, so a symlinked ancestor
 *    cannot smuggle the deletion elsewhere;
 *  - the walk itself never follows a symlink: `Files.walkFileTree`
 *    without `FOLLOW_LINKS` visits a symlink as a plain entry, so a
 *    link is unlinked, never entered.
 */
object SafeDelete {

    /**
     * Recursively deletes [target] after proving it lies inside
     * [boundary]. Returns false -- having deleted nothing -- when
     * containment cannot be proven, and false when the tree survived
     * the attempt; true when [target] is gone (or never existed).
     */
    fun deleteWithin(boundary: File, target: File): Boolean {
        val boundaryReal = try {
            boundary.canonicalFile.toPath()
        } catch (e: IOException) {
            return false
        }
        val parent = target.parentFile ?: return false
        val parentReal = try {
            parent.canonicalFile.toPath()
        } catch (e: IOException) {
            return false
        }
        if (parentReal != boundaryReal && !parentReal.startsWith(boundaryReal)) return false

        val start = parentReal.resolve(target.name)
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        exc?.let { throw it }
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: IOException) {
            return false
        }
        return !Files.exists(start, LinkOption.NOFOLLOW_LINKS)
    }
}
