package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.resolveSystem
import java.io.File
import java.io.RandomAccessFile

/** Which engine a game folder was built with — decoupled from how it gets launched (see [GameLaunchStrategy]/[GameLaunchStrategyResolver]): several launch paths can exist for the same engine. */
enum class GameEngine {
    RENPY, RPG_MAKER_MV, RPG_MAKER_MZ, RPG_MAKER_VX_ACE, RPG_MAKER_VX, RPG_MAKER_XP,
    RPG_MAKER_2000_2003, KIRIKIRI,
    AUGUST, BURIKO, CATSYSTEM2, CMVS, FLASH_AIR, GODOT, TWINE, UNREAL, UNITY,
}

/**
 * Signatures ported from the user's own Pythia project
 * (G:\Support\GameManagement\RenPyPatch\pythia\pythia\plugin_sources\engine\),
 * verified there against real games in their library, not guessed:
 *
 * - Ren'Py: `renpy/` and `game/` subdirectories both present directly
 *   under the game root (Pythia's `RenpyEnginePlugin.is_root`).
 * - RPG Maker MV: `js/rpg_core.js`, or `www/js/rpg_core.js` if exported
 *   with the `www/` wrapper.
 * - RPG Maker MZ: same shape, `rmmz_core.js`.
 * - RPG Maker VX Ace: a filename containing `.rgss3a` anywhere, not just
 *   as an exact suffix — Pythia's own fix for a real install where a
 *   patcher had renamed the archive to `Game.rgss3a.old`.
 * - Kirikiri/KAG3: any `.xp3` file (the engine's own data-archive format)
 *   directly in the game root.
 * - AUGUST engine: at least 2 directories whose name starts with "aug"
 *   (case-insensitive) — Pythia requires more than one to avoid a false
 *   positive on a single coincidentally-named folder.
 * - Buriko General Interpreter (BGI/Ethornell): `BGI.gdb` or `BGI.hvl`.
 * - CatSystem2: `cs2conf.dll`.
 * - CMVS: `cmvs32.exe`, `cmvs64.exe`, or `cmvs.cfg`.
 * - Flash (Adobe AIR package): `META-INF/` directory plus a `mimetype` file.
 * - Godot: a loose `.pck` file, OR an executable (`.exe`/`.x86_64`/`.x86`/
 *   extensionless) whose last 4 bytes are the ASCII magic `GDPC` (Godot's
 *   embedded-pack export) with a valid offset in the preceding 8
 *   little-endian bytes — Pythia's own real fix for an export shape the
 *   loose-`.pck` check alone can't see, verified against a real install.
 *   Only ever reads the last 12 bytes of a candidate file, not the whole
 *   thing, so this stays cheap against multi-gigabyte executables.
 * - Twine/HTML: an `.html` file in the game root whose first 512KB
 *   contains `<tw-storydata`, `twinejs`, `SugarCube`, or `Harlowe` — a
 *   real gap Pythia closed after finding real games with no matching
 *   engine plugin at all.
 * - Unreal Engine: an `Engine/Binaries` directory.
 * - Unity: `UnityPlayer.dll`/`.so`/`.dylib` present up to 3 folders deep —
 *   Pythia's own real fix for a Linux export (`.so` instead of `.dll`) and
 *   a real install packaging the runtime several folders down.
 *
 * RPG Maker XP and VX are deliberately not included: Pythia never
 * implemented them either, for the same reason — no real sample to
 * verify a signature against yet. Guessing one from a game's file
 * extensions rather than a confirmed install isn't a standard worth
 * dropping here just because this is a different project.
 *
 * - RPG Maker 2000/2003: `RPG_RT.exe` or `RPG_RT.ldb` (the engine's own
 *   database file) directly in the game root — the real signature
 *   EasyRPG Player's own project detection uses (confirmed against
 *   EasyRPG's public docs, not guessed), closing the detection gap
 *   `droidtop-platforms/engines-database.json` flagged: droidtop only
 *   ever launched RM2k/2k3 via a manually-added Custom Player before
 *   this, since the engine simply wasn't recognized. `RPG_RT.exe` is a
 *   real Windows executable, so the existing `hasWindowsExecutable`
 *   check already offers [GameLaunchStrategy.WINE_PREFIX] for it with
 *   no resolver change — the primary real path stays the EasyRPG
 *   Player entries in players-database.json (systemId
 *   "rpgmaker-2000-2003"), reached once this folder is recognized as a
 *   game at all.
 */
