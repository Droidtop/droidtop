package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcSource
import dev.droidtop.library.displayName
import dev.droidtop.shell.gamepad.query.FAVOURITES_YES
import dev.droidtop.shell.gamepad.query.INSTALLED_YES
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.LibraryQueryScope
import dev.droidtop.shell.gamepad.query.ListPlace
import dev.droidtop.shell.gamepad.query.OwnershipOptions
import dev.droidtop.shell.gamepad.query.listExclusion
import dev.droidtop.shell.gamepad.query.NamedLibraryView
import dev.droidtop.shell.gamepad.query.StripTabs

/**
 * One row of the PC Games home (docs/SPEC.md 7i): a title, the games on
 * it in the order they are drawn, and -- when the whole set is longer than
 * the row shows -- how many there are, so the title can say "Installed ·
 * 171" while the row holds the first [SHELF_LIMIT].
 */
internal data class PcShelf(val id: String, val title: String, val entries: List<LibraryEntry>, val total: Int) {
    // Update available always says how many: it leads Overview, and its count is the point of it.
    val heading: String get() = if (total > entries.size || id == SHELF_UPDATES) "$title · $total" else title
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
internal const val SHELF_NOT_PLAYED = "not-played"

/**
 * The shelves Home keeps (docs/SPEC.md 7i, "Home art"): recent activity
 * across every library and what needs attention. The rest of [pcShelves]
 * (favourites, what was never played, stores, engine families) is the PC
 * library's own and lives on PC Games' Overview.
 */
internal val HOME_SHELF_IDS = setOf(SHELF_CONTINUE, SHELF_RECENTLY_ADDED, SHELF_UPDATES)

/**
 * Home's shelves: [pcShelves] over the PC fold with [others] (Retro games
 * and game apps) merged in, kept to [HOME_SHELF_IDS]. One shelf builder for
 * both surfaces, so Home and PC Games never disagree about what "Recently
 * added" means.
 */
internal fun homeShelves(
    games: List<LibraryEntry>,
    others: List<LibraryEntry>,
    now: Long = System.currentTimeMillis(),
    options: OwnershipOptions = OwnershipOptions(),
    isRecentlyAdded: (LibraryEntry) -> Boolean = { true },
): List<PcShelf> = pcShelves(games, now, others, options, isRecentlyAdded).filter { it.id in HOME_SHELF_IDS }

/**
 * The shelves plugins asked Home for (docs/plugin-api.md 3 C11) as Home's own shelves: the real entries from
 * [entries] in the plugin's order, titled with the plugin's name so a shelf never passes for droidtop's own. A
 * shelf whose entries are all gone is dropped. Ids are `plugin:<plugin>/<shelf>`, apart from every built-in id.
 * Pure: one map built over the entries, no lookup per card.
 */
internal fun pluginHomeShelves(
    shelves: List<dev.droidtop.library.integrations.PluginShelves.Shelf>,
    entries: List<LibraryEntry>,
): List<PcShelf> {
    if (shelves.isEmpty()) return emptyList()
    val byId = entries.associateBy { it.id }
    return shelves.mapNotNull { s ->
        val rows = s.shelf.entryIds.mapNotNull { byId[it] }
        if (rows.isEmpty()) null else PcShelf("plugin:${s.pluginId}/${s.shelf.id}", "${s.shelf.title}, from ${s.pluginLabel}", rows, rows.size)
    }
}

/**
 * The PC library's shelves, from the folded one-card-per-game list, as a pure
 * function so a JVM test can hold it to its rules and so the tab can run
 * it off the main thread (one sort per shelf over the whole library: never
 * while drawing). [now] is the clock, for "recently".
 *
 * - **Continue playing**: every game with a last-played time, newest
 *   first; the first shelf, led by a hero card (docs/SPEC.md 7i, "Home
 *   art"). The Deck's "Recent games" row, under the name the owner gave it.
 *   [others] (Retro games and launcher apps that are games) join this shelf
 *   and Recently added, merged by the same times, and no other shelf.
 * - **Recently added**: indexed games with a nonzero added time
 *   ([addedEpochMs]), newest first. Legacy rows with no timestamp do not appear.
 * - **Update available**: a source knows a newer version than any folder
 *   here (docs/SPEC.md 7g), only when there is one.
 * - **Favourites**, only when there is one.
 * - **Not played yet**: installed games with no last-played time, newest
 *   added first -- the discovery row, from data the library already has.
 *   Only once something has been played: before that it is the whole
 *   library again.
 * - **Installed**, only when something is NOT installed: on a library of
 *   folder games alone every game is installed, and a shelf that repeats
 *   the whole library says nothing.
 * - With more than one source ([PcSource]: a store, a game folder, the Wine
 *   shortcuts), **one shelf per source**, in the Source filter's order.
 *   With one, a shelf per pinned collection ([pinnedCollections], id and
 *   name), or with none pinned **one per engine family** (Visual Novels,
 *   RPG Maker, Windows, ...): a library of 170 folder games is not one row,
 *   it is the handful of kinds the person collects.
 *
 * On PC Games' Overview ([updatesFirst]) Update available, when it has rows,
 * leads, above Continue playing (docs/SPEC.md 7i); Home keeps its own order.
 *
 * Within a shelf: most recently played first, then by name, so a long
 * shelf shows what the person touches rather than the start of the
 * alphabet.
 *
 * Which of [games] a shelf holds is [listExclusion]'s answer (docs/SPEC.md
 * 7j): Continue playing and Installed ignore ownership, every other shelf
 * keeps out what the ownership [options] keep out of a list, and no shelf
 * holds a hidden game. A PC game is Recently added only when
 * [isRecentlyAdded] says its source added it since its first sync
 * ([dev.droidtop.library.SyncBaselines]).
 */
internal fun pcShelves(
    games: List<LibraryEntry>,
    now: Long = System.currentTimeMillis(),
    others: List<LibraryEntry> = emptyList(),
    options: OwnershipOptions = OwnershipOptions(),
    isRecentlyAdded: (LibraryEntry) -> Boolean = { true },
    updatesFirst: Boolean = false,
    roots: List<String> = emptyList(),
    pinnedCollections: List<Pair<String, String>> = emptyList(),
    collectionsOf: (LibraryEntry) -> Set<String> = { emptySet() },
): List<PcShelf> {
    val listed = games.filter { listExclusion(it, ListPlace.LIST, options) == null }
    val active = games.filter { listExclusion(it, ListPlace.ACTIVITY, options) == null }
    val byRecency = compareByDescending<LibraryEntry> { it.lastPlayedEpochMs ?: 0L }.thenBy { it.title.lowercase() }
    fun shelf(id: String, title: String, all: List<LibraryEntry>): PcShelf? {
        if (all.isEmpty()) return null
        val ordered = all.sortedWith(byRecency)
        return PcShelf(id, title, ordered.take(SHELF_LIMIT), ordered.size)
    }
    val updates = shelf(SHELF_UPDATES, "Update available", listed.filter { it.availableUpdate != null })
    return buildList {
        if (updatesFirst) updates?.let(::add)
        // Continue playing leads: its first card is the hero card (PcCapsule
        // with `hero`), the game the person most likely wants.
        // Local vals, not smart casts: LibraryEntry's properties are
        // declared in another module, which Kotlin will not smart-cast.
        val activity = active + others
        shelf(
            SHELF_CONTINUE,
            "Continue playing",
            activity.filter { entry ->
                val last = entry.lastPlayedEpochMs
                last != null && last <= now
            },
        )?.let(::add)
        val recentlyAdded = (listed + others).filter { it.addedEpochMs() > 0L && isRecentlyAdded(it) }
            .sortedWith(compareByDescending<LibraryEntry> { it.addedEpochMs() }.thenBy { it.title.lowercase() })
        if (recentlyAdded.isNotEmpty()) {
            add(PcShelf(SHELF_RECENTLY_ADDED, "Recently added", recentlyAdded.take(SHELF_LIMIT), recentlyAdded.size))
        }
        if (!updatesFirst) updates?.let(::add)
        shelf(SHELF_FAVOURITES, "Favourites", listed.filter { it.favorite })?.let(::add)
        if (listed.any { it.lastPlayedEpochMs != null }) {
            val unplayed = listed.filter { it.lastPlayedEpochMs == null && it.isInstalled }
                .sortedWith(compareByDescending<LibraryEntry> { it.addedEpochMs() }.thenBy { it.title.lowercase() })
            if (unplayed.isNotEmpty()) add(PcShelf(SHELF_NOT_PLAYED, "Not played yet", unplayed.take(SHELF_LIMIT), unplayed.size))
        }
        val installed = active.filter { it.isInstalled }
        if (installed.size < listed.size) shelf(SHELF_INSTALLED, "Installed", installed)?.let(::add)
        val bySource = listed.groupBy { PcSource.of(it, roots) }
        if (bySource.keys.filterNotNull().size > 1) {
            bySource.entries.filter { it.key != null }
                .sortedWith(compareBy<Map.Entry<PcSource?, List<LibraryEntry>>, PcSource>(PcSource.ORDER) { it.key!! })
                .forEach { (source, rows) -> shelf("source:${source!!.id}", source.label(), rows)?.let(::add) }
        } else if (pinnedCollections.isNotEmpty()) {
            // One source: the person's pinned collections are the shelves (docs/SPEC.md 7i).
            pinnedCollections.forEach { (id, name) -> shelf("collection:$id", name, listed.filter { id in collectionsOf(it) })?.let(::add) }
        } else {
            listed.groupBy { it.kind.displayName() }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, List<LibraryEntry>>> { it.value.size }.thenBy { it.key })
                .forEach { (family, rows) -> shelf("kind:$family", family, rows)?.let(::add) }
        }
    }
}

