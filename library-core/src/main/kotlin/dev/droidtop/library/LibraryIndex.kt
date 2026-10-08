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
     *
     * [folderMtime] is the part's change stamp as the provider took it
     * when it walked the part, which the index keeps for the slow pass to
     * compare against (docs/SPEC.md 7g, step 4). Null leaves it to the
     * index, which reads [key]'s own folder modification time -- right
     * for a part that is one folder. A part that is several folders (a
     * console system) stamps itself, since no single folder's time
     * speaks for it.
     *
     * [complete] says the part's own source answered in full, so a game it
     * no longer holds is gone rather than missing: a store's rows are what
     * the store says the account owns, not files on a drive that may not be
     * mounted (docs/SPEC.md 7g). A folder part never sets it. It is a
     * property of the step and is not kept in the slice.
     */
    @Serializable
    data class Segment(
        val key: String,
        val root: String? = null,
        val entries: List<LibraryEntry> = emptyList(),
        val folderMtime: Long? = null,
        val complete: Boolean = false,
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
 * One part of a provider's slice as the index names it: a
 * [ScanStep.Segment.key] under its [ScanStep.Segment.root]. The key alone
 * is not enough: a console system's key is its id, which every games
 * root that holds that system shares.
 */
data class PartRef(val key: String, val root: String?)

/** The games root a step belongs to, or null for one under no root. */
val ScanStep.root: String?
    get() = when (this) {
        is ScanStep.Segment -> root
        is ScanStep.RootDone -> root
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
        // A complete answer drops what it no longer lists, and with it the
        // missing rows earlier answers left.
        val stillMissing = if (walked.complete) emptyList() else previous?.entries.orEmpty().filterNot { it.id in found }.map { it.asMissing() }
        val merged = walked.copy(entries = walked.entries + stillMissing, complete = false)
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

    /**
     * This slice plus every game [shown] holds that this one does not, in
     * the part [shown] has it in. What a rebuild of the index from the
     * per-game records ends on: a game on screen whose record could not be
     * read (never written, an older shape, a corrupt file) is kept as it is
     * shown rather than dropped, because a rebuild walks no folder and so
     * cannot know the game is gone -- the next walk decides that, as it
     * decides it for every other game (docs/SPEC.md 7g).
     */
    fun including(shown: LibrarySlice): LibrarySlice {
        val have = entries().mapTo(HashSet()) { it.id }
        val merged = segments.toMutableList()
        for (segment in shown.segments) {
            val extra = segment.entries.filter { have.add(it.id) }
            if (extra.isEmpty()) continue
            val at = merged.indexOfFirst { it.key == segment.key && it.root == segment.root }
            if (at >= 0) {
                merged[at] = merged[at].copy(entries = merged[at].entries + extra)
            } else {
                merged += segment.copy(entries = extra)
            }
        }
        return LibrarySlice(merged)
    }

    /** The slice without one entry -- what folding a missing game into its replacement leaves behind. */
    fun without(id: String): LibrarySlice =
        copy(segments = segments.map { segment -> segment.copy(entries = segment.entries.filterNot { it.id == id }) })

    /**
     * Applies what one provider says about one reported path
     * (docs/SPEC.md 7g, "Targeted indexing"). Unlike [merge], the part is
     * not replaced: only the entries that were under [PathIndexing.under]
     * are, by [PathIndexing.entries], and every other entry of the part is
     * left exactly as it was. A game that was under the path and is not in
     * the answer is kept, marked missing, as a walk would keep it. A part
     * that does not exist yet is created, with [folderMtime] as its change
     * stamp (0, unknown, when the index has none, so the slow pass looks at
     * it once). An answer with no [PathIndexing.under] is a whole part, and
     * is merged like a walk's step.
     */
    fun mergePath(indexing: PathIndexing, folderMtime: Long): LibrarySlice {
        val under = indexing.under
            ?: return merge(ScanStep.Segment(indexing.key, indexing.root, indexing.entries, indexing.folderMtime ?: folderMtime, indexing.complete))
        val previous = segments.firstOrNull { it.key == indexing.key && it.root == indexing.root }
        val found = indexing.entries.mapTo(HashSet()) { it.id }
        val kept = previous?.entries.orEmpty().filter { it.id !in found }
        val merged = ScanStep.Segment(
            key = indexing.key,
            root = indexing.root,
            entries = indexing.entries + kept.map { if (it.isUnder(under)) it.asMissing() else it },
            folderMtime = indexing.folderMtime ?: previous?.folderMtime ?: folderMtime,
        )
        return if (previous == null) {
            copy(segments = segments + merged)
        } else {
            copy(segments = segments.map { if (it.key == indexing.key && it.root == indexing.root) merged else it })
        }
    }

    /**
     * The ids of the entries that are on disk at or under [path], in any part.
     * A store row that is not installed is not on disk, even where its
     * install path says it would go.
     */
    fun idsUnder(path: String): List<String> =
        segments.flatMap { segment -> segment.entries.filter { it.isOnDiskUnder(path) }.map { it.id } }.distinct()

    /** The slice without the entries [idsUnder] names: what a file or folder that was deleted leaves behind. */
    fun withoutUnder(path: String): LibrarySlice =
        copy(segments = segments.map { segment -> segment.copy(entries = segment.entries.filterNot { it.isOnDiskUnder(path) }) })

    private fun LibraryEntry.isOnDiskUnder(path: String): Boolean = isUnder(path) && pcInfo?.installed != false

    private fun LibraryEntry.asMissing(): LibraryEntry = if (missing) this else copy(missing = true)
}

/**
 * What one provider's own detection says about ONE reported path
 * ([LibraryProvider.indexPath], docs/SPEC.md 7g, "Targeted indexing"): the
 * part of its slice the path belongs to ([key] and [root], as a
 * [ScanStep.Segment] names them) and [entries], everything it now holds at
 * or under [under], the path the answer is for. A null [under] says [entries]
 * are the WHOLE part (the stores' rows, which are not under any path).
 * [folderMtime] is the part's change stamp when only the provider can take
 * it (the store part's); null leaves the one the index already has.
 */
data class PathIndexing(
    val key: String,
    val root: String?,
    val entries: List<LibraryEntry>,
    val under: String? = null,
    val folderMtime: Long? = null,
    /** [ScanStep.Segment.complete], for an answer that is a whole part. */
    val complete: Boolean = false,
)

/**
 * The place on disk this entry is the game of, or null when it is not one: a
 * ROM or an engine game is its own path, a PC game keeps its folder in its PC
 * facts and its id is the store's. What "an entry under a path" means for
 * [LibrarySlice.mergePath] and [LibrarySlice.withoutUnder].
 */
val LibraryEntry.location: String?
    get() = pcInfo?.installPath?.takeIf { it.isNotBlank() } ?: id.takeIf { it.startsWith("/") }

/** Whether this entry is at or under [path] (a file or a folder, no trailing slash). */
fun LibraryEntry.isUnder(path: String): Boolean {
    val own = location ?: return false
    return own == path || own.startsWith("$path/")
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

    /**
     * Throws the index away and rebuilds it from the per-game records alone,
     * walking no games root (docs/SPEC.md 7g: the index is derived, so
     * rebuilding it is cheap and safe). Returns how many records it was
     * rebuilt from; a store with nothing to rebuild from returns 0.
     */
    suspend fun rebuildFromRecords(): Int = 0

    /**
     * Each part's change stamp as of the last save (docs/SPEC.md 7g,
     * step 4; see [ScanStep.Segment.folderMtime]) -- what the slow
     * rebuild pass compares a part's CURRENT stamp against to decide
     * "changed" from "unchanged." Empty by default: a store that doesn't
     * track this makes every part look unknown, which
     * [LibraryProvider.slowRebuildProgressive]'s own default already
     * treats as "walk it" -- safe, just not faster.
     */
    suspend fun folderMtimes(providerKey: String): Map<PartRef, Long> = emptyMap()
}

object NoOpLibraryIndexStore : LibraryIndexStore {
    override suspend fun load(providerKey: String): LibrarySlice? = null
    override suspend fun save(providerKey: String, slice: LibrarySlice) {}
}