object GameEngineDetector {
    /**
     * Registry-driven classification (docs/SPEC.md §7e2b v4): the
     * database's rules decide, in the database's own row order, and a
     * row whose engine id this app doesn't know is skipped. Only the
     * byte-magic probes below ([builtinProbe]) stay code; the database
     * decides where they apply.
     */
    fun detect(folder: File, defs: List<EngineDef>): GameEngine? =
        defs.firstOrNull { def ->
            def.engine != null && EngineDetectRules.matches(def.detect, folder, ::builtinProbe)
        }?.engine

    private fun builtinProbe(name: String, folder: File): Boolean = when (name) {
        "godot" -> isGodot(folder)
        "twine" -> isTwine(folder)
        "unity" -> isUnity(folder)
        // An unknown builtin fails its rule rather than matching: a
        // newer database referencing a probe this app doesn't ship must
        // not misdetect.
        else -> false
    }

    private val GODOT_EXECUTABLE_SUFFIXES = setOf("exe", "x86_64", "x86", "")
    private val GODOT_EMBEDDED_PCK_MAGIC = byteArrayOf('G'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte())

    private fun isGodot(folder: File): Boolean {
        val candidates = folder.listFiles()?.filter { it.isFile } ?: return false
        if (candidates.any { it.extension.lowercase() == "pck" }) return true
        return candidates.any { it.extension.lowercase() in GODOT_EXECUTABLE_SUFFIXES && hasEmbeddedPckTrailer(it) }
    }

