package dev.droidtop.runtime

/**
 * A command front of droidtop's own container engine (docs/SPEC.md 3d "The docker command" and "The podman command",
 * Droidtop/tracker#555 D6c, D11): `docker` ([DOCKER]) and `podman` ([PODMAN]) in every container, thin clients of one
 * engine, not second runtimes. Each front's wrapper runs [ContainerEnter.HELPER] with the front's own [token], so its
 * request travels the one request channel the enter path uses, with the same argument bound and the same stdin,
 * stdout, stderr and exit status. droidtop reads the arguments into an [EngineRequest] from a closed set ([parse]);
 * nothing the container wrote reaches Android as a command, a path or an argv. The fronts share one request model and
 * one answer; their own defaults and output are D12.
 *
 * Docker and Podman words stay here, in the CLI; droidtop's own screens say "app" and "program".
 */
class EngineCli private constructor(val front: Front) {
    /** The two command fronts, each its tool's own name on every container's PATH. */
    enum class Front(val command: String, val daemon: String) {
        DOCKER("docker", "no Docker daemon"),
        PODMAN("podman", "no Podman service"),
    }

    /** The command's name on every container's PATH; no exported command may take it ([ContainerEnter.validName]). */
    val name: String get() = front.command

    /** The token the wrapper hands the helper: droidtop's own for this front, never one of an exported command. */
    val token: String = ContainerLauncher.token("droidtop:${front.command}")

    /** This front's subcommands droidtop does not answer yet, said as such rather than as unknown. */
    private val later: Set<String> = LATER + if (front == Front.PODMAN) setOf("generate", "play", "kube") else emptySet()

    val usage: String = buildList {
        add("Usage: ${front.command} COMMAND")
        add("")
        add("droidtop's containers, through droidtop's own engine (${front.daemon}):")
        add("  ps [-a] [-q]                 List containers (running ones without -a)")
        add("  images [-q]                  List the images droidtop holds")
        add("  logs [-n N] CONTAINER        Show the last lines of a container's program")
        add("  start CONTAINER...           Start a container's own program")
        add("  stop CONTAINER...            Stop a container's own program")
        add("  exec [-e K=V] [-w DIR] CONTAINER COMMAND [ARG...]")
        add("                               Run a command in another container (pipes, no terminal)")
        add("  pull IMAGE                   Download an image into droidtop's store")
        add("  run [-d] [--name NAME] IMAGE Make a container from an image and start its program")
        add("  create [--name NAME] IMAGE   Make a container from an image without starting its program")
        add("  restart CONTAINER...         Stop a container's own program and start it again")
        add("  inspect CONTAINER...         A container's details as JSON")
        add("  rm [-f] CONTAINER...         Delete a container (-f stops its program first)")
        add("  info                         The engine, and the kernel features this device gives it")
        if (front == Front.PODMAN) add("  pod create|ps|rm|start|stop  Pods: a named group of containers started and stopped together")
        add("  network create|ls|rm         Networks: names containers on one can reach each other by")
        add("")
        add("run also takes [-e NAME=VALUE] [-v FOLDER] IMAGE [COMMAND...]; FOLDER is a folder you mapped in Containers.")
        add("run takes --restart no|on-failure[:N]|always|unless-stopped (no by default).")
        if (front == Front.PODMAN) add("run takes --pod POD to join a pod, or --pod new:NAME to make one.")
        add("-p takes a port on its own number (-p 80:80), which is what happens anyway: a port is the device's own.")
        add("--rm removes the container once its program has run and ended.")
        add("run takes -w FOLDER (an absolute folder) and --entrypoint PROGRAM (\"\" clears the image's).")
        add("Not yet: -u. Options that need a kernel feature (-p 8080:80, --memory, --network,")
        add("--hostname, --tmpfs, ...) say which one and whether this device has it; see ${front.command} info.")
        add("Containers here share the device's network and processes: a port is the device's own.")
    }.joinToString("\n") + "\n"

    /**
     * [args] as a request. [features] is this device's (D10), read only when a `run` names an option from
     * [EngineOptions] ([EngineOptions.mentioned]), so the sentence for it can say what the device lacks.
     */
    fun parse(args: List<String>, features: EngineFeatures? = null): EngineRequest {
        val sub = args.firstOrNull() ?: return EngineRequest.Answer(usage, 0)
        val rest = args.drop(1)
        return when (sub) {
            "-h", "--help", "help" -> EngineRequest.Answer(usage, 0)
            "ps" -> ps(rest)
            "images" -> flags(rest, setOf("-q", "--quiet"))?.let { bad -> error("images", bad) }
                ?: EngineRequest.Images(quiet = rest.isNotEmpty())
            "logs" -> logs(rest)
            "start", "stop" -> {
                rest.firstOrNull { it.startsWith("-") }?.let { return error(sub, it) }
                if (rest.isEmpty()) return EngineRequest.Answer("${front.command} $sub: name at least one container\n", 1)
                if (sub == "start") EngineRequest.Start(rest) else EngineRequest.Stop(rest)
            }
            "exec" -> exec(rest)
            "pull" -> pull(rest)
            "run" -> run(rest, features)
            "create" -> run(rest, features, sub = "create")
            "restart" -> {
                rest.firstOrNull { it.startsWith("-") }?.let { return error(sub, it) }
                if (rest.isEmpty()) return EngineRequest.Answer("${front.command} restart: name at least one container\n", 1)
                EngineRequest.Restart(rest)
            }
            "inspect" -> inspect(rest)
            "pod" -> if (front == Front.PODMAN) pod(rest) else EngineRequest.Answer("docker: 'pod' is not a docker command. See 'docker --help'\n", 1)
            "network" -> network(rest)
            "info" -> flags(rest, emptySet())?.let { bad -> error("info", bad) } ?: EngineRequest.Info
            "rm" -> remove(rest)
            in later -> EngineRequest.Answer("${front.command} $sub: not built in droidtop yet\n", 1)
            in NEVER -> EngineRequest.Answer("${front.command} $sub: droidtop's containers do not have this (${front.daemon})\n", 1)
            else -> EngineRequest.Answer("${front.command}: '$sub' is not a ${front.command} command. See '${front.command} --help'\n", 1)
        }
    }

