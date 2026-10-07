package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameCompatibilityCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.droidtop.library.EngineVerdictStore
import dev.droidtop.library.GameTitleParser
import dev.droidtop.library.PcCompatibility
import dev.droidtop.library.PcFolderScan
import dev.droidtop.library.PcInfo
import dev.droidtop.library.PcStoreNames
import dev.droidtop.library.ScanActivity
import dev.droidtop.library.ScanBudget
import dev.droidtop.library.ScanLog
import dev.droidtop.library.ScanSkips
import dev.droidtop.library.StoreInstall
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibraries
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * droidtop's OWN source-agnostic view of every PC game it knows about —
 * Steam (the vendored gamenative service), the stores droidtop runs itself
 * (GOG, Epic, Amazon Games and itch.io, through
 * [dev.droidtop.library.stores.StoreLibraries], docs/SPEC.md 7g "Stores"),
 * and loose folders — behind one shape.
 *
 * The point of this class, and the audit finding that produced it
 * (docs/SPEC.md §7g): droidtop compiled 830 gamenative source files and
 * referenced eight symbols, all of them Steam. Nothing here draws a
 * screen; droidtop renders its own.
 *
 * A user should not have to care which store a game came from. Source is
 * a fact about a game, worth showing and worth filtering on, but it is
 * never a separate screen.
 */
object PcLibrary {

    /**
     * Where a game came from. Mirrors gamenative's own `GameSource` (the
     * unified model already in the fork) rather than inventing a second
     * vocabulary, but is droidtop's own type so library-core and the
     * shells never import `app.gamenative.*`.
     */
    enum class Source { STEAM, GOG, EPIC, AMAZON, ITCH, FOLDER }

    /**
     * Community compatibility reports for a title, from gamenative's own
     * `api.gamenative.app/api/game-runs` service.
     *
     * **Reference, never a gate** (directed 2026-09-01). droidtop shows
     * this and lets the user decide; it must never block a download, hide
     * an entry, or reorder a library on its own. A rating is other
     * people's experience on other hardware, which is useful information
     * and a bad decision-maker.
     */
    data class Compatibility(
        val averageRating: Float,
        val playableReports: Int,
        val gpuPlayableReports: Int,
        val hasBeenTried: Boolean,
        val reportedNotWorking: Boolean,
    )

    /** One PC game, whatever it came from. */
    data class Game(
        /** Stable across scans and unique across sources: `"steam:440"`. */
        val id: String,
        val source: Source,
        /** The id this game's own store/scanner uses, unprefixed. */
        val nativeId: String,
        val title: String,
        val installed: Boolean,
        val installPath: String?,
        /** On-disk size when installed, download size when not, 0 when unknown. */
        val sizeBytes: Long,
        val artUrl: String?,
        val compatibility: Compatibility?,
        /** The build installed, when the store words one; never made up ([PcInfo.installedVersion]). */
        val installedVersion: String? = null,
        /** Other stores' ids the store's own row names ([PcInfo.externalIds]). */
        val externalIds: Map<String, String> = emptyMap(),
    ) {
        val installDir: File? get() = installPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StoreDaoEntryPoint {
        fun steamAppDao(): app.gamenative.db.dao.SteamAppDao
    }

    private fun daos(context: Context): StoreDaoEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, StoreDaoEntryPoint::class.java)

    /**
     * Every PC game from every source, owned and installed alike.
     *
     * A store the user never signed into simply contributes nothing — its
     * Room tables exist and are empty — so this needs no "is GOG enabled"
     * configuration and grows a source the moment somebody signs in.
     * Each source is read independently: one store's failure (a corrupt
     * row, a schema drift after a vendor sync) costs that store's games,
     * not the whole library.
     */
    suspend fun allGames(context: Context): List<Game> =
        (storeGames(context) + folderGames(context).flatMap { it.games }).sortedBy { it.title.lowercase() }

