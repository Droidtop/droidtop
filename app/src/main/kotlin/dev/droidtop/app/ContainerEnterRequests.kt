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
import dev.droidtop.runtime.EngineIds
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
        fun find(name: String): ContainerInfo? = EngineIds.find(containers, name)
        when (command) {
            is EngineRequest.Ps -> {
                val rows = containers.map { info ->
                    val state = ContainerPrograms.state(info.container.id)
                    val programUp = ContainerPrograms.isRunning(info.container.id)
                    val config = runCatching { runtime.imageRunConfig(info.container) }.getOrNull()
                        ?.let { ContainerProgram.effectiveConfig(app.filesDir, info.container.id, it) }
                    EngineCli.PsRow(
                        // The engine id's first twelve, as both tools print (docs/SPEC.md 3d, "Container ids").
                        id = EngineIds.of(info.container.id).take(12),
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
                val lines = EngineActions.logs(app, info, command.tail, command.sinceMs, command.untilMs)
                // With --until, the lines stop there, and so does a follow (Docker's -f ends at --until too).
                val follows = command.follow && command.untilMs.let { it == null || it > System.currentTimeMillis() }
                if (follows) {
                    followLogs(app, dir, info.container.id, lines, command.untilMs)
                } else {
                    answer(dir, lines.joinToString("") { it + "\n" }, "", 0)
                }
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
                if (made.started && !command.detach) {
                    attached(app, runtime, dir, made, notes, command.autoRemove)
                } else {
                    answer(dir, "${EngineIds.of(made.container.id)}\n", notes.joinToString("") { it + "\n" }, 0)
                }
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
            is EngineRequest.NetworkCreate -> EngineActions.createNetwork(app, command.name).fold(
                // Docker prints the new network's id, Podman its name.
                onSuccess = { answer(dir, (if (cli.front == EngineCli.Front.DOCKER) it.id else it.name) + "\n", "", 0) },
                onFailure = { refuse(dir, "Error response from daemon: ${it.message}", 1) },
            )
            is EngineRequest.NetworkList -> answer(dir, cli.formatNetworks(EngineActions.networks(app), command.quiet), "", 0)
            is EngineRequest.NetworkRemove -> {
                val out = StringBuilder()
                val err = StringBuilder()
                for (ref in command.networks) {
                    when (val removal = EngineActions.removeNetwork(app, runtime, ref)) {
                        is EngineActions.NetworkRemoval.Removed -> out.append(ref).append('\n')
                        EngineActions.NetworkRemoval.Predefined -> err.append("Error response from daemon: $ref is a pre-defined network and cannot be removed\n")
                        EngineActions.NetworkRemoval.Missing -> err.append("Error: No such network: $ref\n")
                        is EngineActions.NetworkRemoval.InUse -> err.append("Error response from daemon: network ${removal.network.name} has active endpoints\n")
                    }
                }
                answer(dir, out.toString(), err.toString(), if (err.isEmpty()) 0 else 1)
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
    /**
     * A `run` without `-d` (rig 2026-10-16: it printed only the id): the program's output as it comes, then its exit
     * code, as Docker and Podman end an attached run. The program writes stdout and stderr together to its log
     * ([ContainerProgram.command]), so both arrive on stdout here; stdin is not attached. With `--rm` the container is
     * removed after the last of its output is read.
     */
    private suspend fun attached(
        app: Context,
        runtime: dev.droidtop.runtime.ContainerRuntime,
        dir: File,
        made: EngineActions.Made,
        notes: List<String>,
        autoRemove: Boolean,
    ) = withContext(Dispatchers.IO) {
        val id = made.container.id
        val log = ContainerProgram.hostLogFile(app.filesDir, id)
        runCatching { FileOutputStream(File(dir, "err")).use { it.write(notes.joinToString("") { n -> n + "\n" }.toByteArray()) } }
        runCatching {
            FileOutputStream(File(dir, "out")).use { out ->
                val follow = LogFollower(log)
                while (ContainerPrograms.isRunning(id)) {
                    follow.copyTo(out)
                    kotlinx.coroutines.delay(FOLLOW_MS)
                }
                follow.copyTo(out)
                follow.finish(out)
            }
        }
        val code = when (val state = ContainerPrograms.state(id)) {
            is ContainerPrograms.State.Ended -> state.exitCode
            is ContainerPrograms.State.Waiting -> state.lastExit
            else -> 125
        }
        if (autoRemove) {
            runCatching { runtime.listContainers() }.getOrNull()?.firstOrNull { it.container.id == id }
                ?.let { EngineActions.remove(app, runtime, it, force = true) }
        }
        writeStatus(dir, code)
    }

    /**
     * `logs -f` (Droidtop/tracker#715): the [tail] lines, then what the program writes next, read the way an attached
     * run reads it, until the program is no longer running, as Docker's and Podman's follow end with their container.
     * The helper's own end (Ctrl+C) closes the FIFO, and the next write ends this. With [untilMs] it ends then too.
     */
    private suspend fun followLogs(app: Context, dir: File, id: String, tail: List<String>, untilMs: Long? = null) = withContext(Dispatchers.IO) {
        val log = ContainerProgram.hostLogFile(app.filesDir, id)
        runCatching { FileOutputStream(File(dir, "err")).use { } }
        runCatching {
            FileOutputStream(File(dir, "out")).use { out ->
                out.write(tail.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))
                out.flush()
                // From where the tail ended: what is written from now on.
                val follow = LogFollower(log, startAt = log.length())
                while (ContainerPrograms.isRunning(id) && (untilMs == null || System.currentTimeMillis() < untilMs)) {
                    follow.copyTo(out)
                    kotlinx.coroutines.delay(FOLLOW_MS)
                }
                follow.copyTo(out)
                follow.finish(out)
            }
        }
        writeStatus(dir, 0)
    }

    /** How often an attached run looks for more output. */
    private const val FOLLOW_MS = 100L

    /**
     * Reads what a program's log gained since the last look, leaving out the line the program's wrapper writes before
     * each start. Should the log be trimmed meanwhile ([ContainerProgram.trim]), reading goes on from its new end.
     */
    private class LogFollower(private val log: File, startAt: Long = 0L) {
        private var offset = startAt
        private var pending = ByteArray(0)

        fun copyTo(out: java.io.OutputStream) {
            val length = log.length()
            if (length < offset) offset = length
            if (length == offset) return
            val bytes = java.io.RandomAccessFile(log, "r").use { file ->
                file.seek(offset)
                ByteArray((length - offset).coerceAtMost(MAX_READ.toLong()).toInt()).also { file.readFully(it) }
            }
            offset += bytes.size
            // Whole lines only, so the start marker is recognised wherever a read ends.
            val text = pending + bytes
            val cut = text.lastIndexOf('\n'.code.toByte()) + 1
            pending = text.copyOfRange(cut, text.size)
            write(out, String(text, 0, cut, Charsets.UTF_8).split('\n').dropLast(1))
        }

        /** The last line, when the program ended without a newline after it. */
        fun finish(out: java.io.OutputStream) {
            if (pending.isEmpty()) return
            out.write(pending)
            out.flush()
            pending = ByteArray(0)
        }

        private fun write(out: java.io.OutputStream, lines: List<String>) {
            val shown = lines.filterNot { it.startsWith(START_MARKER) }
            if (shown.isEmpty()) return
            out.write(shown.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        companion object {
            private const val MAX_READ = 1 shl 20
            private const val START_MARKER = "--- started "
        }
    }

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