    private fun hasEmbeddedPckTrailer(file: File): Boolean {
        val size = file.length()
        if (size < 12) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.seek(size - 4)
                raf.readFully(magic)
                if (!magic.contentEquals(GODOT_EMBEDDED_PCK_MAGIC)) return false

                val offsetBytes = ByteArray(8)
                raf.seek(size - 12)
                raf.readFully(offsetBytes)
                // Little-endian u64, per Godot's own export format.
                var offset = 0L
                for (i in 7 downTo 0) offset = (offset shl 8) or (offsetBytes[i].toLong() and 0xFF)
                offset in 1 until size
            }
        } catch (e: java.io.IOException) {
            false
        }
    }

    private const val TWINE_READ_WINDOW = 512 * 1024
    private val TWINE_MARKERS = listOf("<tw-storydata", "twinejs", "SugarCube", "Harlowe").map { it.toByteArray(Charsets.US_ASCII) }

    private fun isTwine(folder: File): Boolean =
        folder.listFiles()?.filter { it.isFile && it.extension.lowercase() == "html" }
            ?.any { looksLikeTwineExport(it) } == true

    private fun looksLikeTwineExport(file: File): Boolean {
        val chunk = try {
            file.inputStream().use { it.readNBytes(TWINE_READ_WINDOW) }
        } catch (e: java.io.IOException) {
            return false
        }
        return TWINE_MARKERS.any { marker -> chunk.indexOfSubsequence(marker) >= 0 }
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private val UNITY_PLAYER_FILENAMES = setOf("UnityPlayer.dll", "UnityPlayer.so", "UnityPlayer.dylib")

    private fun isUnity(folder: File): Boolean = hasUnityPlayerRuntime(folder, maxDepth = 3)

    private fun hasUnityPlayerRuntime(folder: File, maxDepth: Int): Boolean {
        val entries = folder.listFiles() ?: return false
        if (entries.any { it.isFile && it.name in UNITY_PLAYER_FILENAMES }) return true
        if (maxDepth <= 0) return false
        return entries.any { it.isDirectory && hasUnityPlayerRuntime(it, maxDepth - 1) }
    }

    /**
     * Every immediate subdirectory of [root] that [detect]s as some
     * [GameEngine] -- skips any subdirectory whose name already resolves
     * to a known console system (see [dev.droidtop.library.consoles.resolveSystem]),
     * since those are provably ROM folders, not engine game folders. Real,
     * not theoretical: a real ROMs folder's "j2me" system directory had
     * 18,126 entries, and [isKirikiri]/[isRpgMakerVxAce] each do a full
     * `listFiles()` scan looking for signature files -- wastefully slow on
     * a folder this large, and on external/SD-card storage specifically,
     * slow enough to be the real cause of a reported frozen-UI bug (see
     * also [dev.droidtop.library.Library.scanAll]'s own fix for the other
     * half of that: running this on the wrong dispatcher entirely).
     *
     * Checks one level deeper when the top folder itself doesn't detect --
     * a real, confirmed case, not theoretical: a real Ren'Py download
     * ("BeingADik/BeingADIK-0.8.3-scrappy/{renpy,game}") had its actual
     * `renpy`/`game` markers one folder deeper than the outer, nicely-named
     * folder droidtop wants to show as the game's title (some Ren'Py
     * distribution zips wrap everything in an extra version-named folder,
     * others don't -- checked against several real downloads in the same
     * library this session, inconsistent, so this has to handle both
     * shapes rather than assuming either one). [DetectedGame.displayFolder]
     * stays the outer folder either way (the nicer name); only
     * [DetectedGame.gameRoot] moves to wherever the markers actually are.
     */
    fun scan(
        root: File,
        systemsById: Map<String, ConsoleSystemDef>,
        defs: List<EngineDef>,
        // The user's explicit per-folder engine assignment (docs/SPEC.md
        // §7e2b: "users should be able to specify if we don't know") --
        // wins over every rule, exactly like SystemOverridePrefs wins
        // over folder-name resolution for console folders.
        override: (File) -> GameEngine? = { null },
    ): List<DetectedGame> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && resolveSystem(it.name, systemsById) == null }
            .mapNotNull { top -> detectGame(top, defs, override) }

    /**
     * Is this ONE folder a game, and if so which engine and where are its
     * markers -- the single-folder half of [scan], extracted so that
     * everything asking "is this folder an engine game" asks the same
     * question. Three places did their own slightly different version of
     * it before: [scan]'s loop, [EngineGameProvider.resolveEntry]'s
     * re-detect, and (as of the store/engine ownership rule) the PC
     * provider's suppression check.
     *
     * The nested search is deliberately name-ordered rather than in
     * [File.listFiles] order, which is filesystem-defined and can differ
     * between scans: a wrapper folder containing two detectable
     * subfolders must resolve to the same [DetectedGame] every time.
     */
    fun detectGame(
        folder: File,
        defs: List<EngineDef>,
        override: (File) -> GameEngine? = { null },
    ): DetectedGame? {
        override(folder)?.let { return DetectedGame(folder, folder, it) }
        detect(folder, defs)?.let { return DetectedGame(folder, folder, it) }
        return (folder.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .mapNotNull { nested -> detect(nested, defs)?.let { DetectedGame(folder, nested, it) } }
            .firstOrNull()
    }

    /**
     * THE store/engine ownership rule, in one place (docs/SPEC.md §7g).
     *
     * A store-installed game that engine detection recognises belongs to
     * [EngineGameProvider], and the PC provider must not return a second
     * entry for it: the engine entry routes to enginehost, which runs a
     * Ren'Py or RPG Maker game natively, while the `pc` entry consults no
     * engine detection at all and goes straight to Wine plus CPU
     * translation -- strictly worse where both exist, and on this target
     * currently non-functional (§5b). A store game this returns false for
     * is a genuine Windows title and keeps its `pc` entry and its Wine
     * route.
     *
     * Decided from the FOLDER, by both providers, so the answer does not
     * depend on which provider scanned first (they do not even scan
     * together -- the handheld shell runs Games and Apps as two
     * independent scans) and does not change between scans.
     *
     * What the suppressed entry knew is not lost: the same install
     * directory is handed to [EngineGameProvider] as a
     * [dev.droidtop.library.StoreInstall], and
     * [dev.droidtop.library.withStoreInstall] folds its [PcInfo] and
     * store art onto the surviving engine entry.
     */
    fun engineOwnsInstall(
        installDir: File,
        defs: List<EngineDef>,
        override: (File) -> GameEngine? = { null },
    ): Boolean = detectGame(installDir, defs, override) != null
}

