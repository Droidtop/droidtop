package dev.droidtop.shell.gamepad.query

import android.content.Context
import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.PcSource
import dev.droidtop.library.appSourceLabel
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.shell.gamepad.pc.engineLabel
import dev.droidtop.shell.gamepad.pc.isInstalled
import org.json.JSONObject

/**
 * The one way a list of games is searched, filtered and sorted -- shared
 * by the PC library view and reusable by any console list (docs/SPEC.md
 * 7i's redesign, owner direction 2026-09-27: "A shared filter/sort/search
 * component reusable by console lists").
 *
 * The model is deliberately UI-free and Context-free: [LibraryQuery] is
 * what a view holds, [LibraryQueryScope] is what a list offers (which
 * facets, which sorts, how the PC-only facts are read), and [applyTo] is
 * the one filter+sort pass. A console gamelist and the PC library differ
 * only in the scope they build; nothing here knows which is which.
 *
 * Semantics: values within one facet OR (one store or another), facets AND
 * (a store and installed), the search text ANDs with everything. A facet
 * value an entry does not have excludes the entry, and an entry the facet
 * does not apply to at all (a folder game has no install state) is
 * excluded while that facet filters -- a filter that cannot match is not
 * a filter a game silently passes.
 */
enum class LibrarySortKey(val label: String, val naturalOrder: String, val flippedOrder: String) {
    NAME("Name", "A to Z", "Z to A"),
    RECENT("Last played", "Latest first", "Earliest first"),
    MOST_USED("Most played", "Most first", "Fewest first"),
    PLAYTIME("Playtime", "Longest first", "Shortest first"),
    ADDED("Recently added", "Newest first", "Oldest first"),
    YEAR("Release year", "Oldest first", "Newest first"),
    RATING("Rating", "Highest first", "Lowest first"),
    SIZE("Size", "Largest first", "Smallest first"),
    ;

    /** The direction wording for the order the sort is in right now. */
    fun orderLabel(reversed: Boolean): String = if (reversed) flippedOrder else naturalOrder
}

/**
 * One filterable fact. [valuesOf] is every value this entry carries for
 * the facet -- an entry a facet does not apply to answers empty, and an
 * empty answer never matches a selection. A value is an id where the fact
 * has one (a [PcSource] id), drawn through [valueLabel]; a saved view keeps
 * the id, so a renamed store or folder needs nothing migrated.
 */
enum class LibraryFacet(val key: String, val label: String) {
    // Where a PC game came from: a store, a game folder, a Wine shortcut (docs/SPEC.md 7j).
    SOURCE("source", "Source"),
    // How the account holds a store row ([StoreHolding], docs/SPEC.md 7g); values are the holding's name.
    OWNERSHIP("ownership", "Ownership"),
    // PC or Engine, the word a capsule's badge leads with (docs/SPEC.md 7i).
    KIND("kind", "Kind"),
    // The launcher a game came to droidtop through (Lutris), by its id ([dev.droidtop.library.PcLaunchers]).
    IMPORTED_FROM("via", "Imported from"),
    ENGINE("engine", "Engine"),
    RUNNER("runner", "Runner"),
    INSTALLED("installed", "Install state"),
    READY("ready", "Ready to play"),
    FAVOURITES("favourites", "Favourites"),
    PLAYED("played", "Played"),
    RECENTLY_PLAYED("recent", "Recently played"),
    GENRE("genre", "Genre"),
    DEVELOPER("developer", "Developer"),
    YEAR("year", "Year"),
    PROTONDB("protondb", "ProtonDB tier"),
    UPDATE("update", "Update available"),
    MISSING_ART("missing_art", "Artwork"),
    HIDDEN("hidden", "Hidden"),

    // A console gamelist's own facts (the ES-DE completed flag, and what a
    // Switch game's update and DLC files add up to, docs/SPEC.md 7m).
    COMPLETED("completed", "Completed"),
    SWITCH_CONTENT("switch", "Switch content"),

    // The Apps view's own facts (docs/SPEC.md 7j): read from the entry's
    // [InstalledAppFacts] and the scope's lookups, never from the disk.
    CATEGORY("category", "Category"),
    RUNNING("running", "Running"),
    RECENTLY_USED("used", "Recently used"),
    RECENTLY_INSTALLED("installed_recently", "Recently installed"),
    APP_SOURCE("app_source", "Source"),
    ;

