package dev.droidtop.runtime

import java.io.File

/**
 * One command of a sibling container on the desktop's PATH, distrobox-export `--bin` style (docs/SPEC.md 3d
 * "Exported commands", Droidtop/tracker#555 D5 and D7). [name] is the program's name, run by the sibling's own PATH.
 */
data class ExportedCommand(val containerId: String, val containerName: String, val name: String) {
    /** The token the wrapper names it by ([ContainerLauncher.token] of the container and the name). */
    val token: String get() = ContainerLauncher.token("command:$containerId/$name")
}

/**
 * Running a command of one container from another: the enter path (docs/SPEC.md 3d "Entering another container",
 * Droidtop/tracker#555 D7, the shared-binds option). A program in the primary runs `droidtop-enter <token> args...`
 * (an exported command's wrapper does), which makes a request directory in droidtop's request channel, a folder
 * every container sees at the same path, holding three FIFOs for the program's stdin, stdout and stderr, its
 * arguments and the working folder, and renames it into place. droidtop looks the token up in its own list, runs
 * the command in the sibling with its standard streams on those FIFOs, and writes its exit status. The bytes go
 * from container to container through the FIFOs; nothing passes through Android, and nothing the container wrote
 * is run by Android: the token names only a command droidtop published, and the arguments go to that command.
 *
 * Pipes, not a terminal: a command that needs one (an editor, `top`) does not get it.
 */
object ContainerEnter {
    /** The enter helper inside every container. */
    const val HELPER = "${ContainerLauncher.IN_CONTAINER_DIR}/bin/droidtop-enter"

    /** Where exported commands' wrappers live inside every container; on the PATH ([ContainerLayout.clientEnvironment]). */
    const val COMMANDS_DIR = "${ContainerLauncher.IN_CONTAINER_DIR}/commands"

    /** A request directory's name: `enter.<token>.<pid>.<ms>.d`. */
    private val REQUEST = Regex("enter\\.([0-9a-f]{16})\\.[0-9]+\\.[0-9]+\\.d")

    /** The most argument bytes one request may carry. */
    const val MAX_ARGS_BYTES = 64 * 1024

    /** Names a command may be exported under: a program name, nothing a shell or a path would read specially. */
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")

