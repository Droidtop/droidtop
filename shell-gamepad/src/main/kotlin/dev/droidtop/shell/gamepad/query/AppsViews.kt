package dev.droidtop.shell.gamepad.query

import dev.droidtop.library.AppCategory
import dev.droidtop.library.LibraryEntry

internal const val APPS_VIEW_ALL = "All"
internal const val APPS_VIEW_GAMES = "Games"
internal const val APPS_VIEW_EMULATORS = "Emulators"
internal const val APPS_VIEW_TOOLS = "Tools"
internal const val APPS_VIEW_RECENT = "Recently used"

/**
 * The views the Apps strip leads with (docs/SPEC.md 7j): All, Games,
 * Emulators, Tools (every app that is neither a game nor an emulator) and
 * Recently used. Each is a plain [LibraryQuery] over the Category and
 * Recently used facets, the same shape as a person's saved view, so the
 * strip, the Filter sheet and the saved views are one mechanism.
 */
internal val appsBuiltInViews: List<NamedLibraryView> = listOf(
    NamedLibraryView(APPS_VIEW_ALL, LibraryQuery()),
    NamedLibraryView(APPS_VIEW_GAMES, categoryView(AppCategory.GAMES)),
    NamedLibraryView(APPS_VIEW_EMULATORS, categoryView(AppCategory.EMULATORS)),
    NamedLibraryView(APPS_VIEW_TOOLS, categoryView(AppCategory.SYSTEM, AppCategory.OTHER)),
    NamedLibraryView(APPS_VIEW_RECENT, LibraryQuery(facets = mapOf(LibraryFacet.RECENTLY_USED.key to setOf(USED_YES)))),
)

private fun categoryView(vararg categories: AppCategory) =
    LibraryQuery(facets = mapOf(LibraryFacet.CATEGORY.key to categories.mapTo(HashSet()) { it.label }))

/** The strip's views: the built-in ones, then the person's saved views (a saved name that is a built-in one is ignored). */
internal fun appsStripViews(saved: List<NamedLibraryView>): List<NamedLibraryView> {
    val names = appsBuiltInViews.mapTo(HashSet()) { it.name }
    return appsBuiltInViews + saved.filter { it.name !in names }
}

/** How many apps each view holds, by view name: one pass of the view's own filter, no sort. Worked out off the main thread. */
internal fun appsViewCounts(
    entries: List<LibraryEntry>,
    scope: LibraryQueryScope,
    views: List<NamedLibraryView>,
): Map<String, Int> = views.associate { view -> view.name to entries.count { view.query.matches(it, scope) } }

/** A chip's label: the view's name and its count ("Games · 12"). */
internal fun appsStripLabel(view: NamedLibraryView, counts: Map<String, Int>): String =
    counts[view.name]?.let { "${view.name} · $it" } ?: view.name

/**
 * The view the list shows right now, as an index into [views], or -1 when
 * the query's search and filters match none of them (the strip's pill then
 * names them). The sort is not part of a view's identity: changing it keeps
 * the same view lit.
 */
internal fun appsActiveView(views: List<NamedLibraryView>, query: LibraryQuery): Int =
    views.indexOfFirst { it.query.text == query.text && it.query.facets == query.facets }

/**
 * The query that picking [view] gives: a built-in view keeps the sort the
 * person chose (it is only a filter), a saved view brings its own.
 */
internal fun appsViewQuery(view: NamedLibraryView, current: LibraryQuery): LibraryQuery =
    if (appsBuiltInViews.any { it.name == view.name }) current.copy(text = view.query.text, facets = view.query.facets) else view.query
