package dev.droidtop.library

/**
 * Which other games in the library might be the same game as this one
 * (docs/SPEC.md 7m, "The same game").
 *
 * Grouping merges folders whose derived names are EQUAL; a folder whose
 * name drifted (`MyGame` beside `My_Game_Remastered`, a release renamed
 * mid-series) lands as a second card, and only a person can say the two
 * are one. Pythia found exactly this in its own library and answers it
 * with `reconciliation.find_merge_candidates`: pairs whose names are at
 * least [GameNaming.NAME_SIMILARITY_THRESHOLD] alike, most alike first,
 * for the person to confirm or ignore -- never merged on the score alone.
 *
 * One departure, for cost: Pythia compares every pair of its games,
 * which grows with the square of the library and which docs/SPEC.md 7m
 * already declines. This asks for ONE game, from that game's own screen,
 * and compares it with each other game once.
 *
 * Nothing here touches the filesystem, so it is answerable over a list of
 * groups in a test.
 */
object SimilarGames {

    /** A game that might be the same as the one asked about, and how alike their names are. */
    data class Candidate(val group: LibraryGameGroup, val score: Double)

    /**
     * The games among [among] whose names are at least
     * [GameNaming.NAME_SIMILARITY_THRESHOLD] alike [target]'s, most alike
     * first. Only games that are folders on this device are offered, on
     * either side (a store row's name is the store's), and only games with
     * at least one folder still here: a game that is only missing is the
     * missing-game fold's question (7g), not this one.
     */
    fun candidates(target: LibraryGameGroup, among: List<LibraryGameGroup>): List<Candidate> {
        if (!target.isFolders()) return emptyList()
        val name = target.game.name.lowercase()
        return among
            .filter { it !== target && it.entriesByPath.keys.none { id -> id in target.entriesByPath } }
            .filter { it.isFolders() && it.entriesByPath.values.any { entry -> !entry.missing } }
            .map { Candidate(it, GameNaming.similarity(name, it.game.name.lowercase())) }
            .filter { it.score >= GameNaming.NAME_SIMILARITY_THRESHOLD }
            // Ties by name, so two equally alike games are in the same
            // order every time the list is shown.
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.group.game.name.lowercase() })
    }

    private fun LibraryGameGroup.isFolders(): Boolean =
        entriesByPath.isNotEmpty() && entriesByPath.keys.all { it.startsWith("/") }
}
