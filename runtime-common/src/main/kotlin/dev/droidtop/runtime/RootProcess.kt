package dev.droidtop.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class RootProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0

    /**
     * Whether the command actually ran. False means the process never
     * started at all -- no such binary, or this uid may not execute it --
     * which is a different fact from "it ran and failed", and the one the
     * root check below turns into a state rather than a crash.
     */
    val launched: Boolean get() = exitCode != ProcessRunner.NOT_LAUNCHED
}

/**
 * What root access this device actually offers, as a value.
 *
 * Root is desktop-only and gates nothing in Gaming, so "there is no
 * root here" has to be an ordinary answer every caller can read and
 * report. It used to be an exception out of `ProcessBuilder.start()`:
 * on a device with no `su`, `start()` throws
 * `IOException: error=13, Permission denied`, which escaped
 * `ContainerRuntimeFactory.select` on a `Dispatchers.IO` coroutine and
 * took the whole process down at launch in any non-Gaming mode
 * (reproduced on the emulator rig, 2026-09-10).
 */
enum class RootAccess(val description: String) {
    /** `su -c id` ran and succeeded. */
    AVAILABLE("Root access is available."),

    /** A `su` exists and ran, but refused this app (denied, or no manager granted it). */
    DENIED("A root manager is present but has not granted droidtop root access."),

    /** No `su` on this device, or this uid may not execute it. Not an error: most devices. */
    ABSENT("This device is not rooted (no su), so root-only backends are unavailable.");

    val available: Boolean get() = this == AVAILABLE
}

/**
 * Starts a process and collects its output. Never throws for a process
 * that could not be started: that is reported as
 * [RootProcessResult.launched] being false, with the failure message as
 * stderr. The one place in the repo that starts a command and reads its
 * output. [RootProcess] is its root face; crane and proot run through
 * it directly as the app itself.
 */
object ProcessRunner {
    /** [RootProcessResult.exitCode] when the process never started. */
    const val NOT_LAUNCHED = -1

    suspend fun run(command: List<String>, workingDir: File? = null): RootProcessResult =
        withContext(Dispatchers.IO) {
            // ProcessBuilder.start() indexes the command array before it
            // validates it, so an empty list is an
            // ArrayIndexOutOfBoundsException rather than any documented
            // failure. A caller bug, answered the same way as every other
            // "it never ran".
            if (command.isEmpty()) {
                return@withContext RootProcessResult(NOT_LAUNCHED, "", "no command to run")
            }
            val process = try {
                ProcessBuilder(command)
                    .apply { workingDir?.let { directory(it) } }
                    .start()
            } catch (e: IOException) {
                return@withContext notLaunched(command, e)
            } catch (e: SecurityException) {
                return@withContext notLaunched(command, e)
            }

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            RootProcessResult(exitCode, stdout, stderr)
        }

    private fun notLaunched(command: List<String>, cause: Exception): RootProcessResult =
        RootProcessResult(
            exitCode = NOT_LAUNCHED,
            stdout = "",
            stderr = "could not start ${command.firstOrNull() ?: "(empty command)"}: ${cause.message ?: cause.toString()}",
        )
}

/**
 * Runs a command as root via `su -c`, the standard interface every common
 * Android root solution (Magisk, KernelSU, APatch) provides. Its only
 * consumers are Desktop mode's rooted container stack: droidspaces in
 * :runtime-linux-root (namespace/cgroup/mount operations, see
 * vendor/droidspaces' `check` command) and the runtime selection in
 * :app that asks whether that stack can run. Nothing in Gaming or the
 * launcher may call it (docs/SPEC.md 7i, "Root never gates a Gaming game").
 *
 * UNVERIFIED against a real device: written against the documented `su -c`
 * contract every root solution follows, but never actually run against
 * KernelSU/Magisk/APatch here -- no rooted device attached to this
 * environment. See runtime-linux-root/README.md.
 */
object RootProcess {
    suspend fun run(vararg args: String, workingDir: File? = null): RootProcessResult {
        val shellCommand = args.joinToString(" ") { shellQuote(it) }
        return ProcessRunner.run(listOf("su", "-c", shellCommand), workingDir)
    }

    /** What root this device offers -- the question callers ask instead of inferring it from a failed command. */
    suspend fun access(): RootAccess = accessOf(run("id"))

    /** The mapping itself, separated so it is testable without a device. */
    fun accessOf(idResult: RootProcessResult): RootAccess = when {
        idResult.succeeded -> RootAccess.AVAILABLE
        idResult.launched -> RootAccess.DENIED
        else -> RootAccess.ABSENT
    }

    /** Single-quotes an argument for a POSIX shell, escaping embedded single quotes. */
    private fun shellQuote(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"
}
