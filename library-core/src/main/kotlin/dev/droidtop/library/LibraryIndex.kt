package dev.droidtop.library

import kotlinx.serialization.Serializable

/**
 * What a walk says it has finished (docs/SPEC.md 7g, "the index is
 * updated incrementally").
 *
 * A walk narrates its own progress instead of handing back a growing
 * list the reader has to diff: a provider that walks folder by folder
 * says WHICH folder it just finished, so [Library] can replace that
 * folder's entries and leave every other folder's alone. Inferring the
 * same thing from two successive snapshots cannot tell "this folder no
 * longer holds that game" from "this folder has not been walked yet",
 * which is the difference between marking a game missing and losing it.
 */
sealed interface ScanStep {

    /**
     * One part of the walk is finished: [entries] is everything that part
     * holds NOW, and it replaces whatever that part held before.
     *
     * [key] identifies the part within the provider -- a top-level
     * folder's absolute path for the folder walks, a console system's id
     * for the ROM walk. [root] is the games root it belongs to, or null
     * for a part that is not under a games root at all (a store's own
     * database), which is what makes removing a root a question the
     * index can answer without walking anything.
     */
    @Serializable
    data class Segment(
        val key: String,
        val root: String? = null,
        val entries: List<LibraryEntry> = emptyList(),
    ) : ScanStep

    /**
     * A root is finished, and [keys] is every part it has. A part the
     * index holds for this root that is not in [keys] is a folder that is
     * no longer there, so its games are missing -- the same state a game
     * inside a walked folder gets, because a folder that vanished is the
     * same event one level up. Nothing is dropped here either; only
     * removing the root itself drops (see [Library.keepOnlyRoots]).
     */
    data class RootDone(val root: String, val keys: List<String>) : ScanStep

    companion object {
        /**
         * The [Segment.key] of a provider that answers as a whole because
         * it has no parts to answer in: the package manager's app list,
         * a store's database.
         */
        const val WHOLE = "*"
    }
}

/**
 * One provider's place in the index: its parts, each holding what the
 * last walk of that part found, plus the games that walk no longer found
 * kept as [LibraryEntry.missing].
 *
 * Kept as parts rather than one flat list because that is what makes the
 * merge exact: "these entries replace that folder's" needs to know which
 * of the entries it already holds were that folder's.
 */
@Serializable
data class LibrarySlice(val segments: List<ScanStep.Segment> = emptyList()) {

    /**
     * Everything in this slice, in walk order, one entry per id.
     *
     * A game can be in two parts at once -- overlapping roots, or a game
     * that moved from one folder to another between two walks, which
     * leaves it missing in the folder it left and found in the one it is
     * in now. Found beats missing, because the game is plainly there.
     */
    fun entries(): List<LibraryEntry> {
        val byId = LinkedHashMap<String, LibraryEntry>()
        for (segment in segments) {
            for (entry in segment.entries) {
                val existing = byId[entry.id]
                if (existing == null || (existing.missing && !entry.missing)) byId[entry.id] = entry
            }
        }
        return byId.values.toList()
    }

    /** Whether this slice holds anything at all, walked or missing. */
    fun isEmpty(): Boolean = segments.all { it.entries.isEmpty() }

    /**
     * Applies one finished [step]: a part's entries replace that part's
     * previous ones, and a game that part no longer holds is kept,
     * marked missing.
     */
    fun merge(step: ScanStep): LibrarySlice = when (step) {
        is ScanStep.Segment -> mergeSegment(step)
        is ScanStep.RootDone -> markGoneFoldersMissing(step)
    }

    private fun mergeSegment(walked: ScanStep.Segment): LibrarySlice {
        val previous = segments.firstOrNull { it.key == walked.key && it.root == walked.root }
        val found = walked.entries.mapTo(HashSet()) { it.id }
        val stillMissing = previous?.entries.orEmpty().filterNot { it.id in found }.map { it.asMissing() }
        val merged = walked.copy(entries = walked.entries + stillMissing)
        return if (previous == null) {
            copy(segments = segments + merged)
        } else {
            copy(segments = segments.map { if (it.key == walked.key && it.root == walked.root) merged else it })
        }
    }

    private fun markGoneFoldersMissing(done: ScanStep.RootDone): LibrarySlice = copy(
        segments = segments.map { segment ->
            if (segment.root != done.root || segment.key in done.keys) {
                segment
            } else {
                segment.copy(entries = segment.entries.map { it.asMissing() })
            }
        },
    )

    /**
     * The slice without the roots the user has taken away. Removing a
     * games root removes its entries outright -- that is a choice, not a
     * drive that did not mount (docs/SPEC.md 7g). A part under no root
     * is untouched: a Steam library does not stop existing because a
     * folder was removed from the scan.
     */
    fun keepOnlyRoots(roots: Set<String>): LibrarySlice =
        copy(segments = segments.filter { it.root == null || it.root in roots })

    /** The slice without one entry -- what folding a missing game into its replacement leaves behind. */
    fun without(id: String): LibrarySlice =
        copy(segments = segments.map { segment -> segment.copy(entries = segment.entries.filterNot { it.id == id }) })

    private fun LibraryEntry.asMissing(): LibraryEntry = if (missing) this else copy(missing = true)
}

/**
 * The library index: each provider's last scan result, kept on disk and
 * shown at the next start instead of walking the games roots again
 * (docs/SPEC.md 7g, "the library is an index"). One slice per
 * [LibraryProvider.indexKey]; [Library] decides when a slice is shown,
 * when a walk refreshes part of it, and what a walk finding nothing in a
 * folder means (missing, never deleted).
 *
 * A plain interface for the same reason as [PlayHistoryStore]: [Library]
 * stays constructible in a JVM test with a fake, and the default
 * [NoOpLibraryIndexStore] keeps every existing call site walking as it
 * always did.
 */
interface LibraryIndexStore {
    /** The slice for [providerKey], or null when nothing was ever saved for it. */
    suspend fun load(providerKey: String): LibrarySlice?
    suspend fun save(providerKey: String, slice: LibrarySlice)
}

object NoOpLibraryIndexStore : LibraryIndexStore {
    override suspend fun load(providerKey: String): LibrarySlice? = null
    override suspend fun save(providerKey: String, slice: LibrarySlice) {}
}
