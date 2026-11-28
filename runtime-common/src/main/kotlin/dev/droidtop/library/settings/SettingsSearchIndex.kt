package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.async

/** One matchable row: what it says, and the screen it lives on. */
data class SettingsSearchResult(
    /** Navigate here when the result is picked -- the screen the row lives on, not the row itself. */
    val target: CatalogScreen,
    val screenTitle: String,
    val itemId: String,
    val itemTitle: String,
    val itemSubtitle: String?,
    val icon: CatalogIcon?,
    /**
     * How the root reaches [target]: each row that opened a screen, with that screen, from the root's own row down to
     * [target]; empty for a row of the root itself. A category screen picks its column entry from the first step
     * (a plugin's page found by search opened under Modes, the category on show, mytop-project/tracker#954 item 15).
     */
    val trail: List<Pair<String, CatalogScreen>> = emptyList(),
    val aliases: List<String> = emptyList(),
)

/**
 * A flat, in-memory search over the settings tree reachable from one root
 * screen (docs/SPEC.md settings architecture, "search across settings").
 *
 * Built ONCE per search session ([build], a suspend IO-bound call --
 * roughly the cost of opening every settings screen a single time,
 * EXCEPT screens that declare [CatalogScreen.indexGroups]: their live
 * [CatalogScreen.groups] is skipped for the cheap rows, because some of
 * them cost library-sized work -- Console systems walks and game-counts
 * every folder, which held the first search on "Indexing settings..."
 * for 10-40 s on a real device (mytop-project/tracker#136)) and then filtered
 * per keystroke with a plain substring match
 * ([search], pure and in-memory) -- never rebuilt while someone types,
 * satisfying "no file/database work ... in list rendering".
 *
 * Deliberately shallow: it walks the root's own groups (depth 0) and, for
 * a [NestedScreenItem] found there, TWO levels into whatever [CatalogScreen]
 * it opens (depths 1 and 2) -- never further. Depth 2 exists for Settings >
 * Accounts and sources: its own rows (Steam, ScreenScraper, SteamGridDB...)
 * are themselves depth-1 [NestedScreenItem]s opening a small screen of just
 * that account's fields, and those fields must stay searchable the same way
 * they were when they sat directly on the old per-provider screens. Every
 * depth-0/1/2 screen is either the root itself, reached through
 * [SettingsScreenRegistry], or one of these small inline account screens --
 * all fixed and developer-declared; a screen a catalog builds for one
 * instance (a single ROM folder, one platform, one container) is never
 * indexed, so this stays flat against the size of anyone's library or
 * platform list instead of growing with it.
 */
object SettingsSearchIndex {
    private const val LOG_TAG = "droidtop.SettingsIndex"
    private const val SLOW_SCREEN_MS = 250L

    suspend fun build(context: Context, root: CatalogScreen): List<SettingsSearchResult> {
        val started = android.os.SystemClock.elapsedRealtime()

        // A screen's rows, then each of its pages' rows in the order the rows name them. The pages a screen opens are
        // read side by side: one after another, the index took the sum of every page's time (12.9 s on BlueStacks,
        // 7.3 s of it Game sources and 3.0 s Containers), where side by side it takes about the slowest one's
        // (mytop-project/tracker#954 item 14). The order of the results, and so which of two paths to one row wins, is
        // the same as before.
        suspend fun indexScreen(screen: CatalogScreen, depth: Int, trail: List<Pair<String, CatalogScreen>>): List<SettingsSearchResult> {
            // Which screen holds the index up: any one that takes a quarter second is named in the log with its time.
            val opened = android.os.SystemClock.elapsedRealtime()
            val groups = screen.indexGroups?.invoke(context) ?: screen.groups(context)
            val took = android.os.SystemClock.elapsedRealtime() - opened
            if (took >= SLOW_SCREEN_MS) android.util.Log.i(LOG_TAG, "${screen.id} (${screen.title}): $took ms")
            // Each row, and the page it opens when that page is indexed too, in order.
            val rows = mutableListOf<Pair<SettingsSearchResult, CatalogScreen?>>()
            for (group in groups) {
                // Live device state, not configuration (docs/SPEC.md 7f) --
                // the Quick Menu draws these, Settings does not, and
                // neither does its search.
                if (group.quickOnly) continue
                // Advanced rows are found by name like any other (the renderer opens them when it goes to one).
                for (item in group.allItems) {
                    // A page's header names the page itself, and its bar's buttons are actions, not settings.
                    if (item is TextBlockItem || item is PageHeaderItem || item is ButtonBarItem) continue
                    val result = SettingsSearchResult(
                        target = screen,
                        screenTitle = screen.title,
                        itemId = item.id,
                        itemTitle = item.title,
                        itemSubtitle = item.subtitle,
                        icon = item.icon,
                        trail = trail,
                        aliases = (item as? ActionItem)?.searchAliases.orEmpty(),
                    )
                    val page = if ((depth <= 1 || screen.indexesItsPages) && item is NestedScreenItem) item.resolve() else null
                    rows += result to page
                }
            }
            val pages = kotlinx.coroutines.coroutineScope {
                rows.map { (row, page) ->
                    page?.let { async(kotlinx.coroutines.Dispatchers.IO) { indexScreen(it, depth + 1, trail + (row.itemId to it)) } }
                }.map { it?.await().orEmpty() }
            }
            return rows.flatMapIndexed { index, (result, _) -> listOf(result) + pages[index] }
        }
        val results = indexScreen(root, 0, emptyList())
        android.util.Log.i(LOG_TAG, "index built: ${results.size} rows in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        // A screen reachable along two paths (the Plugins row sits under
        // Accounts and sources, which more than one parent opens) would be
        // indexed twice; the results list keys rows by item id, so a
        // duplicate id crashed it. The first path found wins.
        return results.distinctBy { it.itemId }
    }

    /**
     * Pure, in-memory: safe to call on every keystroke. Ranked as Android Settings ranks
     * (mytop-project/tracker#505): a title that starts with the text, then a title with a word that does,
     * then the text inside a title, then a named alias, then the screen's own name, and last a row whose description has
     * the text as a whole word (a description is prose, so "them" inside "...share them with..." is a
     * weak hit, and a part of a longer word is no hit). Rows of one rank keep the tree's order.
     */
    fun search(index: List<SettingsSearchResult>, query: String): List<SettingsSearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return index.mapNotNull { result -> rank(result, q)?.let { it to result } }
            .sortedBy { it.first }
            .map { it.second }
            .take(40)
    }

    private fun rank(result: SettingsSearchResult, q: String): Int? {
        val title = result.itemTitle
        return when {
            title.startsWith(q, ignoreCase = true) -> 0
            startsAWord(title, q) -> 1
            title.contains(q, ignoreCase = true) -> 2
            result.aliases.any { startsAWord(it, q) } -> 3
            result.screenTitle.contains(q, ignoreCase = true) -> 4
            result.itemSubtitle?.let { hasWholeWord(it, q) } == true -> 5
            else -> null
        }
    }

    private fun startsAWord(text: String, q: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) return false
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            from = at + 1
        }
    }

    private fun hasWholeWord(text: String, q: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) return false
            val before = at == 0 || !text[at - 1].isLetterOrDigit()
            val end = at + q.length
            val after = end >= text.length || !text[end].isLetterOrDigit()
            if (before && after) return true
            from = at + 1
        }
    }
}
