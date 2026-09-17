package dev.droidtop.library

import java.io.File

/**
 * One game as the library shows it: the [GroupedGame] its folders add up
 * to, and the [LibraryEntry] each of those folders actually is.
 *
 * The entries are what everything else in droidtop already works with --
 * launching, artwork, scraped metadata, runner resolution -- so grouping
 * adds a layer over them rather than replacing them: the list draws one
 * card per group, and the detail screen reaches the rest through the
 * group. Nothing below this layer changes, which is why a themed ES-DE
 * gamelist (which lists entries, by ES-DE's own schema) still works
 * unchanged.
 */
data class LibraryGameGroup(
    val game: GroupedGame,
    /** Every entry in this game, by the path that found it. */
    val entriesByPath: Map<String, LibraryEntry>,
) {
    /** The version and copy Play starts: newest version of the first segment. */
    val defaultCopy: GameCopy? get() = game.defaultVersion?.playable

    /** The entry the list draws, titled with the game's own name. */
    val displayEntry: LibraryEntry
        get() {
            val entry = defaultCopy?.let { entriesByPath[it.path] }
                ?: entriesByPath.values.first()
            return if (entry.title == game.name) entry else entry.copy(title = game.name)
        }

    /** How many folders this one card stands for. */
    val folders: Int get() = entriesByPath.size

    /** The entry one copy of this game is, or null when the scan no longer has it. */
    fun entryFor(copy: GameCopy): LibraryEntry? = entriesByPath[copy.path]

    /** Whether this game is more than one folder -- the only case the picker is worth drawing. */
    val hasChoices: Boolean
        get() = game.segments.size > 1 || game.allVersions.size > 1 || game.allVersions.any { it.copies.size > 1 }
}

/**
 * Folding a scan's [LibraryEntry] list into games (docs/SPEC.md 7m).
 *
 * An entry whose id is a path on this device is a folder the scan found,
 * so [GameNaming] can say what its name, version and segment are. An entry
 * with any other id is a store row (`steam:440`) whose name the store
 * already gave: it becomes a group of one under its own title, because
 * deriving a version out of a store's title would be guessing where a real
 * answer exists.
 */
object LibraryGrouping {

    /** Every game in [entries], in the order their names sort. */
    fun group(entries: List<LibraryEntry>): List<LibraryGameGroup> {
        val byPath = entries.filter { it.id.isFolderPath() }.associateBy { it.id }
        val grouped = GameGrouping.group(byPath.keys.map { GameGrouping.Found(path = it, installed = byPath[it]?.pcInfo?.installed != false) })
            .map { game -> LibraryGameGroup(game, game.allVersions.flatMap { it.copies }.mapNotNull { copy -> byPath[copy.path]?.let { copy.path to it } }.toMap()) }
            .filter { it.entriesByPath.isNotEmpty() }
        val ungrouped = entries.filterNot { it.id.isFolderPath() }.map { entry ->
            LibraryGameGroup(
                GroupedGame(entry.title, listOf(GameVersion(version = "", copies = listOf(GameCopy(path = entry.id))))),
                mapOf(entry.id to entry),
            )
        }
        return (grouped + ungrouped).sortedBy { it.game.name.lowercase() }
    }

    /**
     * The group [entry] belongs to, among [among] -- what a detail screen
     * asks so that it can offer the game's other versions and segments.
     * Null when [entry] is not in [among] at all.
     */
    fun groupOf(entry: LibraryEntry, among: List<LibraryEntry>): LibraryGameGroup? =
        group(among).firstOrNull { it.entriesByPath.containsKey(entry.id) }

    /**
     * A store row's id is `steam:440`; a scanned game's id is where it is.
     * Only the second can be read as a folder name.
     */
    private fun String.isFolderPath(): Boolean = startsWith(File.separator) || startsWith("/")
}
