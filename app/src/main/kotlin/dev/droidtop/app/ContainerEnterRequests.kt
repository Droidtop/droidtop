package dev.droidtop.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.droidtop.runtime.ContainerEnter
import dev.droidtop.runtime.ContainerLauncher
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The host side of the enter path (docs/SPEC.md 3d "Entering another container", Droidtop/tracker#555 D7): one
 * request directory from [ContainerEnter.helperScript] is checked and run in its sibling, and the helper is told
 * how it ended. Off the main thread; one coroutine per request, so a long command does not hold up the next.
 *
 * Every request is logged with its timing (tag `droidtop.Enter`: when it was seen against the helper's own clock,
 * how long the sibling took to start where it had to, and how long the command ran), so the shared-binds option can
 * be measured on a rig later (the plan's D7 spike, not yet run).
 */
internal object ContainerEnterRequests {
    private const val TAG = "droidtop.Enter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Handles the request directory [name] in [requestsDir], or ignores it when it is not an enter request. */
    fun handle(context: Context, requestsDir: File, name: String): Boolean {
        val token = ContainerEnter.requestToken(name) ?: return false
        val app = context.applicationContext
        val seenAt = System.currentTimeMillis()
        val dir = File(requestsDir, name)
        // At once, on the watcher's thread: the helper gives up on a request nobody marked taken.
        runCatching { File(dir, ContainerEnter.TAKEN).createNewFile() }
        scope.launch { run(app, dir, token, seenAt) }
        return true
    }

    private suspend fun run(app: Context, dir: File, token: String, seenAt: Long) {
        val writtenAt = dir.name.split('.').getOrNull(3)?.toLongOrNull()
        val command = ContainerEnter.forToken(ContainerEnter.read(app.filesDir), token)
        if (command == null) {
            refuse(dir, "droidtop-enter: that command is not exported (any more)", 127)
            return
        }
        if (!ContainerEnter.allowed(app.filesDir, command.containerId)) {
            refuse(dir, "droidtop-enter: ${command.containerName} does not run commands from the desktop (its page in Containers)", 126)
            return
        }
        val args = runCatching { File(dir, "args").readBytes() }.getOrNull()?.let(ContainerEnter::parseArgs)
        if (args == null) {
            refuse(dir, "droidtop-enter: the arguments could not be read (at most ${ContainerEnter.MAX_ARGS_BYTES} bytes)", 2)
            return
        }
        val cwd = runCatching { File(dir, "cwd").readText().trim() }.getOrNull()
        // The path is the same inside every container: the request channel is under the app storage they all bind.
        val inContainer = "${ContainerLauncher.REQUESTS_DIR}/${dir.name}"
        val started = DesktopSessionService.runInPrimary { runtime, _ ->
            val info = runtime.listContainers().firstOrNull { it.container.id == command.containerId }
            if (info == null) {
                refuse(dir, "droidtop-enter: ${command.containerName} no longer exists", 127)
                return@runInPrimary null
            }
            val startAt = SystemClock.elapsedRealtime()
            if (runtime.siblingsNeedStart && !info.running) runtime.start(info.container)
            val startMs = SystemClock.elapsedRealtime() - startAt
            val runAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                runtime.exec(info.container, ContainerEnter.execCommand(inContainer, command.name, args, cwd), ContainerEnter.execEnvironment(cwd))
            }
            val runMs = SystemClock.elapsedRealtime() - runAt
            val code = result.getOrNull()?.exitCode ?: 125
            Log.i(
                TAG,
                "${command.name} in ${command.containerName}: seen ${writtenAt?.let { "${seenAt - it} ms after" } ?: "?"} the request, " +
                    "sibling start $startMs ms, ran $runMs ms, exit $code",
            )
            result.exceptionOrNull()?.let { refuse(dir, "droidtop-enter: ${it.message ?: it.javaClass.simpleName}", 125) }
                ?: writeStatus(dir, code)
            null
        }
        if (!started) refuse(dir, "droidtop-enter: the desktop is not running", 125)
    }

    /**
     * Ends a request that will not run: the reason on its stderr, its stdout closed, and [code] as its status. Opening
     * a FIFO for writing waits for the helper's reader, which the helper opens before it waits.
     */
    private fun refuse(dir: File, message: String, code: Int) {
        Log.w(TAG, message)
        runCatching { FileOutputStream(File(dir, "err")).use { it.write((message + "\n").toByteArray()) } }
        runCatching { FileOutputStream(File(dir, "out")).close() }
        writeStatus(dir, code)
    }

    /** The exit status, written whole (renamed into place) so the helper never reads half of it. */
    private fun writeStatus(dir: File, code: Int) {
        runCatching {
            val tmp = File(dir, ".status")
            tmp.writeText("$code\n")
            tmp.renameTo(File(dir, "status"))
        }
    }
}
