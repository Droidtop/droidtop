package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.ContainerManager
import com.winlator.container.Shortcut
import dev.droidtop.library.EngineOverridePrefs
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.GameEngineDetector
import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.GameExecutableResolver
import dev.droidtop.library.WindowsLaunchResolver
import dev.droidtop.library.PcGameRuntimeRegistry
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.PcInfo
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.RunnerState
import dev.droidtop.library.withScrapedMetadata
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real "PC" games -- ES-DE's own `"pc"` system id (per direction: "PC", not
 * ES-DE's separate `"windows"` system, which is Linux `.desktop`-shortcut
 * launchers, a different real thing -- see [dev.droidtop.library.consoles.
 * ES_DE_CONSOLE_SYSTEMS]), themed the same way every other system already
 * is (droidtop reads `"pc"` like any other [dev.droidtop.library.consoles.
 * ConsoleSystemDef], no special-casing needed on the theming side).
 *
 * Discovery is real, not stubbed: [ContainerManager.loadShortcuts] is the
 * forked-in `com.winlator.container` tree's own actual mechanism for
 * enumerating installed Windows programs across every Wine prefix it
 * manages (real `.desktop`-style shortcut files under each container's
 * `getDesktopDir()`, the same real, kept-from-upstream ImageFS layout
 * [WineEngine]'s own doc comment describes retaining). This is real,
 * working code already forked in and compiling -- not fabricated.
 *
 * [launch] runs a [Shortcut] through the [WineEngine] seam, in the
 * shortcut's own prefix. It used to require a live
 * `dev.droidtop.runtime.PrimaryContainerSession` first and execute Wine
 * inside that droidspaces container, which meant a shortcut could only
 * ever be launched on a rooted device -- see [WineEngine] and
 * docs/SPEC.md 5b for why that was an accident rather than a design.
 * Per direction: droidtop isn't using gamenative wholesale -- extending
 * the forked `com.winlator.container` tree itself with real Linux-target
 * support (not just Wine prefixes) is real, separate future work, not
 * needed for this launch path.
 */
class PcGameProvider(
    private val context: Context,
    private val wineEngine: WineEngine = BionicWineEngine(context),
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = setOf(LibraryEntryKind.WINE_PROFILE)

    /**
     * The whole PC library, not just Wine prefixes: every game the
     * vendored gamenative-tux data layer knows about from Steam, GOG,
     * Epic, Amazon and scanned folders (via [PcLibrary]), plus the Wine
     * shortcuts this provider always listed.
     *
     * Both are included because they answer different questions. A store
     * row knows a game is OWNED and where it installed to; a Wine
     * shortcut knows a specific executable inside a prefix the user set
     * up by hand. A store game that also has a shortcut would otherwise
     * appear twice, so shortcuts pointing inside a known install
     * directory are dropped in favour of the richer store row.
     *
     * The same "one entry per game, whatever found it" rule applies
     * across providers, not just within this one: a store game whose
     * install directory engine detection recognises belongs to
     * [dev.droidtop.library.EngineGameProvider] and is not returned here
     * at all. See [dev.droidtop.library.GameEngineDetector.engineOwnsInstall].
     */
    override suspend fun scan(): List<LibraryEntry> {
        val found = mutableListOf<LibraryEntry>()
        walk { step -> if (step is dev.droidtop.library.ScanStep.Segment) found += step.entries }
        return found
    }

    /**
     * The walk, a part at a time (docs/SPEC.md 7g): the stores answer as
     * one part, because a store's database is not under a games root and
     * has no folders to answer in; each top-level folder of each games
     * root answers as its own part, the same unit
     * [dev.droidtop.library.EngineGameProvider] uses, so that one folder's
     * games can be replaced in the index without touching the rest.
     */
    override fun scanProgressive(): kotlinx.coroutines.flow.Flow<dev.droidtop.library.ScanStep> =
        kotlinx.coroutines.flow.channelFlow { walk { step -> send(step) } }

    /**
     * The slow rebuild pass (docs/SPEC.md 7g, step 4): the same walk,
     * except that when the stores' stamp ([storeStamp]) still matches
     * [knownMtimes], the store part is left alone and so is every
     * top-level folder whose own modification time still matches. When
     * the stores moved, everything is walked: the store part suppresses
     * Wine shortcuts against every folder game's install directory, so it
     * needs them all.
     *
     * The first round in a process walks everything too. The walk is
     * also what records the install directories engine detection reads
     * ([PcLibrary.knownInstalls]); a process that has loaded the index
     * but not walked has none, and a skipped part would leave them out.
     *
     * This used to be the default, a full re-read of every store and
     * every folder every 30 minutes for a PC library that had not
     * changed. [pauseMs] is not taken: the folder walk is one pass in
     * [PcLibrary.folderGames], not a part at a time.
     */
    override fun slowRebuildProgressive(
        knownMtimes: Map<dev.droidtop.library.PartRef, Long>,
        pauseMs: Long,
    ): kotlinx.coroutines.flow.Flow<dev.droidtop.library.ScanStep> =
        kotlinx.coroutines.flow.channelFlow {
            walk(known = knownMtimes.takeIf { walkedInThisProcess }) { step -> send(step) }
        }

    /** Whether a whole walk has finished since the process started; see [slowRebuildProgressive]. */
    @Volatile
    private var walkedInThisProcess = false

    /**
     * The store part's change stamp: one number over what the store
     * part reads. That is gamenative's store database (its file and its
     * write-ahead log, which move on every write: a sign-in, a library
     * sync, an install), each Wine prefix's Desktop folder (a shortcut
     * added or removed), and the folders the user gave the vendored
     * scanner outside droidtop's roots, along with droidtop's roots
     * themselves, which decide which of those count. What it does not
     * see: the compatibility cache and a change to the engine rules;
     * "Rescan library" is the answer there.
     *
     * One `stat` per file or folder, read BEFORE the part is walked, so
     * a change during the walk moves the stamp past what the index
     * keeps and the next round walks it again.
     */
    private fun storeStamp(): Long {
        val paths = sortedSetOf<String>()
        val database = context.getDatabasePath(app.gamenative.db.DATABASE_NAME)
        paths += database.absolutePath
        paths += database.absolutePath + "-wal"
        runCatching { ContainerManager(context).containers.forEach { paths += it.desktopDir.absolutePath } }
        runCatching { paths += app.gamenative.PrefManager.customGameManualFolders }
        runCatching { paths += app.gamenative.PrefManager.customGameScanRoots }
        val roots = dev.droidtop.library.GamesRoots.current(context).map { it.absolutePath }
        var stamp = 17L
        for (root in roots) stamp = 31 * stamp + root.hashCode()
        for (path in paths) {
            // Inside a root is the folder walk's part, and its stamp.
            if (roots.any { path.startsWith("$it/") }) continue
            stamp = 31 * (31 * stamp + path.hashCode()) + File(path).lastModified()
        }
        return if (stamp == 0L) 1L else stamp
    }

    private suspend fun walk(
        // The slow pass' stamps; null walks everything.
        known: Map<dev.droidtop.library.PartRef, Long>? = null,
        emit: suspend (dev.droidtop.library.ScanStep) -> Unit,
    ) {
        val storeStamp = storeStamp()
        val storeUnchanged = known?.get(dev.droidtop.library.PartRef(dev.droidtop.library.ScanStep.WHOLE, null)) == storeStamp
        val skip: (File, Long) -> Boolean = if (known == null || !storeUnchanged) {
            { _, _ -> false }
        } else {
            { folder, mtime ->
                mtime != 0L && known[dev.droidtop.library.PartRef(folder.absolutePath, folder.parentFile?.absolutePath)] == mtime
            }
        }
        val folderGroups = runCatching { PcLibrary.folderGames(context, skip) }
            .onFailure { android.util.Log.w("droidtop.PcGameProvider", "Reading the PC folders failed", it) }
            .getOrDefault(emptyList())
        val engineDefs = runCatching { EnginesDatabase.defs(context) }.getOrDefault(emptyList())
        if (!storeUnchanged) emitStorePart(storeStamp, folderGroups, engineDefs, emit)
        for (group in folderGroups) {
            if (group.skipped) continue
            emit(
                dev.droidtop.library.ScanStep.Segment(
                    key = group.topFolder,
                    root = group.root,
                    entries = group.games.notOwnedByAnEngine(engineDefs).map { it.toLibraryEntry() }.withEntryMetadata(),
                    folderMtime = group.mtime.takeIf { it != 0L },
                ),
            )
        }
        for ((root, groups) in folderGroups.groupBy { it.root }) {
            emit(dev.droidtop.library.ScanStep.RootDone(root, groups.map { it.topFolder }))
        }
        if (folderGroups.none { it.skipped } && !storeUnchanged) walkedInThisProcess = true
    }

    private suspend fun emitStorePart(
        storeStamp: Long,
        folderGroups: List<PcLibrary.FolderGroup>,
        engineDefs: List<dev.droidtop.library.EngineDef>,
        emit: suspend (dev.droidtop.library.ScanStep) -> Unit,
    ) {
        val storeGames = runCatching { PcLibrary.storeGames(context) }
            .onFailure { android.util.Log.w("droidtop.PcGameProvider", "Reading the PC stores failed", it) }
            .getOrDefault(emptyList())
        // Shortcut suppression measures against EVERY PC game's install
        // directory, engine-owned ones included: a Wine shortcut pointing
        // inside a Ren'Py game's folder is the same duplicate by another
        // route.
        val installDirs = (storeGames + folderGroups.flatMap { it.games })
            .mapNotNull { it.installPath?.takeIf(String::isNotBlank) }
        val shortcutEntries = runCatching { ContainerManager(context).loadShortcuts() }
            .getOrDefault(emptyList())
            .filterNot { shortcut -> installDirs.any { shortcut.path.startsWith(it) } }
            .map { it.toLibraryEntry() }
        dev.droidtop.library.ScanLog.write(
            "pc library: ${storeGames.size} store games, " +
                "${folderGroups.sumOf { it.games.size }} folder games in ${folderGroups.size} folders",
        )
        emit(
            dev.droidtop.library.ScanStep.Segment(
                key = dev.droidtop.library.ScanStep.WHOLE,
                entries = (storeGames.notOwnedByAnEngine(engineDefs).map { it.toLibraryEntry() } + shortcutEntries)
                    .withEntryMetadata(),
                folderMtime = storeStamp,
            ),
        )
    }

    /**
     * The store/engine ownership rule (docs/SPEC.md 7g), asked of the
     * folder rather than of whichever provider ran first: a game whose
     * install directory engine detection recognises is
     * [dev.droidtop.library.EngineGameProvider]'s, and returning a second
     * entry for it here is the duplicate the user actually saw -- one copy
     * routed to enginehost, the other straight past engine detection to
     * Wine. The engine entry is the one that survives because enginehost
     * runs a Ren'Py or RPG Maker game natively, and because it is the
     * route that works on this target at all (5b). Nothing is lost with
     * the entry: PcLibrary hands the same install directories to
     * EngineGameProvider as StoreInstalls, which fold this entry's PcInfo
     * and store art onto the surviving one.
     */
    private fun List<PcLibrary.Game>.notOwnedByAnEngine(
        engineDefs: List<dev.droidtop.library.EngineDef>,
    ): List<PcLibrary.Game> = filterNot { game ->
        val dir = game.installDir ?: return@filterNot false
        GameEngineDetector.engineOwnsInstall(dir, engineDefs) { folder ->
            EngineOverridePrefs.engineFor(context, folder.absolutePath)
        }
    }

    /**
     * The same scraped-metadata merge every other provider applies (see
     * withScrapedMetadata): a PC game's scraped description and cover live
     * in a game_metadata row keyed by this entry's id, and nothing else
     * can carry them back here -- a store row's id is a store id, not a
     * file under a games root, so there is no downloaded_media lookup to
     * fall back on.
     */
    private suspend fun List<LibraryEntry>.withEntryMetadata(): List<LibraryEntry> =
        withScrapedMetadata(dev.droidtop.library.consoles.RomDatabase.get(context).romDao(), scrapedArtworkFirst = true)

    private fun PcLibrary.Game.toLibraryEntry(): LibraryEntry = LibraryEntry(
        id = id,
        title = title,
        kind = LibraryEntryKind.WINE_PROFILE,
        systemId = "pc",
        // Store art is a remote URL; a scanned folder's is a local file.
        // Both are just a URI to the renderer.
        artworkUri = artUrl,
        // One mapping of a store row's facts, shared with the
        // StoreInstall engine detection consumes -- see PcLibrary.toPcInfo.
        pcInfo = toPcInfo(),
    )

    private fun Shortcut.toLibraryEntry(): LibraryEntry = LibraryEntry(
        id = file.absolutePath,
        title = name,
        kind = LibraryEntryKind.WINE_PROFILE,
        systemId = "pc",
        artworkUri = iconFile?.takeIf { it.isFile }?.absolutePath,
        // A hand-made shortcut inside a prefix the user built: installed
        // by definition, and no store behind it to know a size.
        pcInfo = PcInfo(source = "Wine", installed = true),
    )

    override suspend fun launch(entry: LibraryEntry) {
        // A store/folder entry's id is "<source>:<nativeId>", not a
        // shortcut path, so it launches through the PC runtime seam
        // against its own install directory instead.
        if (entry.id.substringBefore(':') in STORE_ID_PREFIXES) {
            launchStoreGame(entry)
            return
        }

        val shortcut = ContainerManager(context).loadShortcuts().find { it.file.absolutePath == entry.id }
            ?: error("Can't launch ${entry.title}: no shortcut found at ${entry.id} (may have been deleted).")

        // The shortcut names its own prefix, so this launches in the
        // container the user actually made the shortcut in rather than
        // in whichever one happens to be first.
        val result = wineEngine.launch(
            prefix = shortcut.container,
            target = shortcut.path,
            workingDir = shortcut.container.rootDir,
        )
        check(result.succeeded) { "Launching ${entry.title} failed: ${result.detail}" }
    }

    /**
     * Runs a store-owned or folder-scanned game through the same
     * [PcGameRuntime] seam engine games already use, so a Windows title
     * from GOG launches exactly the way one detected in a games folder
     * does -- one launch path, not one per store.
     *
     * WHICH runner is the "Runs with" row's answer
     * ([dev.droidtop.library.PcRunnerOptions]), not a second rule kept
     * here. This used to pick a native Linux build whenever the folder
     * had one, whatever the device could do, so on a console with no
     * Linux container a game shipping both builds failed with "no live
     * container session" while its detail screen said it ran with Wine.
     * The availability model already knows both: a native Linux build
     * wins when it can actually run (docs/SPEC.md 5a), Wine otherwise,
     * and the user's per-game choice beats either.
     *
     * Every failure names something the user can act on: not installed,
     * no runtime, or an executable that could not be identified.
     */
    private suspend fun launchStoreGame(entry: LibraryEntry) {
        val installPath = entry.pcInfo?.installPath?.takeIf { it.isNotBlank() }
            ?: error("Can't launch ${entry.title}: it isn't installed yet.")
        val gameRoot = File(installPath)
        check(gameRoot.isDirectory) { "Can't launch ${entry.title}: ${gameRoot.absolutePath} is missing." }

        val runtime = PcGameRuntimeRegistry.runtime
            ?: error("Can't launch ${entry.title}: no PC runtime is registered in this build.")

        // Folder listings, the package manager and enginehost's provider:
        // never on whatever thread the launch was asked from.
        val resolved = withContext(Dispatchers.IO) {
            PcRunnerOptions.resolvedFor(context, entry, PcRunnerOptions.forEntry(context, entry))
        } ?: error(
            "Can't launch ${entry.title}: there's no Windows executable or native Linux build in " +
                "${gameRoot.absolutePath}.",
        )
        val option = resolved.option
        check(option.state != RunnerState.NOT_ON_THIS_DEVICE) {
            "Can't launch ${entry.title}. ${option.reason ?: "${resolved.label} isn't available on this device"}."
        }

        val result = when (option.strategy) {
            GameLaunchStrategy.LINUX_CONTAINER -> {
                val linux = GameExecutableResolver.linuxExecutable(gameRoot)
                    ?: error(
                        "Can't launch ${entry.title}: couldn't identify which Linux launcher to run in " +
                            "${gameRoot.absolutePath}. Pick one explicitly for this game.",
                    )
                runtime.launchLinux(linux, gameRoot)
            }
            GameLaunchStrategy.WINE_PREFIX -> {
                val windows = WindowsLaunchResolver.resolve(context, entry.id, gameRoot)
                    ?: error(
                        "Can't launch ${entry.title}: couldn't identify which executable to run in " +
                            "${gameRoot.absolutePath}. Pick one explicitly for this game.",
                    )
                runtime.launchWindows(windows.executable, gameRoot, windows.workingDir, windows.arguments)
            }
            // An engine this provider does not own (see
            // notOwnedByAnEngine) cannot resolve here; saying so beats
            // launching it through a runner it was never shown with.
            else -> error("Can't launch ${entry.title} with ${resolved.label} from the PC library.")
        }
        check(result.succeeded) { "Launching ${entry.title} failed: ${result.detail}" }
    }

    private companion object {
        val STORE_ID_PREFIXES = setOf("steam", "gog", "epic", "amazon", "folder")
    }
}