    fun valuesOf(entry: LibraryEntry, context: LibraryQueryContext): List<String> = when (this) {
        SOURCE -> PcSource.of(entry, context.pcRoots)?.let { listOf(it.id) }.orEmpty()
        // A store row only: a folder game is no store's to hold.
        OWNERSHIP -> entry.holding()?.let { listOf(it.name) }.orEmpty()
        KIND -> listOf(if (entry.kind in dev.droidtop.shell.gamepad.pc.NON_ENGINE_KINDS) KIND_PC else KIND_ENGINE)
        IMPORTED_FROM -> context.viaOf(entry)?.let { listOf(it) }.orEmpty()
        ENGINE -> entry.engineLabel()?.let { listOf(it) }.orEmpty()
        RUNNER -> context.runnerLabelOf(entry)?.let { listOf(it) }.orEmpty()
        // The one answer the Installed shelf reads too (LibraryEntry.isInstalled): a
        // store row says so, a folder the walk still finds is installed.
        INSTALLED -> listOf(if (entry.isInstalled) INSTALLED_YES else INSTALLED_NO)
        READY -> context.runnerReadyOf(entry)?.let { ready -> listOf(if (ready) READY_YES else READY_NO) }.orEmpty()
        FAVOURITES -> if (entry.favorite) listOf(FAVOURITES_YES) else emptyList()
        PLAYED -> listOf(if (entry.playCount > 0 || entry.lastPlayedEpochMs != null) PLAYED_YES else PLAYED_NO)
        // "Recently played" is droidtop's own window, 14 days, and it is
        // stated here once so the facet's chip, its dialog row and its
        // tests can never disagree about what "recently" means.
        RECENTLY_PLAYED -> entry.lastPlayedEpochMs
            ?.let { if (it >= context.now() - RECENT_WINDOW_MS) listOf(RECENT_YES) else emptyList() }
            .orEmpty()
        GENRE -> entry.genre?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        DEVELOPER -> entry.developer?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        YEAR -> entry.releaseYear()?.let { listOf(it) }.orEmpty()
        PROTONDB -> context.protonTierOf(entry)?.let { listOf(it) }.orEmpty()
        UPDATE -> entry.availableUpdate?.let { listOf(UPDATE_YES) }.orEmpty()
        MISSING_ART -> if (entry.artworkUri == null) listOf(MISSING_ART_YES) else emptyList()
        // Only a hidden entry has the value: a visible one is simply not
        // hidden, and the one rule in [LibraryQuery.matches] keeps hidden
        // entries out of a list unless this facet asks for them.
        HIDDEN -> if (entry.hidden) listOf(HIDDEN_YES) else emptyList()
        COMPLETED -> if (entry.completed) listOf(COMPLETED_YES) else emptyList()
        // A Switch game classification could say nothing about is not
        // "missing" anything: only a row known to be a base game without an
        // update beside it is.
        SWITCH_CONTENT -> buildList {
            val facts = entry.switchFacts
            if (facts != null) {
                if (facts.dlcCount > 0) add(SWITCH_HAS_DLC)
                if (!facts.loose && !facts.hasUpdate) add(SWITCH_MISSING_UPDATE)
                if (facts.loose) add(SWITCH_LOOSE_DLC)
            }
        }
        CATEGORY -> context.appCategoryOf(entry)?.let { listOf(it) }.orEmpty()
        RUNNING -> if (context.isRunning(entry)) listOf(RUNNING_YES) else emptyList()
        // The newest of droidtop's own launch log and, when the person
        // granted Usage access, what Android says (the scope merges both).
        RECENTLY_USED -> context.lastUsedOf(entry)
            ?.let { if (it >= context.now() - USED_WINDOW_MS) listOf(USED_YES) else emptyList() }
            .orEmpty()
        RECENTLY_INSTALLED -> entry.appFacts?.firstInstalledEpochMs
            ?.let { if (it > 0L && it >= context.now() - INSTALLED_WINDOW_MS) listOf(INSTALLED_RECENTLY_YES) else emptyList() }
            .orEmpty()
        APP_SOURCE -> entry.appFacts?.let { listOf(appSourceLabel(it)) }.orEmpty()
    }

    /** What a person reads for one of this facet's values: a source's name for its id, the value itself otherwise. */
    fun valueLabel(value: String): String = when (this) {
        SOURCE -> PcSource.fromId(value).label()
        OWNERSHIP -> StoreHolding.entries.firstOrNull { it.name == value }?.label ?: value
        KIND -> if (value == KIND_ENGINE) "Engine" else "PC"
        IMPORTED_FROM -> dev.droidtop.library.PcLaunchers.label(value)
        else -> value
    }

    /** One line the Filter sheet shows under this facet's values, where its words need saying. */
    val hint: String?
        get() = when (this) {
            KIND -> "Engine: games that run through an engine such as Ren'Py or RPG Maker"
            else -> null
        }

    /** The order a facet's values are listed in: sources in the registry's order ([PcSource.ORDER]), the rest by name. */
    fun valueOrder(): Comparator<FacetValueCount> = when (this) {
        SOURCE -> compareBy<FacetValueCount, PcSource>(PcSource.ORDER) { PcSource.fromId(it.value) }
        OWNERSHIP -> compareBy<FacetValueCount> { offer -> StoreHolding.entries.indexOfFirst { it.name == offer.value } }
        KIND -> compareBy<FacetValueCount> { if (it.value == KIND_PC) 0 else 1 }
        else -> compareBy<FacetValueCount> { it.value.lowercase() }
    }

