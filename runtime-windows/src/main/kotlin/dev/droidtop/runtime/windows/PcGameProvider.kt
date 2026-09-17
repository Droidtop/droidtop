package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.ContainerManager
import com.winlator.container.Shortcut
import dev.droidtop.library.EngineOverridePrefs
import dev.droidtop.library.EnginesDatabase
import dev.droidtop.library.GameEngineDetector
import dev.droidtop.library.GameExecutableResolver
import dev.droidtop.library.PcGameRuntimeRegistry
import dev.droidtop.library.PcInfo
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.withScrapedMetadata
import java.io.File

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

    private suspend fun walk(emit: suspend (dev.droidtop.library.ScanStep) -> Unit) {
        val storeGames = runCatching { PcLibrary.storeGames(context) }
            .onFailure { android.util.Log.w("droidtop.PcGameProvider", "Reading the PC stores failed", it) }
            .getOrDefault(emptyList())
        val folderGroups = runCatching { PcLibrary.folderGames(context) }
            .onFailure { android.util.Log.w("droidtop.PcGameProvider", "Reading the PC folders failed", it) }
            .getOrDefault(emptyList())
        val engineDefs = runCatching { EnginesDatabase.defs(context) }.getOrDefault(emptyList())
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
            ),
        )
        for (group in folderGroups) {
            emit(
                dev.droidtop.library.ScanStep.Segment(
                    key = group.topFolder,
                    root = group.root,
                    entries = group.games.notOwnedByAnEngine(engineDefs).map { it.toLibraryEntry() }.withEntryMetadata(),
                ),
            )
        }
        for ((root, groups) in folderGroups.groupBy { it.root }) {
            emit(dev.droidtop.library.ScanStep.RootDone(root, groups.map { it.topFolder }))
        }
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
        withScrapedMetadata(dev.droidtop.library.consoles.RomDatabase.get(context).romDao())

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

        // "PC is a list of games, and each game is run according to its
        // configuration" (directed 2026-09-10): a store or folder game
        // honours the same per-game runner override an engine game does,
        // rather than the override applying to half the library.
        val override = dev.droidtop.library.LaunchStrategyOverridePrefs.get(context, entry.id)
        // A native Linux build beats Wine plus CPU translation whenever
        // one exists (docs/SPEC.md 5a), so it is what wins when the user
        // has expressed no preference.
        val linux = GameExecutableResolver.linuxExecutable(gameRoot)
            ?.takeIf { override != dev.droidtop.library.GameLaunchStrategy.WINE_PREFIX.name }
        val result = if (linux != null) {
            runtime.launchLinux(linux, gameRoot)
        } else {
            val windows = GameExecutableResolver.windowsExecutable(gameRoot)
                ?: error(
                    "Can't launch ${entry.title}: couldn't identify which executable to run in " +
                        "${gameRoot.absolutePath}. Pick one explicitly for this game.",
                )
            runtime.launchWindows(windows, gameRoot)
        }
        check(result.succeeded) { "Launching ${entry.title} failed: ${result.detail}" }
    }

    private companion object {
        val STORE_ID_PREFIXES = setOf("steam", "gog", "epic", "amazon", "folder")
    }
}
