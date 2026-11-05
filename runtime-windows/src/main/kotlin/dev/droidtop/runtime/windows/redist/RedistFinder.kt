package dev.droidtop.runtime.windows.redist

import java.io.File

/**
 * The redistributable installers a game ships, where the stores and engines put them (docs/SPEC.md 9, "Redistributable
 * installers"; Droidtop/tracker#916), as Steam and Heroic find them before a game's first start:
 * - Steam's `installscript.vdf`: its "Run Process" entries' `process N` paths (`%INSTALLDIR%` is the game's folder);
 * - Unreal's `Engine/Extras/Redist/<language>/`;
 * - Steam's `_CommonRedist/<product>/<version>/`;
 * - GOG's `__redist/<product>/`.
 * Only `.exe` files are taken (MSI packages come in a later slice), and each is then identified by [PeInfo] and
 * [RedistTable], never by its name or folder.
 */
object RedistFinder {
    private const val MAX_DEPTH = 4
    private const val MAX_FILES = 64

    /** The installers under [gameRoot], each once, in the order above. Disk work. */
    fun find(gameRoot: File): List<File> {
        val found = LinkedHashSet<File>()
        installScripts(gameRoot).forEach { script ->
            runCatching { script.readText() }.getOrNull()?.let { text ->
                runProcessPaths(text).mapNotNull { resolve(gameRoot, it) }.forEach { found += it }
            }
        }
        listOf("Engine/Extras/Redist", "_CommonRedist", "__redist", "redist", "Redist").forEach { dir ->
            File(gameRoot, dir).takeIf { it.isDirectory }?.let { exes(it, MAX_DEPTH).forEach { found += it } }
        }
        return found.filter { it.isFile }.take(MAX_FILES)
    }

    /** `installscript.vdf` files in [gameRoot] or one or two folders down (Steam puts some under `_CommonRedist`). */
    private fun installScripts(gameRoot: File): List<File> =
        gameRoot.walkTopDown().maxDepth(3).filter { it.isFile && it.name.equals("installscript.vdf", ignoreCase = true) }.toList()

    private fun exes(dir: File, depth: Int): List<File> =
        dir.walkTopDown().maxDepth(depth).filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }.sortedBy { it.path }.toList()

    /** A script path made a file under [gameRoot]: `%INSTALLDIR%` is the game's folder, `\` is `/`, case is whatever is on disk. */
    internal fun resolve(gameRoot: File, path: String): File? {
        val relative = path.replace('\\', '/').replace(Regex("""(?i)^%INSTALLDIR%/?"""), "")
        if (relative.contains(':') || relative.startsWith("/") || relative.split('/').any { it == ".." }) return null
        var current = gameRoot
        for (part in relative.split('/').filter { it.isNotEmpty() }) {
            current = current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return current.takeIf { it.isFile }
    }

    /** The `process N` values of every entry under "Run Process" in an installscript.vdf's text. Pure. */
    internal fun runProcessPaths(vdf: String): List<String> {
        val root = Vdf.parse(vdf)
        fun find(node: Map<String, Any>, key: String): Map<String, Any>? {
            node.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { @Suppress("UNCHECKED_CAST") return it.value as? Map<String, Any> }
            for (child in node.values) {
                @Suppress("UNCHECKED_CAST")
                (child as? Map<String, Any>)?.let { find(it, key) }?.let { return it }
            }
            return null
        }
        val runProcess = find(root, "Run Process") ?: return emptyList()
        return runProcess.values.flatMap { entry ->
            @Suppress("UNCHECKED_CAST")
            (entry as? Map<String, Any>)?.entries.orEmpty()
                .filter { it.key.lowercase().matches(Regex("""process\s*\d+""")) }
                .mapNotNull { it.value as? String }
        }
    }
}

/** Valve's KeyValues text, as installscript.vdf uses it: quoted or bare keys, string values, `{}` blocks, `//` comments. */
internal object Vdf {
    fun parse(text: String): Map<String, Any> {
        val tokens = tokens(text)
        var i = 0
        fun block(): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            while (i < tokens.size) {
                val key = tokens[i++]
                if (key == "}") break
                if (key == "{") continue
                if (i >= tokens.size) break
                val next = tokens[i++]
                if (next == "{") out[key] = block() else if (next != "}") out[key] = next else break
            }
            return out
        }
        return block()
    }

    private fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> { while (i < text.length && text[i] != '\n') i++ }
                c == '{' || c == '}' -> { out += c.toString(); i++ }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in "\\\"") { sb.append(text[i + 1]); i += 2 } else sb.append(text[i++])
                    }
                    i++
                    out += sb.toString()
                }
                else -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace() && text[i] !in "{}\"") i++
                    out += text.substring(start, i)
                }
            }
        }
        return out
    }
}
