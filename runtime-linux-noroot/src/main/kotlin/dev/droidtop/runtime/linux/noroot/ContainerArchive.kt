package dev.droidtop.runtime.linux.noroot

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
        val process = ProcessBuilder(TAR, "-cf", "-", "-C", rootfs.absolutePath, ".")
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

    /** A rootfs has these; an archive without them is not one, whatever it is. */
    fun looksLikeRootfs(dir: File): Boolean = File(dir, "etc").isDirectory && File(dir, "usr").isDirectory
}