/**
 * [displayFolder] is what a user picked as the game's own folder (used for
 * [dev.droidtop.library.LibraryEntry.id]/title) -- [gameRoot] is wherever
 * the engine's real marker files (and so the real launch file) actually
 * live, which is the same folder for almost every real game but not
 * always (see [GameEngineDetector.scan]'s own doc comment).
 */
data class DetectedGame(val displayFolder: File, val gameRoot: File, val engine: GameEngine)

/**
 * Ways droidtop knows of to actually run a detected game — deliberately
 * plural per direction ("we don't really want to hardcode limited paths
 * ... as wide a list of variety as possible"): the same [GameEngine] can
 * be reachable through more than one of these depending on what's
 * actually on disk and what's actually installed, and droidtop should
 * offer all of them, not assume one.
 */
enum class GameLaunchStrategy {
    /**
     * Hand off to `dev.enginehost` — see [EngineHost]. The real default
     * for the engines it covers (the engines database's enginehost mappings).
     */
    ENGINEHOST,

    /** Hand off to the third-party Kirikiroid2/krkr2 interpreter — see [Kirikiroid2]. Real, wired today, but generic-open-only (opens the app, not a specific game — see that class's own doc comment for why). */
    KIRIKIROID2,

    /** Run inside a Wine prefix (`:runtime-windows`'s `WineEngine`) — works for any engine's Windows/.exe export. Really launches, through the `PcGameRuntime` seam `:app` fills in (see `launchOnPcRuntime`) -- `library-core` reaches a live prefix without needing a Wine engine of its own. */
    WINE_PREFIX,

    /** Run as a native process inside a Linux container (`runtime-common`'s `NativeLinuxGameSession`) — only meaningful for an engine/export that actually has a Linux build. Really launches, through the same `PcGameRuntime` seam as [WINE_PREFIX]. */
    LINUX_CONTAINER,
}

/** For a real per-entry picker UI — see [EngineGameProvider.availableStrategies]. */
fun GameLaunchStrategy.displayName(): String = when (this) {
    GameLaunchStrategy.ENGINEHOST -> "enginehost"
    GameLaunchStrategy.KIRIKIROID2 -> "Kirikiroid2"
    GameLaunchStrategy.WINE_PREFIX -> "Wine"
    GameLaunchStrategy.LINUX_CONTAINER -> "Linux container"
}

/**
 * Determines which [GameLaunchStrategy] options are actually plausible for
 * one detected game folder — real per-entry facts, not a fixed table keyed
 * only on [GameEngine]. Two games of the same engine can have different
 * available strategies (one shipped a Linux build, the other didn't).
 */
