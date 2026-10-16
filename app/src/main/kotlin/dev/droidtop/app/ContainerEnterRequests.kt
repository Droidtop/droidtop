package dev.droidtop.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.droidtop.runtime.ContainerEnter
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerLauncher
import dev.droidtop.runtime.ContainerNames
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.DockerCli
import dev.droidtop.runtime.DockerCommand
import dev.droidtop.runtime.HomeChoice
import dev.droidtop.runtime.OciImageStore
import dev.droidtop.runtime.RootfsImage
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
        val args = runCatching { File(dir, "args").readBytes() }.getOrNull()?.let(ContainerEnter::parseArgs)
        if (args == null) {
            refuse(dir, "droidtop-enter: the arguments could not be read (at most ${ContainerEnter.MAX_ARGS_BYTES} bytes)", 2)
            return
        }
        val cwd = runCatching { File(dir, "cwd").readText().trim() }.getOrNull()
        if (token == DockerCli.TOKEN) {
            docker(app, dir, DockerCli.parse(args), cwd, seenAt, writtenAt)
            return
        }
        val command = ContainerEnter.forToken(ContainerEnter.read(app.filesDir), token)
        if (command == null) {
            refuse(dir, "droidtop-enter: that command is not exported (any more)", 127)
            return
        }
        enter(app, dir, Target(command.containerId, command.containerName, "droidtop-enter"), listOf(command.name) + args, cwd, emptyMap(), seenAt, writtenAt)
    }

    /** Which container a request runs in, and the program name its refusals start with. */
    private data class Target(val containerId: String, val containerName: String, val who: String)

    /**
     * Runs [argv] in [target]'s container with its streams on the request's FIFOs, when the container still exists and
     * runs commands from the desktop, starting it first where the backend needs that.
     */
    private suspend fun enter(app: Context, dir: File, target: Target, argv: List<String>, cwd: String?, env: Map<String, String>, seenAt: Long, writtenAt: Long?) {
        if (!ContainerEnter.allowed(app.filesDir, target.containerId)) {
            refuse(dir, "${target.who}: ${target.containerName} does not run commands from the desktop (its page in Containers)", 126)
            return
        }
        // The path is the same inside every container: the request channel is under the app storage they all bind.
        val inContainer = "${ContainerLauncher.REQUESTS_DIR}/${dir.name}"
        val started = DesktopSessionService.runInPrimary { runtime, _ ->
            val info = runtime.listContainers().firstOrNull { it.container.id == target.containerId }
            if (info == null) {
                refuse(dir, "${target.who}: ${target.containerName} no longer exists", 127)
                return@runInPrimary null
            }
            val startAt = SystemClock.elapsedRealtime()
            if (runtime.siblingsNeedStart && !info.running) runtime.start(info.container)
            val startMs = SystemClock.elapsedRealtime() - startAt
            val runAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                runtime.exec(
                    info.container,
                    ContainerEnter.execCommand(inContainer, argv.first(), argv.drop(1), cwd),
                    env + ContainerEnter.execEnvironment(cwd),
                )
            }
            val runMs = SystemClock.elapsedRealtime() - runAt
            val code = result.getOrNull()?.exitCode ?: 125
            Log.i(
                TAG,
                "${argv.first()} in ${target.containerName}: seen ${writtenAt?.let { "${seenAt - it} ms after" } ?: "?"} the request, " +
                    "sibling start $startMs ms, ran $runMs ms, exit $code",
            )
            result.exceptionOrNull()?.let { refuse(dir, "${target.who}: ${it.message ?: it.javaClass.simpleName}", 125) }
                ?: writeStatus(dir, code)
            null
        }
        if (!started) refuse(dir, "${target.who}: the desktop is not running", 125)
    }

    /**
     * Answers a `docker` request (docs/SPEC.md 3d "The docker command", Droidtop/tracker#555 D6c) from droidtop's own
     * state: the containers, their programs and the image store. Containers are named by their name or their id.
     */
    private suspend fun docker(app: Context, dir: File, command: DockerCommand, cwd: String?, seenAt: Long, writtenAt: Long?) {
        if (command is DockerCommand.Answer) {
            if (command.code == 0) answer(dir, command.text, "", 0) else answer(dir, "", command.text, command.code)
            return
        }
        val runtime = (DesktopSessionService.state.value as? DesktopSessionState.Connected)?.runtime ?: run {
            refuse(dir, "docker: the desktop is not running", 125)
            return
        }
        val containers = runCatching { runtime.listContainers() }.getOrElse {
            refuse(dir, "docker: ${it.message ?: it.javaClass.simpleName}", 1)
            return
        }
        fun find(name: String): ContainerInfo? = containers.firstOrNull { it.displayName == name } ?: containers.firstOrNull { it.container.id == name }
        when (command) {
            is DockerCommand.Ps -> {
                val rows = containers.map { info ->
                    val state = ContainerPrograms.state(info.container.id)
                    val programUp = ContainerPrograms.isRunning(info.container.id)
                    DockerCli.PsRow(
                        name = info.displayName,
                        image = info.image ?: "",
                        status = dockerStatus(state, programUp, info.running),
                        up = programUp || (info.running && runtime.siblingsNeedStart),
                        ports = runCatching { runtime.imageRunConfig(info.container)?.ports }.getOrNull().orEmpty(),
                    )
                }
                answer(dir, DockerCli.formatPs(rows, command), "", 0)
            }
            is DockerCommand.Images -> {
                val images = runCatching { OciImageStore.of(app).entries() }.getOrElse {
                    refuse(dir, "docker images: ${it.message ?: it.javaClass.simpleName}", 1)
                    return
                }
                answer(dir, DockerCli.formatImages(images, command), "", 0)
            }
            is DockerCommand.Logs -> {
                val info = find(command.container) ?: return refuse(dir, "Error: No such container: ${command.container}", 1)
                val lines = ContainerProgram.lastLines(ContainerProgram.hostLogFile(app.filesDir, info.container.id), command.tail)
                answer(dir, lines.joinToString("") { it + "\n" }, "", 0)
            }
            is DockerCommand.Start, is DockerCommand.Stop -> {
                val names = if (command is DockerCommand.Start) command.containers else (command as DockerCommand.Stop).containers
                val out = StringBuilder()
                val err = StringBuilder()
                for (name in names) {
                    val info = find(name)
                    when {
                        info == null -> err.append("Error: No such container: $name\n")
                        command is DockerCommand.Stop -> {
                            ContainerPrograms.stop(info.container.id)
                            out.append(name).append('\n')
                        }
                        runCatching { runtime.imageRunConfig(info.container) }.getOrNull()?.program().isNullOrEmpty() ->
                            err.append("Error: $name has no program of its own to start (its image names none)\n")
                        else -> {
                            ContainerPrograms.start(app, runtime, info.container)
                            out.append(name).append('\n')
                        }
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is DockerCommand.Exec -> {
                val info = find(command.container) ?: return refuse(dir, "Error: No such container: ${command.container}", 1)
                enter(app, dir, Target(info.container.id, info.displayName, "docker exec"), command.command, command.workdir ?: cwd, command.env, seenAt, writtenAt)
            }
            is DockerCommand.Pull -> {
                val pulled = runCatching { OciImageStore.of(app).pull(RootfsImage(reference = command.image)) }
                pulled.fold(
                    onSuccess = { answer(dir, "${command.image}\nDigest: ${it.digest}\n", "", 0) },
                    onFailure = { refuse(dir, "docker pull: ${it.message ?: it.javaClass.simpleName}", 1) },
                )
            }
            is DockerCommand.Run -> {
                val names = containers.map { it.displayName }
                val name = command.name ?: ContainerNames.defaultName(ContainerRole.SIBLING, command.image, names)
                ContainerNames.problemWith(name, names)?.let { return refuse(dir, "docker run: $it", 1) }
                val created = runCatching { runtime.createSibling(RootfsImage(reference = command.image), name) }.getOrElse {
                    return refuse(dir, "docker run: ${it.message ?: it.javaClass.simpleName}", 1)
                }
                // An app container keeps its files to itself (the review's default for app containers, docs/SPEC.md 3d).
                runCatching { runtime.setMounts(created, runtime.mounts(created).copy(home = HomeChoice.Private)) }
                val program = runCatching { runtime.imageRunConfig(created) }.getOrNull()?.program().orEmpty()
                if (program.isEmpty()) {
                    answer(dir, "$name\n", "$name's image names no program to run, so nothing was started; use docker exec $name sh\n", 0)
                } else {
                    ContainerPrograms.start(app, runtime, created)
                    answer(dir, "$name\n", "", 0)
                }
            }
            is DockerCommand.Remove -> {
                val out = StringBuilder()
                val err = StringBuilder()
                for (target in command.containers) {
                    val info = find(target)
                    val running = info != null && (ContainerPrograms.isRunning(info.container.id) || (info.running && runtime.siblingsNeedStart))
                    when {
                        info == null -> err.append("Error: No such container: $target\n")
                        info.container.role == ContainerRole.PRIMARY -> err.append("Error: $target is the desktop's own container; droidtop does not remove it\n")
                        running && !command.force -> err.append("Error: $target is running; stop it first or use docker rm -f\n")
                        else -> runCatching {
                            if (info.running && runtime.siblingsNeedStart) runtime.stop(info.container)
                            ContainerRemoval.remove(app, runtime, info)
                        }.fold(
                            onSuccess = { out.append(target).append('\n') },
                            onFailure = { err.append("Error: $target: ${it.message ?: it.javaClass.simpleName}\n") },
                        )
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is DockerCommand.Answer -> Unit
        }
    }

    /** What `docker ps` says a container is doing, from its program first: that is what Docker's STATUS describes. */
    private fun dockerStatus(state: ContainerPrograms.State, programUp: Boolean, running: Boolean): String = when {
        programUp && state is ContainerPrograms.State.Waiting -> "Restarting (${state.lastExit})"
        programUp -> "Up"
        state is ContainerPrograms.State.Ended -> "Exited (${state.exitCode})"
        state is ContainerPrograms.State.Failed -> "Exited (couldn't start)"
        running -> "Running (no program)"
        else -> "Created"
    }

    /**
     * Ends a request that will not run: the reason on its stderr, its stdout closed, and [code] as its status. Opening
     * a FIFO for writing waits for the helper's reader, which the helper opens before it waits.
     */
    private fun refuse(dir: File, message: String, code: Int) {
        Log.w(TAG, message)
        answer(dir, "", message + "\n", code)
    }

    /**
     * Ends a request droidtop answered itself: [err] and [out] written whole (each FIFO opened, written and closed, so
     * the helper's readers end), then [code] as its status.
     */
    private fun answer(dir: File, out: String, err: String, code: Int) {
        runCatching { FileOutputStream(File(dir, "err")).use { it.write(err.toByteArray()) } }
        runCatching { FileOutputStream(File(dir, "out")).use { it.write(out.toByteArray()) } }
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
