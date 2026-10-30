package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.IncrementTools
import dev.droidtop.runtime.RootProcess
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking

/**
 * Toybox run as root through the root shell over a droidspaces tree, for incremental backups (docs/SPEC.md 3d
 * "Backups on a schedule", Droidtop/tracker#808): its files are root's and other users'. Called on an IO thread by
 * [dev.droidtop.runtime.IncrementalArchiver]; each call waits for the root shell.
 */
internal class RootIncrementTools(private val scratchDir: File) : IncrementTools {
    override fun run(argv: List<String>): String = runBlocking {
        val result = RootProcess.run(*argv.toTypedArray())
        check(result.launched) { RootArchive.NO_ROOT_SHELL }
        // find exits non-zero over an entry it can't read but still lists the rest.
        result.stdout
    }

    override fun pipeOut(argv: List<String>, out: OutputStream) {
        val process = runBlocking { RootProcess.spawn(*argv.toTypedArray()) } ?: error(RootArchive.NO_ROOT_SHELL)
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

    override fun pipeIn(argv: List<String>, input: InputStream) {
        val process = runBlocking { RootProcess.spawn(*argv.toTypedArray()) } ?: error(RootArchive.NO_ROOT_SHELL)
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

    override fun remove(paths: List<String>) {
        // A file or link with rm -f, an emptied directory with rmdir; deepest first, as the list comes.
        paths.chunked(BATCH).forEach { batch ->
            runBlocking { RootProcess.run("/system/bin/rm", "-f", *batch.toTypedArray()) }
            runBlocking { RootProcess.run("/system/bin/rmdir", *batch.toTypedArray()) }
        }
    }

    override fun makeDirectories(paths: List<String>) {
        paths.chunked(BATCH).forEach { batch -> runBlocking { RootProcess.run("/system/bin/mkdir", "-p", *batch.toTypedArray()) } }
    }

    override fun scratchFile(): File = File.createTempFile("increment", ".lst", scratchDir.apply { mkdirs() })

    private companion object {
        const val BATCH = 200
    }
}
