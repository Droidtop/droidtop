package dev.droidtop.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RootProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Runs a command as root via `su -c`, the standard interface every common
 * Android root solution (Magisk, KernelSU, APatch) provides. Lives in
 * runtime-common because more than one consumer needs it: droidspaces
 * (namespace/cgroup/mount operations, see vendor/droidspaces' `check`
 * command) and the GameNative migration in :runtime-windows, which
 * reads another app's data directory with the user's consent.
 *
 * UNVERIFIED against a real device: written against the documented `su -c`
 * contract every root solution follows, but never actually run against
 * KernelSU/Magisk/APatch here — no rooted device attached to this
 * environment. See runtime-linux-root/README.md.
 */
object RootProcess {
    suspend fun run(vararg args: String, workingDir: File? = null): RootProcessResult =
        withContext(Dispatchers.IO) {
            val shellCommand = args.joinToString(" ") { shellQuote(it) }
            val process = ProcessBuilder("su", "-c", shellCommand)
                .apply { workingDir?.let { directory(it) } }
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            RootProcessResult(exitCode, stdout, stderr)
        }

    /** Single-quotes an argument for a POSIX shell, escaping embedded single quotes. */
    private fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"
}
