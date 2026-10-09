package dev.droidtop.runtime

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
    fun deleteWithin(boundary: File, target: File): Boolean = clearWithin(boundary, target) == null

    /**
     * [deleteWithin], saying why it did not: null when [target] is gone,
     * otherwise a sentence naming the first thing that stopped it (the
     * containment proof, or the first path that refused to go). A refusal
     * from the walk no longer ends it: every other entry is still
     * unlinked, so a retry meets less, and the reason reaches the person
     * instead of "nothing was deleted" (#249: a prefix unpacked with
     * read-only directories cannot be emptied by a plain unlink).
     */
    fun clearWithin(boundary: File, target: File): String? {
        val boundaryReal = try {
            boundary.canonicalFile.toPath()
        } catch (e: IOException) {
            return "the boundary ${boundary.path} cannot be resolved (${e.message})"
        }
        val parent = target.parentFile ?: return "${target.path} has no parent"
        val parentReal = try {
            parent.canonicalFile.toPath()
        } catch (e: IOException) {
            return "the parent of ${target.path} cannot be resolved (${e.message})"
        }
        if (parentReal != boundaryReal && !parentReal.startsWith(boundaryReal)) {
            return "${target.path} is not inside ${boundary.path}"
        }

        val start = parentReal.resolve(target.name)
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return null
        var firstFailure: String? = null
        fun failed(path: Path, e: IOException) {
            if (firstFailure == null) firstFailure = "$path: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        }
        try {
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    // Reached only for a real directory (a symlink is a
                    // plain entry without FOLLOW_LINKS). The owner's own
                    // bits are restored first: a directory unpacked
                    // read-only can be neither listed nor emptied.
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        dir.toFile().apply {
                            setReadable(true, true)
                            setWritable(true, true)
                            setExecutable(true, true)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        try {
                            Files.deleteIfExists(file)
                        } catch (e: IOException) {
                            failed(file, e)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        failed(file, exc)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        exc?.let { failed(dir, it) }
                        try {
                            Files.deleteIfExists(dir)
                        } catch (e: IOException) {
                            failed(dir, e)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: IOException) {
            failed(start, e)
        }
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return null
        return firstFailure ?: "$start survived the delete"
    }
}
