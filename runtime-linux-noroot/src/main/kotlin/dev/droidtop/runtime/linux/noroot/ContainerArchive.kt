package dev.droidtop.runtime.linux.noroot

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ

/**
 * Export and import of one proot container's rootfs as a tar stream
 * (docs/SPEC.md 3d "Backup", Droidtop/tracker#81), through Android's own
 * `tar` (toybox) so symlinks, modes and empty directories survive without a
 * tar writer in the app. Ownership is not carried: every file in a proot
 * rootfs is the app's own, and extraction as a non-root app cannot set
 * another owner anyway.
 *
 * Nothing here decides which container or where the stream goes; the
 * runtime hands it the rootfs directory and a stream.
 */
internal object ContainerArchive {
    private const val TAR = "/system/bin/tar"

    /** Writes [rootfs] to [out] as a tar archive, entries relative to the rootfs root. */
    fun export(rootfs: File, out: OutputStream, errorLog: File) {
        require(rootfs.isDirectory) { "no rootfs at $rootfs" }
        val sockets = mutableListOf<String>()
        prepareForTar(rootfs, rootfs, sockets)
        val command = buildList {
            add(TAR); add("-cf"); add("-")
            // Toybox matches --exclude against the member name as tar spells it, "./" first.
            sockets.forEach { add("--exclude=./$it") }
            add("-C"); add(rootfs.absolutePath); add(".")
        }
        val process = ProcessBuilder(command)
            .redirectError(errorLog)
            .start()
        process.inputStream.use { it.copyTo(out) }
        out.flush()
        check(process.waitFor() == 0) { "tar failed: ${errorLog.readText().trim().take(400)}" }
    }

    /**
     * Unpacks the archive on [input] into [into], which must not exist yet.
     * Toybox's tar drops a leading "/" and refuses ".." members, so a
     * hostile archive cannot write outside [into].
     */
    fun import(input: InputStream, into: File, errorLog: File) {
        check(!into.exists()) { "$into already exists" }
        check(into.mkdirs()) { "could not create $into" }
        val process = ProcessBuilder(TAR, "-xf", "-", "-C", into.absolutePath)
            .redirectError(errorLog)
            .redirectOutput(File("/dev/null"))
            .start()
        try {
            process.outputStream.use { input.copyTo(it) }
        } catch (e: IOException) {
            // tar stopped reading: its own message says why
        }
        check(process.waitFor() == 0) { "tar failed: ${errorLog.readText().trim().take(400)}" }
    }

    /**
     * What stops toybox tar finishing over a proot rootfs, all of it found
     * in one walk of [dir] (Droidtop/tracker#81; rig, emulator, the whole
     * message of tar's stderr):
     *
     *  - proot makes an empty placeholder with no permissions at every bind
     *    target the image lacks (`/etc/resolv.conf`, `/run/droidtop-sockets`,
     *    `/run/droidtop-app-storage`, the shared-storage and extra-mount
     *    paths), and a guest running as its fake root can chmod anything it
     *    owns. tar stops with "can't open ...: Permission denied" on each
     *    and fails the backup. Everything under [dir] is the app's own, so
     *    the owner gets read (and search, for a directory) back where it is
     *    missing, before the directory is listed; the archive then holds the
     *    placeholders as the empty entries they are.
     *  - A socket a program left behind is "unknown file type '140000'" to
     *    tar. The container is stopped, so it is dead state: it is left out
     *    of the archive (its path is added to [sockets], relative to [root]).
     *
     * Symbolic links are never followed.
     */
    private fun prepareForTar(root: File, dir: File, sockets: MutableList<String>) {
        ownerAccess(dir.toPath(), OWNER_READ, OWNER_EXECUTE)
        val children = dir.listFiles() ?: return
        for (child in children) {
            val path = child.toPath()
            when {
                Files.isSymbolicLink(path) -> Unit
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> prepareForTar(root, child, sockets)
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> ownerAccess(path, OWNER_READ)
                isSocket(child) -> sockets += child.relativeTo(root).path
            }
        }
    }

    private fun isSocket(file: File): Boolean =
        try {
            OsConstants.S_ISSOCK(Os.lstat(file.path).st_mode)
        } catch (_: ErrnoException) {
            false
        }

    private fun ownerAccess(path: Path, vararg needed: PosixFilePermission) {
        runCatching {
            val have = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
            if (!have.containsAll(needed.toList())) Files.setPosixFilePermissions(path, have + needed)
        }
    }

    /** A rootfs has these; an archive without them is not one, whatever it is. */
    fun looksLikeRootfs(dir: File): Boolean = File(dir, "etc").isDirectory && File(dir, "usr").isDirectory
}