    /** The first argument of [args] that is not one of [allowed], or null when all are. */
    private fun flags(args: List<String>, allowed: Set<String>): String? = args.firstOrNull { it !in allowed }

    private fun error(sub: String, arg: String) = EngineRequest.Answer(
        if (arg.startsWith("-")) "${front.command} $sub: unknown flag: $arg\n" else "${front.command} $sub: unexpected argument: $arg\n",
        1,
    )

    /** `ps [-a] [-q] [--format TEMPLATE]`; a template naming what droidtop cannot fill is refused here, not printed half. */
    private fun ps(args: List<String>): EngineRequest {
        var all = false
        var quiet = false
        var format: String? = null
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-a", "--all" -> all = true
                "-q", "--quiet" -> quiet = true
                "-aq", "-qa" -> {
                    all = true
                    quiet = true
                }
                "--format" -> format = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} ps: --format needs a template\n", 1)
                else -> if (a.startsWith("--format=")) format = a.substringAfter('=') else return error("ps", a)
            }
            i++
        }
        format?.let { template -> psTemplateProblem(template)?.let { return EngineRequest.Answer("${front.command} ps: $it\n", 1) } }
        return EngineRequest.Ps(all, quiet, format)
    }

    private fun logs(args: List<String>): EngineRequest {
        var tail = DEFAULT_TAIL
        var container: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val value = when {
                a == "-n" || a == "--tail" -> args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} logs: $a needs a number\n", 1)
                a.startsWith("--tail=") -> a.substringAfter('=')
                a == "-f" || a == "--follow" -> return EngineRequest.Answer("${front.command} logs: following is not built in droidtop yet\n", 1)
                a.startsWith("-") -> return error("logs", a)
                container == null -> { container = a; null }
                else -> return error("logs", a)
            }
            if (value != null) {
                tail = if (value == "all") MAX_TAIL else value.toIntOrNull()?.takeIf { it >= 0 } ?: return EngineRequest.Answer("${front.command} logs: not a number of lines: $value\n", 1)
            }
            i++
        }
        return container?.let { EngineRequest.Logs(it, tail.coerceAtMost(MAX_TAIL)) } ?: EngineRequest.Answer("${front.command} logs: name a container\n", 1)
    }

    private fun exec(args: List<String>): EngineRequest {
        val env = linkedMapOf<String, String>()
        var workdir: String? = null
        var i = 0
        while (i < args.size && args[i].startsWith("-")) {
            when (val a = args[i]) {
                "-i", "--interactive" -> Unit
                // No terminal through the request's pipes: a -t is taken and the command runs on pipes all the same.
                "-t", "--tty", "-it", "-ti" -> Unit
                "-e", "--env" -> {
                    val kv = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} exec: $a needs NAME=VALUE\n", 1)
                    env(kv)?.let { (k, v) -> env[k] = v } ?: return EngineRequest.Answer("${front.command} exec: not NAME=VALUE: $kv\n", 1)
                }
                "-w", "--workdir" -> workdir = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} exec: $a needs a folder\n", 1)
                else -> return error("exec", a)
            }
            i++
        }
        val container = args.getOrNull(i) ?: return EngineRequest.Answer("${front.command} exec: name a container and a command\n", 1)
        val command = args.drop(i + 1)
        if (command.isEmpty()) return EngineRequest.Answer("${front.command} exec: name a command to run in $container\n", 1)
        return EngineRequest.Exec(container, command, env, workdir)
    }

    /**
     * An image reference droidtop hands its puller: a registry host, path, tag and digest made of the characters a
     * reference uses, and nothing a shell or a flag would read. Pure.
     */
    private val REFERENCE = Regex("[a-z0-9][a-z0-9._:/@-]{0,254}", RegexOption.IGNORE_CASE)

    fun validReference(reference: String): Boolean = REFERENCE.matches(reference) && !reference.contains("..")

    private fun pull(args: List<String>): EngineRequest {
        args.firstOrNull { it.startsWith("-") }?.let { return error("pull", it) }
        val image = args.singleOrNull() ?: return EngineRequest.Answer("${front.command} pull: name one image\n", 1)
        if (!validReference(image)) return EngineRequest.Answer("${front.command} pull: not an image reference: $image\n", 1)
        return EngineRequest.Pull(image)
    }

    /**
     * `inspect`: containers by name or id. `--type container` is what it answers; `-f`/`--format` (Go templates) and
     * images are not built yet.
     */
    private fun inspect(args: List<String>): EngineRequest {
        val names = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--type" || a.startsWith("--type=") -> {
                    val type = if (a == "--type") args.getOrNull(++i) else a.substringAfter('=')
                    if (type != "container") return EngineRequest.Answer("${front.command} inspect: --type ${type ?: ""} is not built in droidtop yet; it inspects containers\n", 1)
                }
                a == "-f" || a == "--format" || a.startsWith("--format=") ->
                    return EngineRequest.Answer("${front.command} inspect: --format is not built in droidtop yet; it prints the whole JSON\n", 1)
                a.startsWith("-") -> return error("inspect", a)
                else -> names += a
            }
            i++
        }
        if (names.isEmpty()) return EngineRequest.Answer("${front.command} inspect: name at least one container\n", 1)
        return EngineRequest.Inspect(names)
    }

    /**
     * `-p` as droidtop can honour it (docs/SPEC.md 3d, D13): every container listens on the device's own ports, so a
     * port published on its own number (`80:80`, `0.0.0.0:80:80`, `/udp` too) is what happens anyway and is taken. A
     * port published on another number, or on a number Docker would choose, needs network namespaces to remap; that is
     * answered from [EngineOptions], so it says whether this device has them. One address other than all of them is
     * not built. Pure.
     */
    internal fun publish(spec: String, features: EngineFeatures?, sub: String): Pair<ExposedPort?, EngineRequest.Answer?> {
        val protocol = spec.substringAfter('/', ExposedPort.TCP)
        val parts = spec.substringBefore('/').split(':')
        val (address, host, container) = when (parts.size) {
            1 -> Triple(null, null, parts[0])
            2 -> Triple(null, parts[0], parts[1])
            3 -> Triple(parts[0], parts[1], parts[2])
            else -> return null to EngineRequest.Answer("${front.command} $sub: not a port: $spec\n", 1)
        }
        val number = container.toIntOrNull()?.takeIf { it in 1..65535 }
        if (number == null || protocol !in setOf(ExposedPort.TCP, "udp") || (host != null && host.isNotEmpty() && host.toIntOrNull() == null)) {
            return null to EngineRequest.Answer("${front.command} $sub: not a port: $spec\n", 1)
        }
        if (address != null && address.isNotEmpty() && address != "0.0.0.0" && address != "::") {
            return null to EngineRequest.Answer("${front.command} $sub: -p on one address ($address) is not built in droidtop yet\n", 1)
        }
        if (host.isNullOrEmpty() || host.toInt() != number) {
            return null to EngineRequest.Answer(EngineOptions.refusal("${front.command} $sub", "-p", features) + " (here a port is the device's own: -p $number:$number is what happens)\n", 1)
        }
        return ExposedPort(number, protocol) to null
    }

    private fun run(args: List<String>, features: EngineFeatures?, sub: String = "run"): EngineRequest {
        val publish = mutableListOf<ExposedPort>()
        var name: String? = null
        val envs = mutableListOf<String>()
        val volumes = mutableListOf<String>()
        var restart: ContainerProgram.RestartPolicy = ContainerProgram.RestartPolicy.No
        var pod: String? = null
        var autoRemove = false
        var detach = false
        val labels = linkedMapOf<String, String>()
        var entrypoint: List<String>? = null
        var workdir: String? = null
        // `--entrypoint ""` clears the image's entrypoint, as Docker's does (Droidtop/tracker#715).
        fun entrypointOf(value: String) = if (value.isEmpty()) emptyList() else listOf(value)
        var i = 0
        while (i < args.size && args[i].startsWith("-")) {
            when (val a = args[i]) {
                "-w", "--workdir" -> {
                    val dir = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: $a needs a folder\n", 1)
                    if (!validWorkdir(dir)) return EngineRequest.Answer("${front.command} $sub: $a needs an absolute folder, not $dir\n", 1)
                    workdir = dir
                }
                "--entrypoint" -> entrypoint = entrypointOf(args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: --entrypoint needs a program\n", 1))
                "-d", "--detach", "-dit", "-itd", "-dt", "-td", "-di", "-id" -> detach = true
                "-i", "-t", "-it", "-ti", "--interactive", "--tty" -> Unit
                "--name" -> name = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: --name needs a name\n", 1)
                "-e", "--env" -> {
                    val kv = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: $a needs NAME=VALUE\n", 1)
                    env(kv) ?: return EngineRequest.Answer("${front.command} $sub: not NAME=VALUE: $kv\n", 1)
                    envs += kv
                }
                "-v", "--volume" -> {
                    val spec = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: $a needs a folder\n", 1)
                    volumes += volume(spec) ?: return volumeRefused(spec, sub)
                }
                "--rm" -> autoRemove = true
                "-l", "--label" -> {
                    val kv = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: $a needs KEY=VALUE\n", 1)
                    labels[kv.substringBefore('=')] = kv.substringAfter('=', "")
                }
                "-p", "--publish" -> {
                    val spec = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: $a needs a port\n", 1)
                    val (port, refused) = publish(spec, features, sub)
                    refused?.let { return it }
                    publish += port!!
                }
                "--pod" -> {
                    if (front != Front.PODMAN) return error(sub, a)
                    pod = args.getOrNull(++i) ?: return EngineRequest.Answer("podman $sub: --pod needs a pod\n", 1)
                }
                "--restart" -> {
                    val words = args.getOrNull(++i) ?: return EngineRequest.Answer("${front.command} $sub: --restart needs a policy\n", 1)
                    restart = ContainerProgram.RestartPolicy.parse(words) ?: return restartRefused(words, sub)
                }
                else -> when {
                    a.startsWith("--publish=") -> {
                        val (port, refused) = publish(a.substringAfter('='), features, sub)
                        refused?.let { return it }
                        publish += port!!
                    }
                    EngineOptions.option(a) != null -> {
                        // One table for every option that needs a kernel feature (docs/SPEC.md 3, D10).
                        val option = EngineOptions.option(a)!!
                        val value = when {
                            a.contains('=') -> a.substringAfter('=')
                            EngineOptions.takesValue(option) -> args.getOrNull(++i)
                            else -> null
                        }
                        if (!EngineOptions.asIs(option, value)) {
                            return EngineRequest.Answer(EngineOptions.refusal("${front.command} $sub", option, features) + "\n", 1)
                        }
                    }
                    a.startsWith("--entrypoint=") -> entrypoint = entrypointOf(a.substringAfter('='))
                    a.startsWith("--workdir=") -> {
                        val dir = a.substringAfter('=')
                        if (!validWorkdir(dir)) return EngineRequest.Answer("${front.command} $sub: --workdir needs an absolute folder, not $dir\n", 1)
                        workdir = dir
                    }
                    a.startsWith("--name=") -> name = a.substringAfter('=')
                    a.startsWith("--label=") -> a.substringAfter('=').let { kv -> labels[kv.substringBefore('=')] = kv.substringAfter('=', "") }
                    a.startsWith("--env=") -> {
                        val kv = a.substringAfter('=')
                        env(kv) ?: return EngineRequest.Answer("${front.command} $sub: not NAME=VALUE: $kv\n", 1)
                        envs += kv
                    }
                    a.startsWith("--pod=") && front == Front.PODMAN -> pod = a.substringAfter('=')
                    a.startsWith("--restart=") -> {
                        val words = a.substringAfter('=')
                        restart = ContainerProgram.RestartPolicy.parse(words) ?: return restartRefused(words, sub)
                    }
                    a.startsWith("--volume=") -> {
                        val spec = a.substringAfter('=')
                        volumes += volume(spec) ?: return volumeRefused(spec, sub)
                    }
                    a in NOT_YET_RUN || NOT_YET_RUN.any { a.startsWith("$it=") } ->
                        return EngineRequest.Answer("${front.command} $sub: $a is not built in droidtop yet\n", 1)
                    else -> return error(sub, a)
                }
            }
            i++
        }
        val image = args.getOrNull(i) ?: return EngineRequest.Answer("${front.command} $sub: name an image\n", 1)
        if (!validReference(image)) return EngineRequest.Answer("${front.command} $sub: not an image reference: $image\n", 1)
        if (name != null && name.isBlank()) return EngineRequest.Answer("${front.command} $sub: --name needs a name\n", 1)
        val command = args.drop(i + 1).takeIf { it.isNotEmpty() }
        val newPod = pod?.startsWith("new:") == true
        val podName = if (newPod) pod!!.removePrefix("new:") else pod
        if (podName != null && !ContainerPods.validName(podName)) return EngineRequest.Answer("podman $sub: not a pod name: $podName\n", 1)
        if (labels.keys.any { it.isBlank() }) return EngineRequest.Answer("${front.command} $sub: a label needs a key\n", 1)
        if (autoRemove && restart != ContainerProgram.RestartPolicy.No) {
            return EngineRequest.Answer("${front.command} $sub: --restart and --rm cannot be used together\n", 1)
        }
        return EngineRequest.Run(
            image, name?.trim(), command, envs, volumes, restart, podName, newPod, publish,
            start = sub == "run", labels = labels, autoRemove = autoRemove, entrypoint = entrypoint, workdir = workdir,
            // `create` starts nothing to attach to.
            detach = detach || sub != "run",
        )
    }

    /** Podman's `pod` subcommands droidtop answers; the rest say they are not built yet. */
    private fun pod(args: List<String>): EngineRequest {
        val sub = args.firstOrNull() ?: return EngineRequest.Answer("podman pod: name a subcommand: create, ps, rm, start, stop\n", 1)
        val rest = args.drop(1)
        return when (sub) {
            "create" -> {
                var name: String? = null
                var i = 0
                while (i < rest.size) {
                    val a = rest[i]
                    when {
                        a == "--name" || a == "-n" -> name = rest.getOrNull(++i) ?: return EngineRequest.Answer("podman pod create: $a needs a name\n", 1)
                        a.startsWith("--name=") -> name = a.substringAfter('=')
                        a == "-p" || a == "--publish" || a.startsWith("--publish=") ->
                            return EngineRequest.Answer("podman pod create: $a is not built in droidtop yet\n", 1)
                        else -> return error("pod create", a)
                    }
                    i++
                }
                if (name != null && !ContainerPods.validName(name)) return EngineRequest.Answer("podman pod create: not a pod name: $name\n", 1)
                EngineRequest.PodCreate(name)
            }
            "ps", "ls", "list" -> flags(rest, setOf("-q", "--quiet"))?.let { bad -> error("pod ps", bad) }
                ?: EngineRequest.PodList(quiet = rest.isNotEmpty())
            "rm" -> {
                val force = rest.any { it == "-f" || it == "--force" }
                rest.firstOrNull { it.startsWith("-") && it != "-f" && it != "--force" }?.let { return error("pod rm", it) }
                val names = rest.filterNot { it.startsWith("-") }
                if (names.isEmpty()) EngineRequest.Answer("podman pod rm: name at least one pod\n", 1) else EngineRequest.PodRemove(names, force)
            }
            "start", "stop" -> {
                rest.firstOrNull { it.startsWith("-") }?.let { return error("pod $sub", it) }
                if (rest.isEmpty()) return EngineRequest.Answer("podman pod $sub: name at least one pod\n", 1)
                if (sub == "start") EngineRequest.PodStart(rest) else EngineRequest.PodStop(rest)
            }
            else -> EngineRequest.Answer("podman pod $sub: not built in droidtop yet\n", 1)
        }
    }

    /**
     * `network create NAME`, `network ls|list [-q]`, `network rm NAME...`: the same networks the engine API keeps
     * (Droidtop/tracker#555, rig 2011: the fronts refused `network` though the API had it). `create` takes
     * `--driver bridge`, the one kind droidtop has; anything else is said as not built.
     */
    private fun network(args: List<String>): EngineRequest {
        val cmd = "${front.command} network"
        val sub = args.firstOrNull() ?: return EngineRequest.Answer("$cmd: name a subcommand: create, ls, rm\n", 1)
        val rest = args.drop(1)
        return when (sub) {
            "create" -> {
                var name: String? = null
                var i = 0
                while (i < rest.size) {
                    val a = rest[i]
                    when {
                        a == "-d" || a == "--driver" -> {
                            val driver = rest.getOrNull(++i) ?: return EngineRequest.Answer("$cmd create: $a needs a driver\n", 1)
                            if (driver != "bridge") return EngineRequest.Answer("$cmd create: the $driver driver is not built in droidtop yet\n", 1)
                        }
                        a.startsWith("--driver=") -> if (a.substringAfter('=') != "bridge") {
                            return EngineRequest.Answer("$cmd create: the ${a.substringAfter('=')} driver is not built in droidtop yet\n", 1)
                        }
                        a.startsWith("-") -> return EngineRequest.Answer("$cmd create: $a is not built in droidtop yet\n", 1)
                        name == null -> name = a
                        else -> return error("network create", a)
                    }
                    i++
                }
                name?.let { EngineRequest.NetworkCreate(it) } ?: EngineRequest.Answer("$cmd create: name the network\n", 1)
            }
            "ls", "list" -> flags(rest, setOf("-q", "--quiet"))?.let { bad -> error("network ls", bad) }
                ?: EngineRequest.NetworkList(quiet = rest.isNotEmpty())
            "rm", "remove" -> {
                rest.firstOrNull { it.startsWith("-") }?.let { return error("network rm", it) }
                if (rest.isEmpty()) EngineRequest.Answer("$cmd rm: name at least one network\n", 1) else EngineRequest.NetworkRemove(rest)
            }
            else -> EngineRequest.Answer("$cmd $sub: not built in droidtop yet\n", 1)
        }
    }

    /** Networks as each tool lists them: Docker with its scope, Podman without. */
    fun formatNetworks(networks: List<Network>, quiet: Boolean): String {
        if (quiet) return networks.joinToString("") { it.id.take(12) + "\n" }
        fun driver(n: Network) = when (n.name) { "host" -> "host"; "none" -> "null"; else -> "bridge" }
        return if (front == Front.DOCKER) {
            table(listOf("NETWORK ID", "NAME", "DRIVER", "SCOPE"), networks.map { listOf(it.id.take(12), it.name, driver(it), "local") })
        } else {
            table(listOf("NETWORK ID", "NAME", "DRIVER"), networks.map { listOf(it.id.take(12), it.name, driver(it)) })
        }
    }

    /** `run` flags droidtop knows of and has not built: said as such, not as unknown. */
    private val NOT_YET_RUN = setOf("-u", "--user")

    private fun restartRefused(words: String, sub: String) = EngineRequest.Answer(
        "${front.command} $sub: --restart takes no, on-failure[:N], always or unless-stopped, not $words\n",
        1,
    )

    /** A folder name droidtop mapped (D3), as `ExtraMount.name` takes it. */
    private val FOLDER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,63}")

    /**
     * The folder name a `-v` names: `NAME`, or `NAME:<where droidtop mounts it>` (`ContainerLayout.EXTRA_MOUNTS_DIR`/NAME,
     * with an optional `:ro` or `:rw`). Null for a host path or another target, which droidtop does not do. Pure.
     */
    internal fun volume(spec: String): String? {
        val parts = spec.split(':')
        val name = parts[0]
        if (!FOLDER_NAME.matches(name) || name.contains("..")) return null
        return when {
            parts.size == 1 -> name
            parts.size in 2..3 && parts[1] == "${ContainerLayout.EXTRA_MOUNTS_DIR}/$name" && (parts.size == 2 || parts[2] in setOf("ro", "rw")) -> name
            else -> null
        }
    }

    private fun volumeRefused(spec: String, sub: String) = EngineRequest.Answer(
        "${front.command} $sub: -v takes the name of a folder you mapped in Containers (it appears at " +
            "${ContainerLayout.EXTRA_MOUNTS_DIR}/<name>); $spec is not one droidtop can mount\n",
        1,
    )

    private fun remove(args: List<String>): EngineRequest {
        val force = args.any { it == "-f" || it == "--force" }
        args.firstOrNull { it.startsWith("-") && it != "-f" && it != "--force" }?.let { return error("rm", it) }
        val names = args.filterNot { it.startsWith("-") }
        if (names.isEmpty()) return EngineRequest.Answer("${front.command} rm: name at least one container\n", 1)
        return EngineRequest.Remove(names, force)
    }

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private fun env(kv: String): Pair<String, String>? {
        val name = kv.substringBefore('=')
        if (!kv.contains('=') || !ENV_NAME.matches(name)) return null
        return name to kv.substringAfter('=')
    }

    /** `info`'s answer: droidtop's engine, the backend running now if the desktop is up, and [features]. Pure. */
    fun formatInfo(features: EngineFeatures, backend: ContainerBackend?): String = buildString {
        append("Engine: droidtop's own (${front.daemon})\n")
        append(
            "Backend: " + when (backend) {
                ContainerBackend.DROIDSPACES -> "root (namespaces and cgroups through the root helper)"
                ContainerBackend.PROOT -> "no root (proot)"
                null -> "the desktop is not running"
            } + "\n",
        )
        append("Kernel features (this boot):\n")
        EngineOptions.describe(features).forEach { append(it).append('\n') }
    }

    /** One `ps` row as droidtop knows it: [createdMs] is null for a container made before droidtop kept the time. */
    data class PsRow(
        val id: String,
        val name: String,
        val image: String,
        val status: String,
        val up: Boolean,
        val ports: List<ExposedPort>,
        val command: List<String> = emptyList(),
        val createdMs: Long? = null,
    )

    /**
     * `ps` in this front's own columns (docs/SPEC.md 3d, "Each tool's output"; D12): CONTAINER ID, IMAGE, COMMAND,
     * CREATED, STATUS, PORTS, NAMES, as both tools print them. Docker shortens Docker Hub's images and quotes the
     * command; Podman keeps the full image name and leaves the command bare. `-q` prints ids, as both tools do.
     */
    fun formatPs(rows: List<PsRow>, command: EngineRequest.Ps, nowMs: Long = System.currentTimeMillis()): String {
        val shown = if (command.all) rows else rows.filter { it.up }
        if (command.quiet) return shown.joinToString("") { it.id + "\n" }
        val format = command.format?.trim()?.takeIf { it.isNotEmpty() && it != "table" }
        if (format != null) return formatPsTemplate(shown, format, nowMs)
        return table(
            listOf("CONTAINER ID", "IMAGE", "COMMAND", "CREATED", "STATUS", "PORTS", "NAMES"),
            shown.map {
                listOf(
                    it.id,
                    if (front == Front.DOCKER) shortImage(it.image) else fullImage(it.image),
                    commandColumn(it.command),
                    it.createdMs?.let { ms -> ago(nowMs - ms) } ?: "",
                    it.status,
                    it.ports.joinToString(", "),
                    it.name,
                )
            },
        )
    }

    /**
     * `ps --format` (rig 2026-10-16: not built). `json` prints each container as one JSON object a line; `table` before a
     * template prints a header row and aligns the tab-separated columns; any other template is filled once a container.
     * The fields are Go template fields, `{{.Names}}`, as both tools name them, and `\t` and `\n` are a tab and a new
     * line, as the tools read them. A field droidtop does not keep (labels, mounts, networks, size) is empty.
     */
    internal fun formatPsTemplate(rows: List<PsRow>, format: String, nowMs: Long): String {
        if (format == "json") {
            return rows.joinToString("") { row ->
                kotlinx.serialization.json.buildJsonObject {
                    PS_FIELDS.keys.forEach { field -> put(field, kotlinx.serialization.json.JsonPrimitive(psField(row, field, nowMs))) }
                }.toString() + "\n"
            }
        }
        val isTable = format.startsWith("table ")
        val template = (if (isTable) format.removePrefix("table ") else format).replace("\\t", "\t").replace("\\n", "\n")
        fun fill(value: (String) -> String) = PS_TEMPLATE_FIELD.replace(template) { value(it.groupValues[1]) }
        val lines = rows.map { row -> fill { psField(row, it, nowMs) } }
        if (!isTable) return lines.joinToString("") { it + "\n" }
        val header = fill { PS_FIELDS[it].orEmpty() }
        return table(header.split('\t'), lines.map { line -> line.split('\t').let { cells -> header.split('\t').indices.map { cells.getOrElse(it) { "" } } } })
    }

    /** What is wrong with a `--format` template droidtop cannot fill, or null when it can. */
    internal fun psTemplateProblem(format: String): String? {
        val body = format.trim().let { if (it == "json" || it == "table") return null else it.removePrefix("table ") }
        val actions = Regex("""\{\{(.*?)\}\}""").findAll(body).map { it.groupValues[1].trim() }.toList()
        actions.forEach { action ->
            val field = Regex("""^\.([A-Za-z]+)$""").find(action)?.groupValues?.get(1)
                ?: return "--format: {{$action}} is not built in droidtop yet; it fills fields such as {{.Names}}"
            if (field !in PS_FIELDS) return "--format: there is no field .$field; the fields are ${PS_FIELDS.keys.joinToString(", ") { ".$it" }}"
        }
        return null
    }

    private fun psField(row: PsRow, field: String, nowMs: Long): String = when (field) {
        "ID" -> row.id
        "Image" -> if (front == Front.DOCKER) shortImage(row.image) else fullImage(row.image)
        "Command" -> commandColumn(row.command)
        "CreatedAt" -> row.createdMs?.let { createdAt(it) }.orEmpty()
        "RunningFor" -> row.createdMs?.let { ago(nowMs - it) }.orEmpty()
        "Ports" -> row.ports.joinToString(", ")
        "State" -> if (row.up) "running" else if (row.status.startsWith("Created")) "created" else "exited"
        "Status" -> row.status
        "Names" -> row.name
        else -> ""
    }

    /** When a container was made, as Docker's CreatedAt prints it: "2026-10-16 13:04:49 +0000 UTC". */
    private fun createdAt(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms)) + " UTC"

    /** The COMMAND column: Docker's quoted and cut at 20 with an ellipsis, Podman's bare and cut with three dots. */
    internal fun commandColumn(argv: List<String>): String {
        val line = argv.joinToString(" ")
        return if (front == Front.DOCKER) {
            "\"" + (if (line.length > COMMAND_WIDTH) line.take(COMMAND_WIDTH - 1) + "\u2026" else line) + "\""
        } else {
            if (line.length > COMMAND_WIDTH) line.take(COMMAND_WIDTH - 3) + "..." else line
        }
    }

    /**
     * `images` in this front's own columns: REPOSITORY, TAG, IMAGE ID, CREATED (when the image was built, from its
     * config), SIZE. Docker shortens Docker Hub's names (`nginx`); Podman keeps them whole (`docker.io/library/nginx`)
     * and spaces its sizes ("21.4 MB").
     */
    fun formatImages(images: List<OciImageStore.Entry>, command: EngineRequest.Images, nowMs: Long = System.currentTimeMillis()): String {
        if (command.quiet) return images.joinToString("") { it.digest.substringAfter(':').take(12) + "\n" }
        return table(
            listOf("REPOSITORY", "TAG", "IMAGE ID", "CREATED", "SIZE"),
            images.map {
                val repo = if (front == Front.DOCKER) repository(it.reference) else fullRepository(it.reference)
                // When the image was built, from its config; empty when the config does not say.
                val created = it.createdMs?.let { ms -> ago(nowMs - ms) }.orEmpty()
                listOf(repo, tag(it.reference), it.digest.substringAfter(':').take(12), created, size(it.sizeBytes))
            },
        )
    }

    /** An image as Docker's `ps` shows it: Docker Hub's prefix dropped, the tag kept. */
    internal fun shortImage(reference: String): String =
        reference.removePrefix("docker.io/").removePrefix("library/")

    /** A repository as Podman names it: the registry kept, Docker Hub's official images under `library/`. */
    internal fun fullRepository(reference: String): String {
        val name = reference.substringBefore('@').let { r -> if (r.substringAfterLast('/').contains(':')) r.substringBeforeLast(':') else r }
        val first = name.substringBefore('/')
        return when {
            !name.contains('/') -> "docker.io/library/$name"
            first.contains('.') || first.contains(':') || first == "localhost" -> name
            else -> "docker.io/$name"
        }
    }

    /** One pod as `pod ps` lists it: its containers that still exist, and how many of their programs run. */
    data class PodRow(val pod: Pod, val containers: Int, val running: Int)

    /**
     * `pod ps` as Podman prints it: POD ID (the first 12 of the id), NAME, STATUS, CREATED, # OF CONTAINERS. A pod with
     * no containers is Created, one with every program up Running, some Degraded, none Exited. `-q` prints ids.
     */
    fun formatPods(rows: List<PodRow>, quiet: Boolean, nowMs: Long = System.currentTimeMillis()): String {
        if (quiet) return rows.joinToString("") { it.pod.id.take(12) + "\n" }
        return table(
            listOf("POD ID", "NAME", "STATUS", "CREATED", "# OF CONTAINERS"),
            rows.map { row ->
                listOf(row.pod.id.take(12), row.pod.name, ContainerPods.status(row.containers, row.running), ago(nowMs - row.pod.createdMs), row.containers.toString())
            },
        )
    }

    /** An image as Podman's `ps` shows it: the full repository, then the tag or the digest. */
    internal fun fullImage(reference: String): String {
        if (reference.isEmpty()) return reference
        val digest = reference.substringAfter('@', "")
        return fullRepository(reference) + if (digest.isNotEmpty()) "@$digest" else ":" + tag(reference)
    }

    /** "2 minutes ago", as both tools word an age. */
    internal fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 2 -> "Less than a second ago"
            s < 60 -> "$s seconds ago"
            s < 120 -> "About a minute ago"
            s < 3600 -> "${s / 60} minutes ago"
            s < 7200 -> "About an hour ago"
            s < 172_800 -> "${s / 3600} hours ago"
            s < 1_209_600 -> "${s / 86_400} days ago"
            else -> "${s / 604_800} weeks ago"
        }
    }

    /** `docker.io/library/nginx:1.27` reads as `nginx`; another registry keeps its name. */
    internal fun repository(reference: String): String {
        val name = reference.substringBefore('@').let { r -> if (r.substringAfterLast('/').contains(':')) r.substringBeforeLast(':') else r }
        return name.removePrefix("docker.io/").removePrefix("library/")
    }

    internal fun tag(reference: String): String {
        if (reference.contains('@')) return "<none>"
        val last = reference.substringAfterLast('/')
        return if (last.contains(':')) last.substringAfter(':') else "latest"
    }

    private fun size(bytes: Long): String {
        val gap = if (front == Front.PODMAN) " " else ""
        return when {
            bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f%sGB", bytes / 1e9, gap)
            bytes >= 1_000_000 -> if (front == Front.PODMAN) {
                String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6)
            } else {
                String.format(java.util.Locale.ROOT, "%.0fMB", bytes / 1e6)
            }
            else -> String.format(java.util.Locale.ROOT, "%.0f%skB", bytes / 1e3, gap)
        }
    }

    /** Columns padded to their widest cell plus three spaces, as Docker prints them. */
    private fun table(header: List<String>, rows: List<List<String>>): String {
        val all = listOf(header) + rows
        val widths = header.indices.map { c -> all.maxOf { it[c].length } }
        return all.joinToString("") { row ->
            row.mapIndexed { c, cell -> if (c == row.lastIndex) cell else cell.padEnd(widths[c] + 3) }.joinToString("").trimEnd() + "\n"
        }
    }

    /** This front's wrapper on every container's PATH. */
    fun wrapperScript(): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# droidtop's ${front.command} command (docs/SPEC.md 3d). Installed by droidtop.")
        appendLine("exec sh ${ContainerEnter.HELPER} $token \"\$@\"")
    }

    companion object {
        /** A working directory droidtop takes: an absolute path with no line breaks, as Docker requires one. Pure. */
        fun validWorkdir(dir: String): Boolean = dir.startsWith("/") && dir.none { it == '\n' || it == '\r' || it == '\u0000' }

        /** `ps --format`'s fields, with the column each heads under `table`, as Docker names them. */
        private val PS_FIELDS = linkedMapOf(
            "ID" to "CONTAINER ID", "Image" to "IMAGE", "Command" to "COMMAND", "CreatedAt" to "CREATED AT",
            "RunningFor" to "CREATED", "Ports" to "PORTS", "State" to "STATE", "Status" to "STATUS", "Names" to "NAMES",
            "Labels" to "LABELS", "Mounts" to "MOUNTS", "Networks" to "NETWORKS", "Size" to "SIZE",
        )
        private val PS_TEMPLATE_FIELD = Regex("""\{\{\s*\.([A-Za-z]+)\s*\}\}""")

        /** The COMMAND column's width, as Docker cuts it. */
        private const val COMMAND_WIDTH = 20

        /** Lines `logs` gives without `--tail`, the most a page shows too. */
        const val DEFAULT_TAIL = 100

        /** The most lines one `logs` answers with; the log itself is kept under [ContainerProgram.LOG_MAX_BYTES]. */
        const val MAX_TAIL = 2000

        /** Subcommands droidtop does not answer yet on either front, said as such rather than as unknown. */
        private val LATER = setOf("rmi", "compose")

        /** Subcommands droidtop will not have: they need a daemon or image building it does not have. */
        private val NEVER = setOf("build", "buildx", "push", "login", "volume", "swarm", "service", "commit", "save", "load", "cp", "attach", "kill", "pause", "unpause", "top", "stats", "events", "port", "update", "wait", "export", "import", "tag", "history", "diff", "system", "context", "plugin", "secret", "config", "node", "stack", "trust", "manifest", "image", "container", "version", "search", "logout", "rename", "machine")

        val DOCKER = EngineCli(Front.DOCKER)
        val PODMAN = EngineCli(Front.PODMAN)

        /** Every front, each a command on every container's PATH. */
        val FRONTS: List<EngineCli> = listOf(DOCKER, PODMAN)

        /** The front whose wrapper hands [token], or null for an exported command's. */
        fun forToken(token: String): EngineCli? = FRONTS.firstOrNull { it.token == token }
    }
}
