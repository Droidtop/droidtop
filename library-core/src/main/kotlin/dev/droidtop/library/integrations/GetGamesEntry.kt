package dev.droidtop.library.integrations

/**
 * The "Get games" entry (docs/SPEC.md 13, "There is no Get games sheet"; owner, 2026-10-16, Droidtop/tracker#218):
 * every place that offers it opens the one search, empty and ready for typing, scoped as that place is, and the
 * download sources answer there. This holds the label and the pure rules the entries and the search share, so a new
 * surface adds an entry that uses them rather than a rule of its own. Pure: no Android, no I/O.
 */
object GetGamesEntry {
    const val LABEL = "Get games"

    /** The search's heading over matches from the download sources, and Updates' row for them (Droidtop/tracker#612, #621). */
    const val NEW_GAMES = "Get new games"

    /**
     * Downloads and Updates' one Get games row (owner, 2026-10-30, Droidtop/tracker#825: "We want a single get games
     * section"): Gaming opens the search in place; elsewhere it opens over the screen in front.
     */
    const val SEARCH_ROW_ID = "get_games_search"

    /** Updates' Get new games row: Gaming opens the search in place; elsewhere it opens Gaming on it. */
    const val UPDATES_ROW_ID = "updates_get_new_games"

    /** The PC Games list's pseudo system id: it has no system folder to download into, so it is never a destination. */
    const val PC_SYSTEM_ID = "pc"

    /**
     * The console system a Get games entry searches and downloads for: [systemId] when it is a real console system,
     * null for everything else (All games, a collection, Apps, PC Games, which searches as PC instead).
     */
    fun searchSystem(systemId: String?): String? = systemId?.trim()?.takeIf { it.isNotEmpty() && it != PC_SYSTEM_ID }

    /**
     * The one line a search shows when no source could be asked at all ([asked] none, [notRunnable] none), or null
     * when there is something to say per source instead. [scoped] is a search for one system, where sources may be
     * installed and serve none of it. It opens Plugins.
     */
    fun noSourceLine(asked: Int, notRunnable: Int, scoped: Boolean): String? = when {
        asked > 0 || notRunnable > 0 -> null
        scoped -> "No download source gets games for this system"
        else -> "No download source is installed"
    }
}
