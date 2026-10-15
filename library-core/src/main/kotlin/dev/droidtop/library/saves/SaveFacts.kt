package dev.droidtop.library.saves

import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * The readers an engine save rule names in its `facts` (docs/FORMAT.md "Save rules"): each reads one value out of
 * the game's own files, because the folder an engine saves in is named by the game (Ren'Py's
 * `config.save_directory`, Godot's project name, LOVE's identity). A value that cannot be read is null, and the
 * rule then says so ([SaveStatus.Unknown]): a name is never guessed.
 *
 * Text first, and every reader has a byte cap. A compiled Ren'Py file is read with zlib only, never unpickled
 * (docs/SPEC.md 7o "Saves"). Godot's packed project (`.pck`) and Ren'Py's `.rpa` archives are not read yet.
 *
 * File work: never on the main thread.
 */
object SaveFacts {
    /** The most a text source is read for. */
    const val MAX_TEXT_BYTES = 512 * 1024

    /** The most a compiled source may inflate to. */
    const val MAX_INFLATED_BYTES = 4 * 1024 * 1024

    const val RENPY_SAVE_DIRECTORY = "renpy.save_directory"
    const val GODOT_NAME = "godot.name"
    const val GODOT_CUSTOM_DIR = "godot.custom_dir"
    const val LOVE_IDENTITY = "love.identity"

    /** Whether droidtop has a reader of this name (the data format refuses any other). */
    fun hasReader(name: String): Boolean = name in setOf(RENPY_SAVE_DIRECTORY, GODOT_NAME, GODOT_CUSTOM_DIR, LOVE_IDENTITY)

    /** The value reader [reader] finds in the game at [folder], or null. */
    fun read(reader: String, folder: File): String? = runCatching {
        when (reader) {
            RENPY_SAVE_DIRECTORY -> renpy(folder)
            GODOT_NAME -> godot(folder)[GODOT_NAME]
            GODOT_CUSTOM_DIR -> godot(folder)[GODOT_CUSTOM_DIR]
            LOVE_IDENTITY -> love(folder)
            else -> null
        }
    }.getOrNull()

    private fun text(file: File): String? =
        if (file.isFile && file.length() <= MAX_TEXT_BYTES) file.readText(Charsets.UTF_8) else null

    // --- Ren'Py -----------------------------------------------------------------------------------------------

    private val RENPY_DEFINE = Regex("""^\s*(?:define\s+)?config\.save_directory\s*=\s*[uU]?[rR]?(["'])(.*?)\1""", RegexOption.MULTILINE)

    private fun renpy(folder: File): String? {
        val game = File(folder, "game")
        text(File(game, "options.rpy"))?.let { source -> renpyOptions(source)?.let { return it } }
        val compiled = File(game, "options.rpyc")
        if (compiled.isFile && compiled.length() <= MAX_TEXT_BYTES) return renpyCompiled(compiled.readBytes())
        return null
    }

    /** `config.save_directory` as `options.rpy` defines it, or null (not defined, or `None`). */
    internal fun renpyOptions(source: String): String? =
        RENPY_DEFINE.find(source)?.groupValues?.get(2)?.takeIf { it.isNotBlank() && it != "None" }

