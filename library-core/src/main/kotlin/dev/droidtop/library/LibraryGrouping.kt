package dev.droidtop.library

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
    /** Every entry in this game, by the id that found it (a folder's path, or a scanned PC folder game's id). */
    val entriesByPath: Map<String, LibraryEntry>,
    /** The ids of the entries the person marked finished ([PartProgress]); only the parts of a multi-part game use them. */
    val finished: Set<String> = emptySet(),
    /** The copy the person chose for this game ([CopyChoices]), by entry id; null leaves it to the card's own order. */
    val chosen: String? = null,
) {
    /**
     * The version and copy the card stands on, and so the one its button acts
     * on ([CopyChoices]): the chosen copy when it is installed or nothing is,
     * else the newest version of the first segment, an installed copy of it
     * first, in the stores' order.
     */
    val defaultCopy: GameCopy?
        get() = CopyChoices.acting(game.allVersions.flatMap { it.copies }, chosen) ?: game.defaultVersion?.playable

    /**
     * The copy Play starts (docs/SPEC.md 7n): for a game of several parts,
     * the first part not marked finished, in the order the names give
     * (`book1`, `book2`, `book3`), newest version of it; for every other
     * game, [defaultCopy]. When every part is finished it is the first
     * again, so Play never has nothing to start.
     */
    val continueCopy: GameCopy?
        get() {
            if (game.segments.size < 2) return defaultCopy
            return game.segments
                .mapNotNull { segment -> segment.versions.firstOrNull()?.playable }
                .firstOrNull { it.path !in finished }
                ?: defaultCopy
        }

    /**
     * The entry Play starts. The card itself stays the first part's entry
     * ([displayEntry]) so its favourite, scraped art and history do not
     * move when a part is finished; only what A launches does.
     */
    val continueEntry: LibraryEntry? get() = continueCopy?.let { entriesByPath[it.path] }

    /**
     * The entry the list draws, titled with the game's own name and
     * carrying the game's available update, which no one folder can know
     * (docs/SPEC.md 7g).
     */
    val displayEntry: LibraryEntry
        get() {
            val entry = defaultCopy?.let { entriesByPath[it.path] }
                ?: entriesByPath.values.first()
            // A folder's update comes from a linked source; a store row's
            // from its store ([GameUpdates.forStore]).
            val update = game.availableUpdate
                ?: entriesByPath.values.firstNotNullOfOrNull { GameUpdates.forStore(it.pcInfo) }
            return if (entry.title == game.name && entry.availableUpdate == update) {
                entry
            } else {
                entry.copy(title = game.name, availableUpdate = update)
            }
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
 * Where a library entry's name, version and part are read from, or null when
 * it is not a folder on this device: the entry's own id when that is a path
 * (an engine game), the install folder of a scanned PC folder game
 * (`folder:CUSTOM_GAME_123`, docs/SPEC.md 7n), nothing for a store row or an
 * app. The one answer to "is this a folder game", for the grouping, the
 * same-game picker and the shell's version rows.
 */
fun LibraryEntry.groupingPath(): String? = when {
    id.startsWith("/") || id.startsWith(java.io.File.separator) -> id
    id.startsWith("folder:") -> pcInfo?.installPath?.takeIf { it.startsWith("/") }
    else -> null
}

/**
 * A game nothing has matched: no cover, no hero, no description and no
 * recorded scrape source (docs/SPEC.md 7n). Drawn plainly, with its parsed
 * title, and offered a Scrape. A game the folder walk no longer finds is not
 * one: it has nothing to look up.
 */
fun LibraryEntry.isUnscraped(): Boolean =
    !missing && artworkUri == null && heroUri == null && description.isNullOrBlank() && fieldSources.isEmpty()

/**
 * Folding a scan's [LibraryEntry] list into games (docs/SPEC.md 7m).
 *
 * An entry that is a folder on this device ([groupingPath]) is one the scan
 * found, so [GameNaming] can say what its name, version and segment are:
 * `Some Game/book1` and `Some Game/book2` are ONE game, `Some Game`, with two
 * segments, whichever provider found the folders. An entry
 * with a store's id (`steam:440`) is a store row whose name the store
 * already gave, so no version is derived from it; the rows of one game
 * owned on several stores become ONE group with a copy per store
 * ([StoreIdentity], docs/SPEC.md 7m). Any other id is a group of one.
 */
object LibraryGrouping {

    /**
     * Every game in [entries], in the order their names sort. [roots] are the
     * games roots, [finished] is
     * the ids of the parts the person marked finished ([PartProgress]); it
     * decides which part of a multi-part game Play continues with and
     * nothing else.
     */
    fun group(
        entries: List<LibraryEntry>,
        finished: Set<String> = emptySet(),
        // The games roots: nothing at or above one is read as a game's title,
        // so a `game` folder straight under a root is unidentified, not "games".
        roots: Collection<String> = emptyList(),
        // The copy each card's button acts on, by card ([CopyChoices.cardKey]) to entry id.
        chosen: Map<String, String> = emptyMap(),
    ): List<LibraryGameGroup> {
        val folders = entries.mapNotNull { entry -> entry.groupingPath()?.let { entry to it } }
        val byPath = folders.associate { (entry, _) -> entry.id to entry }
        val grouped = GameGrouping.group(
            folders.map { (entry, path) ->
                GameGrouping.Found(
                    path = entry.id,
                    installed = entry.pcInfo?.installed != false,
                    latestKnown = entry.latestKnown,
                    name = entry.gameName,
                    namePath = roots.firstNotNullOfOrNull { root -> GameNaming.relativeTo(root, path).takeIf { it != path } } ?: path,
                )
            },
        )
            .map { game ->
                LibraryGameGroup(
                    game,
                    game.allVersions.flatMap { it.copies }.mapNotNull { copy -> byPath[copy.path]?.let { copy.path to it } }.toMap(),
                    finished,
                )
            }
            .filter { it.entriesByPath.isNotEmpty() }
        val (stores, ungroupedRows) = entries.filter { it.groupingPath() == null }.partition { it.ownership() != null }
        val storeGames = StoreIdentity.group(stores).map { merged ->
            val copies = merged.entries.map { entry ->
                GameCopy(path = entry.id, source = PcSource.of(entry)?.label(), installed = entry.pcInfo?.installed != false)
            }
            LibraryGameGroup(
                GroupedGame(merged.name, listOf(GameVersion(version = "", copies = copies))),
                merged.entries.associateBy { it.id },
            )
        }
        val (folderGames, storeOnly) = withMarkedCopies(grouped, storeGames)
        val ungrouped = ungroupedRows.map { entry ->
            LibraryGameGroup(
                // The person's own title wins over the one the source gave (docs/SPEC.md 7n).
                GroupedGame(entry.gameName?.takeIf { it.isNotBlank() } ?: entry.title, listOf(GameVersion(version = "", copies = listOf(GameCopy(path = entry.id))))),
                mapOf(entry.id to entry),
            )
        }
        val all = (folderGames + storeOnly + ungrouped).sortedBy { it.game.name.lowercase() }
        if (chosen.isEmpty()) return all
        return all.map { group ->
            val pick = chosen[CopyChoices.cardKey(group.game.name)]?.takeIf { it in group.entriesByPath }
            if (pick == null) group else group.copy(chosen = pick)
        }
    }

    /**
     * A folder game holding a store's marker ([PcInfo.marker], docs/SPEC.md
     * 7g "Store markers") and the account's row of that game on that store
     * (the same store id, [StoreMarker.key]) are ONE card: a GOG offline
     * install with the GOG account's row, a Heroic install with the Epic
     * account's. The card keeps the folder's versions first (what is here is
     * what Play starts) and the store's copy after them, under the store's
     * name; every folder carrying that marker joins the same card. A marker
     * with no account row (signed out) leaves the folder its own card. One
     * map lookup per folder game.
     */
    private fun withMarkedCopies(
        folders: List<LibraryGameGroup>,
        stores: List<LibraryGameGroup>,
    ): Pair<List<LibraryGameGroup>, List<LibraryGameGroup>> {
        val storeByKey = HashMap<String, Int>()
        stores.forEachIndexed { index, group ->
            group.entriesByPath.values.forEach { entry -> entry.ownership()?.let { storeByKey["${it.store}:${it.id}"] = index } }
        }
        if (storeByKey.isEmpty()) return folders to stores
        val byStore = LinkedHashMap<Int, MutableList<LibraryGameGroup>>()
        val plain = ArrayList<LibraryGameGroup>(folders.size)
        for (folder in folders) {
            val index = folder.entriesByPath.values.firstNotNullOfOrNull { it.pcInfo?.marker?.key }?.let(storeByKey::get)
            if (index == null) plain += folder else byStore.getOrPut(index) { mutableListOf() } += folder
        }
        val joined = byStore.map { (index, members) ->
            val store = stores[index]
            LibraryGameGroup(
                GroupedGame(
                    name = store.game.name,
                    versions = members.flatMap { it.game.versions }.sortedWith(GameVersion.NEWEST_FIRST) + store.game.versions,
                    segments = members.flatMap { it.game.segments },
                    latestKnown = members.firstNotNullOfOrNull { it.game.latestKnown },
                ),
                members.fold(emptyMap<String, LibraryEntry>()) { all, member -> all + member.entriesByPath } + store.entriesByPath,
                members.flatMapTo(HashSet()) { it.finished },
            )
        }
        return (plain + joined) to stores.filterIndexed { index, _ -> index !in byStore }
    }
}