/**
 * When the library first saw this entry; an installed app the library index
 * has not stamped falls back to the package manager's install time. 0 when
 * neither is known. Pure.
 */
internal fun LibraryEntry.addedEpochMs(): Long =
    firstSeenEpochMs.takeIf { it > 0L } ?: appFacts?.firstInstalledEpochMs ?: 0L

/** What a game is, in the one word a badge leads with. */
internal enum class BadgeKind(val word: String) { PC("PC"), ENGINE("Engine"), RETRO("Retro"), APP("App") }

/**
 * The kind badge every card and result row of a mixed list carries
 * (docs/SPEC.md 7i, "Kind badges"; Droidtop/tracker#362): [kind] in one word,
 * then [detail], the store of a PC or engine game or the system of a Retro
 * game. [text] is the same line for a row of words.
 */
internal data class KindBadge(val kind: BadgeKind, val detail: String?) {
    val text: String get() = if (detail == null) kind.word else "${kind.word} · $detail"
}

/** The kinds that are neither a Windows or Linux program, a remote PC, an app nor a console ROM: an engine game. */
internal val NON_ENGINE_KINDS = setOf(
    LibraryEntryKind.NATIVE_ANDROID_APP,
    LibraryEntryKind.WINE_PROFILE,
    LibraryEntryKind.LINUX_CONTAINER_APP,
    LibraryEntryKind.REMOTE_STREAM,
    LibraryEntryKind.CONSOLE_ROM,
)