    /**
     * One top-level folder of one games root, and the games droidtop's
     * own folder rule ([dev.droidtop.library.PcFolderScan]) found under
     * it.
     *
     * The grouping is the walk's unit of work, not a presentation
     * choice: the library index replaces one folder's entries at a time
     * (docs/SPEC.md 7g), so the walk has to say which folder each game
     * came from.
     */
    data class FolderGroup(
        val root: String,
        val topFolder: String,
        val games: List<Game>,
        /** The folder's modification time before its walk: its change stamp in the index. */
        val mtime: Long = 0L,
        /** Left unwalked by the caller's `skip`; [games] is empty because nobody looked. */
        val skipped: Boolean = false,
    )

    /**
     * Every PC game a STORE knows about, plus the folders the vendored
     * scanner was told about by hand outside droidtop's own roots. None
     * of these is under a games root droidtop walks, which is why they
     * are one group rather than per folder.
     */
    suspend fun storeGames(context: Context): List<Game> {
        DroidtopGameIdStore.install(context)
        val dao = daos(context)
        // What the stores last said about newer builds, and the downloads
        // they are running: files and memory only, no network here.
        StoreUpdates.load(context)
        StoreDownloadWatch.start(context)
        return buildList {
            addAll(runCatching { dao.steamAppDao().getAllOwnedAppsAsList().map { it.toGame() } }.getOrDefault(emptyList()))
            // The stores droidtop runs itself (docs/SPEC.md 7g, "Stores"),
            // each read on its own for the same reason as the DAOs above.
            for (store in StoreLibraries.all()) {
                addAll(
                    runCatching { store.games(context).mapNotNull { it.toGame() } }
                        .onFailure { android.util.Log.w(TAG, "Reading ${store.label}'s games failed", it) }
                        .getOrDefault(emptyList()),
                )
            }
            addAll(
                runCatching {
                    // Folders the user added to the vendored scanner by
                    // hand, OUTSIDE droidtop's roots, still count. Inside
                    // them is [folderGames]' answer and only its answer:
                    // droidtop writes its own findings into the scanner's
                    // manual-folder list (see adoptFoundFolders), so
                    // without this filter the same game would arrive twice
                    // -- once as a store part with no root, once as its
                    // folder's part -- and removing the root would leave
                    // the rootless copy behind. The filter runs BEFORE the
                    // item is made into a game: that step reads the folder
                    // for its art, and doing it for every game under the
                    // roots only to throw the result away was a second
                    // pass over every game folder in the library.
                    val ourRoots = dev.droidtop.library.GamesRoots.current(context).map { it.absolutePath }
                    CustomGameScanner.scanAsLibraryItems()
                        .distinctBy { it.appId }
                        // The scanner recognizes a Steam install sitting in a
                        // scanned folder and returns it as a STEAM item; that
                        // game already came from the Steam DAO above, so taking
                        // both would list it twice.
                        .filter { it.gameSource == GameSource.CUSTOM_GAME }
                        .map { it to it.scannerFolder() }
                        .filterNot { (_, path) ->
                            path != null && ourRoots.any { root -> path == root || path.startsWith(root + "/") }
                        }
                        .map { (item, path) -> item.toGame(context, folderPath = path) }
                }.getOrDefault(emptyList()),
            )
        }.sortedBy { it.title.lowercase() }
            .also { games ->
                storeSourceInstalls = games.mapNotNull { it.toStoreInstall() }
                // Asked in the background, for the next walk to read.
                StoreUpdates.refreshInBackground(context, games.filter { it.installed }.map { it.id })
            }
    }

