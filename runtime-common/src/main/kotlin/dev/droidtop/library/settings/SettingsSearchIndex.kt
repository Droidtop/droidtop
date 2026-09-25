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
 * Built ONCE per search session ([build], a suspend IO-bound call -- the
 * same real cost as opening every top-level settings screen a single
 * time) and then filtered per keystroke with a plain substring match
 * ([search], pure and in-memory) -- never rebuilt while someone types,
 * satisfying "no file/database work ... in list rendering".
 *
 * Deliberately shallow: it walks the root's own groups (depth 0) and, for
 * a [NestedScreenItem] found there, ONE level into whatever [CatalogScreen]
 * it opens (depth 1) -- never further. Every depth-0 and depth-1 screen is
 * either the root itself or reached through [SettingsScreenRegistry], the
 * small, developer-declared, FIXED set of top-level screens; a screen a
 * catalog builds for one instance (a single ROM folder, one platform, one
 * container) is never indexed, so this stays flat against the size of
 * anyone's library or platform list instead of growing with it.
 */
object SettingsSearchIndex {
    suspend fun build(context: Context, root: CatalogScreen): List<SettingsSearchResult> {
        val results = mutableListOf<SettingsSearchResult>()

        suspend fun indexScreen(screen: CatalogScreen, depth: Int) {
            for (group in screen.groups(context)) {
                // Live device state, not configuration (docs/SPEC.md 7f) --
                // the Quick Menu draws these, Settings does not, and
                // neither does its search.
                if (group.quickOnly) continue
                for (item in group.items) {
                    results += SettingsSearchResult(
                        target = screen,
                        screenTitle = screen.title,
                        itemId = item.id,
                        itemTitle = item.title,
                        itemSubtitle = item.subtitle,
                        icon = item.icon,
                    )
                    if (depth == 0 && item is NestedScreenItem) {
                        item.resolve()?.let { indexScreen(it, depth + 1) }
                    }
                }
            }
        }
        indexScreen(root, 0)
        return results
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
