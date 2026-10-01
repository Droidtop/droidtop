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

    /**
     * The entry the list draws, titled with the game's own name and
     * carrying the game's available update, which no one folder can know
     * (docs/SPEC.md 7g).
     */
    val displayEntry: LibraryEntry
        get() {
            val entry = defaultCopy?.let { entriesByPath[it.path] }
                ?: entriesByPath.values.first()
            val update = game.availableUpdate
            return if (entry.title == game.name && entry.availableUpdate == update) {
                entry
            } else {
                entry.copy(title = game.name, availableUpdate = update)
            }
        }

    /** How many folders this one card stands for. */
    val folders: Int get() = entriesByPath.size

    /** The F95zone thread the user linked to this game, from whichever of its folders holds the link. */
    val f95Thread: Long? get() = entriesByPath.values.firstNotNullOfOrNull { it.f95Thread }

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
 * with a store's id (`steam:440`) is a store row whose name the store
 * already gave, so no version is derived from it; the rows of one game
 * owned on several stores become ONE group with a copy per store
 * ([StoreIdentity], docs/SPEC.md 7m). Any other id is a group of one.
 */
object LibraryGrouping {

    /** Every game in [entries], in the order their names sort. */
    fun group(entries: List<LibraryEntry>): List<LibraryGameGroup> {
        val byPath = entries.filter { it.id.isFolderPath() }.associateBy { it.id }
        val grouped = GameGrouping.group(
            byPath.values.map { entry ->
                GameGrouping.Found(
                    path = entry.id,
                    installed = entry.pcInfo?.installed != false,
                    latestKnown = entry.latestKnown,
                    name = entry.gameName,
                )
            },
        )
            .map { game -> LibraryGameGroup(game, game.allVersions.flatMap { it.copies }.mapNotNull { copy -> byPath[copy.path]?.let { copy.path to it } }.toMap()) }
            .filter { it.entriesByPath.isNotEmpty() }
        val (stores, ungroupedRows) = entries.filterNot { it.id.isFolderPath() }.partition { it.ownership() != null }
        val storeGames = StoreIdentity.group(stores).map { merged ->
            val copies = merged.entries.map { entry ->
                GameCopy(path = entry.id, source = entry.pcInfo?.source, installed = entry.pcInfo?.installed != false)
            }
            LibraryGameGroup(
                GroupedGame(merged.name, listOf(GameVersion(version = "", copies = copies))),
                merged.entries.associateBy { it.id },
            )
        }
        val ungrouped = ungroupedRows.map { entry ->
            LibraryGameGroup(
                GroupedGame(entry.title, listOf(GameVersion(version = "", copies = listOf(GameCopy(path = entry.id))))),
                mapOf(entry.id to entry),
            )
        }
        return (grouped + storeGames + ungrouped).sortedBy { it.game.name.lowercase() }
    }

    /**
     * A store row's id is `steam:440`; a scanned game's id is where it is.
     * Only the second can be read as a folder name.
     */
    private fun String.isFolderPath(): Boolean = startsWith(File.separator) || startsWith("/")
}
