package dev.droidtop.runtime

/**
 * The `docker` command in every container (docs/SPEC.md 3d "The docker command", Droidtop/tracker#555 D6c): a thin
 * client of droidtop's own container engine, not a second runtime. Its wrapper runs [ContainerEnter.HELPER] with
 * [TOKEN], so a docker request travels the one request channel the enter path uses, with the same argument bound and
 * the same stdin, stdout, stderr and exit status. droidtop reads the arguments into a [DockerCommand] from a closed
 * set ([parse]); nothing the container wrote reaches Android as a command, a path or an argv.
 *
 * Docker words stay here, in the CLI; droidtop's own screens say "app" and "program".
 */
sealed interface DockerCommand {
    /** `docker ps`: containers whose program or container is up; [all] lists every one, [quiet] only names. */
    data class Ps(val all: Boolean, val quiet: Boolean) : DockerCommand

    /** `docker images`: the images droidtop's store holds. */
    data class Images(val quiet: Boolean) : DockerCommand

    /** `docker logs`: the last [tail] lines of a container's program log. */
    data class Logs(val container: String, val tail: Int) : DockerCommand

    /** `docker start`: starts each container's own program. */
    data class Start(val containers: List<String>) : DockerCommand

    /** `docker stop`: stops each container's own program. */
    data class Stop(val containers: List<String>) : DockerCommand

    /** `docker exec`: runs [command] in [container], through pipes ([ContainerEnter]), with [env] and in [workdir]. */
    data class Exec(val container: String, val command: List<String>, val env: Map<String, String>, val workdir: String?) : DockerCommand

    /** Not run: [text] goes to stdout when [code] is 0 (help), to stderr otherwise. */
    data class Answer(val text: String, val code: Int) : DockerCommand
}

object DockerCli {
    /** The command's name on every container's PATH; no exported command may take it ([ContainerEnter.validName]). */
    const val NAME = "docker"

    /** The token the wrapper hands the helper: droidtop's own, never one of an exported command. */
    val TOKEN: String = ContainerLauncher.token("droidtop:docker")

    /** Lines `docker logs` gives without `--tail`, the most a page shows too. */
    const val DEFAULT_TAIL = 100

    /** The most lines one `docker logs` answers with; the log itself is kept under [ContainerProgram.LOG_MAX_BYTES]. */
    const val MAX_TAIL = 2000

    /** Docker subcommands droidtop does not answer yet, said as such rather than as unknown. */
    private val LATER = setOf("run", "pull", "rm", "rmi", "create", "compose", "restart", "inspect")

    /** Docker subcommands droidtop will not have: they need namespaces, a daemon or image building it does not have. */
    private val NEVER = setOf("build", "buildx", "push", "login", "network", "volume", "swarm", "service", "commit", "save", "load", "cp", "attach", "kill", "pause", "unpause", "top", "stats", "events", "port", "update", "wait", "export", "import", "tag", "history", "diff", "system", "context", "plugin", "secret", "config", "node", "stack", "trust", "manifest", "image", "container", "info", "version", "search", "logout", "rename")

    val USAGE: String = """
        Usage: docker COMMAND

        droidtop's containers, through droidtop's own engine (no Docker daemon):
          ps [-a] [-q]                 List containers (running ones without -a)
          images [-q]                  List the images droidtop holds
          logs [-n N] CONTAINER        Show the last lines of a container's program
          start CONTAINER...           Start a container's own program
          stop CONTAINER...            Stop a container's own program
          exec [-e K=V] [-w DIR] CONTAINER COMMAND [ARG...]
                                       Run a command in another container (pipes, no terminal)

        Not yet: run, pull, rm, compose. Containers here share the device's network and
        processes: a port is the device's own, and there is no isolation between them.
    """.trimIndent() + "\n"

    fun parse(args: List<String>): DockerCommand {
        val sub = args.firstOrNull() ?: return DockerCommand.Answer(USAGE, 0)
        val rest = args.drop(1)
        return when (sub) {
            "-h", "--help", "help" -> DockerCommand.Answer(USAGE, 0)
            "ps" -> flags(rest, setOf("-a", "--all", "-q", "--quiet", "-aq", "-qa"))?.let { bad -> error("ps", bad) }
                ?: DockerCommand.Ps(all = rest.any { it in setOf("-a", "--all", "-aq", "-qa") }, quiet = rest.any { it in setOf("-q", "--quiet", "-aq", "-qa") })
            "images" -> flags(rest, setOf("-q", "--quiet"))?.let { bad -> error("images", bad) }
                ?: DockerCommand.Images(quiet = rest.isNotEmpty())
            "logs" -> logs(rest)
            "start", "stop" -> {
                rest.firstOrNull { it.startsWith("-") }?.let { return error(sub, it) }
                if (rest.isEmpty()) return DockerCommand.Answer("docker $sub: name at least one container\n", 1)
                if (sub == "start") DockerCommand.Start(rest) else DockerCommand.Stop(rest)
            }
            "exec" -> exec(rest)
            in LATER -> DockerCommand.Answer("docker $sub: not built in droidtop yet\n", 1)
            in NEVER -> DockerCommand.Answer("docker $sub: droidtop's containers do not have this (no Docker daemon, no namespaces)\n", 1)
            else -> DockerCommand.Answer("docker: '$sub' is not a docker command. See 'docker --help'\n", 1)
        }
    }

