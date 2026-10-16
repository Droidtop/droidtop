package dev.droidtop.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.droidtop.runtime.ContainerEnter
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerLauncher
import dev.droidtop.runtime.ContainerPods
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.EngineCli
import dev.droidtop.runtime.EngineRequest
import dev.droidtop.runtime.EngineFeatureProbe
import dev.droidtop.runtime.EngineFeatures
import dev.droidtop.runtime.EngineInspect
import dev.droidtop.runtime.EngineOptions
import dev.droidtop.runtime.OciImageStore
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        EngineCli.forToken(token)?.let { cli ->
            // The device's kernel features (D10) only when the request needs them: probed once a boot, kept after.
            val features = if (args.firstOrNull() == "info" || (args.firstOrNull() == "run" && EngineOptions.mentioned(args))) {
                EngineFeatureProbe.current(app)
            } else {
                null
            }
            engine(app, dir, cli, cli.parse(args, features), cwd, seenAt, writtenAt, features)
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
     * Answers a `docker` or `podman` request (docs/SPEC.md 3d "The docker command", Droidtop/tracker#555 D6c, D11) from droidtop's own
     * state: the containers, their programs and the image store. Containers are named by their name or their id.
     */
    private suspend fun engine(
        app: Context,
        dir: File,
        cli: EngineCli,
        command: EngineRequest,
        cwd: String?,
        seenAt: Long,
        writtenAt: Long?,
        features: EngineFeatures?,
    ) {
        if (command is EngineRequest.Answer) {
            if (command.code == 0) answer(dir, command.text, "", 0) else answer(dir, "", command.text, command.code)
            return
        }
        if (command is EngineRequest.Info) {
            val backend = (DesktopSessionService.state.value as? DesktopSessionState.Connected)?.runtime?.backend
            answer(dir, cli.formatInfo(features ?: EngineFeatureProbe.current(app), backend), "", 0)
            return
        }
        val runtime = (DesktopSessionService.state.value as? DesktopSessionState.Connected)?.runtime ?: run {
            refuse(dir, "${cli.name}: the desktop is not running", 125)
            return
        }
        val containers = runCatching { runtime.listContainers() }.getOrElse {
            refuse(dir, "${cli.name}: ${it.message ?: it.javaClass.simpleName}", 1)
            return
        }
        fun find(name: String): ContainerInfo? = containers.firstOrNull { it.displayName == name } ?: containers.firstOrNull { it.container.id == name }
        when (command) {
            is EngineRequest.Ps -> {
                val rows = containers.map { info ->
                    val state = ContainerPrograms.state(info.container.id)
                    val programUp = ContainerPrograms.isRunning(info.container.id)
                    val config = runCatching { runtime.imageRunConfig(info.container) }.getOrNull()
                        ?.let { ContainerProgram.effectiveConfig(app.filesDir, info.container.id, it) }
                    EngineCli.PsRow(
                        id = info.container.id,
                        name = info.displayName,
                        image = info.image ?: "",
                        status = EngineFacts.statusText(state, programUp, info.running),
                        up = programUp || (info.running && runtime.siblingsNeedStart),
                        ports = config?.ports.orEmpty(),
                        command = config?.program().orEmpty(),
                        createdMs = withContext(Dispatchers.IO) { ContainerPrograms.created(app, info.container.id) },
                    )
                }
                answer(dir, cli.formatPs(rows, command), "", 0)
            }
            is EngineRequest.Images -> {
                val images = runCatching { OciImageStore.of(app).entries() }.getOrElse {
                    refuse(dir, "${cli.name} images: ${it.message ?: it.javaClass.simpleName}", 1)
                    return
                }
                answer(dir, cli.formatImages(images, command), "", 0)
            }
            is EngineRequest.Logs -> {
                val info = find(command.container) ?: return refuse(dir, "Error: No such container: ${command.container}", 1)
                answer(dir, EngineActions.logs(app, info, command.tail).joinToString("") { it + "\n" }, "", 0)
            }
            is EngineRequest.Start, is EngineRequest.Stop -> {
                val names = if (command is EngineRequest.Start) command.containers else (command as EngineRequest.Stop).containers
                val out = StringBuilder()
                val err = StringBuilder()
                for (name in names) {
                    val info = find(name)
                    when {
                        info == null -> err.append("Error: No such container: $name\n")
                        command is EngineRequest.Stop -> {
                            EngineActions.stop(app, info)
                            out.append(name).append('\n')
                        }
                        !EngineActions.start(app, runtime, info) ->
                            err.append("Error: $name has no program of its own to start (its image names none)\n")
                        else -> out.append(name).append('\n')
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is EngineRequest.Exec -> {
                val info = find(command.container) ?: return refuse(dir, "Error: No such container: ${command.container}", 1)
                enter(app, dir, Target(info.container.id, info.displayName, "${cli.name} exec"), command.command, command.workdir ?: cwd, command.env, seenAt, writtenAt)
            }
            is EngineRequest.Pull -> {
                EngineActions.pull(app, command.image).fold(
                    onSuccess = { answer(dir, "${command.image}\nDigest: $it\n", "", 0) },
                    onFailure = { refuse(dir, "${cli.name} pull: ${it.message ?: it.javaClass.simpleName}", 1) },
                )
            }
            is EngineRequest.Run -> {
                val verb = if (command.start) "run" else "create"
                val made = EngineActions.make(app, runtime, containers, command).getOrElse {
                    return refuse(dir, "${cli.name} $verb: ${it.message}", 1)
                }
                // Both tools print the new container's id (D13); -p on a port's own number is what happens anyway.
                val notes = made.notes.toMutableList()
                if (command.start && !made.hasProgram) {
                    notes += "${made.name}'s image names no program to run, so nothing was started; use ${cli.name} exec ${made.name} sh"
                }
                answer(dir, "${made.container.id}\n", notes.joinToString("") { it + "\n" }, 0)
            }
            is EngineRequest.Remove -> {
                val out = StringBuilder()
                val err = StringBuilder()
                for (target in command.containers) {
                    val info = find(target) ?: run { err.append("Error: No such container: $target\n"); null } ?: continue
                    when (val removal = EngineActions.remove(app, runtime, info, command.force)) {
                        EngineActions.Removal.Removed -> out.append(target).append('\n')
                        EngineActions.Removal.Primary -> err.append("Error: $target is the desktop's own container; droidtop does not remove it\n")
                        EngineActions.Removal.Running -> err.append("Error: $target is running; stop it first or use ${cli.name} rm -f\n")
                        is EngineActions.Removal.Failed -> err.append("Error: $target: ${removal.message}\n")
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is EngineRequest.Restart -> {
                val out = StringBuilder()
                val err = StringBuilder()
                for (name in command.containers) {
                    val info = find(name)
                    when {
                        info == null -> err.append("Error: No such container: $name\n")
                        !EngineActions.restart(app, runtime, info) ->
                            err.append("Error: $name has no program of its own to restart (its image names none)\n")
                        else -> out.append(name).append('\n')
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is EngineRequest.Inspect -> {
                val pods = EngineFacts.pods(app)
                val facts = mutableListOf<EngineInspect.Facts>()
                val err = StringBuilder()
                for (name in command.containers) {
                    val info = find(name) ?: run { err.append("Error: No such object: $name\n"); null } ?: continue
                    facts += EngineFacts.of(app, runtime, info, pods)
                }
                // Docker prints what it found and the errors for the rest, failing if any was missing.
                answer(dir, if (facts.isEmpty()) "[]\n" else EngineInspect.format(facts, cli.front), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is EngineRequest.PodCreate -> {
                EngineActions.makePod(app, command.name).fold(
                    onSuccess = { answer(dir, it.id + "\n", "", 0) },
                    onFailure = { refuse(dir, "${cli.name} pod create: ${it.message}", 1) },
                )
            }
            is EngineRequest.PodList -> {
                val pods = withContext(Dispatchers.IO) { ContainerPods.read(app.filesDir) }
                val rows = pods.map { pod ->
                    val members = pod.members.mapNotNull { id -> containers.firstOrNull { it.container.id == id } }
                    val up = members.count { ContainerPrograms.isRunning(it.container.id) }
                    EngineCli.PodRow(pod, members.size, up)
                }
                answer(dir, cli.formatPods(rows, command.quiet), "", 0)
            }
            is EngineRequest.PodStart, is EngineRequest.PodStop -> {
                val starting = command is EngineRequest.PodStart
                val names = if (command is EngineRequest.PodStart) command.pods else (command as EngineRequest.PodStop).pods
                val pods = withContext(Dispatchers.IO) { ContainerPods.read(app.filesDir) }
                val out = StringBuilder()
                val err = StringBuilder()
                for (wanted in names) {
                    val pod = ContainerPods.find(pods, wanted) ?: run { err.append("Error: no pod with name or ID $wanted found\n"); null } ?: continue
                    if (starting) EngineActions.startPod(app, runtime, pod, containers) else EngineActions.stopPod(app, pod, containers)
                    out.append(pod.id).append('\n')
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 125)
            }
            is EngineRequest.PodRemove -> {
                val out = StringBuilder()
                val err = StringBuilder()
                for (wanted in command.pods) {
                    val pods = withContext(Dispatchers.IO) { ContainerPods.read(app.filesDir) }
                    val pod = ContainerPods.find(pods, wanted) ?: run { err.append("Error: no pod with name or ID $wanted found\n"); null } ?: continue
                    // Its containers go with it, as Podman's pod rm removes them.
                    when (val removal = EngineActions.removePod(app, runtime, pod, containers, command.force)) {
                        is EngineActions.PodRemoval.Removed -> out.append(pod.id).append('\n')
                        is EngineActions.PodRemoval.Running -> err.append(
                            "Error: pod ${pod.name} has running containers (${removal.names.joinToString(", ")}); stop it first or use podman pod rm -f\n",
                        )
                        is EngineActions.PodRemoval.Failed -> err.append("Error: pod ${pod.name}: ${removal.messages.joinToString("; ")}\n")
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 125)
            }
            is EngineRequest.Answer, EngineRequest.Info -> Unit
        }
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