    /**
     * `config.save_directory` from a compiled `options.rpyc` (the "RENPY RPC2" container: a table of slots, slot 1 a
     * zlib stream of the pickled script). The value is the string near the name `save_directory` in the inflated
     * bytes that has the form Ren'Py's own project creation writes, `Name-<digits>`; any other form is not read
     * rather than guessed at.
     */
    internal fun renpyCompiled(bytes: ByteArray): String? {
        val magic = "RENPY RPC2".toByteArray(Charsets.ISO_8859_1)
        if (bytes.size < magic.size + 12 || !bytes.copyOf(magic.size).contentEquals(magic)) return null
        fun le32(at: Int): Long =
            (bytes[at].toLong() and 0xff) or ((bytes[at + 1].toLong() and 0xff) shl 8) or
                ((bytes[at + 2].toLong() and 0xff) shl 16) or ((bytes[at + 3].toLong() and 0xff) shl 24)
        var pos = magic.size
        var start = -1L
        var length = -1L
        while (pos + 12 <= bytes.size) {
            val slot = le32(pos)
            val st = le32(pos + 4)
            val ln = le32(pos + 8)
            pos += 12
            if (slot == 0L) break
            if (slot == 1L) {
                start = st
                length = ln
            }
        }
        if (start < 0 || length <= 0 || start + length > bytes.size) return null
        val inflated = inflate(bytes, start.toInt(), length.toInt()) ?: return null
        val script = String(inflated, Charsets.ISO_8859_1)
        val candidates = Regex("\"([^\"/\\\\\\u0000-\\u001f]{1,100}-\\d+)\"").findAll(script).toList()
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        var at = script.indexOf("save_directory")
        while (at >= 0) {
            for (candidate in candidates) {
                val distance = kotlin.math.abs(candidate.range.first - at)
                if (distance <= 240 && distance < bestDistance) {
                    best = candidate.groupValues[1]
                    bestDistance = distance
                }
            }
            at = script.indexOf("save_directory", at + 1)
        }
        return best
    }

    private fun inflate(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, offset, length)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_INFLATED_BYTES) return null
            }
            return out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    // --- Godot ------------------------------------------------------------------------------------------------

    /** Godot's OS::get_safe_dir_name (core/os/os.cpp:251-276), the name the engine gives a folder. */
    internal fun godotSafeDirName(name: String, allowPaths: Boolean): String {
        var safe = name
        val invalid = mutableListOf(":", "*", "?", "\"", "<", ">", "|")
        if (allowPaths) {
            invalid += ".."
            safe = safe.replace('\\', '/').replace("//", "/").trim()
        } else {
            invalid += "/"
            invalid += "\\"
            safe = safe.trim()
            if (safe == ".") safe = "dot" else if (safe == "..") safe = "twodots"
        }
        for (bad in invalid) safe = safe.replace(bad, "-")
        return safe
    }

    /** `godot.name` and, when the project uses a custom user directory, `godot.custom_dir`, from `project.godot`. */
    internal fun godot(folder: File): Map<String, String> {
        val source = text(File(folder, "project.godot")) ?: return emptyMap()
        return godotProject(source)
    }

    internal fun godotProject(source: String): Map<String, String> {
        var inApplication = false
        var name: String? = null
        var custom = false
        var customName: String? = null
        for (raw in source.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inApplication = line == "[application]"
                continue
            }
            if (!inApplication || line.startsWith(";") || '=' !in line) continue
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            when (key) {
                "config/name" -> name = value.removeSurrounding("\"")
                "config/use_custom_user_dir" -> custom = value == "true"
                "config/custom_user_dir_name" -> customName = value.removeSurrounding("\"")
            }
        }
        val app = name?.let { godotSafeDirName(it, allowPaths = false) }?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        val out = linkedMapOf(GODOT_NAME to app)
        if (custom) {
            val dir = customName?.let { godotSafeDirName(it, allowPaths = true) }?.takeIf { it.isNotEmpty() } ?: app
            out[GODOT_CUSTOM_DIR] = dir
        }
        return out
    }

    // --- LOVE -------------------------------------------------------------------------------------------------

    private val LOVE_IDENTITY_SET = Regex("""^\s*t\.identity\s*=\s*(["'])(.*?)\1""", RegexOption.MULTILINE)

    /** `t.identity` from `conf.lua`, in the game folder or in a `.love` file there. */
    private fun love(folder: File): String? {
        text(File(folder, "conf.lua"))?.let { source -> loveConf(source)?.let { return it } }
        val packs = folder.listFiles { f -> f.isFile && f.name.endsWith(".love", ignoreCase = true) }.orEmpty()
        for (pack in packs.sortedBy { it.name }) {
            val source = runCatching {
                ZipFile(pack).use { zip ->
                    val entry = zip.getEntry("conf.lua") ?: return@use null
                    if (entry.size > MAX_TEXT_BYTES) null else zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }.getOrNull() ?: continue
            loveConf(source)?.let { return it }
        }
        return null
    }

    internal fun loveConf(source: String): String? {
        val live = source.lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        return LOVE_IDENTITY_SET.find(live)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
    }
}