    /**
     * Every value present in [entries] with how many entries carry it, in
     * display order -- the facet's sheet list. A value no entry has is
     * never listed, so a count is never zero.
     */
    fun valueCounts(entries: List<LibraryEntry>, context: LibraryQueryContext): List<FacetValueCount> {
        val counts = LinkedHashMap<String, Int>()
        entries.forEach { entry -> valuesOf(entry, context).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.map { FacetValueCount(it.key, it.value) }.sortedWith(valueOrder())
    }
}

/** One value of a facet and how many entries have it. */
data class FacetValueCount(val value: String, val count: Int)

/** The Kind facet's values: ids, drawn as "PC" and "Engine". */
const val KIND_PC = "pc"
const val KIND_ENGINE = "engine"
const val INSTALLED_YES = "Installed"
const val INSTALLED_NO = "Not installed"
const val READY_YES = "Ready"
const val READY_NO = "Needs setup"
const val FAVOURITES_YES = "Favourites"
const val PLAYED_YES = "Played"
const val PLAYED_NO = "Never played"
const val RECENT_YES = "Recently played"
const val UPDATE_YES = "Update available"
const val MISSING_ART_YES = "Missing art"
const val HIDDEN_YES = "Hidden"
const val COMPLETED_YES = "Completed"
const val SWITCH_HAS_DLC = "Has DLC"
const val SWITCH_MISSING_UPDATE = "Missing update"
const val SWITCH_LOOSE_DLC = "DLC without base game"
const val RUNNING_YES = "Running"
const val USED_YES = "Used this week"
const val INSTALLED_RECENTLY_YES = "Installed this week"

/** "Recently played" is the last 14 days, one definition for every reader. */
const val RECENT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

/** "Recently used" and "Recently installed" (apps) are the last 7 days. */
const val USED_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
const val INSTALLED_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/**
 * How a scope reads the facts that are not on the entry itself. The
 * resolved runner and the remembered ProtonDB tier are worked out away
 * from any list (a runner costs a folder walk; ProtonDB is asked-for,
 * never fetched), so the view hands them in as lookups and a console
 * scope that has neither passes the defaults.
 */
data class LibraryQueryContext(
    val runnerReadyOf: (LibraryEntry) -> Boolean? = { null },
    val runnerLabelOf: (LibraryEntry) -> String? = { null },
    val protonTierOf: (LibraryEntry) -> String? = { null },
    /** An app's category label (Games, Emulators, ...), from the rules the Apps view loaded; null for a game. */
    val appCategoryOf: (LibraryEntry) -> String? = { null },
    /** Whether the task manager lists the entry's app as running right now. */
    val isRunning: (LibraryEntry) -> Boolean = { false },
    /** When the entry was last played or used: droidtop's launch log, and Usage access where granted. */
    val lastUsedOf: (LibraryEntry) -> Long? = { it.lastPlayedEpochMs },
    val now: () -> Long = System::currentTimeMillis,
    /** The person's game folders (absolute paths), loaded once off the main thread: the Source facet's folder values. */
    val pcRoots: List<String> = emptyList(),
    /** The launcher a game was imported through ([dev.droidtop.library.PcLaunchers]), read once off the main thread; null for none. */
    val viaOf: (LibraryEntry) -> String? = { null },
)

/** What one list offers: which facets, which sorts, and how to read the facts. */
data class LibraryQueryScope(
    /** Saved views and the active query persist per list, by this id ("pc", a system id, ...). */
    val id: String,
    val facets: List<LibraryFacet>,
    val sorts: List<LibrarySortKey>,
    val context: LibraryQueryContext = LibraryQueryContext(),
    /** What this list calls a sort, where its words differ from the key's ("Recently used" for apps). */
    val sortLabels: Map<LibrarySortKey, String> = emptyMap(),
    /** What one entry of this list is called, for "12 of 80 apps". */
    val noun: String = "game",
    val nounPlural: String = "games",
    /** The ownership List options (docs/SPEC.md 7j); null for a list with no store rows, which the ownership rules leave alone. */
    val ownership: OwnershipOptions? = null,
) {
    fun sortLabel(key: LibrarySortKey): String = sortLabels[key] ?: key.label
}

/** A search text, facet selections, and a sort -- the whole state of one list's view. */
data class LibraryQuery(
    val text: String = "",
    val facets: Map<String, Set<String>> = emptyMap(),
    val sort: LibrarySortKey = LibrarySortKey.NAME,
    /** The sort run backwards from its natural order ([LibrarySortKey.naturalOrder]); picking the same sort again flips it. */
    val reversed: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isBlank() && facets.values.all { it.isEmpty() }

    fun selected(facet: LibraryFacet): Set<String> = facets[facet.key].orEmpty()

    /** One value toggled on or off; a facet left with nothing selected stops filtering. */
    fun withToggled(facet: LibraryFacet, value: String, on: Boolean): LibraryQuery {
        val selected = (facets[facet.key] ?: emptySet()).let { if (on) it + value else it - value }
        val nextFacets = if (selected.isEmpty()) facets - facet.key else facets + (facet.key to selected)
        return copy(facets = nextFacets)
    }

    /** The same view with no search and no facet selected; the sort stays, it is not a filter. */
    val cleared: LibraryQuery get() = copy(text = "", facets = emptyMap())

    /** Picking the sort that is already active flips its direction; a different one starts in its natural order. */
    fun withSort(key: LibrarySortKey): LibraryQuery =
        if (key == sort) copy(reversed = !reversed) else copy(sort = key, reversed = false)

    /** One chip per active filter, in the scope's facet order: what a chip row draws. */
    fun activeChips(scope: LibraryQueryScope): List<QueryChip> = buildList {
        text.trim().takeIf { it.isNotEmpty() }?.let { add(QueryChip(null, it)) }
        scope.facets.forEach { facet -> selected(facet).sorted().forEach { add(QueryChip(facet, it)) } }
    }

    /** The view with one chip's filter taken off. */
    fun without(chip: QueryChip): LibraryQuery =
        if (chip.facet == null) copy(text = "") else withToggled(chip.facet, chip.value, on = false)

    /** Whether the entry passes the search text and every selected facet value. */
    fun matches(entry: LibraryEntry, scope: LibraryQueryScope): Boolean {
        if (!matchesSearchText(entry, text)) return false
        // Hidden entries, and store rows the person's library does not hold,
        // are out of a list by the one rule (docs/SPEC.md 7j, listExclusion).
        if (exclusionOf(entry, scope) != null) return false
        return matchesFacets(entry, scope)
    }

    private fun matchesFacets(entry: LibraryEntry, scope: LibraryQueryScope): Boolean = scope.facets.all { facet ->
        val selected = selected(facet)
        selected.isEmpty() || facet.valuesOf(entry, scope.context).any { it in selected }
    }

    /**
     * Why [listExclusion] keeps [entry] out of this list, or null. A list that
     * does not offer the Hidden facet keeps hidden entries, as a console list
     * always did; one with no ownership options has no ownership rule.
     */
    fun exclusionOf(entry: LibraryEntry, scope: LibraryQueryScope): Exclusion? =
        listExclusion(
            entry,
            place = placeOf(),
            options = scope.ownership,
            query = this,
            includeHidden = LibraryFacet.HIDDEN !in scope.facets,
        )

    /** Installed and Recently played are about what is on the device and what was played: they ignore ownership (7j). */
    private fun placeOf(): ListPlace =
        if (INSTALLED_YES in selected(LibraryFacet.INSTALLED) || selected(LibraryFacet.RECENTLY_PLAYED).isNotEmpty()) ListPlace.ACTIVITY else ListPlace.LIST

    /**
     * How many free-to-play rows not in the person's library this view would
     * show but for the List option (rule 4 of [listExclusion]), or, with the
     * option on, shows because of it: the grid's footer row (docs/SPEC.md 7j).
     * 0 while the Ownership facet chooses for itself. One pass.
     */
    fun freeNotInLibrary(base: List<LibraryEntry>, scope: LibraryQueryScope): Int {
        if (scope.ownership == null || selected(LibraryFacet.OWNERSHIP).isNotEmpty() || placeOf() == ListPlace.ACTIVITY) return 0
        return base.count { entry ->
            entry.holding() == StoreHolding.FREE && !entry.freeInLibrary() &&
                (!entry.hidden || HIDDEN_YES in selected(LibraryFacet.HIDDEN)) &&
                matchesSearchText(entry, text) && matchesFacets(entry, scope)
        }
    }

    /** The list as this query shows it: filtered, then sorted, ties by title. */
    fun applyTo(base: List<LibraryEntry>, scope: LibraryQueryScope): List<LibraryEntry> =
        base.filter { matches(it, scope) }.sortedWith(sort.comparator(scope.context, reversed))

    /**
     * The facets this list offers with the values it holds and their counts,
     * counted over the whole list (not over the other selected facets, so a
     * count says what picking the value alone would show). A facet no entry
     * has a value for is not offered, and neither is one whose single value
     * every entry carries and nothing has selected: it would narrow nothing.
     * Hidden entries count only toward the Hidden facet. One pass per facet.
     */
    fun facetOffers(base: List<LibraryEntry>, scope: LibraryQueryScope): List<FacetOffer> {
        // Counts are over what the list shows with nothing filtered; the
        // Ownership facet counts every holding (its values are how to see
        // the rows the options keep out), Hidden counts the hidden too.
        val notHidden = base.filter { !it.hidden }
        val unfiltered = LibraryQuery(facets = facets.filterKeys { it == LibraryFacet.OWNERSHIP.key })
        val visible = notHidden.filter { unfiltered.exclusionOf(it, scope) == null }
        return scope.facets.mapNotNull { facet ->
            val over = when (facet) {
                LibraryFacet.HIDDEN -> base
                LibraryFacet.OWNERSHIP -> notHidden
                else -> visible
            }
            val counted = facet.valueCounts(over, scope.context)
            // A selected value nothing has right now (Running, with nothing
            // running) stays listed at zero so it can be taken off again.
            val absent = selected(facet).filter { value -> counted.none { it.value == value } }.map { FacetValueCount(it, 0) }
            val values = (counted + absent).sortedWith(facet.valueOrder())
            val narrowsNothing = counted.size == 1 && absent.isEmpty() && counted[0].count == over.size && selected(facet).isEmpty()
            if (values.isEmpty() || narrowsNothing) null else FacetOffer(facet, values)
        }
    }

    /**
     * How many entries the list shows with no filter on: hidden ones and rows
     * the ownership options keep out are not part of it unless the Hidden or
     * Ownership facet asks for them.
     */
    fun totalIn(base: List<LibraryEntry>, scope: LibraryQueryScope): Int {
        val unfiltered = LibraryQuery(facets = facets.filterKeys { it == LibraryFacet.HIDDEN.key || it == LibraryFacet.OWNERSHIP.key })
        val holdings = unfiltered.selected(LibraryFacet.OWNERSHIP)
        return base.count { entry ->
            unfiltered.exclusionOf(entry, scope) == null && (holdings.isEmpty() || entry.holding()?.name in holdings)
        }
    }
}

/**
 * The two global List options that decide which held store rows an ordinary
 * list shows (docs/SPEC.md 7j): games another account shares (on by
 * default) and free-to-play games the account never added (off). They are
 * not part of a saved view: a view that wants free games selects Ownership.
 */
data class OwnershipOptions(val showShared: Boolean = true, val showFree: Boolean = false)

/**
 * Which of PC Games' optional built-in tabs the strip shows (List options >
 * Strip tabs, docs/SPEC.md 7i): Favourites and Collections may be hidden;
 * Overview, All games and Installed cannot, and the order never changes.
 */
data class StripTabs(val favourites: Boolean = true, val collections: Boolean = true)

/** Which kind of list asks [listExclusion]. */
enum class ListPlace {
    /** An ordinary list: All games, a saved view, a shelf of the library, search. */
    LIST,

