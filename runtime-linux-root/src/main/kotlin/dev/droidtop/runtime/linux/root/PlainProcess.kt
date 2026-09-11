package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.ProcessRunner
import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootProcessResult
import java.io.File

/**
 * Runs a command as the app's own (non-root) process. Unlike [RootProcess],
 * this is for operations that don't need root at all -- crane's registry
 * pulls are plain network calls writing into app-private storage
 * (`context.filesDir`), which any Android process can already do.
 *
 * The process plumbing itself is [ProcessRunner]'s, shared with
 * [RootProcess]: a binary that cannot be started is a result with
 * [RootProcessResult.launched] false, not an exception, here too.
 */
object PlainProcess {
    suspend fun run(vararg args: String, workingDir: File? = null): RootProcessResult =
        ProcessRunner.run(args.toList(), workingDir)
}
