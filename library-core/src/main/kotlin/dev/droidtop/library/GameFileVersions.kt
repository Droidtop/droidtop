package dev.droidtop.library

import java.io.File
import org.json.JSONObject

/**
 * The version a game's own files declare (docs/SPEC.md 7i, "The game page";
 * Droidtop/tracker#406), for a folder game whose name carries none: Ren'Py's
 * `define config.version = "..."` in `game/options.rpy`, and the `version`
 * an RPG Maker MV or MZ developer put in `package.json` (the editor's own
 * template has none, so a value there is the developer's). Read by the engine
 * walk for a folder whose name names no version, and kept on the entry as
 * [LibraryEntry.filesVersion]; never while a list is drawn: a few small file
 * reads, not for the main thread.
 */
object GameFileVersions {
    /** Bigger than any real options.rpy or package.json; a file past it is not read. */
    private const val MAX_BYTES = 512 * 1024L

    private val RENPY_VERSION = Regex("""^\s*define\s+config\.version\s*=\s*[urUR]?(["'])(.+?)\1""", RegexOption.MULTILINE)

    /** The version [folder]'s files declare, or null. File reads. */
    fun read(folder: File): String? =
        text(File(folder, "game/options.rpy"))?.let(::fromRenPyOptions)
            ?: listOf(File(folder, "package.json"), File(folder, "www/package.json"))
                .firstNotNullOfOrNull { file -> text(file)?.let(::fromPackageJson) }

    /** `config.version` from an options.rpy's text, or null. Pure. */
    fun fromRenPyOptions(text: String): String? =
        RENPY_VERSION.find(text)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }

    /** The `version` string from an nw.js package.json's text, or null. Pure. */
    fun fromPackageJson(text: String): String? =
        runCatching { JSONObject(text).optString("version") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun text(file: File): String? =
        file.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.let { runCatching { it.readText() }.getOrNull() }
}
