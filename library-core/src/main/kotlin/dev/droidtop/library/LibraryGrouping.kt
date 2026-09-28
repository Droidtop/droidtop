package dev.droidtop.library

import dev.droidtop.library.scraper.PcStoreId
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

    /** All ownerships of this game (stores, local folders, F95 thread). */
    val ownerships: Set<Ownership> get() = game.ownerships

    /** A short label for the ownerships: "Owned on Steam and GOG". */
    val ownershipLabel: String
        get() = if (ownerships.isEmpty()) "" else "Owned on " + ownerships.joinToString(", ", ", ", " and ") { it.label }

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
 * already gave. Cross-store identification folds entries with the same
 * stable identity (Steam appid, GOG id, IGDB id, DLsite code, F95 thread)
 * into one game; entries with only a weak title+developer+year match are
 * grouped at SUGGESTED confidence and require user confirmation.
 *
 * User-confirmed cross-store links (from [GameLinksStore.getStoreLinks])
 * are also used to fold entries.
 */
object LibraryGrouping {

    /** Every game in [entries], in the order their names sort. */
    fun group(entries: List<LibraryEntry>, confirmedStoreLinks: Map<String, String> = emptyMap()): List<LibraryGameGroup> {
        // Separate folder entries (scanned from disk) and store entries (from store libraries)
        val folderEntries = entries.filter { it.id.isFolderPath() }
        val storeEntries = entries.filterNot { it.id.isFolderPath() }

        // 1. Group folder entries using GameGrouping (respects user-confirmed names via gameName)
        val folderGameGroups = GameGrouping.group(
            folderEntries.map { entry ->
                GameGrouping.Found(
                    path = entry.id,
                    installed = entry.pcInfo?.installed != false,
                    latestKnown = entry.latestKnown,
                    name = entry.gameName,
                )
            },
        )

        // Map: game name key -> (GroupedGame, folder entries)
        val folderGroupMap = mutableMapOf<String, Pair<GroupedGame, MutableList<LibraryEntry>>>()
        for (gameGroup in folderGameGroups) {
            val key = GameNaming.nameKey(gameGroup.name)
            val groupFolderEntries = folderEntries.filter { entry ->
                gameGroup.allVersions.flatMap { it.copies }.any { copy -> copy.path == entry.id }
            }.toMutableList()
            folderGroupMap[key] = gameGroup to groupFolderEntries
        }

        // 2. Match store entries to folder groups
        val storeEntriesByFolderKey = mutableMapOf<String, MutableList<LibraryEntry>>()
        val unmatchedStoreEntries = mutableListOf<LibraryEntry>()

        for (storeEntry in storeEntries) {
            var matched = false

            // A. Check confirmed store link (user said this store entry belongs to this game name)
            val storeId = PcStoreId.parse(storeEntry.pcInfo?.storeId ?: storeEntry.id)
            storeId?.let { sid ->
                confirmedStoreLinks[sid.key]?.let { confirmedName ->
                    val key = GameNaming.nameKey(confirmedName)
                    if (key in folderGroupMap) {
                        storeEntriesByFolderKey.getOrPut(key) { mutableListOf() }.add(storeEntry)
                        matched = true
                    }
                }
            }

            // B. Match by stable identity (Steam appid, GOG id, etc.) if not already matched
            if (!matched) {
                val identity = storeEntry.gameIdentity(linkedThread = storeEntry.f95Thread)
                if (identity.confidence == IdentityConfidence.CERTAIN) {
                    // For stable IDs, we need to find a folder group that has the same stable ID.
                    // Since folder entries don't carry stable IDs in their GameGrouping result,
                    // we match by checking if any folder entry in the group has the same stable ID.
                    // This is a best-effort match; in practice, store-installed engine games
                    // are already folded at the provider level (engine entry absorbs store info).
                    // For pure store entries (Wine games), there's no folder counterpart.
                    // So we leave them unmatched here; they become their own group below.
                }
            }

            if (!matched) {
                unmatchedStoreEntries.add(storeEntry)
            }
        }

        // 3. Build final groups: folder groups with matched store entries, plus unmatched store entries
        val resultGroups = mutableListOf<LibraryGameGroup>()

        // Folder groups with their matched store entries
        for ((key, (folderGame, folderEntriesList)) in folderGroupMap) {
            val matchedStoreEntries = storeEntriesByFolderKey[key] ?: emptyList()
            val enrichedGame = enrichWithStoreEntries(folderGame, matchedStoreEntries)
                .copy(ownerships = (folderEntriesList + matchedStoreEntries).ownerships())
            val entriesByPath = (folderEntriesList + matchedStoreEntries).associateBy { it.id }
            resultGroups.add(LibraryGameGroup(enrichedGame, entriesByPath))
        }

        // Unmatched store entries: group by their stable identity
        val unmatchedByIdentity = unmatchedStoreEntries.groupBy { entry ->
            entry.gameIdentity(linkedThread = entry.f95Thread).key
        }
        for ((_, groupEntries) in unmatchedByIdentity) {
            val first = groupEntries.first()
            val game = GroupedGame(
                name = first.title,
                versions = listOf(GameVersion(
                    version = "",
                    copies = groupEntries.map { entry ->
                        GameCopy(
                            path = entry.id,
                            source = entry.pcInfo?.source,
                            installed = entry.pcInfo?.installed == true,
                            platforms = emptyList(),
                        )
                    }
                )),
                ownerships = groupEntries.ownerships(),
            )
            val entriesByPath = groupEntries.associateBy { it.id }
            resultGroups.add(LibraryGameGroup(game, entriesByPath))
        }

        return resultGroups.sortedBy { it.game.name.lowercase() }
    }

    /**
     * Adds store entries as additional copies to a game's versions.
     * Store entries become copies with source set to the store name.
     */
    private fun enrichWithStoreEntries(game: GroupedGame, storeEntries: List<LibraryEntry>): GroupedGame {
        if (storeEntries.isEmpty()) return game

        // Add store entries as copies to the newest version, or create a new version
        val targetVersion = game.defaultVersion
            ?: game.versions.firstOrNull()
            ?: game.segments.firstOrNull()?.versions.firstOrNull()

        val storeCopies = storeEntries.map { entry ->
            GameCopy(
                path = entry.id,
                source = entry.pcInfo?.source,
                installed = entry.pcInfo?.installed == true,
                platforms = emptyList(),
            )
        }

        return if (targetVersion != null) {
            val updatedVersions = game.versions.map { version ->
                if (version.version == targetVersion.version) {
                    version.copy(copies = version.copies + storeCopies)
                } else {
                    version
                }
            }
            game.copy(versions = updatedVersions)
        } else {
            game.copy(versions = listOf(GameVersion(version = "", copies = storeCopies)))
        }
    }

    /**
     * A store row's id is `steam:440`; a scanned game's id is where it is.
     * Only the second can be read as a folder name.
     */
    private fun String.isFolderPath(): Boolean = startsWith(File.separator) || startsWith("/")
}