package dev.droidtop.library.integrations

/**
 * Where a person can ask for "Get games" (docs/SPEC.md 12a "Get games
 * everywhere"). Every one of these opens the same registered screen
 * ([AcquireContentSources.GET_GAMES_SCREEN_ID]); the context only decides
 * whether the screen already knows which system to download for.
 */
enum class GetGamesContext {
    /** The All games list (no group open). */
    LIBRARY,

    /** One console system's options menu. */
    SYSTEM,

    /** A collection or other list that spans systems. */
    COLLECTION,

    /** The PC Games list, which is not a console system. */
    PC,

    /** The Apps section. */
    APPS,

    /** A game's own page or menu. */
    GAME_PAGE,

    /** A list with nothing in it. */
    EMPTY_STATE,

    /** The search list. */
    SEARCH,
}

/**
 * The ONE description of the "Get games" entry every menu, page, empty
 * state and the search dialog offers, so a new surface adds a row that
 * calls this rather than a branch of its own. Pure: no Android, no I/O.
 *
 * A surface shows [LABEL] and, when pressed, opens
 * `SettingsScreenRegistry.get(GET_GAMES_SCREEN_ID, systemFor(...))`: with a
 * system id that is that system's source list; with none it asks which
 * system to download for; with no source installed either one says so and
 * leads to Plugins and App integrations.
 */
object GetGamesEntry {
    const val LABEL = "Get games"

    /** The PC Games list's pseudo system id: it has no system folder to download into, so it is never a destination. */
    const val PC_SYSTEM_ID = "pc"

    /**
     * The console system to open the source list for, or null to ask which
     * system first. Only a context that is about one system ([GetGamesContext.SYSTEM],
     * [GetGamesContext.GAME_PAGE], [GetGamesContext.EMPTY_STATE], [GetGamesContext.SEARCH])
     * and has a real console system id answers with it; everything else (All games, a collection,
     * PC Games, Apps, the Quick Menu) asks.
     */
    fun systemFor(context: GetGamesContext, systemId: String?): String? = when (context) {
        GetGamesContext.SYSTEM,
        GetGamesContext.GAME_PAGE,
        GetGamesContext.EMPTY_STATE,
        GetGamesContext.SEARCH,
        -> systemId?.trim()?.takeIf { it.isNotEmpty() && it != PC_SYSTEM_ID }
        GetGamesContext.LIBRARY,
        GetGamesContext.COLLECTION,
        GetGamesContext.PC,
        GetGamesContext.APPS,
        -> null
    }

    /** The value under [LABEL] in the search list, by what the search found out about the sources. */
    fun searchSubtitle(state: GetMoreState): String = when (state) {
        GetMoreState.NO_SOURCE -> "No source installed"
        GetMoreState.NOT_READY -> "A source needs approval"
        GetMoreState.FAILED -> "A source failed"
        GetMoreState.NO_MATCH, GetMoreState.FOUND -> "Sources"
    }
}

/**
 * What a search learned from its sources, in the one order a person needs
 * to hear it: nothing installed, installed but not runnable, a source
 * failed, sources answered with nothing, sources found something.
 */
enum class GetMoreState {
    NO_SOURCE,
    NOT_READY,
    FAILED,
    NO_MATCH,
    FOUND;

    companion object {
        /**
         * [outcomes] are the runnable sources' answers, [notRunnable] the
         * installed ones that cannot answer (waiting for approval, turned
         * off, disabled). A failure outranks "no match" and results outrank
         * a failure elsewhere, so the group heading never hides a hit.
         */
        fun of(outcomes: List<SourceOutcome>, notRunnable: List<UnavailableSource>): GetMoreState = when {
            outcomes.isEmpty() && notRunnable.isEmpty() -> NO_SOURCE
            outcomes.isEmpty() -> NOT_READY
            outcomes.any { it.results.isNotEmpty() } -> FOUND
            outcomes.any { it.failure != null } -> FAILED
            else -> NO_MATCH
        }
    }
}
