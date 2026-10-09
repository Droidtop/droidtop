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

/**
 * A game folder's name as the entry's title: what the one title parser says
 * ([GameTitleParser], docs/SPEC.md 7n), so the engine walk's entries read
 * like the PC walk's. The rig showed the textlist drawing `12.0-SCRAPPY`,
 * `Eternum-0.9.5-pc` and `Name_ver1.1-eng` because the entry stored the
 * folder name as it is on disk (Droidtop/tracker#174, #282); the raw name
 * stays on the game page and in the entry id.
 *
 * The one shape a folder name is not a title is an episode folder. Seen on
 * the rig: a Ren'Py series ships one runnable game per episode
 * (`Thief of Hearts/Part1`, `Fetish Locator/Week 1`, `BeingADik/Chap3+`,
 * `ThiefofHeartsPart3-0.0.9-pc`), so the library listed "PART1", "WEEK 1"
 * and "CHAP3+" as games with nothing to say which series they belong to.
 * These are real separate games and must stay separate entries, so the
 * segment the parser read is appended to the series title. Unlike
 * [disambiguateTitles], which repairs a collision after the fact, this is a
 * naming rule that does not need two entries to notice the problem.
 *
 * A folder that is only a version (`Some Game/12.0-scrappy`) is titled by
 * the game above it plus that version, so sibling versions stay told apart.
 * [root] is the games root the folder is under, when known: nothing at or
 * above it is read as a title.
 *
 * Which folder names are sequence markers is [GameNaming]'s answer, not a
 * second list here (docs/SPEC.md 7m). This names ONE entry, which is what a
 * surface listing entries (a themed ES-DE gamelist, by ES-DE's own schema)
 * needs; grouping decides how many entries a game is.
 */
fun qualifiedFolderTitle(folder: File, root: File? = null): String {
    val parsed = GameTitleParser.parse(folder.absolutePath, root?.absolutePath)
    // A folder nothing identifies keeps the only name it has.
    if (parsed.unidentified || parsed.title.isBlank()) return folder.name
    val segment = parsed.part?.label?.takeIf { it.isNotBlank() }
    if (segment != null) return "${parsed.title} - $segment"
    val ownTitle = GameTitleParser.parseName(folder.name).title
    return if (parsed.version.isNotEmpty() && ownTitle != parsed.title) "${parsed.title} ${parsed.version}" else parsed.title
}