/**
 * The [KindBadge] of [entry], from fields the entry already carries and
 * [systemNames] (system id to display name, loaded once): a map read, never a
 * lookup per card. Pure.
 */
internal fun kindBadgeOf(entry: LibraryEntry, systemNames: Map<String, String>): KindBadge = when {
    entry.appFacts != null || entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP -> KindBadge(BadgeKind.APP, null)
    entry.inPcFold -> {
        val store = PcSource.of(entry)?.takeIf { entry.isStoreRow() }?.label()
        KindBadge(if (entry.kind in NON_ENGINE_KINDS) BadgeKind.PC else BadgeKind.ENGINE, store)
    }
    else -> KindBadge(BadgeKind.RETRO, entry.systemId?.let { systemNames[it] ?: it })
}

/**
 * Whether this entry is one of the PC fold's own games (a PC or engine game),
 * as opposed to a Retro game or a launcher app that Home also shows. A launcher
 * app has no system id, so [onPcGamesTab] alone would claim it.
 */
internal val LibraryEntry.inPcFold: Boolean
    get() = appFacts == null && onPcGamesTab

/**
 * Gives the hero card (the first shelf's first card, [isHeroCard]) of a Retro
 * game the landscape art the PC hero rule asks for (docs/SPEC.md 7i, "Home
 * art"): its scraped fanart, else its screenshot, from the media layout it
 * already carries. One file lookup for one card, so the caller runs this off
 * the main thread with the shelves.
 */
