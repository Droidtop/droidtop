package dev.droidtop.library

import java.io.File

/**
 * A ROM entry is titled by its filename, which is right almost always and
 * useless for the layouts where the filename is a fixed part of the
 * engine rather than the name of the game. The rig showed four rows in
 * the Flash system all reading "LIBRARY": four `<game>/library.swf`
 * files, which is simply how that engine lays a game out. Four
 * indistinguishable rows is a library a person cannot use, and no amount
 * of polish above it survives that.
 *
 * The rule: a title shared by more than one entry is replaced by
 * something that is not shared. The containing folder is tried first,
 * because in every layout that produces this the folder IS the game's
 * name; when folders collide too, the entry falls back to its path
 * relative to the scanned folder, which cannot collide because two files
 * cannot have one path.
 *
 * Entries whose titles are already unique are returned untouched --
 * disambiguation never renames a game that did not need it.
 */
internal fun List<LibraryEntry>.disambiguateTitles(scannedFolder: File): List<LibraryEntry> {
    if (size < 2) return this
    val collisions = groupBy { it.title.lowercase() }.filterValues { it.size > 1 }.keys
    if (collisions.isEmpty()) return this

    val byFolder = mutableMapOf<String, Int>()
    forEach { entry ->
        if (entry.title.lowercase() in collisions) {
            val folder = File(entry.id).parentFile?.name ?: return@forEach
            byFolder[folder.lowercase()] = (byFolder[folder.lowercase()] ?: 0) + 1
        }
    }

    return map { entry ->
        if (entry.title.lowercase() !in collisions) return@map entry
        val file = File(entry.id)
        val folder = file.parentFile
        val folderName = folder?.name
        val replacement = when {
            // The folder names the game, and no sibling folder shares it.
            folderName != null &&
                folder.absolutePath != scannedFolder.absolutePath &&
                byFolder[folderName.lowercase()] == 1 -> folderName
            // Two files with one path do not exist, so this always parts them.
            else -> relativeLabel(file, scannedFolder)
        }
        entry.copy(title = replacement)
    }
}

/** `sonic/act1/library.swf` under the system folder becomes `sonic/act1/library`. */
private fun relativeLabel(file: File, scannedFolder: File): String {
    val full = file.absolutePath
    val base = scannedFolder.absolutePath.trimEnd(File.separatorChar) + File.separatorChar
    val relative = if (full.startsWith(base)) full.removePrefix(base) else file.name
    return relative.removeSuffix("." + file.extension).removeSuffix(".")
}