    /** Installed, Continue playing, Recently played: what is on the device or was played, whoever holds it. */
    ACTIVITY,
}

/** Why [listExclusion] keeps an entry out. */
enum class Exclusion { HIDDEN, SHARED, FREE }

/**
 * THE rule for which games a list shows (docs/SPEC.md 7j, "Hidden is one
 * rule", extended by Droidtop/tracker#397): every list, count, shelf and the
 * launcher's search reads it. Highest first:
 * 1. Hidden is out unless [includeHidden] or the Hidden facet is selected in [query].
 * 2. With the Ownership facet selected, the list shows exactly those holdings
 *    (the facet filters; the options below step aside).
 * 3. [ListPlace.ACTIVITY] ignores ownership.
 * 4. Otherwise OWNED and NOT_OWNED are in, FAMILY unless [OwnershipOptions.showShared]
 *    is off, FREE only with [OwnershipOptions.showFree].
 * 5. A FREE row is in the library while installed or ever played ([freeInLibrary]):
 *    derived, never stored. To keep a tried free game out, the person hides it.
 * [options] null means the list has no ownership rule (a console list).
 * Pure; one look at fields the entry already carries.
 */
fun listExclusion(
    entry: LibraryEntry,
    place: ListPlace,
    options: OwnershipOptions?,
    query: LibraryQuery? = null,
    includeHidden: Boolean = false,
): Exclusion? {
    if (entry.hidden && !includeHidden && (query == null || HIDDEN_YES !in query.selected(LibraryFacet.HIDDEN))) return Exclusion.HIDDEN
    if (options == null || place == ListPlace.ACTIVITY) return null
    if (query != null && query.selected(LibraryFacet.OWNERSHIP).isNotEmpty()) return null
    return when (entry.holding()) {
        null, StoreHolding.OWNED, StoreHolding.NOT_OWNED -> null
        StoreHolding.FAMILY -> if (options.showShared) null else Exclusion.SHARED
        StoreHolding.FREE -> if (options.showFree || entry.freeInLibrary()) null else Exclusion.FREE
    }
}

/** A store row's holding, or null for anything no store holds (a folder game, a Wine shortcut, a ROM, an app). */
internal fun LibraryEntry.holding(): StoreHolding? = pcInfo?.takeIf { PcSource.storeIdOf(it.storeId) != null }?.holding

/** Rule 5: a free game counts as the person's once installed or played. */
internal fun LibraryEntry.freeInLibrary(): Boolean = isInstalled || playCount > 0 || lastPlayedEpochMs != null

/** A facet as the filter sheet offers it: the values the list holds, with counts. */
data class FacetOffer(val facet: LibraryFacet, val values: List<FacetValueCount>)

/**
 * One active filter as a chip: a facet value, or (facet null) the search
 * text. The chip row and the sheet's summary line read these, so what is
 * drawn and what is applied cannot differ.
 */
data class QueryChip(val facet: LibraryFacet?, val value: String) {
    val label: String get() = if (facet == null) "\"$value\"" else facet.valueLabel(value)
}

/**
 * The filters no strip view stands for, as the strip's ONE pill, or null
 * while nothing filters (docs/SPEC.md 7i): every active filter, the first two
 * by name and the rest as a number ("Steam · Shared with you · +2, 40 of
 * 3,300"), so it stays short enough for one line. PC Games and Apps both draw it.
 */
fun LibraryQuery.pillText(scope: LibraryQueryScope, shown: Int, total: Int): String? {
    val chips = activeChips(scope)
    if (chips.isEmpty()) return null
    val named = chips.take(PILL_NAMED).joinToString(" · ") { it.label }
    val more = (chips.size - PILL_NAMED).takeIf { it > 0 }?.let { " · +$it" }.orEmpty()
    return "$named$more, ${"%,d".format(shown)} of ${"%,d".format(total)}"
}

/** How many filters the pill names before it counts the rest. */
private const val PILL_NAMED = 2

/** "12 of 80 apps" while something filters, "80 apps" while nothing does. Pure, for the header and the sheets. */
fun queryCountLine(shown: Int, total: Int, filtering: Boolean, scope: LibraryQueryScope): String {
    val noun = if (total == 1) scope.noun else scope.nounPlural
    return if (filtering) "$shown of $total $noun" else "$total $noun"
}

/**
 * The one search-text rule: a blank text matches everything, otherwise the
 * text must be inside the title, genre or developer (case-insensitive).
 * [LibraryQuery.matches] and the console gamelist's search (docs/SPEC.md
 * 12a "Search fan-out") both call this, so a search means the same thing in
 * every list.
 */
fun matchesSearchText(entry: LibraryEntry, text: String): Boolean {
    val wanted = text.trim()
    if (wanted.isBlank()) return true
    return listOfNotNull(
        entry.title,
        entry.genre?.takeIf { it.isNotBlank() },
        entry.developer?.takeIf { it.isNotBlank() },
    ).any { it.contains(wanted, ignoreCase = true) }
}

/**
 * The comparator for [LibrarySortKey]. An entry the sort has no fact for
 * (never played, no release date) is last in BOTH directions, so flipping
 * a sort never puts the blanks first; ties are by title, and only a flipped
 * name sort reverses those too.
 */
fun LibrarySortKey.comparator(
    context: LibraryQueryContext = LibraryQueryContext(),
    reversed: Boolean = false,
): Comparator<LibraryEntry> {
    val byTitle = compareBy<LibraryEntry> { it.title.lowercase() }
    val missingLast = compareBy<LibraryEntry> { !hasFact(it, context) }
    val primary: Comparator<LibraryEntry> = when (this) {
        LibrarySortKey.NAME -> return if (reversed) byTitle.reversed() else byTitle
        LibrarySortKey.RECENT -> compareByDescending<LibraryEntry> { context.lastUsedOf(it) ?: 0L }
        LibrarySortKey.MOST_USED -> compareByDescending<LibraryEntry> { it.playCount }
        LibrarySortKey.PLAYTIME -> compareByDescending<LibraryEntry> { it.playtimeSeconds }
        LibrarySortKey.ADDED -> compareByDescending<LibraryEntry> { it.addedEpochMs() }
        LibrarySortKey.YEAR -> compareBy<LibraryEntry> { it.releaseDate ?: "99999999" }
        LibrarySortKey.RATING -> compareByDescending<LibraryEntry> { it.rating ?: -1f }
        LibrarySortKey.SIZE -> compareByDescending<LibraryEntry> { it.pcInfo?.sizeBytes ?: 0L }
    }
    return missingLast.then(if (reversed) primary.reversed() else primary).then(byTitle)
}

/** Whether the entry has the fact this sort orders by. */
private fun LibrarySortKey.hasFact(entry: LibraryEntry, context: LibraryQueryContext): Boolean = when (this) {
    LibrarySortKey.NAME -> true
    LibrarySortKey.RECENT -> context.lastUsedOf(entry) != null
    LibrarySortKey.MOST_USED -> entry.playCount > 0
    LibrarySortKey.PLAYTIME -> entry.playtimeSeconds > 0
    LibrarySortKey.ADDED -> entry.addedEpochMs() > 0L
    LibrarySortKey.YEAR -> entry.releaseDate != null
    LibrarySortKey.RATING -> entry.rating != null
    LibrarySortKey.SIZE -> (entry.pcInfo?.sizeBytes ?: 0L) > 0L
}

/** When the entry arrived: an app's install time, else the library's first sighting. */
internal fun LibraryEntry.addedEpochMs(): Long =
    appFacts?.firstInstalledEpochMs?.takeIf { it > 0L } ?: firstSeenEpochMs

/** The four-digit year of a real releaseDate ("YYYYMMDDT000000"), or null when there is no year to read. */
internal fun LibraryEntry.releaseYear(): String? =
    releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

/**
 * The Apps view's scope (docs/SPEC.md 7j): its facets in the order the sheet
 * lists them, its sorts under the words an app list uses, and the lookups
 * its facets read -- the category rules (default plus the person's "Mark as
 * game"), the usage log merged with droidtop's own launch log, and which
 * packages are running right now. Pure; the callers load the inputs off the
 * main thread.
 */
fun appsQueryScope(
    rules: AppCategoryRules,
    usage: Map<String, Long>,
    running: Set<String>,
): LibraryQueryScope = LibraryQueryScope(
    id = APPS_SCOPE_ID,
    facets = listOf(
        LibraryFacet.CATEGORY, LibraryFacet.RUNNING, LibraryFacet.RECENTLY_USED, LibraryFacet.RECENTLY_INSTALLED,
        LibraryFacet.FAVOURITES, LibraryFacet.HIDDEN, LibraryFacet.APP_SOURCE,
    ),
    sorts = listOf(LibrarySortKey.NAME, LibrarySortKey.RECENT, LibrarySortKey.MOST_USED, LibrarySortKey.ADDED),
    sortLabels = mapOf(
        LibrarySortKey.RECENT to "Recently used",
        LibrarySortKey.MOST_USED to "Most used",
        LibrarySortKey.ADDED to "Recently installed",
    ),
    noun = "app",
    nounPlural = "apps",
    context = LibraryQueryContext(
        appCategoryOf = { entry -> rules.categoryOf(entry.id, entry.appFacts)?.label },
        isRunning = { entry -> entry.id in running },
        lastUsedOf = { entry -> listOfNotNull(entry.lastPlayedEpochMs, usage[entry.id]).maxOrNull() },
    ),
)

/**
 * A console gamelist's scope: one remembered view per group (a system, an
 * engine), the facets a gamelist has facts for, and the sorts ES-DE's own
 * gamelist options offer. The one model behind the Select menu's "Sort by"
 * and "Filter" rows.
 */
fun retroQueryScope(groupLabel: String): LibraryQueryScope = LibraryQueryScope(
    id = "retro:$groupLabel",
    facets = listOf(
        LibraryFacet.FAVOURITES, LibraryFacet.COMPLETED, LibraryFacet.PLAYED, LibraryFacet.GENRE,
        LibraryFacet.DEVELOPER, LibraryFacet.YEAR, LibraryFacet.SWITCH_CONTENT, LibraryFacet.HIDDEN,
    ),
    sorts = listOf(LibrarySortKey.NAME, LibrarySortKey.RATING, LibrarySortKey.YEAR, LibrarySortKey.RECENT),
    sortLabels = mapOf(LibrarySortKey.YEAR to "Release date"),
)

/**
 * The Standard launcher's Games list (docs/SPEC.md 2c, "Games in the Launcher"): one remembered view
 * over every game the library holds, console, PC and engine alike, with the facts any of them has.
 */
fun launcherGamesQueryScope(): LibraryQueryScope = LibraryQueryScope(
    id = LAUNCHER_GAMES_SCOPE_ID,
    facets = listOf(
        LibraryFacet.FAVOURITES, LibraryFacet.RECENTLY_PLAYED, LibraryFacet.PLAYED, LibraryFacet.GENRE,
        LibraryFacet.DEVELOPER, LibraryFacet.YEAR, LibraryFacet.HIDDEN,
    ),
    sorts = listOf(LibrarySortKey.NAME, LibrarySortKey.RECENT, LibrarySortKey.MOST_USED, LibrarySortKey.YEAR, LibrarySortKey.RATING),
    sortLabels = mapOf(LibrarySortKey.YEAR to "Release date"),
)

/** The launcher's Games list keeps its own remembered view under this scope id ([LibraryViewPrefs]). */
const val LAUNCHER_GAMES_SCOPE_ID = "launcher_games"

/** Apps keep their own remembered view under this scope id ([LibraryViewPrefs]). */
const val APPS_SCOPE_ID = "apps"

/**
 * A view saved by name: the tabs a list leads with are built-in ones (All
 * games, Installed), and the same shape is what a person's own saved views
 * are -- one mechanism for both. [id] stays the same when the view is renamed
 * or edited, so it keeps its place; [pinned] puts it on the strip as a tab
 * (docs/SPEC.md 7i), otherwise it is listed with the saved views only.
 */
data class NamedLibraryView(
    val name: String,
    val query: LibraryQuery,
    val id: String = name,
    val pinned: Boolean = true,
)

/**
 * Per-list persistence of saved views and of the query the list was left
 * showing: a filtered list stays filtered on the way back, for the PC
 * library, the Apps view and every console gamelist alike.
 */
object LibraryViewPrefs {
    private const val VIEWS_PREFIX = "droidtop_library_views_"
    private const val ACTIVE_PREFIX = "droidtop_library_active_"

