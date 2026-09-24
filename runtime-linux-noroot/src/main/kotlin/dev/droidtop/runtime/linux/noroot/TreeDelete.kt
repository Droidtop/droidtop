package dev.droidtop.runtime.linux.noroot

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * The one way the proot backend removes a container tree.
 *
 * A Linux rootfs is full of symlinks, absolute ones included, and on the
 * host an absolute link points into Android's own filesystem. Kotlin's
 * `deleteRecursively` follows symlinked directories; that is how a cleanup
 * walked out of a Wine prefix and emptied internal storage on 2026-09-02
 * (docs/SPEC.md 5b). This walks with `Files.walkFileTree` and no
 * FOLLOW_LINKS option, so a symlink is visited as a file and unlinked,
 * never entered. proot's binds are not mounts, so unlike droidspaces'
 * [dev.droidtop.runtime.linux.root.RootfsDelete] there is no live mount
 * that could lie underneath the tree.
 *
 * It also refuses any tree that is not strictly inside [fence]: every
 * caller passes the backend's own containers directory, so a wrong path is
 * a refusal, not a deletion somewhere else.
 */
internal object TreeDelete {
    fun delete(tree: File, fence: File) {
        val root = tree.toPath().toAbsolutePath().normalize()
        val boundary = fence.toPath().toAbsolutePath().normalize()
        require(root.startsWith(boundary) && root != boundary) {
            "refusing to delete $root: it is not inside $boundary"
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root)
            return
        }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // A guest can chmod its own directories read-only; the app
                // owns the tree, so it can always give itself access back.
                runCatching {
                    val permissions = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
                    val needed = setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    )
                    if (!permissions.containsAll(needed)) {
                        Files.setPosixFilePermissions(dir, permissions + needed)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // Unreadable, but the entry itself can still be unlinked.
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
