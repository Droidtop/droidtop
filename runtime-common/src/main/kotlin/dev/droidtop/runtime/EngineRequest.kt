package dev.droidtop.runtime

/**
 * One request to droidtop's container engine, whichever front asked (docs/SPEC.md 3d "The docker command" and "The
 * podman command", Droidtop/tracker#555 D11): each command front ([EngineCli.DOCKER], [EngineCli.PODMAN], and later the
 * API daemons) reads its own tool's syntax into these, and droidtop answers them one way. A closed set: nothing the
 * container wrote reaches Android as a command, a path or an argv.
 */
sealed interface EngineRequest {
    /** `ps`: containers whose program or container is up; [all] lists every one, [quiet] only names. */
    data class Ps(val all: Boolean, val quiet: Boolean) : EngineRequest

    /** `images`: the images droidtop's store holds. */
    data class Images(val quiet: Boolean) : EngineRequest

    /** `logs`: the last [tail] lines of a container's program log. */
    data class Logs(val container: String, val tail: Int) : EngineRequest

    /** `start`: starts each container's own program. */
    data class Start(val containers: List<String>) : EngineRequest

    /** `stop`: stops each container's own program. */
    data class Stop(val containers: List<String>) : EngineRequest

    /** `pull`: puts [image] in droidtop's one image store. */
    data class Pull(val image: String) : EngineRequest

    /**
     * `run`: a new container from [image], named [name] (droidtop's default name when null), whose own program
     * droidtop then starts, as its page's Start it does. Always detached: the program's output is its log.
     */
    data class Run(
        val image: String,
        val name: String?,
        /** Replaces the image's `Cmd` when given (never its entrypoint). */
        val args: List<String>? = null,
        /** `NAME=VALUE` entries added after the image's own environment. */
        val env: List<String> = emptyList(),
        /** Folders the person already mapped (docs/SPEC.md 3d "Home and folders"), by name; never a host path. */
        val volumes: List<String> = emptyList(),
        /** `--restart`; the tools' own default, `no`, when not given (docs/SPEC.md 3d, "Restart policies"). */
        val restart: ContainerProgram.RestartPolicy = ContainerProgram.RestartPolicy.No,
        /** Podman's `--pod`: the pod the container joins, or a new one to make when [newPod] (`--pod new:NAME`). */
        val pod: String? = null,
        val newPod: Boolean = false,
        /** `-p PORT:PORT`: ports published on the same number, which they are anyway (docs/SPEC.md 3d, D13). */
        val publish: List<ExposedPort> = emptyList(),
        /** False for `create`: the container is made and its program is not started. */
        val start: Boolean = true,
    ) : EngineRequest

    /** `restart`: stops each container's own program, then starts it. */
    data class Restart(val containers: List<String>) : EngineRequest

    /** `inspect`: each container's details as JSON, in the tool's own shape. */
    data class Inspect(val containers: List<String>) : EngineRequest

    /** `rm`: deletes each container; [force] stops a running program first, as Docker's `-f` does. */
    data class Remove(val containers: List<String>, val force: Boolean) : EngineRequest

    /** `exec`: runs [command] in [container], through pipes ([ContainerEnter]), with [env] and in [workdir]. */
    data class Exec(val container: String, val command: List<String>, val env: Map<String, String>, val workdir: String?) : EngineRequest

    /** Not run: [text] goes to stdout when [code] is 0 (help), to stderr otherwise. */
    data class Answer(val text: String, val code: Int) : EngineRequest

    /** `info`: what droidtop's engine is and which kernel features this device gives it (D10). */
    data object Info : EngineRequest

    /** Podman's `pod create`: a new pod named [name] (a name of droidtop's when null). Pods: docs/SPEC.md 3d, D12. */
    data class PodCreate(val name: String?) : EngineRequest

    /** Podman's `pod ps`: every pod; [quiet] prints only ids. */
    data class PodList(val quiet: Boolean) : EngineRequest

    /** Podman's `pod rm`: removes each pod and its stopped containers; [force] stops and removes running ones too. */
    data class PodRemove(val pods: List<String>, val force: Boolean) : EngineRequest

    /** Podman's `pod start`: starts the program of every container in each pod. */
    data class PodStart(val pods: List<String>) : EngineRequest

    /** Podman's `pod stop`: stops the program of every container in each pod. */
    data class PodStop(val pods: List<String>) : EngineRequest
}