    /**
     * The games under droidtop's own roots, a top-level folder at a
     * time. A folder [skip] names (the slow pass' unchanged folders,
     * docs/SPEC.md 7g) comes back listed but unwalked, and what the last
     * walk recorded for it (its installs, its games in the scanner's
     * folder list) is kept.
     *
     * Two phases, both reported through [dev.droidtop.library.ScanActivity]
     * as "n of m folders": the walk of the folders ([scanGameFolders]),
     * which is the slow one on a card, and the turning of what it found
     * into games, whose finished folders are handed to [onGroup] one at a
     * time so the caller can publish them as they arrive rather than all
     * at the end. A cancelled caller stops the walk at its next directory
     * and nothing of the unfinished work is returned or published.
     */
    suspend fun folderGames(
        context: Context,
        skip: (folder: File, mtime: Long) -> Boolean = { _, _ -> false },
        onGroup: suspend (FolderGroup) -> Unit = {},
    ): List<FolderGroup> {
        DroidtopGameIdStore.install(context)
        // The scanner looks in its own managed folders plus whatever
        // roots it has been told about. droidtop already asks the user
        // for their games folders ONCE, so those are the roots -- without
        // this the folder source could only ever see gamenative's own
        // CustomGames directory, which nothing in droidtop tells anybody
        // about.
        val roots = dev.droidtop.library.GamesRoots.current(context)
        val rootPaths = roots.map { it.absolutePath }
        try {
            var found: List<ScannedFolder> = emptyList()
            if (roots.isNotEmpty()) {
                try {
                    // A store installs into the person's game folders
                    // (docs/SPEC.md 7g, "Where a store installs"), so the
                    // walk meets its games too: those folders are the
                    // store's entries, with the store's facts, and are not
                    // listed a second time as folder games.
                    val storeOwned = storeOwnedFolders(context)
                    found = scanGameFolders(context, roots, skip).map { group ->
                        if (storeOwned.isEmpty()) group else group.copy(gameFolders = group.gameFolders.filterNot { it.isUnder(storeOwned) })
                    }
                    adoptFoundFolders(rootPaths, found)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "Scanning droidtop's roots for PC games failed", t)
                }
            }
            val withGames = found.count { it.gameFolders.isNotEmpty() }
            var built = 0
            val groups = ArrayList<FolderGroup>(found.size)
            for (group in found) {
                currentCoroutineContext().ensureActive()
                if (group.gameFolders.isNotEmpty()) {
                    ScanActivity.set(SCAN_SOURCE, "Reading PC game details: ${++built} of $withGames folders")
                }
                // droidtop's own folders are turned into items HERE, from the
                // list this scan just produced, and not read back out of the
                // preference it was also written to. The rig proved why:
                // `PrefManager.setPref` hands the write to a DataStore
                // coroutine and returns, while `candidateFolders()` reads the
                // value back synchronously, so the very first scan after an
                // install read the EMPTY set and the PC library was 151
                // engine games and not one folder game. On a device that had
                // scanned before, the previous run's value hid the race
                // completely -- which is exactly why build 537 showed 171
                // games and a freshly installed 539 showed 151.
                val games = group.gameFolders
                    .mapNotNull { folder -> runCatching { CustomGameScanner.createLibraryItemFromFolder(folder) }.getOrNull() }
                    .distinctBy { it.appId }
                    // The scanner recognizes a Steam install sitting in a
                    // scanned folder and returns it as a STEAM item; that
                    // game already came from the Steam DAO, so taking both
                    // would list it twice.
                    .filter { it.gameSource == GameSource.CUSTOM_GAME }
                    .map { it.toGame(context, group.root) }
                    .sortedBy { it.title.lowercase() }
                val folderGroup = FolderGroup(
                    root = group.root,
                    topFolder = group.topFolder,
                    games = games,
                    mtime = group.mtime,
                    skipped = group.skipped,
                )
                groups += folderGroup
                onGroup(folderGroup)
            }
            // Recording the installs here, in the one place every source's
            // install directories are already known, is what keeps
            // [knownInstalls]/[knownInstallRoots] answerable synchronously.
            // It used to be a separate `installRoots(context)` entry point
            // that nothing ever called, so the store half of that list stayed
            // empty forever and only Steam's own paths reached engine
            // detection.
            val unwalked = groups.filter { it.skipped }.map { it.topFolder }
            folderSourceInstalls = folderSourceInstalls.filter { install -> install.installDir.absolutePath.isUnder(unwalked) } +
                groups.flatMap { it.games }.mapNotNull { it.toStoreInstall() }
            return groups
        } finally {
            ScanActivity.finish(SCAN_SOURCE)
        }
    }


    /** Only what is actually on this device — what the library grid shows. */
    suspend fun installedGames(context: Context): List<Game> = allGames(context).filter { it.installed }

    /**
     * Every directory any source installs games under. droidtop's engine
     * detection scans these, so a Ren'Py or RPG Maker game installed from
     * GOG flows through the SAME detection, grouping and launch-strategy
     * resolution as one sitting in a games folder — enginehost included.
     * That is the whole reason this returns roots rather than a store's
     * own launch command.
     */
    /**
     * Install roots discovered by the most recent [allGames] call, plus
     * Steam's own paths (which the service can answer synchronously).
     *
     * Synchronous because engine detection's `extraRoots` hook is, and
     * must not block a scan thread on four Room queries. The store half
     * is therefore one scan behind on a cold start — a GOG-installed
     * Ren'Py game is detected on the second scan, not the first — which
     * is the right trade against stalling every scan.
     */
    fun knownInstallRoots(): List<File> {
        val steam = runCatching { SteamService.allInstallPaths }.getOrDefault(emptyList()).map(::File)
        // The PARENT of each game's install directory, not the directory
        // itself. Engine detection reads a root's children as candidate
        // game folders (see `GameEngineDetector.scan`), so handing it a
        // game's own folder points it one level too deep and it finds
        // nothing there. Steam's own paths already arrive as library
        // roots, which is why a Steam-installed Ren'Py game was detected
        // and a GOG one would not have been even once this was populated.
        val storeRoots = storeInstalls.mapNotNull { it.installDir.parentFile }
        return (steam + storeRoots)
            .filter { it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    /**
     * The installs behind [knownInstallRoots], with the store's own facts
     * attached. Engine detection takes these so that a store-installed
     * engine game keeps its source, size, compatibility and cover art
     * after its duplicate `pc` entry is suppressed.
     *
     * Same one-scan-behind caveat as [knownInstallRoots], and for the
     * same reason: it must answer without blocking a scan thread on four
     * Room queries.
     */
    fun knownInstalls(): List<StoreInstall> = storeInstalls.filter { it.installDir.isDirectory }

    /**
     * The two halves are recorded separately because they are now walked
     * separately -- the stores answer as one, the roots answer a folder
     * at a time -- and one half completing must not erase the other's.
     */
    private val storeInstalls: List<StoreInstall> get() = storeSourceInstalls + folderSourceInstalls

    @Volatile
    private var storeSourceInstalls: List<StoreInstall> = emptyList()

    @Volatile
    private var folderSourceInstalls: List<StoreInstall> = emptyList()

    /**
     * Cached community compatibility only — no network call. Scanning a
     * library must not depend on a reachable server or a signed-in
     * account, so an entry simply carries no rating until something else
     * has populated the cache.
     */
    private fun compatibilityFor(title: String): Compatibility? =
        runCatching { GameCompatibilityCache.getCached(title) }.getOrNull()?.let { response ->
            Compatibility(
                averageRating = response.avgRating,
                playableReports = response.totalPlayableCount,
                gpuPlayableReports = response.gpuPlayableCount,
                hasBeenTried = response.hasBeenTried,
                reportedNotWorking = response.isNotWorking,
            )
        }

    private fun SteamApp.toGame(): Game = Game(
        id = "steam:$id",
        source = Source.STEAM,
        nativeId = id.toString(),
        title = name,
        installed = runCatching { SteamService.isAppInstalled(id) }.getOrDefault(false),
        // Steam's own row carries no install path; the service resolves it.
        installPath = runCatching { SteamService.getAppDirPath(id) }.getOrNull(),
        // SteamApp models no size on disk, and walking the install tree on
        // every scan would cost more than the number is worth.
        sizeBytes = 0L,
        artUrl = clientIconUrl.takeIf { clientIconHash.isNotEmpty() },
        compatibility = compatibilityFor(name),
    )

    /**
     * A row of a store droidtop runs itself. Null for a store this enum
     * does not name yet: a store is a [Source] before its rows can be
     * filtered on or drawn with its name.
     */
    private fun StoreGame.toGame(): Game? {
        val source = Source.entries.firstOrNull { it != Source.FOLDER && it.name.equals(store, ignoreCase = true) } ?: return null
        return Game(
            id = key,
            source = source,
            nativeId = gameId,
            title = title,
            installed = installed,
            installPath = installPath,
            sizeBytes = sizeBytes,
            artUrl = artUrl,
            compatibility = compatibilityFor(title),
            installedVersion = installedVersion,
            externalIds = externalIds,
        )
    }

    /** One top-level folder of one root, and the game folders droidtop's rule found under it. */
    private data class ScannedFolder(
        val root: String,
        val topFolder: String,
        val gameFolders: List<String>,
        val mtime: Long,
        val skipped: Boolean,
        val skips: ScanSkips,
    )

    /**
     * The folders the stores droidtop runs have put games in, finished or
     * still downloading (each store records the folder before its download
     * starts). One read of each store's own rows; off the main thread with
     * the walk that asks.
     */
    private suspend fun storeOwnedFolders(context: Context): List<String> =
        StoreLibraries.all().flatMap { store ->
            runCatching { store.games(context) }.getOrDefault(emptyList())
                .mapNotNull { game -> game.installPath?.takeIf(String::isNotBlank)?.let { File(it).absolutePath } }
        }

    private fun String.isUnder(folders: Collection<String>): Boolean =
        folders.any { folder -> this == folder || startsWith("$folder/") }

    /** The key this walk's progress is shown under ([ScanActivity]). */
    private const val SCAN_SOURCE = "pc"

    /**
     * How many top-level folders are walked at once. What a walk of a
     * removable card costs is the latency of each call, not its
     * bandwidth, so a few at once finish in a fraction of the time; more
     * than a few only queue up behind the card's own FUSE daemon.
     */
    private const val SCAN_PARALLELISM = 3

    /**
     * Every directory listing the folder walk has read, for as long as the
     * process lives, so a rescan of folders nobody touched asks the card
     * for a modification time per folder instead of a listing
     * ([PcFolderScan.ListingCache]).
     */
    private val listingCache = PcFolderScan.ListingCache()

    /** [block]'s answer, or [default] when it throws. A cancellation is never swallowed. */
    private suspend fun <T> orDefault(default: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            default
        }

    /**
     * The PC game folders under droidtop's own games roots, handed to
     * gamenative's folder scanner as the exact folders to make items for.
     *
     * Why not hand it the ROOTS, which is what this did until 2026-09-16:
     * the vendored scanner's rule is "every immediate subfolder of a scan
     * root is a game", and a games root is not laid out that way. On the
     * rig it produced `EA [PC]`, `EPIC [PC]`, `GAMEPASS [PC]`,
     * `UBISOFT [PC]`, `BATTLE.NET [PC]`, `ROMS [PC]` and `.STFOLDER [PC]`
     * -- none of them games -- while SimCity, three Ubisoft games and
     * every other game one level deeper never appeared at all. Which
     * folders are games is droidtop's own question and it has its own
     * answer ([PcFolderScan]); the scanner keeps everything it is
     * genuinely good at -- app ids, icon extraction, container
     * configuration, the install lifecycle -- for the folders droidtop
     * names.
     *
     * Roots droidtop previously wrote into the scanner's own root list
     * are taken back out, so an upgrade stops producing the wrapper
     * entries rather than keeping them until the user finds the vendored
     * setting. Manual folders the user added by hand outside droidtop's
     * roots are left alone.
     *
     * The folders of every root are walked [SCAN_PARALLELISM] at a time,
     * and each walked folder gets its own `droidtop.ScanLog` line with
     * what it cost ([PcFolderScan.Work]), as the ROM and engine walks
     * already do: the five-minute rescan of 79 games on a card
     * (Droidtop/tracker#275) could not be explained from one line for the
     * whole root.
     */
    private suspend fun scanGameFolders(
        context: Context,
        roots: List<File>,
        skip: (folder: File, mtime: Long) -> Boolean,
    ): List<ScannedFolder> = coroutineScope {
        val rootPaths = roots.map { it.absolutePath }
        runCatching {
            val currentRoots = app.gamenative.PrefManager.customGameScanRoots
            val keptRoots = currentRoots.filterNot { it in rootPaths }.toSet()
            if (keptRoots.size != currentRoots.size) {
                app.gamenative.PrefManager.customGameScanRoots = keptRoots
            }
        }.onFailure { android.util.Log.w(TAG, "Could not take droidtop's roots out of the folder scanner", it) }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // The engine rules go in because which folders are PC games and
        // which are engine games is one question: a category folder
        // holding engine games is nobody's game (PcFolderScan rule 6).
        val defs = orDefault<List<dev.droidtop.library.EngineDef>>(emptyList()) {
            dev.droidtop.library.EnginesDatabase.defs(context)
        }
        // The ROM system folders, which the ROM walk owns (PcFolderScan rule 1).
        val systems = orDefault<Map<String, dev.droidtop.library.consoles.ConsoleSystemDef>>(emptyMap()) {
            dev.droidtop.library.consoles.ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        }
        val job = currentCoroutineContext()[Job]
        // The engine verdicts survive a restart and are the same ones the
        // engine walk reads ([EngineVerdictStore]); loaded and saved off the
        // main thread, and saved even when the scan is cancelled part way.
        val verdicts = withContext(Dispatchers.IO) { EngineVerdictStore.forRules(context, defs) }
        val tops = withContext(Dispatchers.IO) {
            roots.flatMap { root -> PcFolderScan.topLevelFolders(root).map { root to it } }
        }
        val done = AtomicInteger()
        val enginePlanned = AtomicInteger()
        val engineFinished = AtomicInteger()
        fun showProgress() {
            val planned = enginePlanned.get()
            val engines = if (planned > 0) ", checking engines: ${engineFinished.get()} of $planned" else ""
            ScanActivity.set(SCAN_SOURCE, "Looking at PC game folders: ${done.get()} of ${tops.size}$engines")
        }
        val options = PcFolderScan.Options(
            systemsById = systems,
            cache = listingCache,
            cancelled = { job?.isActive == false },
            verdicts = verdicts,
            folderBudgetMs = ScanBudget.DEFAULT_TOP_FOLDER_BUDGET_MS,
            onEngineWork = { planned, finished ->
                enginePlanned.addAndGet(planned)
                engineFinished.addAndGet(finished)
                showProgress()
            },
        )
        val gate = Semaphore(SCAN_PARALLELISM)
        showProgress()
        val scanned = try {
            tops.map { (root, folder) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val top = try {
                            PcFolderScan.scanTopLevel(folder, defs, options, skip)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            android.util.Log.w(TAG, "Walking ${folder.absolutePath} for PC games failed", t)
                            // Unwalked, not empty: what the last walk found there is kept.
                            PcFolderScan.TopLevelFolder(folder, emptyList(), folder.lastModified(), skipped = true)
                        }
                        if (!top.skipped || top.slow) {
                            // A slow folder is logged too, with the reason, and not published:
                            // what the last walk found there is kept and the next scan retries it.
                            ScanLog.write(
                                label = "pc folder ${folder.absolutePath}",
                                games = top.games.size,
                                skipped = top.skips,
                                durationMs = top.work.millis,
                                note = top.work.describe(),
                                base = folder,
                            )
                        }
                        done.incrementAndGet()
                        showProgress()
                        ScannedFolder(
                            root = root.absolutePath,
                            topFolder = folder.absolutePath,
                            gameFolders = top.games.map { it.absolutePath },
                            mtime = top.mtime,
                            skipped = top.skipped,
                            skips = top.skips,
                        )
                    }
                }
            }.awaitAll()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { EngineVerdictStore.save(context) }
        }
        val skips = ScanSkips()
        for (folder in scanned) skips.addAll(folder.skips)
        // A scan that finds nothing and a scan that never ran look the
        // same from the library; this line is how they are told apart.
        ScanLog.write(
            label = "pc folders under " + rootPaths.joinToString(", "),
            games = scanned.sumOf { it.gameFolders.size },
            skipped = skips,
            durationMs = android.os.SystemClock.elapsedRealtime() - startedAt,
        )
        scanned
    }

    /** Tells the vendored folder scanner which folders are games: what [scanGameFolders] found, beside what it already had. */
    private fun adoptFoundFolders(rootPaths: List<String>, scanned: List<ScannedFolder>) {
        val found = scanned.flatMap { it.gameFolders }.toSet()
        runCatching {
            val current = app.gamenative.PrefManager.customGameManualFolders
            // A folder this walk skipped keeps the games the last walk
            // named under it; everything else under the roots is this
            // walk's answer.
            val unwalked = scanned.filter { it.skipped }.map { it.topFolder }
            val theirs = current.filterNot { manual ->
                rootPaths.any { manual.startsWith(it + "/") } && !manual.isUnder(unwalked)
            }
            val wanted = (theirs + found).toSet()
            if (wanted != current) app.gamenative.PrefManager.customGameManualFolders = wanted
        }.onFailure { android.util.Log.w(TAG, "Could not tell the folder scanner which folders are games", it) }
    }

    private const val TAG = "droidtop.PcLibrary"

    /**
     * The gamenative [LibraryItem] behind one droidtop PC entry id, which
     * is what Steam's own store screen (install, verify, DLC, downloads) is
     * written against — droidtop hosts that screen for Steam (docs/SPEC.md
     * 7i). The stores droidtop runs itself have no such screen: their
     * entries resolve to nothing here. Built from Steam's own row, so the
     * item's shape is what gamenative's LibraryViewModel gives the screen.
     */
    suspend fun libraryItemFor(context: Context, entryId: String): LibraryItem? {
        DroidtopGameIdStore.install(context)
        val nativeId = entryId.substringAfter(':')
        val dao = daos(context)
        return runCatching {
            when (entryId.substringBefore(':')) {
                "steam" -> dao.steamAppDao().getAllOwnedAppsAsList()
                    .firstOrNull { it.id.toString() == nativeId }?.toLibraryItem()
                // A folder game IS a LibraryItem already -- the scanner
                // produced the id this entry carries.
                "folder" -> CustomGameScanner.scanAsLibraryItems().firstOrNull { it.appId == nativeId }
                else -> null
            }
        }.getOrNull()
    }

    private fun SteamApp.toLibraryItem(): LibraryItem = LibraryItem(
        appId = "${GameSource.STEAM.name}_$id",
        name = name,
        iconHash = clientIconHash,
        capsuleImageUrl = runCatching { getCapsuleUrl() }.getOrDefault(""),
        headerImageUrl = headerUrl,
        heroImageUrl = runCatching { getHeroUrl() }.getOrDefault(""),
        gameSource = GameSource.STEAM,
        isInstalled = runCatching { SteamService.isAppInstalled(id) }.getOrDefault(false),
    )

    /**
     * The folder a scanner item names, or null. The scanner's appId is
     * "CUSTOM_GAME_<numeric id>"; the numeric half is what resolves back to
     * a folder.
     */
    private fun LibraryItem.scannerFolder(): String? =
        appId.substringAfterLast('_').toIntOrNull()
            ?.let { id -> runCatching { CustomGameScanner.findCustomGameById(id) }.getOrNull() }

    /**
     * A scanned folder's cover or icon, remembered by the folder's
     * modification time ([ART_PREFS]). Finding it is the vendored scanner's
     * business and not a cheap one: it lists the folder and every folder
     * directly under it, more than once, and may read the executable to
     * extract an icon -- per game, on every scan, for an answer that only
     * changes when the folder does. A folder whose time has not moved gets
     * its remembered answer.
     *
     * Only a FOUND image is remembered. The scanner extracts an icon in the
     * background after it first sees a game, possibly into a subfolder
     * whose change does not move this folder's time, so "nothing yet" has
     * to be asked again next time.
     */
    private fun cachedArt(context: Context, folderPath: String, find: () -> String?): String? {
        val prefs = context.applicationContext.getSharedPreferences(ART_PREFS, Context.MODE_PRIVATE)
        val mtime = File(folderPath).lastModified()
        val remembered = prefs.getString(folderPath, null)
        if (mtime != 0L && remembered != null && remembered.substringBefore('\t') == mtime.toString()) {
            return remembered.substringAfter('\t')
        }
        val art = find()
        if (mtime != 0L && art != null) prefs.edit().putString(folderPath, "$mtime\t$art").apply()
        return art
    }

    private const val ART_PREFS = "droidtop_pc_folder_art"

    /**
     * A loose folder of files the user pointed droidtop at — a GOG
     * offline installer's output, an itch download, a portable game.
     * These are "installed" by definition: the files are already there.
     */
    private fun LibraryItem.toGame(context: Context, root: String? = null, folderPath: String? = scannerFolder()): Game {
        // A custom game has no store CDN behind it, so its art is whatever
        // image sits in the folder rather than a remote URL.
        val localArt = folderPath?.let { path ->
            cachedArt(context, path) {
                runCatching {
                    CustomGameScanner.findCapsuleCoverForCustomGame(appId)
                        ?: CustomGameScanner.findIconFileForCustomGame(appId)
                }.getOrNull()
            }
        }
        // The scanner names a game by its raw folder name; the title is the
        // parsed one (docs/SPEC.md 7n), read from the whole path so
        // `Some Game/book3` is `Some Game`. The folder name stays on disk
        // and on the game page; nothing here reads the disk.
        val title = folderPath?.let { GameTitleParser.parse(it, root).title }?.takeIf { it.isNotBlank() } ?: name
        return Game(
            id = "folder:$appId",
            source = Source.FOLDER,
            nativeId = appId,
            title = title,
            installed = true,
            installPath = folderPath,
            sizeBytes = sizeBytes,
            artUrl = localArt,
            compatibility = compatibilityFor(title),
        )
    }
}