    /** The first argument of [args] that is not one of [allowed], or null when all are. */
    private fun flags(args: List<String>, allowed: Set<String>): String? = args.firstOrNull { it !in allowed }

    private fun error(sub: String, arg: String) = DockerCommand.Answer(
        if (arg.startsWith("-")) "docker $sub: unknown flag: $arg\n" else "docker $sub: unexpected argument: $arg\n",
        1,
    )

    private fun logs(args: List<String>): DockerCommand {
        var tail = DEFAULT_TAIL
        var container: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val value = when {
                a == "-n" || a == "--tail" -> args.getOrNull(++i) ?: return DockerCommand.Answer("docker logs: $a needs a number\n", 1)
                a.startsWith("--tail=") -> a.substringAfter('=')
                a == "-f" || a == "--follow" -> return DockerCommand.Answer("docker logs: following is not built in droidtop yet\n", 1)
                a.startsWith("-") -> return error("logs", a)
                container == null -> { container = a; null }
                else -> return error("logs", a)
            }
            if (value != null) {
                tail = if (value == "all") MAX_TAIL else value.toIntOrNull()?.takeIf { it >= 0 } ?: return DockerCommand.Answer("docker logs: not a number of lines: $value\n", 1)
            }
            i++
        }
        return container?.let { DockerCommand.Logs(it, tail.coerceAtMost(MAX_TAIL)) } ?: DockerCommand.Answer("docker logs: name a container\n", 1)
    }

    private fun exec(args: List<String>): DockerCommand {
        val env = linkedMapOf<String, String>()
        var workdir: String? = null
        var i = 0
        while (i < args.size && args[i].startsWith("-")) {
            when (val a = args[i]) {
                "-i", "--interactive" -> Unit
                // No terminal through the request's pipes: a -t is taken and the command runs on pipes all the same.
                "-t", "--tty", "-it", "-ti" -> Unit
                "-e", "--env" -> {
                    val kv = args.getOrNull(++i) ?: return DockerCommand.Answer("docker exec: $a needs NAME=VALUE\n", 1)
                    env(kv)?.let { (k, v) -> env[k] = v } ?: return DockerCommand.Answer("docker exec: not NAME=VALUE: $kv\n", 1)
                }
                "-w", "--workdir" -> workdir = args.getOrNull(++i) ?: return DockerCommand.Answer("docker exec: $a needs a folder\n", 1)
                else -> return error("exec", a)
            }
            i++
        }
        val container = args.getOrNull(i) ?: return DockerCommand.Answer("docker exec: name a container and a command\n", 1)
        val command = args.drop(i + 1)
        if (command.isEmpty()) return DockerCommand.Answer("docker exec: name a command to run in $container\n", 1)
        return DockerCommand.Exec(container, command, env, workdir)
    }

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private fun env(kv: String): Pair<String, String>? {
        val name = kv.substringBefore('=')
        if (!kv.contains('=') || !ENV_NAME.matches(name)) return null
        return name to kv.substringAfter('=')
    }

    /** One `docker ps` row as droidtop knows it. */
    data class PsRow(val name: String, val image: String, val status: String, val up: Boolean, val ports: List<ExposedPort>)

    fun formatPs(rows: List<PsRow>, command: DockerCommand.Ps): String {
        val shown = if (command.all) rows else rows.filter { it.up }
        if (command.quiet) return shown.joinToString("") { it.name + "\n" }
        return table(listOf("NAME", "IMAGE", "STATUS", "PORTS"), shown.map { listOf(it.name, it.image, it.status, it.ports.joinToString(", ")) })
    }

    fun formatImages(images: List<OciImageStore.Entry>, command: DockerCommand.Images): String {
        if (command.quiet) return images.joinToString("") { it.digest.substringAfter(':').take(12) + "\n" }
        return table(
            listOf("REPOSITORY", "TAG", "IMAGE ID", "SIZE"),
            images.map { listOf(repository(it.reference), tag(it.reference), it.digest.substringAfter(':').take(12), size(it.sizeBytes)) },
        )
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

    private fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1fGB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.0fMB", bytes / 1e6)
        else -> String.format(java.util.Locale.ROOT, "%.0fkB", bytes / 1e3)
    }

    /** Columns padded to their widest cell plus three spaces, as Docker prints them. */
    private fun table(header: List<String>, rows: List<List<String>>): String {
        val all = listOf(header) + rows
        val widths = header.indices.map { c -> all.maxOf { it[c].length } }
        return all.joinToString("") { row ->
            row.mapIndexed { c, cell -> if (c == row.lastIndex) cell else cell.padEnd(widths[c] + 3) }.joinToString("").trimEnd() + "\n"
        }
    }

    /** The `docker` wrapper on every container's PATH. */
    fun wrapperScript(): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# droidtop's docker command (docs/SPEC.md 3d \"The docker command\"). Installed by droidtop.")
        appendLine("exec sh ${ContainerEnter.HELPER} $TOKEN \"\$@\"")
    }
}
