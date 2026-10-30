package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootTar
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A droidspaces container's rootfs as a tar stream, as root (docs/SPEC.md 3d "Backup" and "Copy";
 * Droidtop/tracker#758). The tree is root's, so Android's own `tar` (toybox) runs through the root shell (Shizuku or
 * Sui) rather than as the app, which is what proot's ContainerArchive does; the commands are [RootTar]'s. As root, tar
 * reads every file and keeps owners and modes on the way back in. A socket left behind is dead state in a stopped container and unknown to tar,
 * so it is left out. The container must be stopped; the runtime sees to that.
 */
internal object RootArchive {
    const val NO_ROOT_SHELL = "This needs Shizuku or Sui, which droidtop can't reach right now"

    /** Writes [rootfs] to [out] as a tar archive. Throws with tar's own words when it fails. */
    suspend fun export(rootfs: String, out: OutputStream) {
        val sockets = RootProcess.run(*RootTar.socketsCommand(rootfs).toTypedArray())
        check(sockets.launched) { NO_ROOT_SHELL }
        val command = RootTar.exportCommand(rootfs, RootTar.socketExcludes(rootfs, sockets.stdout))
        val process = RootProcess.spawn(*command.toTypedArray()) ?: error(NO_ROOT_SHELL)
        withContext(Dispatchers.IO) {
            val errors = StringBuilder()
            val reader = Thread({ errors.append(process.errorStream.bufferedReader().readText()) }, "root-tar-errors")
            reader.start()
            try {
                process.inputStream.use { it.copyTo(out) }
                out.flush()
            } finally {
                val code = process.waitFor()
                reader.join()
                check(code == 0) { "tar failed: ${errors.toString().trim().take(400)}" }
            }
        }
    }

    /** Unpacks [input] into [into], which the caller made empty. Throws with tar's own words when it fails. */
    suspend fun import(input: InputStream, into: String) {
        val made = RootProcess.run("/system/bin/mkdir", "-p", into)
        check(made.launched) { NO_ROOT_SHELL }
        check(made.succeeded) { "could not make $into: ${made.stderr.trim()}" }
        val process = RootProcess.spawn(*RootTar.importCommand(into).toTypedArray()) ?: error(NO_ROOT_SHELL)
        withContext(Dispatchers.IO) {
            val errors = StringBuilder()
            val reader = Thread({ errors.append(process.errorStream.bufferedReader().readText()) }, "root-tar-errors")
            reader.start()
            try {
                process.outputStream.use { input.copyTo(it) }
            } catch (e: IOException) {
                // tar stopped reading: its own message says why
            }
            process.inputStream.use { it.readBytes() }
            val code = process.waitFor()
            reader.join()
            check(code == 0) { "tar failed: ${errors.toString().trim().take(400)}" }
        }
    }

    /** Copies [rootfs] to [into] as root. Throws with cp's own words when it fails. */
    suspend fun copy(rootfs: String, into: String) {
        val result = RootProcess.run(*RootTar.copyCommand(rootfs, into).toTypedArray())
        check(result.launched) { NO_ROOT_SHELL }
        check(result.succeeded) { "the copy failed: ${result.stderr.trim().take(400)}" }
    }
}
