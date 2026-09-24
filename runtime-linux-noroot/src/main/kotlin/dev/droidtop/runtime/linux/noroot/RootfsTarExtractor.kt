package dev.droidtop.runtime.linux.noroot

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts a flat image tarball (`crane export`'s output: every layer
 * applied, whiteouts resolved) into a directory the app itself owns, for
 * the proot backend.
 *
 * Why in-process rather than a `tar` binary: an unrooted app has no tar it
 * may rely on (and may not exec one it extracted itself above targetSdk
 * 28), and the tree must end up owned by the app so the app can later
 * remove it. Ownership in the archive is ignored on purpose: proot's
 * fake-root extension (`--root-id`) presents every file as root's to the
 * guest, which is what a distro's own tools expect.
 *
 * What the image asks for and what this writes:
 *  - directories, regular files and symlinks as they are, with the
 *    image's permission bits, plus owner read/write (and search, for a
 *    directory) so the app can always read, update and delete its own
 *    tree. Directory modes are applied last, so a read-only directory in
 *    the image does not stop its own contents being written.
 *  - hard links as hard links where the filesystem allows the app one,
 *    otherwise as a copy of the target (never a symlink: a program that
 *    finds itself through a hard link, perl or busybox, would see a
 *    different name). proot's `--link2symlink` covers links made later,
 *    at run time.
 *  - device nodes and FIFOs are skipped: an unprivileged process cannot
 *    create them, and the guest's /dev is the host's, bound in by proot.
 *
 * Nothing is ever written through a symlink. Every component of an
 * entry's parent path is checked with lstat before the entry is written,
 * and an entry beneath a symlink is skipped rather than followed. An image
 * legitimately contains absolute symlinks (Debian's /var/run -> /run), and
 * on the host an absolute link points out of the rootfs into Android's own
 * filesystem: following one while extracting would write outside the
 * container, the same defect class as the 2026-09-02 storage wipe
 * (docs/SPEC.md 5b).
 */
internal object RootfsTarExtractor {
    /** How an extraction went. [skipped] names each entry not written, with the reason. */
    data class Result(val written: Int, val skipped: List<String>)

    fun extract(tar: File, destination: File): Result =
        BufferedInputStream(tar.inputStream(), 1 shl 16).use { extract(it, destination) }

    fun extract(input: InputStream, destination: File): Result {
        val root = destination.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        val skipped = mutableListOf<String>()
        val directoryModes = mutableListOf<Pair<Path, Int>>()
        var written = 0

        val tar = TarArchiveInputStream(input)
        while (true) {
            val entry: TarArchiveEntry = tar.nextTarEntry ?: break
            val relative = entryPath(entry.name)
            if (relative == null) {
                skipped += "${entry.name}: outside the image root"
                continue
            }
            if (relative.nameCount == 1 && relative.toString().isEmpty()) {
                // "./" itself: only its mode matters.
                if (entry.isDirectory) directoryModes += root to entry.mode
                continue
            }
            val target = root.resolve(relative)
            if (!parentIsRealDirectories(root, relative)) {
                skipped += "${entry.name}: beneath a symlink or a non-directory"
                continue
            }
            try {
                Files.createDirectories(target.parent)
                when {
                    entry.isDirectory -> {
                        if (Files.isSymbolicLink(target) || Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                            Files.delete(target)
                        }
                        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(target)
                        directoryModes += target to entry.mode
                    }
                    entry.isSymbolicLink -> {
                        replaceable(target)
                        Files.createSymbolicLink(target, Paths.get(entry.linkName))
                    }
                    entry.isLink -> {
                        val sourceRelative = entryPath(entry.linkName)
                        if (sourceRelative == null || !parentIsRealDirectories(root, sourceRelative)) {
                            skipped += "${entry.name}: hard link to ${entry.linkName}, outside the image root"
                            continue
                        }
                        val source = root.resolve(sourceRelative)
                        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                            skipped += "${entry.name}: hard link to ${entry.linkName}, which is not in the image"
                            continue
                        }
                        replaceable(target)
                        try {
                            Files.createLink(target, source)
                        } catch (e: IOException) {
                            copyOf(source, target)
                        } catch (e: UnsupportedOperationException) {
                            copyOf(source, target)
                        } catch (e: SecurityException) {
                            copyOf(source, target)
                        }
                    }
                    // Before isFile, which commons-compress also answers
                    // true for any non-directory type it has no other
                    // name for.
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
                        skipped += "${entry.name}: device node or FIFO"
                        continue
                    }
                    entry.isFile -> {
                        replaceable(target)
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                            tar.copyTo(out, 1 shl 16)
                        }
                        Files.setPosixFilePermissions(target, permissions(entry.mode or 0b110_000_000))
                    }
                    else -> {
                        skipped += "${entry.name}: device node or FIFO"
                        continue
                    }
                }
                written++
            } catch (e: IOException) {
                skipped += "${entry.name}: ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        // Deepest first, so tightening a parent never blocks a child.
        for ((dir, mode) in directoryModes.asReversed()) {
            runCatching { Files.setPosixFilePermissions(dir, permissions(mode or 0b111_000_000)) }
        }
        return Result(written, skipped)
    }

    /**
     * The entry's path relative to the image root, or null when it would
     * leave it. Leading `/` and `./` are dropped; a `..` component anywhere
     * rejects the entry, whatever it would resolve to.
     */
    internal fun entryPath(name: String): Path? {
        val components = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (components.any { it == ".." }) return null
        if (components.isEmpty()) return Paths.get("")
        return Paths.get(components.first(), *components.drop(1).toTypedArray())
    }

    /** Whether every existing ancestor of [relative] under [root] is a real directory (no symlink, no file). */
    private fun parentIsRealDirectories(root: Path, relative: Path): Boolean {
        var current = root
        for (i in 0 until relative.nameCount - 1) {
            current = current.resolve(relative.getName(i))
            if (Files.isSymbolicLink(current)) return false
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return false
            }
        }
        return true
    }

    /** Clears the way for a non-directory entry. A later entry replaces an earlier one, as tar does. */
    private fun replaceable(target: Path) {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(target.toString(), null, "a directory is already there")
        }
        Files.deleteIfExists(target)
    }

    private fun copyOf(source: Path, target: Path) {
        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun permissions(mode: Int): Set<PosixFilePermission> {
        val result = mutableSetOf<PosixFilePermission>()
        val bits = listOf(
            0b100_000_000 to PosixFilePermission.OWNER_READ,
            0b010_000_000 to PosixFilePermission.OWNER_WRITE,
            0b001_000_000 to PosixFilePermission.OWNER_EXECUTE,
            0b000_100_000 to PosixFilePermission.GROUP_READ,
            0b000_010_000 to PosixFilePermission.GROUP_WRITE,
            0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
            0b000_000_100 to PosixFilePermission.OTHERS_READ,
            0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
            0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE,
        )
        for ((bit, permission) in bits) if (mode and bit != 0) result += permission
        return result
    }
}
