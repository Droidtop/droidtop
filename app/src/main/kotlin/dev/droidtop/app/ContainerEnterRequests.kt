package dev.droidtop.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.droidtop.runtime.ContainerEnter
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerLauncher
import dev.droidtop.runtime.ContainerNames
import dev.droidtop.runtime.ContainerPods
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.EngineCli
import dev.droidtop.runtime.EngineRequest
import dev.droidtop.runtime.EngineFeatureProbe
import dev.droidtop.runtime.EngineFeatures
import dev.droidtop.runtime.EngineInspect
import dev.droidtop.runtime.EngineOptions
import dev.droidtop.runtime.HomeChoice
import dev.droidtop.runtime.OciImageStore
import dev.droidtop.runtime.Pod
import dev.droidtop.runtime.RootfsImage
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
                        status = dockerStatus(state, programUp, info.running),
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
                val lines = ContainerProgram.lastLines(ContainerProgram.hostLogFile(app.filesDir, info.container.id), command.tail)
                answer(dir, lines.joinToString("") { it + "\n" }, "", 0)
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
                            ContainerPrograms.stoppedByPerson(app, info.container.id)
                            out.append(name).append('\n')
                        }
                        runCatching { runtime.imageRunConfig(info.container) }.getOrNull()?.program().isNullOrEmpty() ->
                            err.append("Error: $name has no program of its own to start (its image names none)\n")
                        else -> {
                            ContainerPrograms.startedByPerson(app, runtime, info.container)
                            out.append(name).append('\n')
                        }
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is EngineRequest.Exec -> {
                val info = find(command.container) ?: return refuse(dir, "Error: No such container: ${command.container}", 1)
                enter(app, dir, Target(info.container.id, info.displayName, "${cli.name} exec"), command.command, command.workdir ?: cwd, command.env, seenAt, writtenAt)
            }
            is EngineRequest.Pull -> {
                val pulled = runCatching { OciImageStore.of(app).pull(RootfsImage(reference = command.image)) }
                pulled.fold(
                    onSuccess = { answer(dir, "${command.image}\nDigest: ${it.digest}\n", "", 0) },
                    onFailure = { refuse(dir, "${cli.name} pull: ${it.message ?: it.javaClass.simpleName}", 1) },
                )
            }
            is EngineRequest.Run -> {
                val verb = if (command.start) "run" else "create"
                val names = containers.map { it.displayName }
                val name = command.name ?: ContainerNames.defaultName(ContainerRole.SIBLING, command.image, names)
                ContainerNames.problemWith(name, names)?.let { return refuse(dir, "${cli.name} $verb: $it", 1) }
                // A volume is a folder the person already mapped in some container (D3), found by its name: never a
                // path the container chose (the review's bound for the docker request kind).
                val mapped = containers.flatMap { runCatching { runtime.mounts(it.container).folders }.getOrDefault(emptyList()) }
                val folders = command.volumes.map { wanted ->
                    mapped.firstOrNull { it.name == wanted }
                        ?: return refuse(dir, "${cli.name} $verb: no mapped folder is called $wanted; map it on a container's page in Containers first", 1)
                }
                // Podman's --pod: an existing pod, or a new one for --pod new:NAME (docs/SPEC.md 3d, "Pods").
                val pods = withContext(Dispatchers.IO) { ContainerPods.read(app.filesDir) }
                val pod = command.pod?.let { wanted ->
                    if (command.newPod) {
                        ContainerPods.problemWith(pods, wanted)?.let { return refuse(dir, "${cli.name} $verb: $it", 1) }
                        Pod(ContainerPods.newId(), wanted, System.currentTimeMillis())
                    } else {
                        ContainerPods.find(pods, wanted) ?: return refuse(dir, "${cli.name} $verb: no pod is called $wanted", 1)
                    }
                }
                val created = runCatching { runtime.createSibling(RootfsImage(reference = command.image), name) }.getOrElse {
                    return refuse(dir, "${cli.name} $verb: ${it.message ?: it.javaClass.simpleName}", 1)
                }
                ContainerPrograms.recordCreated(app, created.id)
                if (pod != null) {
                    withContext(Dispatchers.IO) {
                        val current = ContainerPods.read(app.filesDir).let { if (command.newPod) ContainerPods.with(it, pod) else it }
                        ContainerPods.save(app.filesDir, ContainerPods.withMember(current, pod.id, created.id))
                    }
                }
                // An app container keeps its files to itself (the review's default for app containers, docs/SPEC.md 3d).
                runCatching {
                    val current = runtime.mounts(created)
                    runtime.setMounts(created, current.copy(home = HomeChoice.Private, folders = (current.folders + folders).distinctBy { it.name }))
                }
                // The tools' own default, no restart, unless --restart said otherwise (docs/SPEC.md 3d, "Restart policies").
                withContext(Dispatchers.IO) { ContainerPrograms.setRestartPolicy(app, created.id, command.restart) }
                if (command.args != null || command.env.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        ContainerProgram.saveOverrides(app.filesDir, created.id, ContainerProgram.RunOverrides(command.args, command.env))
                    }
                }
                val program = runCatching { runtime.imageRunConfig(created) }.getOrNull()
                    ?.let { ContainerProgram.effectiveConfig(app.filesDir, created.id, it) }?.program().orEmpty()
                // Both tools print the new container's id (D13); -p on a port's own number is what happens anyway.
                val notes = StringBuilder()
                command.publish.forEach { notes.append("$name listens on the device's own port $it; -p $it is what happens anyway\n") }
                when {
                    !command.start -> answer(dir, "${created.id}\n", notes.toString(), 0)
                    program.isEmpty() -> answer(
                        dir,
                        "${created.id}\n",
                        notes.append("$name's image names no program to run, so nothing was started; use ${cli.name} exec $name sh\n").toString(),
                        0,
                    )
                    else -> {
                        ContainerPrograms.start(app, runtime, created)
                        answer(dir, "${created.id}\n", notes.toString(), 0)
                    }
                }
            }
            is EngineRequest.Remove -> {
                val out = StringBuilder()
                val err = StringBuilder()
                for (target in command.containers) {
                    val info = find(target)
                    val running = info != null && (ContainerPrograms.isRunning(info.container.id) || (info.running && runtime.siblingsNeedStart))
                    when {
                        info == null -> err.append("Error: No such container: $target\n")
                        info.container.role == ContainerRole.PRIMARY -> err.append("Error: $target is the desktop's own container; droidtop does not remove it\n")
                        running && !command.force -> err.append("Error: $target is running; stop it first or use ${cli.name} rm -f\n")
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
            is EngineRequest.Restart -> {
                val out = StringBuilder()
                val err = StringBuilder()
                for (name in command.containers) {
                    val info = find(name)
                    when {
                        info == null -> err.append("Error: No such container: $name\n")
                        runCatching { runtime.imageRunConfig(info.container) }.getOrNull()?.program().isNullOrEmpty() ->
                            err.append("Error: $name has no program of its own to restart (its image names none)\n")
                        else -> {
                            ContainerPrograms.stop(info.container.id)
                            ContainerPrograms.startedByPerson(app, runtime, info.container)
                            out.append(name).append('\n')
                        }
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is EngineRequest.Inspect -> {
                val pods = withContext(Dispatchers.IO) { ContainerPods.read(app.filesDir) }
                val facts = mutableListOf<EngineInspect.Facts>()
                val err = StringBuilder()
                for (name in command.containers) {
                    val info = find(name) ?: run { err.append("Error: No such object: $name\n"); null } ?: continue
                    val id = info.container.id
                    val config = runCatching { runtime.imageRunConfig(info.container) }.getOrNull()
                        ?.let { ContainerProgram.effectiveConfig(app.filesDir, id, it) }
                    val state = ContainerPrograms.state(id)
                    facts += EngineInspect.Facts(
                        id = id,
                        name = info.displayName,
                        image = info.image ?: "",
                        imageDigest = info.digest,
                        createdMs = withContext(Dispatchers.IO) { ContainerPrograms.created(app, id) },
                        status = inspectStatus(state, ContainerPrograms.isRunning(id)),
                        exitCode = (state as? ContainerPrograms.State.Ended)?.exitCode,
                        error = (state as? ContainerPrograms.State.Failed)?.message,
                        config = config,
                        restart = withContext(Dispatchers.IO) { ContainerPrograms.restartPolicy(app, id) },
                        folders = runCatching { runtime.mounts(info.container).folders.map { it.name } }.getOrDefault(emptyList()),
                        podId = pods.firstOrNull { id in it.members }?.id,
                    )
                }
                // Docker prints what it found and the errors for the rest, failing if any was missing.
                answer(dir, if (facts.isEmpty()) "[]\n" else EngineInspect.format(facts, cli.front), err.toString(), if (err.isEmpty()) 0 else 1)
            }
            is EngineRequest.PodCreate -> {
                val made = withContext(Dispatchers.IO) {
                    val current = ContainerPods.read(app.filesDir)
                    val id = ContainerPods.newId()
                    val podName = command.name ?: ContainerPods.defaultName(id)
                    ContainerPods.problemWith(current, podName)?.let { return@withContext Result.failure<Pod>(IllegalStateException(it)) }
                    val pod = Pod(id, podName, System.currentTimeMillis())
                    ContainerPods.save(app.filesDir, ContainerPods.with(current, pod))
                    Result.success(pod)
                }
                made.fold(
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
                    for (member in pod.members.mapNotNull { id -> containers.firstOrNull { it.container.id == id } }) {
                        if (starting) ContainerPrograms.startedByPerson(app, runtime, member.container) else ContainerPrograms.stoppedByPerson(app, member.container.id)
                    }
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
                    val members = pod.members.mapNotNull { id -> containers.firstOrNull { it.container.id == id } }
                    val running = members.filter { ContainerPrograms.isRunning(it.container.id) || (it.running && runtime.siblingsNeedStart) }
                    if (running.isNotEmpty() && !command.force) {
                        err.append("Error: pod ${pod.name} has running containers (${running.joinToString(", ") { it.displayName }}); stop it first or use podman pod rm -f\n")
                        continue
                    }
                    // Its containers go with it, as Podman's pod rm removes them.
                    val failed = members.mapNotNull { member ->
                        runCatching {
                            if (member.running && runtime.siblingsNeedStart) runtime.stop(member.container)
                            ContainerRemoval.remove(app, runtime, member)
                        }.exceptionOrNull()?.let { "${member.displayName}: ${it.message ?: it.javaClass.simpleName}" }
                    }
                    if (failed.isNotEmpty()) {
                        err.append("Error: pod ${pod.name}: ${failed.joinToString("; ")}\n")
                        continue
                    }
                    withContext(Dispatchers.IO) { ContainerPods.save(app.filesDir, ContainerPods.without(ContainerPods.read(app.filesDir), pod.id)) }
                    out.append(pod.id).append('\n')
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 125)
            }
            is EngineRequest.Answer, EngineRequest.Info -> Unit
        }
    }

    /** Docker's State.Status for `inspect`: created, running, restarting or exited. */
    private fun inspectStatus(state: ContainerPrograms.State, programUp: Boolean): String = when {
        programUp && state is ContainerPrograms.State.Waiting -> "restarting"
        programUp -> "running"
        state is ContainerPrograms.State.Ended || state is ContainerPrograms.State.Failed -> "exited"
        else -> "created"
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