object GameLaunchStrategyResolver {
    /**
     * [engineHostInstalled]/[engineHostEngineVersion] are plain facts the
     * caller computes from a real `Context` before calling this
     * ([EngineHost.isInstalled]/[resolveEngineVersion]) — deliberately,
     * matching how [kirikiroid2Installed] already works: this resolver
     * stays pure/Android-free so [GameLaunchStrategyResolverTest]'s plain
     * JVM unit tests keep working with zero Robolectric/mocking setup, not
     * a Context threaded in just for this one strategy.
     */
    fun resolve(
        engine: GameEngine,
        folder: File,
        kirikiroid2Installed: Boolean = false,
        engineHostInstalled: Boolean = false,
        engineHostEngineVersion: String? = null,
        // Whether enginehost's own UID can actually read the game folder
        // -- see EngineHost.canReachGameFolder. Defaults true so the pure
        // JVM tests, which model real readable game folders, stay
        // unchanged.
        engineHostCanReachFolder: Boolean = true,
        // Database-declared priority (EnginesDatabase, docs/SPEC.md §7e2):
        // AVAILABILITY stays this function's own real checks below -- the
        // order only decides which available strategy wins. Null keeps the
        // historical append order (and the pure-JVM unit tests untouched).
        preferredOrder: List<GameLaunchStrategy>? = null,
        // Whether the engines database declares an enginehost mapping
        // for [engine]. Defaults true so the pure JVM tests, which model
        // registry-covered engines, stay unchanged (same convention as
        // engineHostCanReachFolder above).
        enginehostSupported: Boolean = true,
    ): List<GameLaunchStrategy> {
        val strategies = mutableListOf<GameLaunchStrategy>()
        // Only offered when there's an actual engineVersion to launch
        // with -- a folder with no enginehost.json of its own and no
        // per-folder override set isn't a real available option yet, see
        // resolveEngineVersion's own doc comment.
        if (engineHostInstalled && engineHostCanReachFolder && enginehostSupported &&
            (File(folder, "enginehost.json").isFile || engineHostEngineVersion != null)
        ) {
            strategies += GameLaunchStrategy.ENGINEHOST
        }
        if (kirikiroid2Installed && engine == GameEngine.KIRIKIRI) strategies += GameLaunchStrategy.KIRIKIROID2
        if (hasWindowsExecutable(folder)) strategies += GameLaunchStrategy.WINE_PREFIX
        // Kirikiri (the original commercial engine) is Windows-native with
        // no official Linux port of the *engine itself* (confirmed, not
        // assumed) -- LINUX_CONTAINER (running a game's own Linux export
        // inside a container) is never offered for it regardless of folder
        // contents. Unrelated to Kirikiroid2 above, a real independent
        // third-party interpreter rather than an official Linux build of
        // the engine.
        if (engine != GameEngine.KIRIKIRI && hasLinuxBuild(folder)) strategies += GameLaunchStrategy.LINUX_CONTAINER
        if (preferredOrder == null) return strategies
        // Reorder by the database's declared priority; anything available
        // but undeclared keeps its place after the declared ones.
        return preferredOrder.filter { it in strategies } + strategies.filterNot { it in preferredOrder }
    }

    private fun hasWindowsExecutable(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.extension.lowercase() == "exe" } == true

    /**
     * Real, checkable evidence that this folder contains a native Linux
     * build -- never assumed present just because an engine generally
     * supports Linux. Two independent shapes, both engine-agnostic:
     *
     * - `lib/<prefix>linux-<arch>/` -- Ren'Py's own interpreter layout
     *   (the same folder-naming rule the user's Pythia project inspects).
     *   This was the only shape checked before, which silently excluded
     *   every build that ships a bare ELF and no `lib/` at all.
     * - A `<GameName>.x86_64` / `<GameName>.x86` file in the root -- the
     *   conventional extension for a Linux ELF launcher. This is the
     *   same file [GameExecutableResolver.linuxExecutable] then has to
     *   run, so the two stay in step: offering LINUX_CONTAINER for a
     *   folder whose launcher the resolver cannot name would just move
     *   the failure later.
     */
    private fun hasLinuxBuild(folder: File): Boolean {
        val entries = folder.listFiles() ?: return false
        if (entries.any { it.isFile && it.extension.lowercase() in LINUX_LAUNCHER_EXTENSIONS }) return true
        return File(folder, "lib").listFiles()?.any { it.isDirectory && it.name.contains("linux") } == true
    }

    private val LINUX_LAUNCHER_EXTENSIONS = setOf("x86_64", "x86")
}

