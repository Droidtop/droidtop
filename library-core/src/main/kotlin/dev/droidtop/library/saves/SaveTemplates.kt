package dev.droidtop.library.saves

import java.io.File
import java.nio.file.Files

/**
 * Where a game keeps its saves, as templates in the vocabulary the agent core already uses (droidtop-agent
 * `crates/core/src/saves.rs`, Ludusavi's): a root token, then a path that may hold `*` and `?`
 * (`<winAppData>/Game/` and then a file glob). Each side resolves the tokens in its own copy of the game; a file is named by its
 * token and the path below it with forward slashes, compared without case.
 *
 * This is the same walk the core makes (docs/SPEC.md 7o "Saves"): no link is followed, depth is bounded, and a name
 * that could leave its root is refused. The agent-core move (Droidtop/tracker#546, slice 1) will give the one
 * implementation to both sides; until then this reads, and never writes, so the two cannot disagree about a file.
 *
 * Every function here does file work: never on the main thread.
 */
object SaveTemplates {
    const val BASE = "<base>"
    const val ENGINE_DATA = "<engineData>"
    const val EMU_SAVES = "<emuSaves>"
    const val EMU_STATES = "<emuStates>"

    /** The tokens the agent core knows (saves.rs TOKENS), then the ones an engine or emulator rule adds. */
    val TOKENS: List<String> = listOf(
        BASE, "<home>", "<winAppData>", "<winLocalAppData>", "<winLocalAppDataLow>", "<winDocuments>",
        "<winPublic>", "<winProgramData>", "<winDir>", "<xdgData>", "<xdgConfig>",
        ENGINE_DATA, EMU_SAVES, EMU_STATES,
    )

    /** How deep a folder named by a template is walked. */
    const val MAX_DEPTH = 12

    /** The most files one search reports, and so the most it ever walks into a list. */
    const val MAX_FILES = 4096

    /** A template split into its token and path components. */
    data class Split(val token: String, val parts: List<String>)

    /** A template's token and path, or null when it starts with no known token or climbs out of it. */
    fun split(pattern: String): Split? {
        val text = pattern.replace('\\', '/')
        val token = TOKENS.firstOrNull { text.startsWith(it) } ?: return null
        val rest = text.substring(token.length)
        if (rest.isNotEmpty() && !rest.startsWith("/")) return null
        val parts = rest.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return Split(token, parts)
    }

    /** `*` and `?` against one name, without case. */
    fun glob(pattern: String, name: String): Boolean {
        val p = pattern.lowercase()
        val n = name.lowercase()
        var pi = 0
        var ni = 0
        var star = -1
        var mark = 0
        while (ni < n.length) {
            if (pi < p.length && (p[pi] == '?' || p[pi] == n[ni])) {
                pi++
                ni++
            } else if (pi < p.length && p[pi] == '*') {
                star = pi
                mark = ni
                pi++
            } else if (star >= 0) {
                pi = star + 1
                mark++
                ni = mark
            } else {
                return false
            }
        }
        while (pi < p.length && p[pi] == '*') pi++
        return pi == p.length
    }

    private fun hasGlob(part: String) = part.contains('*') || part.contains('?')

    /** One file a search found. [root] is the folder its token stood for; [name] is its canonical name. */
    data class SaveFile(val name: String, val file: File, val size: Long, val modifiedMs: Long, val root: File)

    /** What a search found: its files, and whether it stopped at [MAX_FILES]. */
    data class Scan(val files: List<SaveFile>, val truncated: Boolean) {
        val bytes: Long get() = files.sumOf { it.size }
        val newestMs: Long get() = files.maxOfOrNull { it.modifiedMs } ?: 0L
    }

    private class Entry(val name: String, val file: File, val isDir: Boolean)

