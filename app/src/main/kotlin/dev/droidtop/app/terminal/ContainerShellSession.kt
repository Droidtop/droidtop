package dev.droidtop.app.terminal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.droidtop.runtime.InteractiveShell
import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.util.AppScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * One shell in a container on droidtop's own terminal (docs/SPEC.md 3d, "A terminal on Android"; Droidtop/tracker#909):
 * a Termux [TerminalSession] whose program runs on the pseudo-terminal Termux's JNI makes (`/dev/ptmx`, fork, `setsid`,
 * the slave as the controlling terminal; `TIOCSWINSZ` when the view's size changes). The program starts when the
 * [com.termux.view.TerminalView] it is attached to is first laid out.
 *
 * - [InteractiveShell.OnTerminal] (proot): the backend's own command line runs on the pseudo-terminal directly.
 * - [InteractiveShell.Elevated] (droidspaces): the command runs as root through the elevated helper, which gives
 *   pipes; the container makes its own terminal ([dev.droidtop.runtime.ContainerTerminal.onContainerTerminal]) and a
 *   relay on droidtop's pseudo-terminal ([TerminalLaunch.relayArgv]) carries the bytes through two FIFOs.
 *
 * The session belongs to the screen that made it: [close] ends it and everything it started. There is no background
 * session and no service.
 */
internal class ContainerShellSession private constructor(
    val session: TerminalSession,
    private val ending: () -> Unit,
) {
    private var closed = false

    /**
     * Ends the shell: a hang-up to its process group, then a kill ([hangUp]), and the backend's own end
     * ([InteractiveShell.OnTerminal.end], or the relay's) off the main thread. Main thread; once.
     */
    fun close() {
        if (closed) return
        closed = true
        hangUp(session)
        AppScopes.io.launch {
            runCatching(ending).onFailure { Log.w(TAG, "ending the terminal's shell failed", it) }
        }
    }

    companion object {
        private const val TAG = "droidtop.Terminal"

        /** Makes the session for [shell]; the FIFOs an elevated shell needs are made off the main thread. */
        suspend fun start(context: Context, shell: InteractiveShell, client: TerminalSessionClient): ContainerShellSession =
            when (shell) {
                is InteractiveShell.OnTerminal -> ContainerShellSession(
                    TerminalSession(
                        shell.argv.first(),
                        shell.workingDir,
                        shell.argv.toTypedArray(),
                        TerminalLaunch.environmentLines(shell.environment),
                        TerminalLaunch.SCROLLBACK_ROWS,
                        client,
                    ),
                    shell.end,
                )
                is InteractiveShell.Elevated -> {
                    val dir = withContext(Dispatchers.IO) { ElevatedRelay.makeFifos(context) }
                    val relay = ElevatedRelay(dir, shell.argvFor)
                    val argv = TerminalLaunch.relayArgv(relay.fromShell.path, relay.toShell.path)
                    val session = TerminalSession(
                        argv.first(),
                        dir.path,
                        argv.toTypedArray(),
                        TerminalLaunch.environmentLines(TerminalLaunch.RELAY_ENVIRONMENT),
                        TerminalLaunch.SCROLLBACK_ROWS,
                        client,
                    )
                    relay.start(session)
                    ContainerShellSession(session) { relay.stop() }
                }
            }

        /**
         * A hang-up to the shell's whole process group (`setsid` made it the group's leader), then a kill of the shell
         * itself, so one that ignores the hang-up still ends. Never signals a pid of 0 or below: before the terminal is
         * laid out the session has not started (pid 0), after it ended it is -1, and a signal to 0 would reach
         * droidtop's own process group.
         */
        fun hangUp(session: TerminalSession) {
            val pid = session.pid
            if (pid <= 0) return
            runCatching { Os.kill(-pid, OsConstants.SIGHUP) }
            runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
    }

    /**
     * The bytes between droidtop's pseudo-terminal and an elevated shell: what is typed comes out of [toShell] and
     * goes to the root process's input; its output and errors go into [fromShell], which the relay copies to the
     * screen. When the root process ends, [fromShell] is closed, and the relay exits.
     */
    private class ElevatedRelay(private val dir: File, private val argvFor: (Int, Int) -> List<String>) {
        val fromShell = File(dir, "out")
        val toShell = File(dir, "in")

        @Volatile private var stopped = false
        @Volatile private var process: Process? = null

        fun start(session: TerminalSession) {
            AppScopes.io.launch {
                // Each open waits for the relay's end of the FIFO, which it opens once the terminal is laid out. A
                // terminal closed first has removed them ([stop]).
                val typed = runCatching { FileInputStream(toShell) }.getOrNull() ?: return@launch
                val screen = runCatching { FileOutputStream(fromShell) }.getOrNull() ?: return@launch typed.close()
                try {
                    if (stopped) return@launch
                    // The emulator exists before the relay starts, so its size is the terminal's.
                    val emulator = session.emulator
                    val p = RootProcess.spawn(*argvFor(emulator?.mRows ?: 24, emulator?.mColumns ?: 80).toTypedArray())
                    if (p == null) {
                        screen.write(NO_HELPER.toByteArray())
                        return@launch
                    }
                    process = p
                    if (stopped) return@launch
                    val lock = Any()
                    thread(name = "terminal-typed", isDaemon = true) { copy(typed, p.outputStream, null) }
                    val errors = thread(name = "terminal-errors", isDaemon = true) { copy(p.errorStream, screen, lock) }
                    copy(p.inputStream, screen, lock)
                    errors.join(JOIN_MS)
                } finally {
                    runCatching { screen.close() }
                    runCatching { typed.close() }
                }
            }
        }

        /** Ends the root process and removes the FIFOs, first letting an open still waiting for the relay return. */
        fun stop() {
            stopped = true
            process?.destroy()
            listOf(toShell, fromShell).forEach { fifo ->
                runCatching { Os.close(Os.open(fifo.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)) }
            }
            fromShell.delete()
            toShell.delete()
            dir.delete()
        }

        private fun copy(from: InputStream, to: OutputStream, lock: Any?) {
            val buffer = ByteArray(BUFFER)
            try {
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    if (lock == null) {
                        to.write(buffer, 0, n)
                        to.flush()
                    } else {
                        synchronized(lock) {
                            to.write(buffer, 0, n)
                            to.flush()
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // One side went away; the relay's end is the normal end.
            } finally {
                if (lock == null) runCatching { to.close() }
            }
        }

        companion object {
            private const val BUFFER = 4096
            private const val JOIN_MS = 1_000L
            private const val NO_HELPER = "\r\nThe shell couldn't start: droidtop has no root access right now.\r\n"

            /** A fresh folder in the cache with the two FIFOs. Disk work: never on the main thread. */
            fun makeFifos(context: Context): File {
                val dir = File(File(context.cacheDir, "terminal"), UUID.randomUUID().toString())
                check(dir.mkdirs()) { "could not make ${dir.path}" }
                Os.mkfifo(File(dir, "out").path, "600".toInt(8))
                Os.mkfifo(File(dir, "in").path, "600".toInt(8))
                return dir
            }
        }
    }
}
