package dev.droidtop.library.settings

import android.content.Context

/** One matchable row: what it says, and the screen it lives on. */
data class SettingsSearchResult(
    /** Navigate here when the result is picked -- the screen the row lives on, not the row itself. */
    val target: CatalogScreen,
    val screenTitle: String,
    val itemId: String,
    val itemTitle: String,
    val itemSubtitle: String?,
    val icon: CatalogIcon?,
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
 * for 10-40 s on a real device (Droidtop/tracker#136)) and then filtered
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
    suspend fun build(context: Context, root: CatalogScreen): List<SettingsSearchResult> {
        val results = mutableListOf<SettingsSearchResult>()

        suspend fun indexScreen(screen: CatalogScreen, depth: Int) {
            for (group in screen.indexGroups?.invoke(context) ?: screen.groups(context)) {
                // Live device state, not configuration (docs/SPEC.md 7f) --
                // the Quick Menu draws these, Settings does not, and
                // neither does its search.
                if (group.quickOnly) continue
                for (item in group.items) {
                    if (item is TextBlockItem) continue
                    results += SettingsSearchResult(
                        target = screen,
                        screenTitle = screen.title,
                        itemId = item.id,
                        itemTitle = item.title,
                        itemSubtitle = item.subtitle,
                        icon = item.icon,
                    )
                    if (depth <= 1 && item is NestedScreenItem) {
                        item.resolve()?.let { indexScreen(it, depth + 1) }
                    }
                }
            }
        }
        indexScreen(root, 0)
        // A screen reachable along two paths (the Plugins row sits under
        // Accounts and sources, which more than one parent opens) would be
        // indexed twice; the results list keys rows by item id, so a
        // duplicate id crashed it. The first path found wins.
        return results.distinctBy { it.itemId }
    }

    /** Pure, in-memory: safe to call on every keystroke. */
    fun search(index: List<SettingsSearchResult>, query: String): List<SettingsSearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return index.filter { result ->
            result.itemTitle.contains(q, ignoreCase = true) ||
                result.itemSubtitle?.contains(q, ignoreCase = true) == true ||
                result.screenTitle.contains(q, ignoreCase = true)
        }.take(40)
    }
}