internal fun withRetroHero(shelves: List<PcShelf>): List<PcShelf> = shelves.mapIndexed { index, shelf ->
    val first = shelf.entries.firstOrNull()
    if (index != 0 || first == null || first.inPcFold || first.heroUri != null || first.mediaLocator == null) {
        shelf
    } else {
        val art = first.mediaForImageTypes(listOf("fanart", "screenshot"))
        if (art == null) shelf else shelf.copy(entries = listOf(first.copy(heroUri = art)) + shelf.entries.drop(1))
    }
}

/**
 * Whether a card is the hero card: the first card of the first shelf, drawn
 * landscape (docs/SPEC.md 7i, "Home art"). Usually Continue playing's; on a
 * library nothing has been played from yet, whatever shelf leads, so the
 * page always opens on one large piece of art. Pure.
 */
internal fun isHeroCard(shelfIndex: Int, itemIndex: Int): Boolean = shelfIndex == 0 && itemIndex == 0

/**
 * Where the cursor goes when the shelves are worked out again (a scan still
 * running adds games, and a shelf ordered by size can move up or down): the
 * same game on the same shelf, found by id, so the rows do not slide the
 * cursor onto another game under the user; else the same places, clamped.
 * Returns (shelf, item). Pure.
 */
internal fun cursorAfter(old: List<PcShelf>, new: List<PcShelf>, shelf: Int, item: Int): Pair<Int, Int> {
    fun clamp(index: Int, size: Int) = index.coerceIn(0, (size - 1).coerceAtLeast(0))
    val oldShelf = old.getOrNull(shelf)
    val newShelf = oldShelf?.let { wanted -> new.indexOfFirst { it.id == wanted.id } }?.takeIf { it >= 0 }
        ?: return clamp(shelf, new.size) to clamp(item, new.getOrNull(clamp(shelf, new.size))?.entries?.size ?: 0)
    val entries = new[newShelf].entries
    val oldId = oldShelf?.entries?.getOrNull(item)?.id
    val newItem = oldId?.let { id -> entries.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: clamp(item, entries.size)
    return newShelf to newItem
}

/** The names of PC Games' built-in tabs (docs/SPEC.md 7i), in the strip's fixed order. */
internal const val VIEW_OVERVIEW = "Overview"
internal const val VIEW_ALL = "All games"
internal const val VIEW_INSTALLED = "Installed"
internal const val VIEW_FAVOURITES = "Favourites"
internal const val VIEW_COLLECTIONS = "Collections"

/**
 * The built-in grid tabs: the whole library, what is installed, the
 * favourites. The same shape as a person's own saved view
 * ([NamedLibraryView]), so a tab, the Filter sheet and the saved views are
 * one mechanism. Their ids never collide with a saved view's (`builtin:`).
 */
internal val pcBuiltInViews: List<NamedLibraryView> = listOf(
    NamedLibraryView(VIEW_ALL, LibraryQuery(), id = "builtin:all"),
    NamedLibraryView(VIEW_INSTALLED, LibraryQuery(facets = mapOf(LibraryFacet.INSTALLED.key to setOf(INSTALLED_YES))), id = "builtin:installed"),
    NamedLibraryView(VIEW_FAVOURITES, LibraryQuery(facets = mapOf(LibraryFacet.FAVOURITES.key to setOf(FAVOURITES_YES))), id = "builtin:favourites"),
)

/** One tab of PC Games' strip (docs/SPEC.md 7i). */
internal sealed interface PcTab {
    /** The PC library's own shelves, which the page opens on: no filter over the grid. */
    data object Overview : PcTab

    /** A grid over a query: a built-in view, or one of the person's pinned views ([builtIn] false). */
    data class Grid(val view: NamedLibraryView, val builtIn: Boolean) : PcTab

    /** The collections and the saved views, as tiles. */
    data object Collections : PcTab
}

/**
 * The strip, in its fixed order (docs/SPEC.md 7i): Overview, All games,
 * Installed, Favourites, Collections, then the person's pinned views in their
 * own order (the "Updates" view everyone was given first). Built-in tabs do
 * not come and go with what is in them; only Favourites and Collections can be
 * hidden ([StripTabs]). Store tabs are gone: a store is a Source filter, and
 * any filter can be saved as a tab. Pure.
 */
internal fun pcStripTabs(saved: List<NamedLibraryView>, shown: StripTabs = StripTabs()): List<PcTab> = buildList {
    add(PcTab.Overview)
    add(PcTab.Grid(pcBuiltInViews[0], builtIn = true))
    add(PcTab.Grid(pcBuiltInViews[1], builtIn = true))
    if (shown.favourites) add(PcTab.Grid(pcBuiltInViews[2], builtIn = true))
    if (shown.collections) add(PcTab.Collections)
    saved.filter { it.pinned }.forEach { add(PcTab.Grid(it, builtIn = false)) }
}

/**
 * How many games each grid tab holds, by view id, worked out once per library
 * change off the main thread (one pass of each view's own filter over the
 * folded library, no sort): the strip's counts (docs/SPEC.md 7i).
 */
internal fun pcViewCounts(games: List<LibraryEntry>, scope: LibraryQueryScope, views: List<NamedLibraryView>): Map<String, Int> =
    views.associate { view -> view.id to games.count { view.query.matches(it, scope) } }

/** A tab's label: a grid tab carries its count ("Installed · 12") once it is known. */
internal fun pcTabLabel(tab: PcTab, counts: Map<String, Int>): String = when (tab) {
    PcTab.Overview -> VIEW_OVERVIEW
    PcTab.Collections -> VIEW_COLLECTIONS
    is PcTab.Grid -> counts[tab.view.id]?.let { "${tab.view.name} · ${"%,d".format(it)}" } ?: tab.view.name
}

/**
 * [saved] with the pinned view [id] moved [step] places among the pinned views
 * (Move left -1, Move right +1), or to the first place after the built-in
 * tabs ([toFirst]). Unpinned views keep their places. Pure.
 */
internal fun movePinned(saved: List<NamedLibraryView>, id: String, step: Int = 0, toFirst: Boolean = false): List<NamedLibraryView> {
    val pinned = saved.filter { it.pinned }
    val from = pinned.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return saved
    val to = if (toFirst) 0 else (from + step).coerceIn(0, pinned.lastIndex)
    if (to == from) return saved
    val reordered = pinned.toMutableList().apply { add(to, removeAt(from)) }
    // The pinned views take the pinned slots in their new order; the rest stay put.
    val queue = ArrayDeque(reordered)
    return saved.map { if (it.pinned) queue.removeFirst() else it }
}

/**
 * The grid's last row while the List option keeps free-to-play games the
 * account never added out of a view, or shows them (docs/SPEC.md 7j): no
 * game leaves a list in silence. Pure.
 */
internal fun freeRowText(count: Int, showing: Boolean): String {
    val games = "%,d free-to-play %s not in your library".format(count, if (count == 1) "game" else "games")
    return if (showing) "Showing $games. Hide them" else "$games ${if (count == 1) "is" else "are"} not shown. Show them"
}