    fun savedViews(context: Context, scopeId: String): List<NamedLibraryView> {
        val raw = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(VIEWS_PREFIX + scopeId, null) ?: return emptyList()
        val views = decodeViews(raw)
        // Once, the first time views saved before facet values were ids are read.
        if (views.none { LEGACY_STORE_KEY in it.query.facets }) return views
        val roots = rootsOf(context)
        val migrated = views.map { it.copy(query = migrateLegacyStore(it.query, ::storeIdForLabel, roots)) }
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(VIEWS_PREFIX + scopeId, encodeViews(migrated)).apply()
        return migrated
    }

    /**
     * Saving a view whose id is already saved replaces it in place (an edit
     * keeps its tab position); a new name is a new view at the end, and
     * saving a name again replaces that view, keeping its place.
     */
    fun saveView(context: Context, scopeId: String, view: NamedLibraryView) {
        writeViews(context, scopeId, withView(savedViews(context, scopeId), view))
    }

    /** Pure, for the JVM tests: [views] with [view] saved by the rule [saveView] states. */
    internal fun withView(views: List<NamedLibraryView>, view: NamedLibraryView): List<NamedLibraryView> {
        val index = views.indexOfFirst { it.id == view.id }.takeIf { it >= 0 } ?: views.indexOfFirst { it.name == view.name }
        return if (index < 0) views + view else views.toMutableList().apply { set(index, view.copy(id = views[index].id)) }
    }