/**
 * This game as the shape engine detection consumes, or null when it
 * is not actually installed anywhere on this device.
 *
 * Detection needs the directory; the rest of it is what keeps the
 * game's store-side information alive once the engine entry claims
 * that directory and the `pc` entry is suppressed (see
 * `GameEngineDetector.engineOwnsInstall`).
 */
fun PcLibrary.Game.toStoreInstall(): StoreInstall? = installDir?.let { dir ->
    StoreInstall(installDir = dir, pcInfo = toPcInfo(), artworkUri = artUrl)
}

/**
 * The store-side facts a [dev.droidtop.library.LibraryEntry] carries.
 *
 * Built here rather than in `PcGameProvider` because both an entry it
 * returns itself and a [StoreInstall] handed to engine detection need
 * exactly the same values; two mappings would be two chances for a
 * merged entry to disagree with the one it replaced.
 */
fun PcLibrary.Game.toPcInfo(): PcInfo = PcInfo(
    source = source.displayName(),
    storeId = id,
    installed = installed,
    sizeBytes = sizeBytes,
    installPath = installPath,
    installedVersion = installedVersion,
    externalIds = externalIds,
    latestVersion = StoreUpdates.resultFor(id)?.latest,
    update = StoreUpdates.resultFor(id)?.update ?: StoreUpdate.UNKNOWN,
    compatibility = compatibility?.let {
        PcCompatibility(
            averageRating = it.averageRating,
            playableReports = it.playableReports,
            gpuPlayableReports = it.gpuPlayableReports,
            hasBeenTried = it.hasBeenTried,
            reportedNotWorking = it.reportedNotWorking,
        )
    },
)

fun PcLibrary.Source.displayName(): String = when (this) {
    PcLibrary.Source.STEAM -> PcStoreNames.STEAM
    PcLibrary.Source.GOG -> PcStoreNames.GOG
    PcLibrary.Source.EPIC -> PcStoreNames.EPIC
    PcLibrary.Source.AMAZON -> PcStoreNames.AMAZON
    PcLibrary.Source.ITCH -> PcStoreNames.ITCH
    PcLibrary.Source.FOLDER -> "Folder"
}
