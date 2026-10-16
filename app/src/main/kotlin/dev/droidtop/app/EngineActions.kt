package dev.droidtop.app

import android.content.Context
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerNetworks
import dev.droidtop.runtime.ContainerNames
import dev.droidtop.runtime.ContainerPods
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.Crane
import dev.droidtop.runtime.EngineRequest
import dev.droidtop.runtime.HomeChoice
import dev.droidtop.runtime.OciImageStore
import dev.droidtop.runtime.Pod
import dev.droidtop.runtime.RootfsImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What droidtop's engine does for a request, done one way for every front (docs/SPEC.md 3d; Droidtop/tracker#555
 * D14): the docker and podman commands print text from these, the Engine API prints JSON. Each says what happened
 * in a value, never by printing, so neither front can do a thing the other does differently.
 */
internal object EngineActions {
    /** A container `run` or `create` made: started or not, with the notes to show (a `-p` on its own number). */
    data class Made(val container: Container, val name: String, val notes: List<String>, val started: Boolean, val hasProgram: Boolean)

    /**
     * Makes a container as [request] asks, and starts its program when it asks and the image names one. A failure is
     * the plain reason, for the front to put its own words before.
     */
    suspend fun make(app: Context, runtime: ContainerRuntime, containers: List<ContainerInfo>, request: EngineRequest.Run): Result<Made> {
        val names = containers.map { it.displayName }
        val name = request.name ?: ContainerNames.defaultName(ContainerRole.SIBLING, request.image, names)
        ContainerNames.problemWith(name, names)?.let { return Result.failure(Refused(it)) }
        // A volume is a folder the person already mapped in some container (D3), found by its name: never a path the
        // container chose (the review's bound for the docker request kind).
        val mapped = containers.flatMap { runCatching { runtime.mounts(it.container).folders }.getOrDefault(emptyList()) }
        val folders = request.volumes.map { wanted ->
            mapped.firstOrNull { it.name == wanted }
                ?: return Result.failure(Refused("no mapped folder is called $wanted; map it on a container's page in Containers first"))
        }
        // Podman's --pod: an existing pod, or a new one for --pod new:NAME (docs/SPEC.md 3d, "Pods").
        val pods = withContext(Dispatchers.IO) { ContainerPods.read(app.filesDir) }
        val pod = request.pod?.let { wanted ->
            if (request.newPod) {
                ContainerPods.problemWith(pods, wanted)?.let { return Result.failure(Refused(it)) }
                Pod(ContainerPods.newId(), wanted, System.currentTimeMillis())
            } else {
                ContainerPods.find(pods, wanted) ?: return Result.failure(Refused("no pod is called $wanted"))
            }
        }
        val created = runCatching { runtime.createSibling(RootfsImage(reference = request.image), name) }.getOrElse {
            return Result.failure(Refused(it.message ?: it.javaClass.simpleName))
        }
        ContainerPrograms.recordCreated(app, created.id)
        if (pod != null) {
            withContext(Dispatchers.IO) {
                val current = ContainerPods.read(app.filesDir).let { if (request.newPod) ContainerPods.with(it, pod) else it }
                ContainerPods.save(app.filesDir, ContainerPods.withMember(current, pod.id, created.id))
            }
        }
        // Networks of droidtop's own it joins (docs/SPEC.md 3d, "Networks"): by name only, every container being on
        // the device's network already.
        if (request.networks.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                var current = ContainerNetworks.read(app.filesDir)
                request.networks.forEach { current = ContainerNetworks.withMember(current, it.networkId, created.id, it.aliases) }
                ContainerNetworks.save(app.filesDir, current)
            }
        }
        // An app container keeps its files to itself (the review's default for app containers, docs/SPEC.md 3d).
        runCatching {
            val current = runtime.mounts(created)
            runtime.setMounts(created, current.copy(home = HomeChoice.Private, folders = (current.folders + folders).distinctBy { it.name }))
        }
        // The tools' own default, no restart, unless --restart said otherwise (docs/SPEC.md 3d, "Restart policies").
        withContext(Dispatchers.IO) { ContainerPrograms.setRestartPolicy(app, created.id, request.restart) }
        if (request.args != null || request.env.isNotEmpty() || request.labels.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                ContainerProgram.saveOverrides(app.filesDir, created.id, ContainerProgram.RunOverrides(request.args, request.env, request.labels))
            }
        }
        val program = runCatching { runtime.imageRunConfig(created) }.getOrNull()
            ?.let { ContainerProgram.effectiveConfig(app.filesDir, created.id, it) }?.program().orEmpty()
        val notes = request.publish.map { "$name listens on the device's own port $it; -p $it is what happens anyway" } +
            request.networks.flatMap { it.aliases }.distinct().takeIf { it.isNotEmpty() }
                ?.let { listOf("the names ${it.joinToString(", ")} are kept but do not resolve yet: every container is on the device's network, so reach another by localhost") }
                .orEmpty()
        val start = request.start && program.isNotEmpty()
        if (start) ContainerPrograms.start(app, runtime, created)
        if (request.autoRemove) removeWhenOver(app, runtime, created)
        return Result.success(Made(created, name, notes, start, program.isNotEmpty()))
    }

    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How long `--rm` waits for a container's program to be started (by `start`, after `create`) before giving up. */
    private const val START_WAIT_MS = 10 * 60_000L

    /**
     * `--rm` (docs/SPEC.md 3d): once [container]'s program has been started and has ended, the container is removed
     * by the one removal path. A container whose program is never started in [START_WAIT_MS] is kept, as nothing ran.
     */
    private fun removeWhenOver(app: Context, runtime: ContainerRuntime, container: Container) {
        watchers.launch {
            val id = container.id
            val before = ContainerPrograms.state(id)
            // Started is the job seen running, or the state turned to over since: a quick program can do both between
            // two looks.
            fun ranSince(): Boolean = ContainerPrograms.state(id).let {
                it !== before && (it is ContainerPrograms.State.Ended || it is ContainerPrograms.State.Failed)
            }
            val waitUntil = System.currentTimeMillis() + START_WAIT_MS
            while (!ContainerPrograms.isRunning(id) && !ranSince() && System.currentTimeMillis() < waitUntil) delay(LOOK_MS)
            if (!ContainerPrograms.isRunning(id) && !ranSince()) return@launch
            while (ContainerPrograms.isRunning(id)) delay(LOOK_MS)
            val info = runCatching { runtime.listContainers() }.getOrNull()?.firstOrNull { it.container.id == id } ?: return@launch
            remove(app, runtime, info, force = true)
        }
    }

    private const val LOOK_MS = 250L

    /** A reason a request was not done, worded for a person; the front adds its own name before it. */
    class Refused(message: String) : Exception(message)

    /** Whether [info]'s image names a program of its own to start. */
    suspend fun hasProgram(runtime: ContainerRuntime, info: ContainerInfo): Boolean =
        !runCatching { runtime.imageRunConfig(info.container) }.getOrNull()?.program().isNullOrEmpty()

    /** Starts [info]'s program, as the person's start (`unless-stopped` holds); false when its image names none. */
    suspend fun start(app: Context, runtime: ContainerRuntime, info: ContainerInfo): Boolean {
        if (!hasProgram(runtime, info)) return false
        ContainerPrograms.startedByPerson(app, runtime, info.container)
        return true
    }

    suspend fun stop(app: Context, info: ContainerInfo) = ContainerPrograms.stoppedByPerson(app, info.container.id)

    /** Stops [info]'s program and starts it again; false when its image names none. */
    suspend fun restart(app: Context, runtime: ContainerRuntime, info: ContainerInfo): Boolean {
        if (!hasProgram(runtime, info)) return false
        ContainerPrograms.stop(info.container.id)
        ContainerPrograms.startedByPerson(app, runtime, info.container)
        return true
    }

    /** Whether [info]'s program, or the container itself where the backend starts it, is up. */
    fun isRunning(runtime: ContainerRuntime, info: ContainerInfo): Boolean =
        ContainerPrograms.isRunning(info.container.id) || (info.running && runtime.siblingsNeedStart)

    sealed interface Removal {
        data object Removed : Removal
        data object Primary : Removal
        data object Running : Removal
        data class Failed(val message: String) : Removal
    }

    /** Removes [info] through the one removal path; a running one only with [force], never the desktop's own. */
    suspend fun remove(app: Context, runtime: ContainerRuntime, info: ContainerInfo, force: Boolean): Removal = when {
        info.container.role == ContainerRole.PRIMARY -> Removal.Primary
        isRunning(runtime, info) && !force -> Removal.Running
        else -> runCatching {
            if (info.running && runtime.siblingsNeedStart) runtime.stop(info.container)
            ContainerRemoval.remove(app, runtime, info)
        }.fold(onSuccess = { Removal.Removed }, onFailure = { Removal.Failed(it.message ?: it.javaClass.simpleName) })
    }

    /**
     * Puts [image] in droidtop's one image store; its digest. The store is keyed on the digest, so the tag is resolved
     * first (`crane digest`), as a container's creation does. `docker pull` passed the bare reference and the store
     * refused every pull ("pull needs a resolved digest").
     */
    suspend fun pull(app: Context, image: String): Result<String> = runCatching {
        val digest = Crane.digest(Crane.binaryPath(app), image)
        OciImageStore.of(app).pull(RootfsImage(reference = image, digest = digest))
        digest
    }

    /** The last [tail] lines of [info]'s program log. */
    suspend fun logs(app: Context, info: ContainerInfo, tail: Int): List<String> = withContext(Dispatchers.IO) {
        ContainerProgram.lastLines(ContainerProgram.hostLogFile(app.filesDir, info.container.id), tail)
    }
}
