package dev.droidtop.runtime

/**
 * One application installed in a container, as its own desktop entry
 * describes it.
 *
 * [id] is the desktop file's name (`foot.desktop`), the freedesktop
 * identity of an application. [command] is the entry's `Exec` line split
 * into arguments with its field codes removed. [terminal] is `Terminal=true`:
 * a console program that needs a terminal window around it.
 */
data class ContainerApp(
    val id: String,
    val name: String,
    val command: List<String>,
    val terminal: Boolean,
)

/**
 * The applications installed in a container, read from the freedesktop
 * desktop entries its packages install (`/usr/share/applications`,
 * `/usr/local/share/applications`): the same list any Linux desktop's menu
 * shows, so a package the user installs appears without droidtop knowing
 * about it. Read through [ContainerRuntime.exec], so it works the same on
 * either backend, and launched the same way ([launch]): the program runs in
 * the container and its window appears on the shared desktop (docs/SPEC.md
 * 2, 2a).
 *
 * Parsing follows the Desktop Entry Specification for what a launcher needs:
 * only the `[Desktop Entry]` group, only `Type=Application`, entries with
 * `NoDisplay` or `Hidden` set are left out, `Exec` is unquoted per the
 * spec's rules and its field codes (`%f`, `%U`, ...) are dropped because
 * nothing is being opened with the application.
 */
object ContainerApplications {
    private const val FILE_MARKER = "@@droidtop-desktop-file "

    /** Prints every desktop file, each preceded by a marker line naming it. */
    private val LIST_SCRIPT = listOf(
        "/bin/sh",
        "-c",
        "for f in /usr/share/applications/*.desktop /usr/local/share/applications/*.desktop; do " +
            "[ -f \"\$f\" ] && { printf '\\n$FILE_MARKER%s\\n' \"\$f\"; cat \"\$f\"; }; done; true",
    )

    suspend fun list(runtime: ContainerRuntime, container: Container): List<ContainerApp> {
        val result = runtime.exec(container, LIST_SCRIPT)
        check(result.succeeded) { "couldn't list applications: ${result.stderr.ifBlank { result.stdout }.trim()}" }
        return parseListing(result.stdout)
    }

    /**
     * Runs [app] in [container] and waits until it exits (its window
     * closed), returning a failure message, or null for a normal exit. A
     * `Terminal=true` program runs inside [ContainerTerminal.PACKAGE].
     */
    suspend fun launch(runtime: ContainerRuntime, container: Container, app: ContainerApp): String? {
        val result = runtime.exec(container, launchCommand(app))
        if (result.succeeded) return null
        val detail = result.stderr.ifBlank { result.stdout }.trim().lines().takeLast(3).joinToString(" ")
        return "${app.name} exited with code ${result.exitCode}" + if (detail.isEmpty()) "." else ": $detail"
    }

    fun launchCommand(app: ContainerApp): List<String> =
        if (app.terminal) listOf(ContainerTerminal.PACKAGE, "-e") + app.command else app.command

    /** Splits [listing] (the output of [LIST_SCRIPT]) into entries and parses each; sorted by name. */
    fun parseListing(listing: String): List<ContainerApp> {
        val apps = mutableListOf<ContainerApp>()
        var currentPath: String? = null
        val body = StringBuilder()
        fun flush() {
            val path = currentPath ?: return
            parseEntry(path.substringAfterLast('/'), body.toString())?.let { apps += it }
        }
        for (line in listing.lineSequence()) {
            if (line.startsWith(FILE_MARKER)) {
                flush()
                currentPath = line.removePrefix(FILE_MARKER).trim()
                body.setLength(0)
            } else {
                body.append(line).append('\n')
            }
        }
        flush()
        // A later directory's entry with the same id wins, as in the spec's
        // lookup order; /usr/local is listed second.
        return apps.associateBy { it.id }.values.sortedBy { it.name.lowercase() }
    }

    /** One desktop file's [Desktop Entry] group, or null when it is not a visible application. */
    fun parseEntry(id: String, text: String): ContainerApp? {
        val values = mutableMapOf<String, String>()
        var inEntry = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            // Localized keys (Name[de]) are not the entry's own name.
            if ('[' in key) continue
            values.putIfAbsent(key, line.substring(eq + 1).trim())
        }
        if (values["Type"] != "Application") return null
        if (values["NoDisplay"].equals("true", ignoreCase = true)) return null
        if (values["Hidden"].equals("true", ignoreCase = true)) return null
        val name = values["Name"]?.takeIf { it.isNotBlank() } ?: return null
        val command = splitExec(values["Exec"] ?: return null).takeIf { it.isNotEmpty() } ?: return null
        return ContainerApp(
            id = id,
            name = unescapeString(name),
            command = command,
            terminal = values["Terminal"].equals("true", ignoreCase = true),
        )
    }

    /**
     * An `Exec` value as arguments: first the general string-value escapes
     * (`\s`, `\t`, `\\` ...), then the spec's quoting (double quotes, inside
     * which backslash escapes `"`, `` ` ``, `$` and `\`), then field codes:
     * a `%x` argument is dropped, `%%` is a literal percent sign.
     */
    fun splitExec(exec: String): List<String> {
        val value = unescapeString(exec)
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasArg = false
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                inQuotes && c == '\\' && i + 1 < value.length -> {
                    current.append(value[i + 1])
                    i++
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                    hasArg = true
                }
                !inQuotes && c.isWhitespace() -> {
                    if (hasArg) args += current.toString()
                    current.setLength(0)
                    hasArg = false
                }
                else -> {
                    current.append(c)
                    hasArg = true
                }
            }
            i++
        }
        if (hasArg) args += current.toString()
        return args.mapNotNull { expandFieldCodes(it) }
    }

    private fun expandFieldCodes(arg: String): String? {
        // A lone field code stands for files/URLs; with nothing to open it
        // expands to no argument at all.
        if (arg.length == 2 && arg[0] == '%' && arg[1] != '%') return null
        val out = StringBuilder()
        var i = 0
        while (i < arg.length) {
            val c = arg[i]
            if (c == '%' && i + 1 < arg.length) {
                if (arg[i + 1] == '%') out.append('%')
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun unescapeString(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    's' -> out.append(' ')
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    // Anything else (\" \$ \`) belongs to Exec's own quoting
                    // rules, which run after this pass; kept as written.
                    else -> out.append(c).append(value[i + 1])
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
