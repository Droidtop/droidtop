package dev.droidtop.shell.gamepad.query

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.shell.gamepad.pc.engineLabel
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
enum class LibrarySortKey(val label: String) {
    NAME("Name"),
    RECENT("Last played"),
    PLAYTIME("Playtime"),
    ADDED("Added"),
    YEAR("Release year"),
    RATING("Rating"),
    SIZE("Size"),
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
    ;

    fun valuesOf(entry: LibraryEntry, context: LibraryQueryContext): List<String> = when (this) {
        STORE -> listOf(entry.sourceLabel())
        ENGINE -> entry.engineLabel()?.let { listOf(it) }.orEmpty()
        RUNNER -> context.runnerLabelOf(entry)?.let { listOf(it) }.orEmpty()
        INSTALLED -> entry.pcInfo?.let { listOf(if (it.installed) INSTALLED_YES else INSTALLED_NO) }.orEmpty()
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
        HIDDEN -> listOf(if (entry.hidden) HIDDEN_YES else HIDDEN_NO)
    }

    /** Every value present in [entries], in display order -- the facet's dialog list. */
    fun valuesIn(entries: List<LibraryEntry>, context: LibraryQueryContext): List<String> =
        entries.flatMap { valuesOf(it, context) }.distinct().sortedBy { it.lowercase() }
}

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
const val HIDDEN_NO = "Not hidden"

/** "Recently played" is the last 14 days, one definition for every reader. */
const val RECENT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

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
    val now: () -> Long = System::currentTimeMillis,
)

/** What one list offers: which facets, which sorts, and how to read the facts. */
data class LibraryQueryScope(
    /** Saved views and the active query persist per list, by this id ("pc", a system id, ...). */
    val id: String,
    val facets: List<LibraryFacet>,
    val sorts: List<LibrarySortKey>,
    val context: LibraryQueryContext = LibraryQueryContext(),
)

/** A search text, facet selections, and a sort -- the whole state of one list's view. */
data class LibraryQuery(
    val text: String = "",
    val facets: Map<String, Set<String>> = emptyMap(),
    val sort: LibrarySortKey = LibrarySortKey.NAME,
) {
    val isEmpty: Boolean get() = text.isBlank() && facets.values.all { it.isEmpty() }

    fun selected(facet: LibraryFacet): Set<String> = facets[facet.key].orEmpty()

    /** One value toggled on or off; a facet left with nothing selected stops filtering. */
    fun withToggled(facet: LibraryFacet, value: String, on: Boolean): LibraryQuery {
        val selected = (facets[facet.key] ?: emptySet()).let { if (on) it + value else it - value }
        val nextFacets = if (selected.isEmpty()) facets - facet.key else facets + (facet.key to selected)
        return copy(facets = nextFacets)
    }

    val clearFacets: LibraryQuery get() = copy(facets = emptyMap())

    /** Whether the entry passes the search text and every selected facet value. */
    fun matches(entry: LibraryEntry, scope: LibraryQueryScope): Boolean {
        val wanted = text.trim()
        if (wanted.isNotBlank()) {
            val haystack = listOfNotNull(
                entry.title,
                entry.genre?.takeIf { it.isNotBlank() },
                entry.developer?.takeIf { it.isNotBlank() },
            )
            if (haystack.none { it.contains(wanted, ignoreCase = true) }) return false
        }
        return scope.facets.all { facet ->
            val selected = selected(facet)
            selected.isEmpty() || facet.valuesOf(entry, scope.context).any { it in selected }
        }
    }

    /** The list as this query shows it: filtered, then sorted, ties by title. */
    fun applyTo(base: List<LibraryEntry>, scope: LibraryQueryScope): List<LibraryEntry> =
        base.filter { matches(it, scope) }.sortedWith(comparator())
}

/** The comparator for [LibrarySortKey]; by name last, so two orders of one sort stay one order. */
fun LibrarySortKey.comparator(): Comparator<LibraryEntry> {
    val byTitle = compareBy<LibraryEntry> { it.title.lowercase() }
    return when (this) {
        LibrarySortKey.NAME -> byTitle
        LibrarySortKey.RECENT -> compareByDescending<LibraryEntry> { it.lastPlayedEpochMs ?: 0L }.thenBy { it.title.lowercase() }
        LibrarySortKey.PLAYTIME -> compareByDescending<LibraryEntry> { it.playtimeSeconds }.thenBy { it.title.lowercase() }
        // Unknown is last, never guessed: a game droidtop has no stamp for
        // does not sort as if it were older or newer than every other one
        // by accident of the number chosen to stand for "unknown".
        LibrarySortKey.ADDED -> compareByDescending<LibraryEntry> { it.addedEpochMs ?: -1L }.thenBy { it.title.lowercase() }
        LibrarySortKey.YEAR -> compareBy<LibraryEntry> { it.releaseDate ?: "99999999" }.thenBy { it.title.lowercase() }
        LibrarySortKey.RATING -> compareByDescending<LibraryEntry> { it.rating ?: -1f }.thenBy { it.title.lowercase() }
        LibrarySortKey.SIZE -> compareByDescending<LibraryEntry> { it.pcInfo?.sizeBytes ?: 0L }.thenBy { it.title.lowercase() }
    }
}

/** The four-digit year of a real releaseDate ("YYYYMMDDT000000"), or null when there is no year to read. */
internal fun LibraryEntry.releaseYear(): String? =
    releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

/**
 * A view saved by name: the chips the PC library leads with are built-in
 * ones (Continue playing, Installed, per store), and the same shape is
 * what a person's own saved views are -- one mechanism for both.
 */
data class NamedLibraryView(val name: String, val query: LibraryQuery)

/**
 * Per-list persistence of saved views and of the query the list was left
 * showing: a filtered list stays filtered on the way back, the same
 * promise [dev.droidtop.shell.gamepad.GamelistFilterPrefs] already makes
 * a console gamelist.
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
                    val values = facetJson.optJSONArray(key) ?: continue
                    put(key, (0 until values.length()).mapNotNull { values.optString(it).ifBlank { null } }.toSet())
                }
            }
            LibraryQuery(
                text = json.optString("text"),
                facets = facets,
                sort = runCatching { LibrarySortKey.valueOf(json.optString("sort")) }.getOrDefault(LibrarySortKey.NAME),
            )
        }.getOrNull() ?: LibraryQuery()
    }
    /** Pure, for the JVM tests. */
    internal fun encodeViews(views: List<NamedLibraryView>): String {
        val json = JSONObject()
        views.forEach { view -> json.put(view.name, JSONObject(encodeQuery(view.query))) }
        return json.toString()
    }

    /** Pure, for the JVM tests. */
    internal fun decodeViews(raw: String?): List<NamedLibraryView> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().toList().mapNotNull { name ->
                val query = json.optJSONObject(name)?.toString()?.let { decodeQuery(it) } ?: return@mapNotNull null
                NamedLibraryView(name, query)
            }
        }.getOrDefault(emptyList())
    }
}
