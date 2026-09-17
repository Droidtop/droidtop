package dev.droidtop.library

/**
 * Which game is the replacement for which (docs/SPEC.md 7g).
 *
 * A game the walk no longer finds stays in the library as
 * [LibraryEntry.missing]; a folder that has appeared since is very often
 * the same game at a new version -- `Game v0.3` deleted, `Game v0.4`
 * unpacked beside it -- and the user says so from either side. What this
 * answers is which candidates to put first, and it is Pythia's answer:
 * `find_candidates` returns a CERTAIN match and then SUGGESTED ones by
 * descending similarity, never merging on similarity by itself, because
 * on the user's own library `love_of_magic_book1` and `book2` score 0.94
 * against each other and are two different games (see
 * [GameNaming.NAME_SIMILARITY_THRESHOLD]).
 *
 * Certain here is name equality after [GameNaming.derive] has taken the
 * version, mods and language off the folder name -- Pythia's own
 * `_merge_version` key, which is what makes two builds of one game one
 * game. Pythia's stronger certainties (the same path, a sync marker, a
 * platform id) cannot apply: the whole premise is that the path is gone.
 *
 * Nothing here touches the filesystem, so it is answerable over a list of
 * entries in a test.
 */
object MissingGames {

    /** A candidate and why it is one, so a picker can say which is which. */
    data class Candidate(
        val entry: LibraryEntry,
        /** The name [GameNaming] derived, which is what was compared. */
        val name: String,
        /** 1.0 for the same derived name, else the similarity that put it on the list. */
        val score: Double,
        /** Whether this is the same game by name, rather than a suggestion. */
        val certain: Boolean,
    )

    /**
     * [among] ordered as replacements for [target]: same derived name
     * first, then names at least [GameNaming.NAME_SIMILARITY_THRESHOLD]
     * alike, most alike first. Anything below the threshold is not
     * offered at all -- a list of every game in the library sorted by
     * nothing is not a shortlist.
     *
     * [target] itself, and any entry with the same id, is never a
     * candidate for itself.
     */
    fun candidates(target: LibraryEntry, among: List<LibraryEntry>): List<Candidate> {
        val targetName = nameOf(target)
        val targetKey = GameNaming.nameKey(targetName)
        val scored = among
            .filter { it.id != target.id }
            .map { entry ->
                val name = nameOf(entry)
                val certain = targetKey.isNotEmpty() && GameNaming.nameKey(name) == targetKey
                Candidate(
                    entry = entry,
                    name = name,
                    score = if (certain) 1.0 else GameNaming.similarity(targetName.lowercase(), name.lowercase()),
                    certain = certain,
                )
            }
            .filter { it.certain || it.score >= GameNaming.NAME_SIMILARITY_THRESHOLD }
        // Ties are broken by name so two equally-scored candidates are in
        // the same order on every screen that lists them.
        return scored.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.name.lowercase() })
    }

    /**
     * The name to compare an entry by: what its folder name says, for a
     * game that is a folder, and the title the source gave for a store
     * row, whose name nobody has to derive.
     */
    fun nameOf(entry: LibraryEntry): String =
        if (entry.id.startsWith("/")) GameNaming.derive(entry.id).name else entry.title
}