    /** A folder's entries, links skipped: a save set never follows one. */
    private fun entries(dir: File): List<Entry> {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
        val out = ArrayList<Entry>(children.size)
        for (child in children) {
            if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)) continue
            out += Entry(child.name, child, child.isDirectory)
        }
        out.sortBy { it.name }
        return out
    }

    private fun safeComponent(part: String): Boolean =
        part.isNotEmpty() && part != "." && part != ".." && part.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }

    /**
     * Every file [patterns] name under [roots] (a token's folders: more than one when a rule has several places to
     * look). A pattern whose token has no root is skipped. At most [MAX_FILES] files are reported.
     */
    fun collect(patterns: List<String>, roots: Map<String, List<File>>): Scan {
        val found = LinkedHashMap<String, SaveFile>()
        var truncated = false
        fun add(name: String, file: File, root: File): Boolean {
            if (found.size >= MAX_FILES) {
                truncated = true
                return false
            }
            val key = file.path.lowercase()
            if (key !in found) {
                found[key] = SaveFile(name, file, runCatching { file.length() }.getOrDefault(0L), runCatching { file.lastModified() }.getOrDefault(0L), root)
            }
            return true
        }
        for (pattern in patterns) {
            val split = split(pattern) ?: continue
            for (root in roots[split.token].orEmpty()) {
                if (!root.isDirectory) continue
                walk(root, split.parts, split.token, 0, root, ::add)
                if (truncated) break
            }
            if (truncated) break
        }
        return Scan(found.values.sortedBy { it.name.lowercase() }, truncated)
    }

    private fun walk(dir: File, parts: List<String>, name: String, depth: Int, root: File, add: (String, File, File) -> Boolean): Boolean {
        if (depth > MAX_DEPTH) return true
        val first = parts.firstOrNull() ?: return everythingUnder(dir, name, depth, root, add)
        val rest = parts.drop(1)
        if (first == "**") {
            if (!walk(dir, rest, name, depth, root, add)) return false
            for (entry in entries(dir)) {
                if (entry.isDir && !walk(entry.file, parts, "$name/${entry.name}", depth + 1, root, add)) return false
            }
            return true
        }
        for (entry in entries(dir)) {
            val hit = if (hasGlob(first)) glob(first, entry.name) else entry.name.equals(first, ignoreCase = true)
            if (!hit || !safeComponent(entry.name)) continue
            val childName = "$name/${entry.name}"
            if (entry.isDir) {
                if (!walk(entry.file, rest, childName, depth + 1, root, add)) return false
            } else if (rest.isEmpty()) {
                if (!add(childName, entry.file, root)) return false
            }
        }
        return true
    }

    private fun everythingUnder(dir: File, name: String, depth: Int, root: File, add: (String, File, File) -> Boolean): Boolean {
        if (depth > MAX_DEPTH) return true
        for (entry in entries(dir)) {
            if (!safeComponent(entry.name)) continue
            val childName = "$name/${entry.name}"
            if (entry.isDir) {
                if (!everythingUnder(entry.file, childName, depth + 1, root, add)) return false
            } else if (!add(childName, entry.file, root)) {
                return false
            }
        }
        return true
    }

    /**
     * [template] with each `{name}` or `{first|second}` filled from [values] (the first alternative that has a value),
     * or null when one has none. A value that would climb out of its place, or a path separator inside a name that is
     * meant to be one component, is refused as missing.
     */
    fun fill(template: String, values: Map<String, String?>): String? {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '{') {
                out.append(c)
                i++
                continue
            }
            val end = template.indexOf('}', i)
            if (end < 0) return null
            val value = template.substring(i + 1, end).split('|').firstNotNullOfOrNull { values[it]?.takeIf { v -> v.isNotBlank() } } ?: return null
            if (value.replace('\\', '/').split('/').any { it == ".." }) return null
            // A glob character in a name is only ever the character itself.
            out.append(value.replace('*', '?'))
            i = end + 1
        }
        return out.toString()
    }

    /** The names of the placeholders in [template]. */
    fun placeholders(template: String): List<String> =
        Regex("\\{([^{}]*)\\}").findAll(template).flatMap { it.groupValues[1].split('|') }.toList()
}