    fun hostHelper(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), "bin/droidtop-enter")

    fun hostCommandsDir(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), "commands")

    /** Whether [name] can be exported: a plain program name, and none of droidtop's own helpers or commands. */
    fun validName(name: String): Boolean = NAME.matches(name) && !name.startsWith("droidtop-") && EngineCli.FRONTS.none { it.name == name }

    /** The file droidtop makes in a request directory the moment it sees it, which the helper waits for. */
    const val TAKEN = "taken"

    /** The token a request directory named [dirName] asks for, or null when it is not an enter request. */
    fun requestToken(dirName: String): String? = REQUEST.matchEntire(dirName)?.groupValues?.get(1)

    /** The arguments a request wrote (each ended by a NUL), or null when they are more than [MAX_ARGS_BYTES]. */
    fun parseArgs(bytes: ByteArray): List<String>? {
        if (bytes.size > MAX_ARGS_BYTES) return null
        if (bytes.isEmpty()) return emptyList()
        val text = String(bytes, Charsets.UTF_8)
        return text.removeSuffix("\u0000").split('\u0000')
    }

    /**
     * What droidtop runs in the sibling for a request in [requestDir] (the path both containers see): the command
     * by name with its arguments, its working folder when the sibling has it (else its home), and its streams on
     * the request's FIFOs. The command and the arguments are argv entries, never shell text.
     */
    fun execCommand(requestDir: String, command: String, args: List<String>, cwd: String?): List<String> =
        listOf(
            "/bin/sh",
            "-c",
            "d=\$1; shift; [ -n \"\$DROIDTOP_CWD\" ] && cd \"\$DROIDTOP_CWD\" 2>/dev/null; " +
                "exec \"\$@\" <\"\$d/in\" >\"\$d/out\" 2>\"\$d/err\"",
            "sh",
            requestDir,
            command,
        ) + args

    /**
     * [command] run directly, its output collected by the runtime rather than sent to a request's FIFOs: the Engine
     * API's exec (docs/SPEC.md 3d, D14). The working folder comes from [execEnvironment] as for [execCommand], so the
     * script is the same constant text and the command is argv entries, never shell text.
     */
    fun directCommand(command: List<String>): List<String> =
        listOf("/bin/sh", "-c", "[ -n \"\$DROIDTOP_CWD\" ] && cd \"\$DROIDTOP_CWD\" 2>/dev/null; exec \"\$@\"", "sh") + command

    /** The environment [execCommand] reads its working folder from. */
    fun execEnvironment(cwd: String?): Map<String, String> = if (cwd.isNullOrBlank()) emptyMap() else mapOf("DROIDTOP_CWD" to cwd)

    /**
     * The helper. It refuses anything but a token as its first argument, makes the request directory under a
     * hidden name and renames it into place, so droidtop only sees a whole request (inotify's MOVED_TO). It then
     * copies its stdin into the command and the command's output out, and exits with the command's status.
     */
    fun helperScript(): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# Runs an exported command of another container (docs/SPEC.md 3d). Installed by droidtop.")
        appendLine("set -eu")
        appendLine("case \"\${1:-}\" in")
        appendLine("  *[!0-9a-f]* | '') echo 'usage: droidtop-enter <command> [arguments]' >&2; exit 2 ;;")
        appendLine("esac")
        appendLine("tok=\$1; shift")
        appendLine("base=${ContainerLauncher.REQUESTS_DIR}")
        appendLine("id=\"enter.\$tok.\$\$.\$(date +%s)000\"")
        appendLine("tmp=\"\$base/.\$id\"")
        appendLine("mkdir -m 700 \"\$tmp\"")
        appendLine("mkfifo \"\$tmp/in\" \"\$tmp/out\" \"\$tmp/err\"")
        appendLine(": > \"\$tmp/args\"")
        appendLine("for a in \"\$@\"; do printf '%s\\000' \"\$a\" >> \"\$tmp/args\"; done")
        appendLine("pwd > \"\$tmp/cwd\" 2>/dev/null || true")
        appendLine("d=\"\$base/\$id.d\"")
        appendLine("mv \"\$tmp\" \"\$d\"")
        // droidtop marks the request taken at once; with no droidtop watching, nothing would ever open the FIFOs.
        appendLine("n=0")
        appendLine("while [ ! -e \"\$d/taken\" ] && [ \$n -lt 100 ]; do sleep 0.1 2>/dev/null || sleep 1; n=\$((n + 1)); done")
        appendLine("if [ ! -e \"\$d/taken\" ]; then")
        appendLine("  echo 'droidtop-enter: droidtop did not answer; is the desktop running?' >&2")
        appendLine("  rm -f \"\$d/in\" \"\$d/out\" \"\$d/err\" \"\$d/args\" \"\$d/cwd\"")
        appendLine("  rmdir \"\$d\" 2>/dev/null || true")
        appendLine("  exit 125")
        appendLine("fi")
        appendLine("cat \"\$d/err\" >&2 &")
        appendLine("e=\$!")
        // A background command's stdin is /dev/null in a non-interactive shell (POSIX), so the caller's is kept on 3.
        appendLine("exec 3<&0")
        appendLine("cat <&3 > \"\$d/in\" 2>/dev/null &")
        appendLine("i=\$!")
        appendLine("cat \"\$d/out\"")
        appendLine("wait \$e || true")
        appendLine("kill \$i 2>/dev/null || true")
        appendLine("n=0")
        appendLine("while [ ! -s \"\$d/status\" ] && [ \$n -lt 100 ]; do sleep 1; n=\$((n + 1)); done")
        appendLine("code=\$(cat \"\$d/status\" 2>/dev/null || echo 125)")
        appendLine("rm -f \"\$d/in\" \"\$d/out\" \"\$d/err\" \"\$d/args\" \"\$d/cwd\" \"\$d/status\" \"\$d/taken\"")
        appendLine("rmdir \"\$d\" 2>/dev/null || true")
        appendLine("exit \"\$code\"")
    }

    /** An exported command's wrapper: the command's own name on the PATH, running it through [HELPER]. */
    fun wrapperScript(export: ExportedCommand): String = buildString {
        appendLine("#!/bin/sh")
        appendLine("# ${export.name} from ${export.containerName.replace('\n', ' ')}, exported by droidtop (docs/SPEC.md 3d).")
        appendLine("exec sh $HELPER ${export.token} \"\$@\"")
    }

    /** The exported commands list: one `containerId<TAB>containerName<TAB>name` line each. */
    fun encode(commands: List<ExportedCommand>): String = buildString {
        commands.forEach { c ->
            if (listOf(c.containerId, c.containerName).any { it.contains('\t') || it.contains('\n') } || !validName(c.name)) return@forEach
            append("${c.containerId}\t${c.containerName}\t${c.name}\n")
        }
    }

    fun decode(text: String): List<ExportedCommand> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 3 || f[0].isBlank() || !validName(f[2])) null else ExportedCommand(f[0], f[1], f[2])
    }.toList()

    private const val LIST_FILE = "commands.list"

    fun hostListFile(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), LIST_FILE)

    fun read(filesDir: File): List<ExportedCommand> =
        runCatching { hostListFile(filesDir).takeIf { it.isFile }?.readText() }.getOrNull()?.let(::decode).orEmpty()

    fun forToken(commands: List<ExportedCommand>, token: String): ExportedCommand? = commands.firstOrNull { it.token == token }

    /** [commands] with [command] in it (one per name in the whole desktop: a later container takes the name) or out. */
    fun with(commands: List<ExportedCommand>, command: ExportedCommand, on: Boolean): List<ExportedCommand> {
        val rest = commands.filterNot { it.name == command.name }
        return if (on) rest + command else rest
    }

    fun without(commands: List<ExportedCommand>, containerId: String): List<ExportedCommand> =
        commands.filterNot { it.containerId == containerId }

    /**
     * Saves [commands] and makes the wrappers match: one executable script per exported name in [hostCommandsDir],
     * every other file there removed. Small files; off the main thread.
     */
    fun save(filesDir: File, commands: List<ExportedCommand>) {
        val list = hostListFile(filesDir)
        list.parentFile?.mkdirs()
        writeIfChanged(list, encode(commands))
        writeWrappers(filesDir, commands)
    }

    /** The wrappers for [commands] and the docker command, stale ones removed, and the helper; run at every desktop start and after each change. */
    fun writeWrappers(filesDir: File, commands: List<ExportedCommand>) {
        val helper = hostHelper(filesDir)
        helper.parentFile?.mkdirs()
        writeIfChanged(helper, helperScript())
        val dir = hostCommandsDir(filesDir)
        dir.mkdirs()
        // droidtop's own docker and podman commands live beside them (docs/SPEC.md 3d "The docker command"), and so
        // do droidtop-open, droidtop-view and a fallback xdg-open (2a), which an exported xdg-open replaces.
        val wanted = ContainerLauncher.pathCommands() + commands.associate { it.name to wrapperScript(it) } +
            EngineCli.FRONTS.associate { it.name to it.wrapperScript() }
        dir.listFiles()?.forEach { if (it.isFile && it.name !in wanted) it.delete() }
        wanted.forEach { (name, text) ->
            val file = File(dir, name)
            writeIfChanged(file, text)
            file.setExecutable(true, false)
        }
    }

    // ---- the per-container setting: may the desktop run this container's commands ----

    private const val OFF_FILE = "enter-off.list"

    /** The containers whose commands the desktop may not run; every other container may (the default is on). */
    fun hostOffFile(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), OFF_FILE)

    fun allowed(filesDir: File, containerId: String): Boolean =
        runCatching { hostOffFile(filesDir).takeIf { it.isFile }?.readLines().orEmpty() }.getOrDefault(emptyList())
            .none { it == containerId }

    fun setAllowed(filesDir: File, containerId: String, on: Boolean) {
        val file = hostOffFile(filesDir)
        val off = runCatching { file.takeIf { it.isFile }?.readLines().orEmpty() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() && it != containerId }
        file.parentFile?.mkdirs()
        writeIfChanged(file, (if (on) off else off + containerId).joinToString("") { "$it\n" })
    }

    private fun writeIfChanged(file: File, text: String) {
        if (file.isFile && file.readText() == text) return
        val tmp = File(file.parentFile, ".${file.name}")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) tmp.delete()
    }
}
