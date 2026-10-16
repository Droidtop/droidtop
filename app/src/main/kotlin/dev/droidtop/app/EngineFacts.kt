package dev.droidtop.app

import android.content.Context
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerPods
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.EngineInspect
import dev.droidtop.runtime.Pod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What droidtop knows about one container, gathered one way for every front (docs/SPEC.md 3d; Droidtop/tracker#555
 * D13, D14): the command fronts' `inspect` and the Docker Engine API read the same [EngineInspect.Facts], so a client
 * never sees two answers for one container. File reads happen off the main thread.
 */
internal object EngineFacts {
    suspend fun of(app: Context, runtime: ContainerRuntime, info: ContainerInfo, pods: List<Pod>): EngineInspect.Facts {
        val id = info.container.id
        val config = runCatching { runtime.imageRunConfig(info.container) }.getOrNull()
            ?.let { ContainerProgram.effectiveConfig(app.filesDir, id, it) }
        val state = ContainerPrograms.state(id)
        return EngineInspect.Facts(
            id = id,
            name = info.displayName,
            image = info.image ?: "",
            imageDigest = info.digest,
            createdMs = withContext(Dispatchers.IO) { ContainerPrograms.created(app, id) },
            status = status(state, ContainerPrograms.isRunning(id)),
            exitCode = (state as? ContainerPrograms.State.Ended)?.exitCode,
            error = (state as? ContainerPrograms.State.Failed)?.message,
            config = config,
            restart = withContext(Dispatchers.IO) { ContainerPrograms.restartPolicy(app, id) },
            folders = runCatching { runtime.mounts(info.container).folders.map { it.name } }.getOrDefault(emptyList()),
            podId = pods.firstOrNull { id in it.members }?.id,
            labels = withContext(Dispatchers.IO) { ContainerProgram.overrides(app.filesDir, id)?.labels.orEmpty() },
        )
    }

    suspend fun pods(app: Context): List<Pod> = withContext(Dispatchers.IO) { ContainerPods.read(app.filesDir) }

    /** Docker's State.Status: created, running, restarting or exited. */
    fun status(state: ContainerPrograms.State, programUp: Boolean): String = when {
        programUp && state is ContainerPrograms.State.Waiting -> "restarting"
        programUp -> "running"
        state is ContainerPrograms.State.Ended || state is ContainerPrograms.State.Failed -> "exited"
        else -> "created"
    }

    /** What `ps` says a container is doing, from its program first: that is what Docker's STATUS describes. */
    fun statusText(state: ContainerPrograms.State, programUp: Boolean, running: Boolean): String = when {
        programUp && state is ContainerPrograms.State.Waiting -> "Restarting (${state.lastExit})"
        programUp -> "Up"
        state is ContainerPrograms.State.Ended -> "Exited (${state.exitCode})"
        state is ContainerPrograms.State.Failed -> "Exited (couldn't start)"
        running -> "Running (no program)"
        else -> "Created"
    }
}
