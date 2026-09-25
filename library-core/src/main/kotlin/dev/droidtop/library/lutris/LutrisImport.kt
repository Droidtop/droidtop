package dev.droidtop.library.lutris

import org.json.JSONArray
import org.json.JSONObject

/**
 * One line of an import's preview: what the script asked for, and what
 * droidtop does with it, in the person's words.
 */
data class ImportLine(val what: String, val detail: String)

/**
 * The prefix-wide settings a script can change, in droidtop's own
 * vocabulary -- the closed set docs/security/2026-09-25-lutris-importer.md
 * fixes. Null or empty means "the script says nothing about it".
 *
 * [components] are gamenative's own Windows-component ids (its
 * `wincomponents.json`: `direct3d`, `directsound`, ...), never DLL names
 * or URLs from the script. [dllOverrides] values are already in Wine's own
 * `WINEDLLOVERRIDES` form (`n,b`, `b`, `` for disabled).
 */
data class WinePrefixChanges(
    val dxvk: Boolean? = null,
    val esync: Boolean? = null,
    val components: Set<String> = emptySet(),
    val dllOverrides: Map<String, String> = emptyMap(),
    val env: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = dxvk == null && esync == null && components.isEmpty() && dllOverrides.isEmpty() && env.isEmpty()
}

/**
 * What one Lutris Wine script says, translated -- docs/SPEC.md 7e3.
 *
 * [exePath] and [workingDir] are the script's own paths with `$GAMEDIR`
 * taken off, as components; which file in THIS game's folder they are is
 * answered separately, on disk, by [LutrisImport.resolveInFolder].
 * [covered] is what the script does that droidtop already does its own
 * way; [notImported] is everything else, each with why.
 */
data class LutrisImportPlan(
    val exePath: List<String>?,
    val args: List<String>?,
    val workingDir: List<String>?,
    val prefix: WinePrefixChanges,
    val covered: List<ImportLine>,
    val notImported: List<ImportLine>,
)

/** A script refused as a whole, before anything was read into a plan. */
class LutrisScriptRefused(message: String) : IllegalArgumentException(message)

/**
 * Lutris install script -> droidtop settings, as a pure DATA TRANSLATION.
 *
 * Nothing here runs, fetches or writes anything a script names, and
 * nothing ever will: `execute`, `task`, `write_file` and the rest are
 * read only to say, in the preview, what the script would have done and
 * that droidtop did not do it. The mapping tables below are closed and
 * are code, reviewed like code; a key, directive, task or verb they do
 * not name is not imported. The threat model this implements is
 * docs/security/2026-09-25-lutris-importer.md; the format is Lutris's
 * own `docs/installers.rst`.
 */
object LutrisImport {

    /** Parse limits (threat model, decision 8). */
    const val MAX_DIRECTIVES = 500
    const val MAX_FILES = 200
    const val MAX_STRING = 4096

