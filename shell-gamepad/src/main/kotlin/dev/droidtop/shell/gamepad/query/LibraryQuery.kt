package dev.droidtop.shell.gamepad.query

import android.content.Context
import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.appSourceLabel
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.shell.gamepad.pc.engineLabel
import dev.droidtop.shell.gamepad.pc.isInstalled
import dev.droidtop.shell.gamepad.pc.sourceLabel
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
 * Semantics: values within one facet OR (Steam or GOG), facets AND
 * (Steam and installed), the search text ANDs with everything. A facet
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
 * empty answer never matches a selection.
 */
enum class LibraryFacet(val key: String, val label: String) {
    STORE("store", "Store"),
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
        STORE -> listOf(entry.sourceLabel())
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

    /**
     * Every value present in [entries] with how many entries carry it, in
     * display order -- the facet's sheet list. A value no entry has is
     * never listed, so a count is never zero.
     */
    fun valueCounts(entries: List<LibraryEntry>, context: LibraryQueryContext): List<FacetValueCount> {
        val counts = LinkedHashMap<String, Int>()
        entries.forEach { entry -> valuesOf(entry, context).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.map { FacetValueCount(it.key, it.value) }.sortedBy { it.value.lowercase() }
    }
}

/** One value of a facet and how many entries have it. */
data class FacetValueCount(val value: String, val count: Int)

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
        // Hidden entries are out of every list unless the Hidden facet asks
        // for them (docs/SPEC.md 7j): one rule for games and apps alike.
        if (entry.hidden && LibraryFacet.HIDDEN in scope.facets && HIDDEN_YES !in selected(LibraryFacet.HIDDEN)) return false
        return scope.facets.all { facet ->
            val selected = selected(facet)
            selected.isEmpty() || facet.valuesOf(entry, scope.context).any { it in selected }
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
        val visible = base.filter { !it.hidden }
        return scope.facets.mapNotNull { facet ->
            val over = if (facet == LibraryFacet.HIDDEN) base else visible
            val counted = facet.valueCounts(over, scope.context)
            // A selected value nothing has right now (Running, with nothing
            // running) stays listed at zero so it can be taken off again.
            val absent = selected(facet).filter { value -> counted.none { it.value == value } }.map { FacetValueCount(it, 0) }
            val values = (counted + absent).sortedBy { it.value.lowercase() }
            val narrowsNothing = counted.size == 1 && absent.isEmpty() && counted[0].count == over.size && selected(facet).isEmpty()
            if (values.isEmpty() || narrowsNothing) null else FacetOffer(facet, values)
        }
    }

    /** How many entries the list shows with no filter on: hidden ones are not part of it unless the Hidden facet asks. */
    fun totalIn(base: List<LibraryEntry>, scope: LibraryQueryScope): Int =
        if (LibraryFacet.HIDDEN !in scope.facets || HIDDEN_YES in selected(LibraryFacet.HIDDEN)) base.size else base.count { !it.hidden }
}

/** A facet as the filter sheet offers it: the values the list holds, with counts. */
data class FacetOffer(val facet: LibraryFacet, val values: List<FacetValueCount>)

/**
 * One active filter as a chip: a facet value, or (facet null) the search
 * text. The chip row and the sheet's summary line read these, so what is
 * drawn and what is applied cannot differ.
 */
data class QueryChip(val facet: LibraryFacet?, val value: String) {
    val label: String get() = if (facet == null) "\"$value\"" else value
}

/**
 * The filters no strip view stands for, as the strip's ONE pill ("Running,
 * 2 of 40"), or null while nothing filters. PC Games and Apps both draw it.
 */
fun LibraryQuery.pillText(scope: LibraryQueryScope, shown: Int, total: Int): String? {
    val chips = activeChips(scope)
    return if (chips.isEmpty()) null else chips.joinToString(", ") { it.label } + ", $shown of $total"
}

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

/** Apps keep their own remembered view under this scope id ([LibraryViewPrefs]). */
const val APPS_SCOPE_ID = "apps"

/**
 * A view saved by name: the chips the PC library leads with are built-in
 * ones (Continue playing, Installed, per store), and the same shape is
 * what a person's own saved views are -- one mechanism for both.
 */
data class NamedLibraryView(val name: String, val query: LibraryQuery)

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
        return decodeViews(raw)
    }

    /** Saving a name again replaces it, keeping the order it was first saved in. */
    fun saveView(context: Context, scopeId: String, view: NamedLibraryView) {
        val views = savedViews(context, scopeId).filterNot { it.name == view.name } + view
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(VIEWS_PREFIX + scopeId, encodeViews(views)).apply()
    }

    fun removeView(context: Context, scopeId: String, name: String) {
        val views = savedViews(context, scopeId).filterNot { it.name == name }
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
        if (views.isEmpty()) prefs.remove(VIEWS_PREFIX + scopeId) else prefs.putString(VIEWS_PREFIX + scopeId, encodeViews(views))
        prefs.apply()
    }

    fun activeQuery(context: Context, scopeId: String): LibraryQuery {
        val raw = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(ACTIVE_PREFIX + scopeId, null) ?: return LibraryQuery()
        return decodeQuery(raw) ?: LibraryQuery()
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
            array.put(JSONObject().put("name", view.name).put("query", JSONObject(encodeQuery(view.query))))
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
                NamedLibraryView(name, query)
            }
        }.getOrDefault(emptyList())
    }
}
