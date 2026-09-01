package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootProcessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a command as the app's own (non-root) process. Unlike [RootProcess],
 * this is for operations that don't need root at all — crane's registry
 * pulls are plain network calls writing into app-private storage
 * (`context.filesDir`), which any Android process can already do.
 */
object PlainProcess {
    suspend fun run(vararg args: String, workingDir: File? = null): RootProcessResult =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(*args)
                .apply { workingDir?.let { directory(it) } }
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            RootProcessResult(exitCode, stdout, stderr)
        }
}
