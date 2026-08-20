package dev.droidtop.runtime.linux.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class RootProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * Runs a command as root via `su -c`, the standard interface every common
 * Android root solution (Magisk, KernelSU, APatch) provides — droidspaces
 * itself needs root for the namespace/cgroup/mount operations its CLI
 * performs (see vendor/droidspaces' `check` command, which verifies this).
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
