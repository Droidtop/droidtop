package dev.droidtop.shell.gamepad.query

/**
 * The ways into the one search (docs/SPEC.md 12a, "One search: where it opens"; Droidtop/tracker#485 F7c, the
 * owner's "we also want per-console search items", 2026-10-14). There is one search runner (UnifiedSearch,
 * drawn by [LibrarySearchDialog]); what changes is its scope:
 *
 * - **Everything**: Start > Search, Y on Home, the band's search. The device's games and apps, the sources,
 *   and the system names themselves ("snes" finds the system).
 * - **One system**: "Search SNES" in a system's gamelist Select menu (and, later, Home's system tiles and the
 *   running console game's L2 sheet): that system's library and the sources that serve it; a download goes to that
 *   system's folder (SourceDestinations).
 * - **One page**: a page's own field (the plugin Store, Installed) filters that page's list in memory.
 */
internal object SearchEntry {
    /** The row that opens a system's search: "Search SNES"; with text already typed, "Search SNES: zelda". */
    fun systemLabel(systemName: String, text: String = ""): String =
        if (text.isBlank()) "Search $systemName" else "Search $systemName: ${text.trim()}"

    /**
     * The systems whose name or id the text matches, for the global search's system rows: a whole-word or prefix
     * match, case-insensitive ("snes" finds "SNES", "super nin" finds "Super Nintendo"). [systems] is id to name.
     */
    fun matchingSystems(text: String, systems: Map<String, String>): List<Pair<String, String>> {
        val q = text.trim().lowercase()
        if (q.length < 2) return emptyList()
        return systems.entries.filter { (id, name) ->
            id.lowercase() == q || name.lowercase().startsWith(q) || name.lowercase().split(' ').any { it.startsWith(q) }
        }.sortedBy { it.value.lowercase() }.map { it.key to it.value }
    }
}