    fun removeView(context: Context, scopeId: String, name: String) {
        writeViews(context, scopeId, savedViews(context, scopeId).filterNot { it.name == name })
    }

    /** All of a list's saved views at once, in the order given: a reorder, a pin or an unpin. */
    fun writeViews(context: Context, scopeId: String, views: List<NamedLibraryView>) {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
        if (views.isEmpty()) prefs.remove(VIEWS_PREFIX + scopeId) else prefs.putString(VIEWS_PREFIX + scopeId, encodeViews(views))
        prefs.apply()
    }

    /** The PC library's scope id; its saved views are the strip's pinned tabs. */
    const val PC_SCOPE_ID = "pc"

    /** The id of the "Updates" view everyone was given once (docs/SPEC.md 7i). */
    const val UPDATES_VIEW_ID = "updates"
    private const val SEEDED_UPDATES = "droidtop_library_seeded_updates_view"

    /**
     * The pinned "Updates" view over the Update available facet, first among
     * [views], given once when the Updates built-in tab went away. Pure, for
     * the JVM tests; unpinning or removing it later is the person's choice.
     */
    internal fun withUpdatesView(views: List<NamedLibraryView>): List<NamedLibraryView> =
        if (views.any { it.id == UPDATES_VIEW_ID }) {
            views
        } else {
            listOf(NamedLibraryView("Updates", LibraryQuery(facets = mapOf(LibraryFacet.UPDATE.key to setOf(UPDATE_YES))), UPDATES_VIEW_ID)) + views
        }

