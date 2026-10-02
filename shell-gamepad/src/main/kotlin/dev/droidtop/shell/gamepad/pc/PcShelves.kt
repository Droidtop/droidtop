package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.displayName
import dev.droidtop.shell.gamepad.query.INSTALLED_YES
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibrarySortKey
import dev.droidtop.shell.gamepad.query.NamedLibraryView
import dev.droidtop.shell.gamepad.query.RECENT_YES

/**
 * One row of the PC Games home (docs/SPEC.md 7i): a title, the games on
 * it in the order they are drawn, and -- when the whole set is longer than
 * the row shows -- how many there are, so the title can say "Installed ·
 * 171" while the row holds the first [SHELF_LIMIT].
 */
internal data class PcShelf(val id: String, val title: String, val entries: List<LibraryEntry>, val total: Int) {
    val heading: String get() = if (total > entries.size) "$title · $total" else title
}

/**
 * How many capsules a shelf holds. The Deck's home rows hold about this
 * many; the full set is one press away (the view strip, [pcBuiltInViews]),
 * and a row of thousands would be a grid lying on its side.
 */
internal const val SHELF_LIMIT = 24

/** Steam's home shelf order: what you were playing, then what needs attention, then by where it came from. */
internal const val SHELF_CONTINUE = "continue"
internal const val SHELF_RECENTLY_ADDED = "recently-added"
internal const val SHELF_UPDATES = "updates"
internal const val SHELF_FAVOURITES = "favourites"
internal const val SHELF_INSTALLED = "installed"

/**
 * The home's shelves, from the folded one-card-per-game list, as a pure
 * function so a JVM test can hold it to its rules and so the tab can run
 * it off the main thread (one sort per shelf over the whole library: never
 * while drawing). [now] is the clock, for "recently".
 *
 * - **Recently added**: indexed games with a nonzero first-seen time,
 *   newest first. Legacy rows with no timestamp do not appear.
 * - **Continue playing**: every game with a last-played time, newest
 *   first. The Deck's "Recent games" row, under the name the owner gave it.
 * - **Update available**: a source knows a newer version than any folder
 *   here (docs/SPEC.md 7g), only when there is one.
 * - **Favourites**, only when there is one.
 * - **Installed**, only when something is NOT installed: on a library of
 *   folder games alone every game is installed, and a shelf that repeats
 *   the whole library says nothing.
 * - **One shelf per store** (Steam, GOG, ...) for store rows, largest
 *   first, then **one per engine family** (Visual Novels, RPG Maker,
 *   Windows, ...) for the rest: a library of 170 folder games is not one
 *   row, it is the handful of kinds the person collects.
 *
 * Within a shelf: most recently played first, then by name, so a long
 * shelf shows what the person touches rather than the start of the
 * alphabet.
 */
internal fun pcShelves(games: List<LibraryEntry>, now: Long = System.currentTimeMillis()): List<PcShelf> {
    val byRecency = compareByDescending<LibraryEntry> { it.lastPlayedEpochMs ?: 0L }.thenBy { it.title.lowercase() }
    fun shelf(id: String, title: String, all: List<LibraryEntry>): PcShelf? {
        if (all.isEmpty()) return null
        val ordered = all.sortedWith(byRecency)
        return PcShelf(id, title, ordered.take(SHELF_LIMIT), ordered.size)
    }
    return buildList {
        val recentlyAdded = games.filter { it.firstSeenEpochMs > 0L }
            .sortedWith(compareByDescending<LibraryEntry> { it.firstSeenEpochMs }.thenBy { it.title.lowercase() })
        if (recentlyAdded.isNotEmpty()) {
            add(PcShelf(SHELF_RECENTLY_ADDED, "Recently added", recentlyAdded.take(SHELF_LIMIT), recentlyAdded.size))
        }
        // Local vals, not smart casts: LibraryEntry's properties are
        // declared in another module, which Kotlin will not smart-cast.
        shelf(
            SHELF_CONTINUE,
            "Continue playing",
            games.filter { entry ->
                val last = entry.lastPlayedEpochMs
                last != null && last <= now
            },
        )?.let(::add)
        shelf(SHELF_UPDATES, "Update available", games.filter { it.availableUpdate != null })?.let(::add)
        shelf(SHELF_FAVOURITES, "Favourites", games.filter { it.favorite })?.let(::add)
        val installed = games.filter { it.isInstalled }
        if (installed.size < games.size) shelf(SHELF_INSTALLED, "Installed", installed)?.let(::add)
        fun storeOf(entry: LibraryEntry): String? = entry.pcInfo?.source?.takeIf { it != "Folder" }
        games.filter { storeOf(it) != null }
            .groupBy { storeOf(it)!! }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<LibraryEntry>>> { it.value.size }.thenBy { it.key })
            .forEach { (store, rows) -> shelf("store:$store", store, rows)?.let(::add) }
        games.filter { storeOf(it) == null }
            .groupBy { it.kind.displayName() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<LibraryEntry>>> { it.value.size }.thenBy { it.key })
            .forEach { (family, rows) -> shelf("kind:$family", family, rows)?.let(::add) }
    }
}

/**
 * The views the strip always offers, in the strip's order after Home:
 * the whole library, what is installed, what was played lately. The same
 * shape as a person's own saved view ([NamedLibraryView]), so the strip,
 * the filter dialog and the saved views are one mechanism.
 */
internal val pcBuiltInViews: List<NamedLibraryView> = listOf(
    NamedLibraryView("All games", LibraryQuery()),
    NamedLibraryView("Installed", LibraryQuery(facets = mapOf(LibraryFacet.INSTALLED.key to setOf(INSTALLED_YES)))),
    NamedLibraryView(
        "Continue playing",
        LibraryQuery(facets = mapOf(LibraryFacet.RECENTLY_PLAYED.key to setOf(RECENT_YES)), sort = LibrarySortKey.RECENT),
    ),
)