    /**
     * Translates [script] (the `script` object of a Lutris installer).
     * Throws [LutrisScriptRefused] for a script over a parse limit, which
     * is refused whole rather than half-read.
     */
    fun translate(script: JSONObject): LutrisImportPlan {
        checkLimits(script, depth = 0)
        val covered = mutableListOf<ImportLine>()
        val notImported = mutableListOf<ImportLine>()
        val prefix = PrefixBuilder(notImported)

        var exe: List<String>? = null
        var args: List<String>? = null
        var workingDir: List<String>? = null

        script.optJSONObject("game")?.let { game ->
            for (key in game.keys()) {
                val value = game.opt(key)
                when (key) {
                    "exe" -> exe = gamePath(value, notImported, "Executable", requireExe = true)
                    "args" -> args = arguments(value, notImported)
                    "working_dir" -> workingDir = gamePath(value, notImported, "Working folder", requireExe = false)
                    "prefix" -> covered += ImportLine(
                        "Prefix location",
                        "droidtop uses its own prefix for this game; a script does not choose where it is",
                    )
                    "arch" -> when (value?.toString()?.lowercase()) {
                        "win64" -> covered += ImportLine("64-bit prefix", "droidtop's prefix is 64-bit already")
                        "win32" -> covered += ImportLine(
                            "32-bit prefix",
                            "droidtop's prefix is 64-bit and runs 32-bit programs through WoW64, so nothing changes",
                        )
                        else -> notImported += ImportLine("Prefix architecture \"$value\"", "Not a value Lutris documents")
                    }
                    "launch_configs" -> notImported += ImportLine(
                        "Other launch options (${(value as? JSONArray)?.length() ?: 0})",
                        "Only the main executable is imported",
                    )
                    // Store identifiers, not settings: what Lutris uses to
                    // find the game in a store, which droidtop already has.
                    in IDENTIFIER_KEYS -> Unit
                    else -> notImported += ImportLine("Game option \"$key\"", "droidtop has no setting for this")
                }
            }
        }

        script.optJSONObject("wine")?.let { wine ->
            for (key in wine.keys()) {
                val value = wine.opt(key)
                when (key) {
                    "dxvk" -> prefix.dxvk = boolean(value, "DXVK", notImported)
                    "esync" -> prefix.esync = boolean(value, "Esync", notImported)
                    "overrides" -> prefix.overrides(value)
                    "version" -> notImported += ImportLine(
                        "Wine build \"$value\"",
                        "Choose the Wine build under Prefix and graphics; a script does not pick one",
                    )
                    "dxvk_version" -> notImported += ImportLine(
                        "DXVK version \"$value\"",
                        "Choose the DXVK version under Prefix and graphics",
                    )
                    else -> notImported += ImportLine("Wine option \"$key\"", "droidtop has no setting for this")
                }
            }
        }

        script.optJSONObject("system")?.let { system ->
            for (key in system.keys()) {
                val value = system.opt(key)
                when (key) {
                    "env" -> (value as? JSONObject)?.let { env ->
                        for (name in env.keys()) prefix.env(name, env.opt(name)?.toString().orEmpty())
                    } ?: run { notImported += ImportLine("Environment", "Not a list of variables") }
                    // Lutris sets exactly this variable for this option.
                    "pulse_latency" -> if (boolean(value, "Audio latency", notImported) == true) prefix.env("PULSE_LATENCY_MSEC", "60")
                    "single_cpu" -> if (boolean(value, "Single CPU", notImported) == true) {
                        notImported += ImportLine(
                            "Run on one CPU core",
                            "droidtop's Windows launch has no CPU pinning",
                        )
                    }
                    else -> notImported += ImportLine("System option \"$key\"", "droidtop has no setting for this")
                }
            }
        }

        (script.opt("files") as? JSONArray)?.let { files ->
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                for (id in file.keys()) notImported += fileLine(id, file.opt(id))
            }
        }

        (script.opt("installer") as? JSONArray)?.let { steps ->
            for (index in 0 until steps.length()) {
                val step = steps.optJSONObject(index)
                if (step == null) {
                    notImported += ImportLine("Step ${index + 1}", "Not a step Lutris documents")
                    continue
                }
                for (name in step.keys()) directive(name, step.opt(name), prefix, covered, notImported)
            }
        }

        for (key in script.keys()) {
            when (key) {
                "game", "wine", "system", "files", "installer" -> Unit
                "requires", "extends" -> notImported += ImportLine(
                    "Needs \"${script.opt(key)}\" installed first",
                    "Scripts that build on another game are not imported",
                )
                "require-binaries" -> notImported += ImportLine(
                    "Needs the programs \"${script.opt(key)}\"",
                    "Only the installer used them, and droidtop runs no installer",
                )
                "variables" -> notImported += ImportLine(
                    "Script variables",
                    "droidtop does not fill in Lutris variables; anything using one is not imported",
                )
                // Text Lutris shows when its own installer finishes.
                "install_complete_text" -> Unit
                else -> notImported += ImportLine("Section \"$key\"", "droidtop has no setting for this")
            }
        }

