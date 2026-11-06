package dev.droidtop.library.imports

/**
 * Pegasus's own files, read for an import (Droidtop/tracker#724): its collections from `metadata.pegasus.txt` (or
 * `metadata.txt`) and its favourites from `favorites.txt`. Only read: no ROM is touched, moved or copied, and nothing
 * is written back. Pure; the caller reads the files.
 *
 * Pegasus's metadata format: `key: value` lines; a line starting with whitespace continues the previous value (one
 * more item of a list such as `files`); `#` starts a comment; `collection:` opens a collection and `game:` a game.
 * A collection's own `files:` list and every later game's `file:`/`files:` belong to the collection last opened.
 */
object PegasusImport {
    /** One Pegasus collection: its name and its games' files, as the metadata names them (relative or absolute). */
    data class Collection(val name: String, val files: List<String>)

    fun parseMetadata(text: String): List<Collection> {
        val collections = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        var lastKey: String? = null
        for (raw in text.lineSequence()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            if (raw.first().isWhitespace()) {
                // A continuation: one more item of the last key's list.
                val value = raw.trim()
                if ((lastKey == "files" || lastKey == "file") && value.isNotEmpty()) current?.let { collections.getValue(it).add(value) }
                continue
            }
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val key = raw.substring(0, colon).trim().lowercase()
            val value = raw.substring(colon + 1).trim()
            lastKey = key
            when (key) {
                "collection" -> if (value.isNotEmpty()) {
                    current = value
                    collections.getOrPut(value) { mutableListOf() }
                }
                "file", "files" -> if (value.isNotEmpty()) current?.let { collections.getValue(it).add(value) }
            }
        }
        return collections.map { (name, files) -> Collection(name, files.distinct()) }
    }

    /** `favorites.txt`: one game file per line. */
    fun parseFavorites(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct().toList()

    /** Which library entries [files] name, and which files named none. */
    data class Match(val ids: List<String>, val unmatched: List<String>)

    /**
     * Matches Pegasus's file paths to the library's ROM entries ([entryPaths]: each entry's id, an absolute path): an
     * absolute path that is the entry's, else the entry whose path ends with the file's relative path (so the import
     * works whatever folder the metadata sits in), else the one entry with that file name; one that names several is
     * not matched. Pure.
     */
    fun match(files: List<String>, entryPaths: List<String>): Match {
        val byName = entryPaths.groupBy { it.substringAfterLast('/') }
        val ids = mutableListOf<String>()
        val unmatched = mutableListOf<String>()
        for (file in files) {
            val clean = file.replace('\\', '/').removePrefix("./")
            // A path that names more than one entry is not guessed at: it is reported as not found.
            val found = entryPaths.firstOrNull { it == clean }
                ?: entryPaths.filter { it.endsWith("/$clean") }.singleOrNull()
                ?: byName[clean.substringAfterLast('/')]?.singleOrNull()
            if (found != null) ids.add(found) else unmatched.add(file)
        }
        return Match(ids.distinct(), unmatched)
    }

    /** The one line an import reports: "12 of 15 games found; 3 not in your library". Pure. */
    fun reportLine(matched: Int, total: Int, what: String): String {
        val missing = total - matched
        return "$what: $matched of $total games found" + if (missing > 0) "; $missing not in your library" else ""
    }
}
