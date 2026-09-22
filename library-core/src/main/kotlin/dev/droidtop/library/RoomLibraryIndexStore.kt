package dev.droidtop.library

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The index database, in RAM (docs/SPEC.md 7g, step 3): [LibraryIndexDatabase]
 * is the persistence, and every column in it is derived from a
 * [GameRecord] -- so opening an empty or shape-mismatched database is
 * never a games-root walk, only a read of the records
 * ([rebuildFromRecords]).
 *
 * Implements the same [LibraryIndexStore] interface
 * [dev.droidtop.library.FileLibraryIndexStore] used to (the JSON slice
 * file this replaces): [Library]'s own walk/merge/publish loop is
 * unchanged, and only the persistence underneath one call
 * ([load]/[save]) is now a real database instead of one JSON file per
 * provider.
 *
 * [save] receives the WHOLE merged slice every time (that's what
 * [Library.libraryProgressive] hands it), so this diffs it against the
 * slice it last wrote and touches only the segments that actually
 * changed -- "updates are the size of the change" (docs/SPEC.md 7g).
 * Each changed segment is one [LibraryIndexDao.replacePart] transaction.
 */
class RoomLibraryIndexStore(
    private val db: LibraryIndexDatabase,
    private val records: GameRecordStore,
) : LibraryIndexStore {

    private val lastWritten = ConcurrentHashMap<String, LibrarySlice>()
    private val seeded = AtomicBoolean(false)

    /**
     * Runs once per process, before the first real read: an empty
     * `games` table (a fresh install, or a version this build destructively
     * migrated away -- see [LibraryIndexDatabase]'s own doc comment) is
     * rebuilt from the records rather than left empty until the next walk,
     * which is what makes "a schema change touches no games root and
     * takes seconds" true on the very next start, not just after a walk.
     */
    private suspend fun ensureSeeded() {
        if (seeded.getAndSet(true)) return
        if (db.dao().gameCount() == 0 && records.all().isNotEmpty()) {
            rebuildFromRecords()
        }
    }

    override suspend fun load(providerKey: String): LibrarySlice? {
        ensureSeeded()
        val parts = db.dao().partsFor(providerKey)
        if (parts.isEmpty()) return null
        val gamesByPart = db.dao().gamesFor(providerKey).groupBy { it.part }
        val segments = parts.map { part ->
            // Single-game hydration happens HERE, once per part at load
            // time -- never per list draw, which is what "lists never
            // read the record" (docs/SPEC.md 7g) means in practice: the
            // published Flow<List<LibraryEntry>> below serves this
            // in-memory result, it does not re-read anything.
            val entries = gamesByPart[part.key].orEmpty().mapNotNull { row -> records.get(row.id)?.entry }
            ScanStep.Segment(key = part.key, root = part.root, entries = entries)
        }
        val slice = LibrarySlice(segments)
        lastWritten[providerKey] = slice
        return slice
    }

    override suspend fun save(providerKey: String, slice: LibrarySlice) {
        val previousByKey = lastWritten[providerKey]?.segments?.associateBy { it.key to it.root }.orEmpty()
        val keptKeys = slice.segments.mapTo(HashSet()) { it.key to it.root }
        // A segment this save no longer carries at all is one
        // [Library.keepOnlyRoots] dropped, not one a walk emptied (a walk
        // never removes a segment, only marks its games missing) -- so
        // this is the one real "delete" case docs/SPEC.md 7g means by
        // "removing a root still drops its rows and records": the row
        // AND the record both go, since nothing else keeps either once
        // the root is gone.
        for ((keyRoot, segment) in previousByKey) {
            if (keyRoot in keptKeys) continue
            for (entry in segment.entries) records.delete(entry.id)
            db.dao().deleteGamesInPart(providerKey, segment.key)
            db.dao().deletePart(providerKey, segment.key)
        }
        for (segment in slice.segments) {
            if (previousByKey[segment.key to segment.root] == segment) continue
            // The record stays the truth even for a game the index only
            // sees through a MERGE, not a fresh detection this walk --
            // a "found -> missing" transition (docs/SPEC.md 7g,
            // LibrarySlice.merge) is a real change to that game, and
            // GameRecordStore.put is a no-op when nothing actually
            // differs, so this costs nothing for the freshly-detected
            // games whose record the provider already wrote moments ago.
            for (entry in segment.entries) {
                records.put(
                    GameRecord(
                        entry = entry,
                        provider = providerKey,
                        root = segment.root,
                        part = segment.key,
                        launch = records.get(entry.id)?.launch ?: LaunchFacts.None,
                    ),
                )
            }
            db.dao().replacePart(
                PartIndexEntity(
                    provider = providerKey,
                    key = segment.key,
                    root = segment.root,
                    folderMtime = folderMtimeOf(segment.key),
                    walkedAt = System.currentTimeMillis(),
                ),
                segment.entries.map { it.toIndexRow(providerKey, segment.key, segment.root) },
            )
        }
        lastWritten[providerKey] = slice
    }

    override suspend fun folderMtimes(providerKey: String): Map<String, Long> =
        db.dao().partsFor(providerKey).associate { it.key to it.folderMtime }

    /**
     * Drops and rebuilds the WHOLE index from every readable record --
     * no games-root walk (docs/SPEC.md 7g, step 3: "takes seconds").
     * Called automatically by [ensureSeeded] on an empty database, and
     * exposed here for a real, explicit "rebuild the index" action
     * (a schema mismatch this build can't otherwise absorb, or a person
     * who wants a fresh index without a fresh walk).
     */
    suspend fun rebuildFromRecords() {
        val all = records.all()
        db.dao().clearGames()
        db.dao().clearParts()
        val byPart = all.groupBy { Triple(it.provider, it.part, it.root) }
        for ((partKey, recordsInPart) in byPart) {
            val (provider, part, root) = partKey
            db.dao().replacePart(
                PartIndexEntity(
                    provider = provider,
                    key = part ?: ScanStep.WHOLE,
                    root = root,
                    folderMtime = part?.let { folderMtimeOf(it) } ?: 0L,
                    walkedAt = System.currentTimeMillis(),
                ),
                recordsInPart.map { it.entry.toIndexRow(provider, part, root) },
            )
        }
        lastWritten.clear()
        ScanLog.write("index: rebuilt from ${all.size} records into ${byPart.size} parts, no games-root walk")
    }

    /**
     * 0 when [key] isn't itself a directory -- a console system's part
     * is several folders, not one (docs/SPEC.md 7g,
     * [dev.droidtop.library.consoles.ConsoleRomProvider]'s own doc
     * comment), and the step-4 slow pass must read 0 as "unknown, walk
     * it" rather than "unchanged since forever."
     */
    private fun folderMtimeOf(key: String): Long =
        runCatching { File(key).takeIf { it.isDirectory }?.lastModified() }.getOrNull() ?: 0L
}

private fun LibraryEntry.toIndexRow(provider: String, part: String?, root: String?): GameIndexEntity = GameIndexEntity(
    id = id,
    provider = provider,
    root = root,
    part = part,
    kind = kind.name,
    systemId = systemId,
    title = title,
    sortName = sortName,
    collectionSortName = collectionSortName,
    missing = missing,
    hidden = hidden,
    favorite = favorite,
    completed = completed,
    kidGame = kidGame,
    broken = broken,
    genre = genre,
    players = players,
    rating = rating,
    releaseDate = releaseDate,
    artworkUri = artworkUri,
    recordPath = recordPathFor(id),
)