/** The [LibraryEntryKind] an engine's games appear under. Internal rather than private: the PC/engine scraper resolves an entry's engine back out of its kind to name its `downloaded_media` folder. */
internal fun GameEngine.toLibraryEntryKind(): LibraryEntryKind = when (this) {
    GameEngine.RENPY -> LibraryEntryKind.RENPY
    GameEngine.RPG_MAKER_MV -> LibraryEntryKind.RPG_MAKER_MV
    GameEngine.RPG_MAKER_MZ -> LibraryEntryKind.RPG_MAKER_MZ
    GameEngine.RPG_MAKER_VX_ACE -> LibraryEntryKind.RPG_MAKER_VX_ACE
    GameEngine.RPG_MAKER_VX -> LibraryEntryKind.RPG_MAKER_VX
    GameEngine.RPG_MAKER_XP -> LibraryEntryKind.RPG_MAKER_XP
    GameEngine.RPG_MAKER_2000_2003 -> LibraryEntryKind.RPG_MAKER_2000_2003
    GameEngine.KIRIKIRI -> LibraryEntryKind.KIRIKIRI
    GameEngine.AUGUST -> LibraryEntryKind.AUGUST
    GameEngine.BURIKO -> LibraryEntryKind.BURIKO
    GameEngine.CATSYSTEM2 -> LibraryEntryKind.CATSYSTEM2
    GameEngine.CMVS -> LibraryEntryKind.CMVS
    GameEngine.FLASH_AIR -> LibraryEntryKind.FLASH_AIR
    GameEngine.GODOT -> LibraryEntryKind.GODOT
    GameEngine.TWINE -> LibraryEntryKind.TWINE
    GameEngine.UNREAL -> LibraryEntryKind.UNREAL
    GameEngine.UNITY -> LibraryEntryKind.UNITY
}

/**
 * [LibraryProvider] for detected engine games — launches via whichever
 * real interpreter actually handles the entry's [GameEngine] ([EngineHost]
 * for the 11 VN-shaped engines it covers, [Kirikiroid2] for Kirikiri;
 * [GameLaunchStrategy.WINE_PREFIX]/[GameLaunchStrategy.LINUX_CONTAINER]
 * both launch through the `PcGameRuntime` seam), not a single hardcoded
 * path for every kind. JoiPlay direct-launch support was removed entirely (not just
 * deprioritized) — real, confirmed: JoiPlay doesn't expose an intent
 * contract that lets an external caller launch a specific game, so the
 * old `ACTION_VIEW`-at-the-executable integration never actually worked,
 * only looked plausible. [Kirikiroid2]'s own generic-open-only launch has
 * the same real limitation for a different reason (documented on that
 * class) but is kept since opening the app at all is still real,
 * working, useful — guessing at a launchable file inside a folder to feed
 * an `ACTION_VIEW` intent isn't, once there's no real intent contract on
 * the other end for it to reach.
 *
 * [gamesRoots] is deliberately plural, not one folder -- games/ROMs aren't
 * necessarily all in one place (a real SD card folder plus an internal
 * one, say), and per direction, ROM/game support itself is opt-in for
 * users who never touch it at all, so this needs to work with zero roots
 * configured too (an empty list just means [scan] returns nothing, not an
 * error).
 */
