package dev.droidtop.library

import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.resolveSystem
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Which folders under a games root are Windows/Linux PC games, decided by
 * droidtop rather than by the vendored scanner's one-level rule.
 *
 * The vendored gamenative scanner (`CustomGameScanner.candidateFolders`)
 * takes every IMMEDIATE subfolder of a scan root and calls each one a
 * game. On the rig (games root = the user's whole library) that produced
 * seven entries that are not games at all -- `EA [PC]`, `EPIC [PC]`,
 * `GAMEPASS [PC]`, `UBISOFT [PC]`, `BATTLE.NET [PC]`, `ROMS [PC]` and
 * `.STFOLDER [PC]` -- and, worse, hid the real games inside them: the
 * library had an `EA` entry and no SimCity, a `UBISOFT` entry and none of
 * the three Ubisoft games under it.
 *
 * The rule here is the one [GameEngineDetector.scan] already applies to
 * engine games, expressed for PC games, where the evidence is an
 * executable rather than an engine marker:
 *
 *  1. A folder a library scan must not descend into is not a game
 *     ([ScanPrune]): hidden and marker folders, a store's non-game tree.
 *     A ROM system folder (`psx`, `gba`: [GameEngineDetector.isConsoleSystemFolder])
 *     is not one either: the ROM walk owns it, and the PC walk used to
 *     list every file in it to find no executable (Droidtop/tracker#275).
 *     ES-DE's own `pc` and `windows` systems are the exception, since a
 *     PC game is exactly what a person keeps there.
 *  2. **A folder that directly holds an executable is the game.** Its own
 *     subfolders are not further games -- that is what listed
 *     `Ghost Recon Breakpoint/benchmark` as a game of its own. The
 *     engine walk states the same rule from its own side
 *     ([GameEngineDetector.isPlainPcGameFolder]): a folder holding an
 *     executable and no engine evidence is a PC game and the engine walk
 *     neither claims it nor descends into it. This scan lists a folder
 *     that holds an executable AND engine evidence too; `PcGameProvider`
 *     drops that entry through the same shared rule
 *     ([GameEngineDetector.engineOwnsInstall]), so the folder is listed
 *     once, by the walk that knows what it is.
 *  3. **A folder that only holds other folders is a container**, whatever
 *     it is called: it contributes the games found below it and never
 *     itself. `EA`, `Ubisoft` and `roms` are containers; so is a games
 *     root.
 *  4. A folder that holds files of its own AND games below it is those
 *     games' root, not their container: this is the
 *     `<Game>/Binaries/Win64/Game.exe` shape, where the executable sits
 *     two levels down but the game is plainly the outer folder. The rig
 *     showed why this cannot be restricted to ONE game below: every
 *     Ubisoft install (`Far Cry 5/{bin,bin_plus}/FarCry5.exe`) keeps its
 *     executables in two payload folders, so the one-game form listed
 *     `bin` and `bin_plus` and never Far Cry 5. It does not apply inside
 *     a store tree, where `steamapps` holding one installed game must
 *     still yield the game and not `steamapps`.
 *  5. A folder with no executable anywhere below it is not a PC game.
 *     That is what makes an empty store folder contribute nothing
 *     instead of an entry a user cannot launch.
 *  6. **A folder with two or more ENGINE games directly below it is a
 *     container**, before any of the above is asked
 *     ([GameEngineDetector.holdsSeveralGames]). `adult/godot` holds two
 *     Godot games and a loose Godot Linux build left beside them, so
 *     rule 2 read it as a game and hid both. Engine evidence is what
 *     tells that shape from an install's payload folders: `bin` and
 *     `bin_plus` hold an executable and no engine at all.
 *
 * Engine games are NOT this scan's business: [GameEngineDetector] finds
 * them, and `PcGameProvider` already drops a PC entry for any folder
 * engine detection owns (docs/SPEC.md 7g).
 *
 * **What a scan costs** (docs/SPEC.md 7g, "PC folder scan cost"). The disk
 * work is what takes the time, and on a removable card behind Android's
 * FUSE layer every `stat` is a round trip to a user-space daemon, so the
 * walk is written to touch as little as it can and no more than once:
 *
 *  - ONE directory listing per folder, whose entries are told apart with
 *    ONE `stat` each ([listingOf]); what rules 2 and 4 need (a program, a
 *    file of its own) is read off that listing, never asked of the folder
 *    again. The walk used to list a folder three times and `stat` every
 *    entry three times.
 *  - "Is there a game below this folder" (rule 4) is a question of
 *    EXISTENCE, answered breadth first and stopped at the first game
 *    ([hasGameBelow]); it used to enumerate every game below, which for a
 *    `Game/Binaries/Win64/Game.exe` shape meant walking the game's whole
 *    asset tree to depth four to confirm what the third level already
 *    showed.
 *  - A listing is remembered per folder by the folder's modification time
 *    ([ListingCache]): a directory's time moves when an entry is added,
 *    removed or renamed in it, so an unchanged folder costs one `stat`
 *    instead of a listing and a `stat` per entry.
 *  - Top-level folders are independent of one another, so a caller may
 *    walk several at once ([scanTopLevel]); the cost is latency, not
 *    bandwidth, and latency overlaps.
 *
 * Every walk reports what it did ([Work]) so a slow root can be told from
 * a big one in `droidtop.ScanLog`.
 */
object PcFolderScan {

    /** Same bound as [GameEngineDetector.MAX_SCAN_DEPTH], for the same reason. */
    const val MAX_SCAN_DEPTH = 4

    /** ES-DE's own PC systems: a console-style name whose folders ARE where PC games live. */
    private val PC_SYSTEM_IDS = setOf("pc", "windows")

    /**
     * What a walk may be given beyond the roots and the engine rules.
     *
     * [systemsById] is the console systems, used for rule 1's ROM folders
     * only; empty means "no folder is known to be a ROM system folder".
     * [cache] is the caller's own [ListingCache], kept for as long as the
     * caller wants listings remembered; null remembers nothing, which is
     * what a test wants. [cancelled] is asked before every listing and
     * answers "stop now": the walk then throws [CancellationException], so
     * a blocking walk can be abandoned from a coroutine without waiting
     * for its folder to finish.
     */
    class Options(
        val systemsById: Map<String, ConsoleSystemDef> = emptyMap(),
        val cache: ListingCache? = null,
        val cancelled: () -> Boolean = { false },
        /** Rule 6's verdicts kept across restarts ([EngineVerdicts]); null keeps none. */
        val verdicts: EngineVerdicts? = null,
        /** Told how many folders an engine check is about to look at and how many it has finished, as (planned, finished) deltas from any thread. */
        val onEngineWork: (planned: Int, finished: Int) -> Unit = { _, _ -> },
    )

    /**
     * What one top-level folder's walk did, for the scan log: how many
     * directory listings it read from disk, how many entries it `stat`ed,
     * how many listings the cache answered instead, how often and for how
     * long rule 6's engine probe ran, and the wall time of the whole walk.
     */
    data class Work(
        val listings: Int = 0,
        val entriesStatted: Int = 0,
        val cachedListings: Int = 0,
        val engineChecks: Int = 0,
        val engineMs: Long = 0L,
        val millis: Long = 0L,
        val engineVerdictsKept: Int = 0,
    ) {
        fun describe(): String =
            "$listings listings, $entriesStatted entries statted, $cachedListings listings from cache, " +
                "$engineChecks engine checks ($engineMs ms), $engineVerdictsKept engine verdicts remembered"
    }

    /**
     * The folders a root's walk is split into: its immediate subfolders,
     * in name order. Rule 3 makes the root itself a container, so each of
     * these is the unit of work (and of the index's replace-one-folder
     * merge, docs/SPEC.md 7g).
     */
    fun topLevelFolders(root: File): List<File> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        }

    /**
     * Every PC game folder under [root], deepest-evidence-first. [root]
     * itself is treated as a container: a games root is never a game.
     *
     * [defs] are the engine-detection rules, used for rule 6 only -- an
     * empty list simply means "no folder is known to hold engine games",
     * which is what a caller with no database loaded should get.
     */
    fun gamesUnder(root: File, defs: List<EngineDef> = emptyList()): List<File> =
        gamesByTopLevelFolder(root, defs).flatMap { it.games }

    /**
     * One top-level folder of a games root, and the PC games under it.
     * [mtime] is the folder's own modification time, read before its walk
     * (a change during the walk then moves it past the stamp the index
     * keeps, and the next slow round walks the folder again). [skipped]
     * is a folder the caller's `skip` left unwalked: [games] is empty
     * because nobody looked, not because it holds none. [work] is what
     * the walk cost and [skips] the folders it did not enter and why
     * (ROM system folders), both for the scan log.
     */
    data class TopLevelFolder(
        val folder: File,
        val games: List<File>,
        val mtime: Long = 0L,
        val skipped: Boolean = false,
        val work: Work = Work(),
        val skips: ScanSkips = ScanSkips(),
    )

    /**
     * The same walk, kept in the shape the index merges in: one entry per
     * top-level folder of [root], so the caller can replace that folder's
     * games on their own and say which folders the root still has
     * (docs/SPEC.md 7g). A folder with no games under it is still listed,
     * with none: "this folder is here and holds no games" and "this
     * folder is gone" are different answers.
     *
     * [skip] is the slow pass' knob (docs/SPEC.md 7g): a folder it names,
     * given its modification time, is listed but not walked.
     */
    fun gamesByTopLevelFolder(
        root: File,
        defs: List<EngineDef> = emptyList(),
        options: Options = Options(),
        skip: (folder: File, mtime: Long) -> Boolean = { _, _ -> false },
    ): List<TopLevelFolder> = topLevelFolders(root).map { scanTopLevel(it, defs, options, skip) }

    /**
     * One top-level folder's walk, on the calling thread. Independent of
     * every other folder's, so a caller that wants the walk to overlap its
     * disk waits runs several of these at once ([ListingCache] is safe to
     * share between them).
     */
    fun scanTopLevel(
        folder: File,
        defs: List<EngineDef> = emptyList(),
        options: Options = Options(),
        skip: (folder: File, mtime: Long) -> Boolean = { _, _ -> false },
    ): TopLevelFolder {
        val mtime = folder.lastModified()
        if (skip(folder, mtime)) return TopLevelFolder(folder, emptyList(), mtime, skipped = true)
        val walk = Walk(defs, options)
        val startedAt = System.currentTimeMillis()
        val games = walk.walk(folder, depth = 1)
        return TopLevelFolder(
            folder = folder,
            games = games,
            mtime = mtime,
            work = walk.work(System.currentTimeMillis() - startedAt),
            skips = walk.skips,
        )
    }

    /**
     * What a read of one folder said: its subfolders in name order, and the
     * two facts about its own files that rules 2 and 4 are made of. Kept
     * whole so a repeat walk of an unchanged folder asks the disk nothing
     * but its modification time.
     */
    class Listing internal constructor(
        internal val mtime: Long,
        internal val folders: List<File>,
        internal val hasProgram: Boolean,
        internal val hasOwnFile: Boolean,
        /** How many entries the folder held, which with [mtime] tells [EngineVerdicts] the folder is the one it judged. */
        internal val entryCount: Int,
    ) {
        /** Rule 6's answer for this folder, with the engine rules it was asked under; filled in when first needed. */
        @Volatile
        internal var engineDefs: List<EngineDef>? = null

        @Volatile
        internal var holdsEngineGames: Boolean = false
    }

    /**
     * Listings remembered by folder path and modification time, for as long
     * as the holder keeps it. A directory's modification time moves when an
     * entry is added, removed or renamed in it, which is every change a
     * listing records, so an entry that matches is the listing a fresh read
     * would give; what a listing does NOT record (a file's own contents,
     * the execute bit of an extensionless file) is not something a rescan
     * of a games folder is asked to notice. Bounded: past [MAX_ENTRIES] it
     * starts over rather than growing with the library.
     */
    class ListingCache {
        private val entries = ConcurrentHashMap<String, Listing>()

        internal fun get(folder: File, mtime: Long): Listing? =
            entries[folder.path]?.takeIf { it.mtime == mtime }

        internal fun put(folder: File, listing: Listing) {
            if (entries.size >= MAX_ENTRIES) entries.clear()
            entries[folder.path] = listing
        }

        companion object {
            const val MAX_ENTRIES = 20_000
        }
    }

    /**
     * Rule 6's verdict per folder, kept across restarts: a cold start after
     * the app was closed re-checks only the folders that changed. A verdict
     * is for one folder path, one modification time, one entry count and one
     * set of engine rules ([fingerprintOf]); a change to any of them is a
     * miss. As with [ListingCache], what happens INSIDE a game folder
     * without touching its parent is not noticed until the parent changes.
     * The caller loads it once, hands it to every walk, and saves it after
     * the scan, off the main thread ([load], [save]); it is safe to share
     * between walks and bounded like the listing cache.
     */
    class EngineVerdicts private constructor(private val fingerprint: String) {
        private class Verdict(val mtime: Long, val entries: Int, val holds: Boolean)

        private val verdicts = ConcurrentHashMap<String, Verdict>()

        @Volatile
        private var dirty = false

        internal fun get(folder: File, listing: Listing): Boolean? =
            verdicts[folder.path]?.takeIf { listing.mtime != 0L && it.mtime == listing.mtime && it.entries == listing.entryCount }?.holds

        internal fun put(folder: File, listing: Listing, holds: Boolean) {
            if (listing.mtime == 0L) return
            if (verdicts.size >= ListingCache.MAX_ENTRIES) verdicts.clear()
            verdicts[folder.path] = Verdict(listing.mtime, listing.entryCount, holds)
            dirty = true
        }

        /** Writes the verdicts when any were added since they were loaded; a failed write loses only the saving. */
        fun save(file: File) {
            if (!dirty) return
            try {
                val tmp = File(file.path + ".tmp")
                tmp.bufferedWriter().use { out ->
                    out.write(fingerprint)
                    out.newLine()
                    for ((path, v) in verdicts) {
                        out.write("${v.mtime}\t${v.entries}\t${if (v.holds) 1 else 0}\t$path")
                        out.newLine()
                    }
                }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
                dirty = false
            } catch (_: java.io.IOException) {
                // Nothing to keep; the next scan checks again.
            }
        }

        companion object {
            /** The same rules give the same fingerprint in every process, which a hash of the rules' own objects would not. */
            fun fingerprintOf(defs: List<EngineDef>): String =
                "engine-verdicts-1 " + defs.joinToString("|") { "${it.id}:${it.detect}" }.hashCode()

            /** The verdicts [file] kept for [defs], or none when it is missing, unreadable, or was written for other rules. */
            fun load(file: File, defs: List<EngineDef>): EngineVerdicts {
                val fingerprint = fingerprintOf(defs)
                val result = EngineVerdicts(fingerprint)
                try {
                    file.bufferedReader().use { input ->
                        if (input.readLine() != fingerprint) return result
                        input.forEachLine { line ->
                            val parts = line.split('\t', limit = 4)
                            if (parts.size != 4) return@forEachLine
                            val mtime = parts[0].toLongOrNull() ?: return@forEachLine
                            val entries = parts[1].toIntOrNull() ?: return@forEachLine
                            result.verdicts[parts[3]] = Verdict(mtime, entries, parts[2] == "1")
                        }
                    }
                } catch (_: java.io.IOException) {
                    result.verdicts.clear()
                }
                return result
            }
        }
    }

    /** One top-level folder's walk: the rules, and the counts [Work] reports. */
    private class Walk(private val defs: List<EngineDef>, private val options: Options) {
        val skips = ScanSkips()
        private val skipped = HashSet<String>()
        private var listings = 0
        private var entriesStatted = 0
        private var cachedListings = 0
        private var engineChecks = 0
        private var engineNanos = 0L
        private var verdictsKept = 0

        fun work(millis: Long) =
            Work(listings, entriesStatted, cachedListings, engineChecks, engineNanos / 1_000_000L, millis, verdictsKept)

        fun walk(folder: File, depth: Int): List<File> {
            if (!walkable(folder, depth)) return emptyList()
            val listing = listingOf(folder)
            // A store's own install root is the store's business, never a
            // game, however many executables its client drops in it.
            val isStoreRoot = ScanPrune.storeRootOwner(folder) != null
            if (!isStoreRoot && listing.hasProgram && !holdsEngineGames(folder, listing)) return listOf(folder)

            val insideStoreTree = isStoreRoot || ScanPrune.storeTreeRoot(folder) != null
            // A part or version folder with a stray file of its own is still
            // not the game; the game is the folder below it that has one.
            val structural = GameNaming.isStructuralFolderName(folder.name)
            if (listing.hasOwnFile && !insideStoreTree && !structural) {
                // Rule 4 only needs to know THAT a game is below.
                if (!hasGameBelow(listing, depth)) return emptyList()
                if (!holdsEngineGames(folder, listing)) return listOf(folder)
            }
            return childrenOf(listing, depth).flatMap { (child, childDepth) -> walk(child, childDepth) }
        }

        /**
         * Whether some game is below [listing]'s folder: rule 2 asked of each
         * folder under it, one level at a time, shallowest first, stopping
         * at the first that answers yes. Same answer as walking every child
         * and asking whether anything came back, at the cost of the levels
         * above the first game.
         */
        private fun hasGameBelow(listing: Listing, depth: Int): Boolean {
            var level = childrenOf(listing, depth)
            while (level.isNotEmpty()) {
                val next = ArrayList<Pair<File, Int>>()
                for ((child, childDepth) in level) {
                    if (!walkable(child, childDepth)) continue
                    val below = listingOf(child)
                    if (ScanPrune.storeRootOwner(child) == null && below.hasProgram && !holdsEngineGames(child, below)) return true
                    next += childrenOf(below, childDepth)
                }
                level = next
            }
            return false
        }

        /**
         * The engine walk's depth rule, asked of the same names: a part or
         * version folder is one game's structure and costs no depth
         * (GameNaming.isStructuralFolderName, docs/SPEC.md 7m).
         */
        private fun childrenOf(listing: Listing, depth: Int): List<Pair<File, Int>> =
            listing.folders.mapNotNull { child ->
                when {
                    GameNaming.isStructuralFolderName(child.name) -> child to depth
                    depth < MAX_SCAN_DEPTH -> child to depth + 1
                    else -> null
                }
            }

        private fun walkable(folder: File, depth: Int): Boolean {
            if (!ScanPrune.isScannableFolder(folder)) return false
            if (isRomSystemFolder(folder, depth)) {
                if (skipped.add(folder.path)) skips.add(folder, GameEngineDetector.CONSOLE_SYSTEM_FOLDER_REASON)
                return false
            }
            return true
        }

        private fun isRomSystemFolder(folder: File, depth: Int): Boolean {
            val systems = options.systemsById
            if (systems.isEmpty()) return false
            if (resolveSystem(folder.name, systems)?.id in PC_SYSTEM_IDS) return false
            return GameEngineDetector.isConsoleSystemFolder(folder, systems, depth)
        }

        /**
         * Rule 6, asked at most once per folder and only when the folder has
         * two subfolders at all (it needs two games below). Probing a
         * subfolder for engine evidence is the costliest thing a walk does
         * per folder, so it is bounded to marker names in one read of each
         * subfolder ([FolderFacts]), a few subfolders are looked at at once,
         * and the verdict is kept per folder across restarts
         * ([EngineVerdicts]); [Work] counts it for that reason.
         */
        private fun holdsEngineGames(folder: File, listing: Listing): Boolean {
            if (defs.isEmpty() || listing.folders.size < 2) return false
            if (listing.engineDefs === defs) return listing.holdsEngineGames
            val kept = options.verdicts?.get(folder, listing)
            val holds = if (kept != null) {
                verdictsKept++
                kept
            } else {
                val startedAt = System.nanoTime()
                val asked = GameEngineDetector.holdsSeveralGames(
                    folder,
                    defs,
                    subfolders = listing.folders,
                    hooks = GameEngineDetector.ProbeHooks(
                        cancelled = options.cancelled,
                        planned = { options.onEngineWork(it, 0) },
                        finished = { options.onEngineWork(0, 1) },
                    ),
                )
                engineNanos += System.nanoTime() - startedAt
                engineChecks++
                options.verdicts?.put(folder, listing, asked)
                asked
            }
            listing.holdsEngineGames = holds
            listing.engineDefs = defs
            return holds
        }

        private fun listingOf(folder: File): Listing {
            if (options.cancelled()) throw CancellationException("PC folder scan cancelled")
            val cache = options.cache
            // Read BEFORE the listing: a change during it then moves the
            // time past the one stored, and the next walk reads again.
            val mtime = if (cache != null || options.verdicts != null) folder.lastModified() else 0L
            if (cache != null && mtime != 0L) {
                cache.get(folder, mtime)?.let {
                    cachedListings++
                    return it
                }
            }
            listings++
            val folders = ArrayList<File>()
            var hasProgram = false
            var hasOwnFile = false
            val entries = folder.listFiles() ?: emptyArray()
            for (entry in entries) {
                entriesStatted++
                if (entry.isDirectory) {
                    folders += entry
                    continue
                }
                if (!entry.name.startsWith(".")) hasOwnFile = true
                if (!hasProgram && GameExecutableResolver.isProgram(entry)) hasProgram = true
            }
            folders.sortBy { it.name.lowercase() }
            val listing = Listing(mtime, folders, hasProgram, hasOwnFile, entries.size)
            if (cache != null && mtime != 0L) cache.put(folder, listing)
            return listing
        }
    }
}
