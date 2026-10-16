package dev.droidtop.library.integrations

/**
 * A JSON `acquire_content` integration's one row in the one search (docs/SPEC.md 13, "There is no Get games sheet";
 * coordinator decision 2026-10-16 under the owner's Get games direction, Droidtop/tracker#218): it cannot answer a
 * search (its template fires an `am start` and never hears back), so it hands the query to its app instead. Pure.
 */
object IntegrationHandoff {
    private val SYSTEM_PLACEHOLDERS = setOf(IntegrationPlaceholders.SYSTEM_ID, IntegrationPlaceholders.SYSTEM_NAME, IntegrationPlaceholders.SYSTEM_FOLDER)

    /**
     * Whether a search covers [template]: one that names the system ({system.id}, {system.name}, {system.folder})
     * needs a search scoped to one console system ([scopedToSystem]), the only place those have values; one that
     * does not is offered in every search.
     */
    fun covers(template: String, scopedToSystem: Boolean): Boolean =
        scopedToSystem || IntegrationPlaceholders.usedIn(template).none { it in SYSTEM_PLACEHOLDERS }

    /**
     * The row's title and detail: "Search <app>" and `for "<query>"` when the template takes the query, else
     * "Open <app>". The query is the detail and never the title, as the web row's is, so the title never matches it and
     * the row ranks after every source's (SearchRank OTHER, then [SearchRowKind.WEB]).
     */
    fun text(label: String, template: String, query: String): Pair<String, String?> =
        if (IntegrationPlaceholders.QUERY in IntegrationPlaceholders.usedIn(template)) {
            "Search $label" to "for \u201c${query.trim()}\u201d"
        } else {
            "Open $label" to null
        }
}