    /** Gives the PC library its "Updates" view, once ever; off the main thread. */
    fun seedOnce(context: Context, scopeId: String) {
        if (scopeId != PC_SCOPE_ID) return
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SEEDED_UPDATES, false)) return
        writeViews(context, scopeId, withUpdatesView(savedViews(context, scopeId)))
        prefs.edit().putBoolean(SEEDED_UPDATES, true).apply()
    }

    /** The query the list was left showing, or [default] when it has never been changed (Last played opens by recency). */
    fun activeQuery(context: Context, scopeId: String, default: LibraryQuery = LibraryQuery()): LibraryQuery {
        val raw = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(ACTIVE_PREFIX + scopeId, null) ?: return default
        val query = decodeQuery(raw) ?: LibraryQuery()
        if (LEGACY_STORE_KEY !in query.facets) return query
        return migrateLegacyStore(query, ::storeIdForLabel, rootsOf(context)).also { setActiveQuery(context, scopeId, it) }
    }

    /**
     * The facet views saved before facet values were ids filtered stores by:
     * key "store", values the names a library row carried ("Steam", "Steam
     * Family", "Folder", "Wine").
     */
    internal const val LEGACY_STORE_KEY = "store"

    /**
     * [query] with its old Store selections as Source ids, so a saved view
     * filters the same games it did (docs/SPEC.md 7j, "Filters"): a store's
     * name, and its "<name> Family" and "<name> Free" groups, become the
     * store's id; "Folder" becomes every game folder ([roots]) and the folders
     * outside them; "Wine" the Wine shortcuts. A name no store has becomes the
     * store id it spells. Pure, for the JVM tests.
     */
    internal fun migrateLegacyStore(query: LibraryQuery, storeIdForLabel: (String) -> String?, roots: List<String>): LibraryQuery {
        val old = query.facets[LEGACY_STORE_KEY] ?: return query
        val ids = old.flatMap { label ->
            when (label) {
                "Folder" -> roots.map { PcSource.Folder(it).id } + PcSource.Folder("").id
                "Wine" -> listOf(PcSource.WineShortcut.id)
                else -> {
                    val name = label.removeSuffix(" Family").removeSuffix(" Free")
                    listOf(storeIdForLabel(label) ?: storeIdForLabel(name) ?: name.lowercase())
                }
            }
        }.toSet()
        val facets = query.facets - LEGACY_STORE_KEY
        return query.copy(facets = if (ids.isEmpty()) facets else facets + (LibraryFacet.SOURCE.key to ids))
    }

    private fun storeIdForLabel(label: String): String? =
        dev.droidtop.library.stores.StoreLibraries.all().firstOrNull { it.label.equals(label, ignoreCase = true) }?.id

    private fun rootsOf(context: Context): List<String> =
        runCatching { dev.droidtop.library.GamesRoots.current(context).map { it.absolutePath } }.getOrDefault(emptyList())

    private const val SHOW_SHARED = "droidtop_library_show_shared"
    private const val SHOW_FREE = "droidtop_library_show_free"

    /** The ownership List options (docs/SPEC.md 7j), global, not per view. A preferences read: off the main thread. */
    fun ownershipOptions(context: Context): OwnershipOptions {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        val defaults = OwnershipOptions()
        return OwnershipOptions(
            showShared = prefs.getBoolean(SHOW_SHARED, defaults.showShared),
            showFree = prefs.getBoolean(SHOW_FREE, defaults.showFree),
        )
    }

    private const val TAB_FAVOURITES = "droidtop_library_pc_tab_favourites"
    private const val TAB_COLLECTIONS = "droidtop_library_pc_tab_collections"

    /** Which of the PC strip's optional built-in tabs show (List options > Strip tabs); a preferences read. */
    fun stripTabs(context: Context): StripTabs {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        return StripTabs(favourites = prefs.getBoolean(TAB_FAVOURITES, true), collections = prefs.getBoolean(TAB_COLLECTIONS, true))
    }

    fun setStripTabs(context: Context, tabs: StripTabs) {
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(TAB_FAVOURITES, tabs.favourites)
            .putBoolean(TAB_COLLECTIONS, tabs.collections)
            .apply()
    }

    fun setOwnershipOptions(context: Context, options: OwnershipOptions) {
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(SHOW_SHARED, options.showShared)
            .putBoolean(SHOW_FREE, options.showFree)
            .apply()
    }

    fun setActiveQuery(context: Context, scopeId: String, query: LibraryQuery) {
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(ACTIVE_PREFIX + scopeId, encodeQuery(query)).apply()
    }

    /** Pure, for the JVM tests. */
    internal fun encodeQuery(query: LibraryQuery): String {
        val json = JSONObject()
            .put("sort", query.sort.name)
            .put("reversed", query.reversed)
            .put("text", query.text)
        val facets = JSONObject()
        query.facets.forEach { (key, values) -> facets.put(key, org.json.JSONArray(values)) }
        return json.put("facets", facets).toString()
    }

    /** Pure, for the JVM tests; a stored value that is not one of ours reads as the default view. */
    internal fun decodeQuery(raw: String?): LibraryQuery? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val facets = buildMap {
                val facetJson = json.optJSONObject("facets") ?: JSONObject()
                facetJson.keys().forEach { key ->
                    val values = facetJson.optJSONArray(key)
                    if (values != null) {
                        put(key, (0 until values.length()).mapNotNull { values.optString(it).ifBlank { null } }.toSet())
                    }
                }
            }
            LibraryQuery(
                text = json.optString("text"),
                facets = facets,
                sort = runCatching { LibrarySortKey.valueOf(json.optString("sort")) }.getOrDefault(LibrarySortKey.NAME),
                reversed = json.optBoolean("reversed", false),
            )
        }.getOrNull() ?: LibraryQuery()
    }
    /**
     * Pure, for the JVM tests. A JSONArray, not a name-keyed JSONObject:
     * `org.json:json`'s own JSONObject does not promise its `keys()`
     * iteration order matches insertion order (confirmed live -- the
     * round trip test below caught it reordering two views), and this
     * list's own order is a real, stated guarantee ("saved views keep
     * their order").
     */
    internal fun encodeViews(views: List<NamedLibraryView>): String {
        val array = org.json.JSONArray()
        views.forEach { view ->
            array.put(
                JSONObject().put("name", view.name).put("id", view.id).put("pinned", view.pinned)
                    .put("query", JSONObject(encodeQuery(view.query))),
            )
        }
        return array.toString()
    }

    /** Pure, for the JVM tests. A JSONObject from before this format is simply empty, never an error. */
    internal fun decodeViews(raw: String?): List<NamedLibraryView> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val name = entry.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val query = entry.optJSONObject("query")?.toString()?.let { decodeQuery(it) } ?: return@mapNotNull null
                // A view saved before ids and pins is its own name, and was on the strip.
                NamedLibraryView(name, query, id = entry.optString("id").ifBlank { name }, pinned = entry.optBoolean("pinned", true))
            }
        }.getOrDefault(emptyList())
    }
}