class EngineGameProvider(
    private val context: Context,
    // Extra scan roots beyond the user's own games folders: the app
    // layer passes store library directories (Steam's steamapps/common
    // dirs) so a store-installed engine game flows through the SAME
    // detection, grouping, and launch-strategy resolution as any other
    // engine game, enginehost included. A supplier because the set is
    // live: the install location can change between scans.
    private val extraRoots: () -> List<File> = { emptyList() },
    // The store's own facts about the games installed under those roots:
    // source, store id, size, install path, compatibility, cover art.
    // Separate from [extraRoots] because it answers a different question.
    // extraRoots says WHERE to look, and a Steam library folder holds
    // games no store row knows about too; this says what a store knows
    // about a folder once detection has claimed it. Without it,
    // suppressing the duplicate `pc` entry (see
    // [GameEngineDetector.engineOwnsInstall]) would silently delete every
    // store-side fact about the game.
    private val storeInstalls: () -> List<StoreInstall> = { emptyList() },
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = GameEngine.entries.map { it.toLibraryEntryKind() }.toSet()

    override suspend fun scan(): List<LibraryEntry> {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val installs = storeInstalls()
        val installsByDir = installs.byInstallDir()
        // A store game's install directory is a CHILD of the root
        // detection walks, so its parent is the root: the same
        // relationship PcLibrary.knownInstallRoots already produces, kept
        // here so this provider works from a bare list of installs too.
        val roots = (GamesRoots.current(context) + extraRoots() + installs.mapNotNull { it.installDir.parentFile })
            .distinctBy { it.absolutePath }
        // .withScrapedMetadata is what makes a scrape of an engine game
        // visible at all: the scraper writes a game_metadata row keyed by
        // the entry id, and without this merge the next scan rebuilt the
        // entry straight from the filesystem and dropped every scraped
        // field on the floor.
        return roots.flatMap { root ->
            GameEngineDetector.scan(
                root,
                systemsById,
                EnginesDatabase.defs(context),
                override = { folder -> EngineOverridePrefs.engineFor(context, folder.absolutePath) },
            ).map { detected ->
                LibraryEntry(
                    id = detected.displayFolder.absolutePath,
                    title = detected.displayFolder.name,
                    kind = detected.engine.toLibraryEntryKind(),
                    artworkUri = EsDeArtwork.resolve(root, detected.engine.esDeSystemName(), detected.displayFolder.name),
                ).withStoreInstall(installsByDir.forFolder(detected.displayFolder))
            }
        }
            // Roots can overlap now that store installs contribute their
            // own parents; the same folder reached from two roots is
            // still one game.
            .distinctBy { it.id }
            .withScrapedMetadata(
                dev.droidtop.library.consoles.RomDatabase.get(context).romDao(),
                // A game scraped BEFORE this rule existed has its metadata
                // row under the `pc` entry's store id. That entry no
                // longer exists, so without this fallback the scrape would
                // look like it had been thrown away.
                alsoUnderId = { it.pcInfo?.storeId },
            )
    }

    /** [gameRoot] to [detectedEngine] -- see [GameEngineDetector.scan]'s own doc comment for why [gameRoot] isn't always [entry]'s own [LibraryEntry.id] folder. */
    private data class ResolvedEntry(val gameRoot: File, val detectedEngine: GameEngine)

    private fun resolveEntry(entry: LibraryEntry): ResolvedEntry {
        val displayFolder = File(entry.id)
        // Re-detect rather than caching gameRoot on LibraryEntry -- cheap
        // (a handful of listFiles() calls), and keeps LibraryEntry's shape
        // shared/uniform across every provider rather than growing an
        // engine-games-only field. Through GameEngineDetector.detectGame,
        // the same call scan() itself uses, so a launch can never resolve
        // a different folder than the scan that listed the entry did.
        val detected = GameEngineDetector.detectGame(
            displayFolder,
            EnginesDatabase.defs(context),
            override = { folder -> EngineOverridePrefs.engineFor(context, folder.absolutePath) },
        ) ?: error("Couldn't re-detect an engine for ${displayFolder.absolutePath}")
        return ResolvedEntry(detected.gameRoot, detected.engine)
    }

    /**
     * Every [GameLaunchStrategy] genuinely available for [entry] right
     * now, in the same real priority order [launch] would pick from --
     * exposed so a real UI picker (matching ConsoleSystemsActivity's own
     * PlayerPicker for ROMs) can show the user an actual choice instead of
     * [launch] silently resolving one. Enginehost being the *default*
     * pick doesn't make it the *only* option: every strategy this returns
     * stays real and selectable via [LaunchStrategyOverridePrefs.set].
     */
    fun availableStrategies(entry: LibraryEntry): List<GameLaunchStrategy> {
        val (gameRoot, engine) = resolveEntry(entry)
        return GameLaunchStrategyResolver.resolve(
            engine = engine,
            folder = gameRoot,
            kirikiroid2Installed = Kirikiroid2.isInstalled(context),
            engineHostInstalled = EngineHost.isInstalled(context),
            engineHostEngineVersion = resolveEngineVersion(context, gameRoot, engine),
            engineHostCanReachFolder = EngineHost.canReachGameFolder(context, gameRoot),
            preferredOrder = EnginesDatabase.priorityFor(context, engine),
            enginehostSupported = EnginesDatabase.enginehostTargetFor(context, engine) != null,
        )
    }

    override suspend fun launch(entry: LibraryEntry) {
        val (gameRoot, engine) = resolveEntry(entry)
        val available = availableStrategies(entry)
        val overrideStrategy = LaunchStrategyOverridePrefs.get(context, entry.id)
        val strategy = available.firstOrNull { it.name == overrideStrategy } ?: available.firstOrNull()
            ?: error(
                "No way to launch ${entry.title} -- install enginehost (Ren'Py/RPG Maker/etc) " +
                    "or Kirikiroid2 (Kirikiri), or point it at a Windows .exe (Wine) or a Linux " +
                    "build (Linux container) once those are wired to a running session.",
            )

        when (strategy) {
            GameLaunchStrategy.ENGINEHOST -> {
                // engineVersion may be null here (a folder with its own
                // enginehost.json doesn't need one) -- EngineHost.launch
                // itself only requires it when actually building a config
                // extra, and fails loudly then, not before.
                EngineHost.launch(
                    context,
                    gameRoot,
                    EnginesDatabase.enginehostTargetFor(context, engine)
                        ?: error("engines-database has no enginehost mapping for $engine"),
                    resolveEngineVersion(context, gameRoot, engine),
                )
            }
            GameLaunchStrategy.KIRIKIROID2 -> Kirikiroid2.open(context)
            // Both PC strategies go through the PcGameRuntime seam that
            // :app fills in (see that interface's own doc comment). These
            // used to be dead error() stubs, so a game could be offered
            // Wine and then fail on activation with "not wired up yet";
            // now it either really launches or says specifically why not.
            GameLaunchStrategy.WINE_PREFIX -> launchOnPcRuntime(gameRoot, windows = true)
            GameLaunchStrategy.LINUX_CONTAINER -> launchOnPcRuntime(gameRoot, windows = false)
        }
    }

    /**
     * Runs [gameRoot]'s real executable through droidtop's own PC runtime.
     * Every failure here names something the user can act on -- which
     * runtime is missing, which container isn't running, which executable
     * couldn't be identified -- rather than the old blanket "not
     * implemented".
     */
    private suspend fun launchOnPcRuntime(gameRoot: File, windows: Boolean) {
        val runtime = PcGameRuntimeRegistry.runtime
            ?: error(
                "droidtop's PC runtime isn't registered in this process. Launch from the main " +
                    "droidtop app rather than a standalone surface.",
            )
        check(runtime.isAvailable) {
            if (windows) {
                "The Windows environment isn't set up yet -- run \"Set up Windows games\" in Settings."
            } else {
                "Start Desktop mode first: a native Linux build runs inside a live container, " +
                    "and none is connected right now."
            }
        }

        val executable = if (windows) {
            GameExecutableResolver.windowsExecutable(gameRoot)
        } else {
            GameExecutableResolver.linuxExecutable(gameRoot)
        } ?: error(
            "Couldn't identify which file to run in ${gameRoot.name} -- it has no single obvious " +
                (if (windows) "Windows executable" else "Linux launcher") +
                ". Set one explicitly with a custom player.",
        )

        val result = if (windows) {
            runtime.launchWindows(executable, gameRoot)
        } else {
            runtime.launchLinux(executable, gameRoot)
        }
        check(result.succeeded) { "Launching ${executable.name} failed: ${result.detail}" }
    }
}
