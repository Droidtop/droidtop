package dev.droidtop.runtime

import dev.droidtop.runtime.tasks.PrivilegedShell
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

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
    /** The elevated helper ran `id -u` and it answered 0. */
    AVAILABLE("Root access is available through Shizuku or Sui."),

    /** The elevated helper runs commands, but not as root (Shizuku started over ADB). */
    DENIED("Shizuku runs as the ADB shell, not as root. Start Shizuku with root, or use Sui, for the rooted desktop."),

    /** No elevated helper droidtop may use, or none that can start a process. Not an error: most devices. */
    ABSENT("No root through Shizuku or Sui here, so root-only backends are unavailable.");

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

    /** [RootProcessResult.exitCode] when the process exited cleanly but its input could not all be written. */
    const val INPUT_FAILED = -2

    /**
     * Runs [command]. With [stdin], the process's standard input is what
     * [stdin] writes (on this coroutine) while stdout and stderr are read
     * on their own threads, so neither a full output pipe nor a slow
     * consumer can deadlock the write. If [stdin] throws, the process is
     * killed and the failure is reported in stderr, never thrown.
     */
    suspend fun run(
        command: List<String>,
        workingDir: File? = null,
        stdin: ((OutputStream) -> Unit)? = null,
    ): RootProcessResult =
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
            collect(process, stdin)
        }

    /**
     * Feeds [stdin] to an already started [process] and collects its
     * output, as [run] does for a process it started itself. The elevated
     * helper's processes ([RootProcess]) come through here.
     */
    suspend fun collect(process: Process, stdin: ((OutputStream) -> Unit)? = null): RootProcessResult =
        withContext(Dispatchers.IO) {
            try {
                coroutineScope {
                    val stdout = async { process.inputStream.bufferedReader().readText() }
                    val stderr = async { process.errorStream.bufferedReader().readText() }
                    val inputFailure = runCatching { process.outputStream.use { out -> stdin?.invoke(out) } }.exceptionOrNull()
                    if (inputFailure != null && inputFailure !is IOException) process.destroy()
                    val exitCode = process.waitFor()
                    val errors = stderr.await()
                    RootProcessResult(
                        exitCode = if (inputFailure != null && exitCode == 0) INPUT_FAILED else exitCode,
                        stdout = stdout.await(),
                        stderr = if (inputFailure == null) errors else listOf(errors, "input failed: ${inputFailure.message ?: inputFailure}")
                            .filter { it.isNotBlank() }.joinToString("\n"),
                    )
                }
            } catch (e: CancellationException) {
                process.destroy()
                throw e
            }
        }

    internal fun notLaunched(command: List<String>, cause: Exception): RootProcessResult =
        RootProcessResult(
            exitCode = NOT_LAUNCHED,
            stdout = "",
            stderr = "could not start ${command.firstOrNull() ?: "(empty command)"}: ${cause.message ?: cause.toString()}",
        )
}

/**
 * Runs a command as root through the elevated helper the person chose
 * (docs/SPEC.md "The task manager": the Shizuku app or Sui, through
 * [TaskManager.shell]'s [PrivilegedShell.spawn]). droidtop itself never
 * runs `su` (owner rule: root only through Shizuku); a helper started with
 * root runs the command as root, one started over ADB runs it as the shell
 * user, which [access] reports as [RootAccess.DENIED].
 *
 * Its only consumers are Desktop mode's rooted container stack: droidspaces
 * in :runtime-linux-root (namespace/cgroup/mount operations) and the
 * runtime selection in :app that asks whether that stack can run, plus the
 * plugin host's root-approval check. Nothing in Gaming or the launcher may
 * call it (docs/SPEC.md 7i, "Root never gates a Gaming game"). The command
 * is an argv, never a shell line: the helper starts it directly.
 */
object RootProcess {
    /** How long [access] waits for the helper's binder, which reaches a new process shortly after it starts. */
    private const val HELPER_WAIT_MS = 3_000L
    private const val HELPER_POLL_MS = 250L

    /**
     * Runs [args] as root and collects its output; a long-lived command
     * lives as long as the caller waits, and cancelling kills it. [stdin]
     * as for [ProcessRunner.run]. With no helper that can start a process,
     * the result is not [RootProcessResult.launched].
     */
    suspend fun run(vararg args: String, stdin: ((OutputStream) -> Unit)? = null): RootProcessResult =
        run(TaskManager.shell, args.toList(), stdin)

    internal suspend fun run(shell: PrivilegedShell, argv: List<String>, stdin: ((OutputStream) -> Unit)?): RootProcessResult {
        if (argv.isEmpty()) return RootProcessResult(ProcessRunner.NOT_LAUNCHED, "", "no command to run")
        val process = withContext(Dispatchers.IO) { runCatching { shell.spawn(argv) }.getOrNull() }
            ?: return RootProcessResult(ProcessRunner.NOT_LAUNCHED, "", "no elevated helper (Shizuku or Sui) can start ${argv.first()}")
        return ProcessRunner.collect(process, stdin)
    }

    /**
     * What root this device offers -- the question callers ask instead of
     * inferring it from a failed command. Waits up to [HELPER_WAIT_MS] for
     * the helper's binder, which arrives a moment after the process starts.
     */
    suspend fun access(): RootAccess = withContext(Dispatchers.IO) {
        var result = run(TaskManager.shell, listOf("id", "-u"), null)
        var waited = 0L
        while (!result.launched && waited < HELPER_WAIT_MS) {
            delay(HELPER_POLL_MS)
            waited += HELPER_POLL_MS
            result = run(TaskManager.shell, listOf("id", "-u"), null)
        }
        accessOf(result)
    }

    /**
     * [access] for a caller that cannot suspend (the plugin host's root
     * approval, already on a background thread): one try, no waiting.
     * Blocks on the helper: never on the main thread.
     */
    fun accessNow(): RootAccess {
        val process = runCatching { TaskManager.shell.spawn(listOf("id", "-u")) }.getOrNull() ?: return RootAccess.ABSENT
        return runCatching {
            val out = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            accessOf(RootProcessResult(process.waitFor(), out, ""))
        }.getOrDefault(RootAccess.ABSENT)
    }

    /** The mapping itself, separated so it is testable without a device: root is `id -u` answering 0. */
    fun accessOf(idResult: RootProcessResult): RootAccess = when {
        idResult.succeeded && idResult.stdout.trim() == "0" -> RootAccess.AVAILABLE
        idResult.launched -> RootAccess.DENIED
        else -> RootAccess.ABSENT
    }
}
