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

    /**
     * A game that might be the same as the one asked about, and how alike their names are. [store] is the store key
     * ("steam:440") when it is a store's row: picking it links the folders to it ([Library.linkToStore]) rather than
     * folding two games' folders together ([Library.mergeGames]).
     */
    data class Candidate(val group: LibraryGameGroup, val score: Double, val store: String? = null)

    /**
     * The games among [among] whose names are at least
     * [GameNaming.NAME_SIMILARITY_THRESHOLD] alike [target]'s, most alike
     * first. [target] is a game that is folders on this device. Offered are
     * other such games with at least one folder still here (a game that is
     * only missing is the missing-game fold's question, 7g), and games that
     * are only a store's rows (Droidtop/tracker#745: the same game owned on
     * Steam and kept as a folder), an equal name by [GameNaming.nameKey]
     * counting as fully alike.
     */
    fun candidates(target: LibraryGameGroup, among: List<LibraryGameGroup>): List<Candidate> {
        if (!target.isFolders()) return emptyList()
        val name = target.game.name.lowercase()
        val key = GameNaming.nameKey(target.game.name)
        return among
            .filter { it !== target && it.entriesByPath.keys.none { id -> id in target.entriesByPath } }
            .mapNotNull { other ->
                val store = other.storeKey()
                when {
                    store != null -> Candidate(
                        other,
                        if (GameNaming.nameKey(other.game.name) == key) 1.0 else GameNaming.similarity(name, other.game.name.lowercase()),
                        store,
                    )
                    other.isFolders() && other.entriesByPath.values.any { entry -> !entry.missing } ->
                        Candidate(other, GameNaming.similarity(name, other.game.name.lowercase()))
                    else -> null
                }
            }
            .filter { it.score >= GameNaming.NAME_SIMILARITY_THRESHOLD }
            // Ties by name, so two equally alike games are in the same
            // order every time the list is shown.
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.group.game.name.lowercase() })
    }

    private fun LibraryGameGroup.isFolders(): Boolean =
        entriesByPath.isNotEmpty() && entriesByPath.values.all { it.groupingPath() != null }

    /** The store key of a game that is only a store's rows (no folder of it here), else null. */
    private fun LibraryGameGroup.storeKey(): String? {
        if (entriesByPath.isEmpty() || entriesByPath.values.any { it.groupingPath() != null }) return null
        return entriesByPath.values.firstNotNullOfOrNull { it.ownership() }?.let { "${it.store}:${it.id}" }
    }

    /**
     * Whether [game] is folders linked to a store's row by the person or a scrape ([LibraryEntry.sameAs],
     * [LibraryEntry.storeIds]), which [Library.unlinkFromStore] can undo; a store's marker in the folder is not.
     */
    fun linkedToStore(game: LibraryGameGroup): Boolean {
        val entries = game.entriesByPath.values
        val folders = entries.filter { it.groupingPath() != null }
        return folders.isNotEmpty() && folders.none { it.pcInfo?.marker != null } &&
            entries.any { it.groupingPath() == null && it.ownership() != null }
    }
}