        return LutrisImportPlan(exe, args, workingDir, prefix.build(), covered, notImported)
    }

    /** The per-game half of an import, matched against the game's own folder. */
    data class InFolder(
        /** Path of the executable relative to the game folder, `/`-separated. */
        val executable: String?,
        val workingDir: String?,
        val notImported: List<ImportLine>,
    )

    /**
     * Which file in [gameRoot] the script's executable (and working
     * folder) is: the longest trailing run of the script's own path that
     * exists under [gameRoot]. A Lutris game directory holds a whole
     * prefix (`drive_c/GOG Games/Foo/bin/Foo.exe`) where droidtop's game
     * folder is the game alone, so the leading components are expected
     * not to be there; the trailing ones are the game's own layout.
     *
     * The match must be inside [gameRoot] once canonicalised (a symlink
     * cannot lead out), and an executable must be a regular `.exe` file.
     * Disk work: never on the main thread.
     */
    fun resolveInFolder(plan: LutrisImportPlan, gameRoot: java.io.File): InFolder {
        val notImported = mutableListOf<ImportLine>()
        val exe = plan.exePath?.let { path ->
            longestExisting(path, gameRoot) { it.isFile && it.extension.equals("exe", ignoreCase = true) }
                ?: run {
                    notImported += ImportLine(
                        "Executable ${path.last()}",
                        "There is no ${path.last()} in this game's folder",
                    )
                    null
                }
        }
        val dir = plan.workingDir?.let { path ->
            if (path.isEmpty()) return@let ""
            longestExisting(path, gameRoot) { it.isDirectory } ?: run {
                notImported += ImportLine(
                    "Working folder ${path.joinToString("/")}",
                    "No folder like it is in this game's folder",
                )
                null
            }
        }
        return InFolder(exe, dir, notImported)
    }

    private fun longestExisting(path: List<String>, gameRoot: java.io.File, accept: (java.io.File) -> Boolean): String? {
        val root = runCatching { gameRoot.canonicalFile }.getOrNull() ?: return null
        for (start in path.indices) {
            val relative = path.subList(start, path.size).joinToString("/")
            val candidate = java.io.File(root, relative)
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
            if (!canonical.path.startsWith(root.path + java.io.File.separator)) continue
            if (accept(canonical)) return canonical.path.removePrefix(root.path + java.io.File.separator).replace(java.io.File.separatorChar, '/')
        }
        return null
    }

    /**
     * A script path as components under `$GAMEDIR`, or null (with the
     * reason recorded) when it is anything else: an absolute path, a path
     * through `..`, another Lutris variable, or -- for [requireExe] -- not
     * a Windows program.
     */
    private fun gamePath(value: Any?, notImported: MutableList<ImportLine>, label: String, requireExe: Boolean): List<String>? {
        val raw = (value as? String)?.trim().orEmpty()
        if (raw.isEmpty()) {
            notImported += ImportLine(label, "The script gives no path")
            return null
        }
        val unixy = raw.replace('\\', '/')
        val relative = when {
            unixy == "\$GAMEDIR" -> ""
            unixy.startsWith("\$GAMEDIR/") -> unixy.removePrefix("\$GAMEDIR/")
            unixy.startsWith("/") || unixy.startsWith("~") || Regex("^[A-Za-z]:").containsMatchIn(unixy) -> {
                notImported += ImportLine("$label $raw", "A path outside the game's folder is never imported")
                return null
            }
            else -> unixy
        }
        if ('$' in relative) {
            notImported += ImportLine("$label $raw", "It uses a Lutris variable droidtop does not fill in")
            return null
        }
        val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            notImported += ImportLine("$label $raw", "A path that climbs out of the game's folder is never imported")
            return null
        }
        if (requireExe && (parts.isEmpty() || !parts.last().endsWith(".exe", ignoreCase = true))) {
            notImported += ImportLine("$label $raw", "Not a Windows program")
            return null
        }
        return parts
    }

    /**
     * Arguments as a list, split once and quote-aware, or null (with the
     * reason recorded) when they carry a Lutris variable or a control
     * character. They reach Wine as separate arguments, never through a
     * shell (threat model, decision 4).
     */
    internal fun arguments(value: Any?, notImported: MutableList<ImportLine>): List<String>? {
        val raw = value?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if ('$' in raw) {
            notImported += ImportLine("Arguments $raw", "They use a Lutris variable droidtop does not fill in")
            return null
        }
        if (raw.any { it.isISOControl() }) {
            notImported += ImportLine("Arguments", "They contain control characters")
            return null
        }
        return splitArguments(raw) ?: run {
            notImported += ImportLine("Arguments $raw", "A quote is never closed")
            null
        }
    }

    /** Whitespace-separated, with "double" and 'single' quotes grouping; null for an unclosed quote. */
    internal fun splitArguments(raw: String): List<String>? {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (c in raw) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    inToken = true
                }
                c.isWhitespace() -> if (inToken) {
                    out += current.toString()
                    current.clear()
                    inToken = false
                }
                else -> {
                    current.append(c)
                    inToken = true
                }
            }
        }
        if (quote != null) return null
        if (inToken) out += current.toString()
        return out
    }

    private fun directive(
        name: String,
        value: Any?,
        prefix: PrefixBuilder,
        covered: MutableList<ImportLine>,
        notImported: MutableList<ImportLine>,
    ) {
        val args = value as? JSONObject
        fun arg(key: String) = args?.opt(key)?.toString()?.takeIf { it.isNotBlank() }
        when (name) {
            "task" -> task(args, prefix, covered, notImported)
            "execute" -> notImported += ImportLine(
                if (arg("command") != null) "Runs a shell command" else "Runs ${arg("file") ?: "a program"}",
                "droidtop never runs a program or command a script names",
            )
            "insert-disc" -> notImported += ImportLine("Asks for the game's disc", "droidtop runs no installer")
            "write_file", "write_config", "write_json" -> notImported += ImportLine(
                "Writes ${arg("file") ?: "a file"}",
                "droidtop does not change files in a game's folder; make the change by hand if the game needs it",
            )
            "extract", "move", "merge", "copy", "mkdir", "chmodx", "rename" -> notImported += ImportLine(
                "${STEP_VERBS.getValue(name)} ${arg("file") ?: arg("src") ?: (value as? String) ?: ""}".trim(),
                "The game's files are already in its folder; droidtop does not unpack, copy or move them",
            )
            "input_menu" -> notImported += ImportLine(
                "Asks you to choose: ${arg("description") ?: "an option"}",
                "Only the installer used the answer, and droidtop runs no installer",
            )
            "gogdl_setup" -> notImported += ImportLine(
                "Downloads the game from GOG",
                "Sign in to GOG under Stores and folders to install it there",
            )
            else -> notImported += ImportLine("Step \"$name\"", "Not a step droidtop imports")
        }
    }

    private fun task(
        args: JSONObject?,
        prefix: PrefixBuilder,
        covered: MutableList<ImportLine>,
        notImported: MutableList<ImportLine>,
    ) {
        val taskName = args?.optString("name").orEmpty().removePrefix("wine.")
        fun arg(key: String) = args?.opt(key)?.toString()?.takeIf { it.isNotBlank() }
        when (taskName) {
            "winetricks" -> arg("app").orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { verb ->
                val component = WINETRICKS_COMPONENTS[verb.lowercase()]
                    ?: D3DX9.takeIf { it.matches(verb.lowercase()) }?.let { "direct3d" }
                if (component != null) {
                    prefix.components += component
                } else {
                    notImported += ImportLine(
                        "Winetricks: $verb",
                        "Not one of the Windows components droidtop can switch on; install it by hand if the game needs it",
                    )
                }
            }
            "create_prefix" -> {
                covered += ImportLine("Create the prefix", "droidtop's prefix is made by Set up Windows games")
                args?.opt("overrides")?.let { prefix.overrides(it) }
            }
            "winekill", "eject_disc", "eject_disk" -> covered += ImportLine(
                "Stop Wine or eject a disc",
                "Nothing to do: droidtop runs no installer",
            )
            "wineexec" -> notImported += ImportLine(
                "Runs ${arg("executable") ?: "a program"} in the prefix",
                "droidtop never runs a program a script names",
            )
            "set_regedit" -> notImported += ImportLine(
                "Registry: ${arg("path") ?: ""}\\${arg("key") ?: ""} = ${arg("value") ?: ""}",
                "droidtop does not edit the registry; set it by hand if the game needs it",
            )
            "set_regedit_file", "delete_registry_key" -> notImported += ImportLine(
                "Registry change",
                "droidtop does not edit the registry; make it by hand if the game needs it",
            )
            else -> notImported += ImportLine("Task \"${taskName.ifEmpty { "unnamed" }}\"", "Not a task droidtop imports")
        }
    }

    private fun fileLine(id: String, value: Any?): ImportLine {
        val url = when (value) {
            is JSONObject -> value.optString("url")
            else -> value?.toString().orEmpty()
        }
        val detail = when {
            url.startsWith("N/A") -> "The installer asked for a file; this game's files are already in its folder"
            url.startsWith("\$STEAM") -> "Steam data is not imported"
            else -> "droidtop downloads nothing a script names" +
                (runCatching { java.net.URI(url).host }.getOrNull()?.let { " (from $it)" } ?: "")
        }
        return ImportLine("File \"$id\"", detail)
    }

    /**
     * A yes/no option as YAML writers actually spell it; an unreadable
     * value is recorded as not imported rather than guessed.
     */
    private fun boolean(value: Any?, label: String, notImported: MutableList<ImportLine>): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> null
        }
        else -> null
    } ?: run {
        notImported += ImportLine("$label \"$value\"", "Not a yes or no")
        null
    }

    private fun checkLimits(value: Any?, depth: Int) {
        if (depth > 16) throw LutrisScriptRefused("The script is nested too deeply")
        when (value) {
            is String -> if (value.length > MAX_STRING) throw LutrisScriptRefused("The script has a value longer than droidtop reads")
            is JSONObject -> {
                (value.opt("installer") as? JSONArray)?.let {
                    if (depth == 0 && it.length() > MAX_DIRECTIVES) throw LutrisScriptRefused("The script has more steps than droidtop reads")
                }
                (value.opt("files") as? JSONArray)?.let {
                    if (depth == 0 && it.length() > MAX_FILES) throw LutrisScriptRefused("The script lists more files than droidtop reads")
                }
                for (key in value.keys()) {
                    if (key.length > MAX_STRING) throw LutrisScriptRefused("The script has a key longer than droidtop reads")
                    checkLimits(value.opt(key), depth + 1)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) checkLimits(value.opt(index), depth + 1)
        }
    }

    /** Collects the prefix half, refusing whatever the allowlists do not name. */
    private class PrefixBuilder(private val notImported: MutableList<ImportLine>) {
        var dxvk: Boolean? = null
        var esync: Boolean? = null
        val components = linkedSetOf<String>()
        private val overrides = linkedMapOf<String, String>()
        private val env = linkedMapOf<String, String>()

        fun overrides(value: Any?) {
            val map = value as? JSONObject ?: run {
                notImported += ImportLine("DLL overrides", "Not a list of DLLs")
                return
            }
            for (key in map.keys()) {
                val dll = key.trim().removeSuffix(".dll").removeSuffix(".DLL")
                val mode = OVERRIDE_MODES[map.opt(key)?.toString()?.trim()?.lowercase()]
                when {
                    !DLL_NAME.matches(dll) -> notImported += ImportLine("DLL override $key", "Not a DLL name")
                    mode == null -> notImported += ImportLine(
                        "DLL override $key = ${map.opt(key)}",
                        "Not an override Lutris documents",
                    )
                    else -> overrides[dll] = mode
                }
            }
        }

        fun env(name: String, value: String) {
            when {
                name == "WINEDLLOVERRIDES" -> notImported += ImportLine(
                    "Environment WINEDLLOVERRIDES",
                    "DLL overrides are imported only from the script's overrides list",
                )
                name == "WINEESYNC" -> esync = value.trim() != "0"
                !ENV_NAMES.any { it(name) } -> notImported += ImportLine(
                    "Environment $name",
                    "Not one of the variables droidtop imports",
                )
                !ENV_VALUE.matches(value) -> notImported += ImportLine(
                    "Environment $name=$value",
                    "Paths, variables and spaces in a value are never imported",
                )
                else -> env[name] = value
            }
        }

        fun build() = WinePrefixChanges(dxvk, esync, components.toSet(), overrides.toMap(), env.toMap())
    }

    private val IDENTIFIER_KEYS = setOf("appid", "gogid", "humbleid", "game_id", "steamid")

    private val STEP_VERBS = mapOf(
        "extract" to "Unpacks",
        "move" to "Moves",
        "merge" to "Copies",
        "copy" to "Copies",
        "mkdir" to "Makes a folder",
        "chmodx" to "Makes executable",
        "rename" to "Renames",
    )

    /**
     * Winetricks verbs that install exactly what one of gamenative's own
     * Windows components carries (its `assets/wincomponents/
     * wincomponents.json`), and nothing else. A verb not here is not
     * imported: most (`dotnet48`, `vcrun2019`, `corefonts`, `win7`)
     * have no component, and switching on a nearby one would be a guess.
     */
    internal val WINETRICKS_COMPONENTS = mapOf(
        "d3dx10" to "direct3d",
        "d3dx10_43" to "direct3d",
        "d3dx11_42" to "direct3d",
        "d3dx11_43" to "direct3d",
        "d3dcompiler_42" to "direct3d",
        "d3dcompiler_43" to "direct3d",
        "d3dcompiler_46" to "direct3d",
        "d3dcompiler_47" to "direct3d",
        "dsound" to "directsound",
        "dinput8" to "directinput8",
        "dinput" to "directinput",
        "directmusic" to "directmusic",
        "quartz" to "directshow",
        "amstream" to "directshow",
        "qasf" to "directshow",
        "qcap" to "directshow",
        "qedit" to "directshow",
        "directplay" to "directplay",
        "xact" to "xaudio",
        "xact_x64" to "xaudio",
        "vcrun2010" to "vcrun2010",
    )

    /** `d3dx9` and `d3dx9_24` .. `d3dx9_43`, all inside the direct3d component. */
    private val D3DX9 = Regex("d3dx9(_(2[4-9]|3[0-9]|4[0-3]))?")

    private val DLL_NAME = Regex("[A-Za-z0-9_.-]{1,64}")

    /** Lutris's documented override values, in Wine's own spelling. */
    private val OVERRIDE_MODES = mapOf(
        "n" to "n",
        "native" to "n",
        "b" to "b",
        "builtin" to "b",
        "n,b" to "n,b",
        "native,builtin" to "n,b",
        "b,n" to "b,n",
        "builtin,native" to "b,n",
        "disabled" to "",
        "" to "",
    )

    /** Threat model, decision 5: tuning variables only, never loader or path variables. */
    private val ENV_NAMES: List<(String) -> Boolean> = listOf(
        { it.startsWith("DXVK_") },
        { it.startsWith("VKD3D_") },
        { it.startsWith("MESA_") },
        { it.startsWith("mesa_") },
        { it.startsWith("__GL_") },
        { it == "WINE_LARGE_ADDRESS_AWARE" },
        { it == "STAGING_SHARED_MEMORY" },
        { it == "PULSE_LATENCY_MSEC" },
    )

    private val ENV_VALUE = Regex("[A-Za-z0-9_.,:=+-]{0,256}")
}
