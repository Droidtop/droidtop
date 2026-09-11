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
    AUGUST, BURIKO, CATSYSTEM2, CMVS, FLASH_AIR, GODOT, HTML, UNREAL, UNITY,
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
 * - HTML: any `.html`/`.htm` file directly in the game root. Twine
 *   stories are the case Pythia closed this gap for, but the database
 *   row is `html`, not `twine`, and its own note says so: the row is
 *   ordered LAST precisely because Godot and Unity web exports also ship
 *   an `index.html` and are classified by their richer signatures first,
 *   so nothing narrower than "there is a page here" is needed. Which
 *   HTML dialect a game is stays a version question, not a detection
 *   one — [EngineVersionDetector] still reads `tw-storydata`'s
 *   `creator-version` and reports a version only for real Twine exports.
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
    fun detect(folder: File, defs: List<EngineDef>): GameEngine? = detect(folder, defs) { true }

    /**
     * [detect] restricted to the rules [ruleFilter] accepts -- how
     * [detectGame] separates "this folder IS a game root" evidence from
     * "an engine game is somewhere under this folder" evidence
     * (see [DetectRule.readsUnnamedSubtree]). Row order within a tier is
     * still the database's own file order.
     */
    private fun detect(folder: File, defs: List<EngineDef>, ruleFilter: (DetectRule) -> Boolean): GameEngine? =
        defs.firstOrNull { def ->
            def.engine != null && EngineDetectRules.matches(def.detect.filter(ruleFilter), folder, ::builtinProbe)
        }?.engine

    private fun builtinProbe(name: String, folder: File): Boolean = when (name) {
        "godot" -> isGodot(folder)
        "html" -> isHtml(folder)
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

    private val HTML_EXTENSIONS = setOf("html", "htm")

    /** No file is read: the row's own evidence is that a page exists in the root. */
    private fun isHtml(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.extension.lowercase() in HTML_EXTENSIONS } == true

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

    /** How many folders below a games root [scan] looks for games. */
    /**
     * Folder names a library scan never descends into and never calls a
     * game. All of them are bookkeeping a filesystem or a sync tool put
     * beside the user's files, and every one of them is structurally
     * indistinguishable from a game folder to the detector: a directory
     * full of files it has no rule for.
     *
     * The rig showed the failure directly -- a Syncthing marker folder
     * inside a games root was listed in the library as ".STFOLDER [PC]".
     *
     * Two rules, deliberately: a leading dot (which is how Syncthing,
     * Android's own caches and every Unix tool mark "not for you", and
     * covers .stfolder / .stversions / .stignore / .Trash-1000 /
     * .thumbnails without naming each) plus the handful of markers that
     * came from Windows and NEVER carry a dot.
     */
    private val NEVER_A_GAME_FOLDER = setOf(
        "system volume information",
        "\$recycle.bin",
        "recycler",
        "lost+found",
        "found.000",
    )

    /**
     * Whether a scan may look at [dir] at all. One predicate, used by
     * every place that enumerates subfolders, so a marker folder cannot
     * be skipped by the walk and still become a game through the nested
     * search.
     */
    fun isScannableFolder(dir: File): Boolean {
        val name = dir.name
        if (name.startsWith(".")) return false
        return name.lowercase() !in NEVER_A_GAME_FOLDER
    }

    const val MAX_SCAN_DEPTH = 4

    /**
     * Every game under [root], wherever in its folder tree the games
     * actually sit: the walk descends through folders that are not games
     * until games are detected, and each detected game is ONE entry whose
     * [DetectedGame.displayFolder] is the game's own folder. A folder that
     * merely CONTAINS games is never itself a game (docs/SPEC.md 7i) --
     * real case, and the reason this is a walk rather than one level:
     * a library root added above the engine folders
     * ("GameSync/Adult/<engine>/<game>") used to yield a single game named
     * "Adult", because the compiled-Ren'Py `.rpa` fallback reads an
     * unnamed subtree and so matched the wrapper three levels up.
     *
     * Three things bound and order the walk:
     *
     * - A folder that is itself a game STOPS the descent. Engine games
     *   have subfolders of their own (`game/`, `www/`, `<name>_Data/`) and
     *   none of them is a second game.
     * - [MAX_SCAN_DEPTH] folders below the root, so a mistakenly-added
     *   storage root cannot walk the whole device.
     * - Any subdirectory whose name resolves to a known console system is
     *   skipped at every level (see
     *   [dev.droidtop.library.consoles.resolveSystem]), since those are
     *   provably ROM folders. Real, not theoretical: a real ROMs folder's
     *   "j2me" system directory had 18,126 entries, and
     *   [isKirikiri]/[isRpgMakerVxAce] each do a full `listFiles()` scan
     *   looking for signature files -- wastefully slow on a folder that
     *   large, and on external/SD-card storage specifically, slow enough to
     *   be the real cause of a reported frozen-UI bug (see also
     *   [dev.droidtop.library.Library.scanAll]'s own fix for the other half
     *   of that: running this on the wrong dispatcher entirely).
     *
     * Rules that read an unnamed subtree ([DetectRule.readsUnnamedSubtree]
     * -- the compiled-Ren'Py `.rpa`/`.rpyc` fallback at depth 2, Unity's
     * three-deep player search) say a game is somewhere below without
     * naming where, so they match at every folder on the way down to the
     * evidence: `Compiled/game/archive.rpa` matches at `Compiled` AND at
     * `game`. The OUTERMOST of those is the game root the rule means, so a
     * folder whose only evidence is a subtree rule keeps the game when
     * nothing precise sits below it, and yields to the games below it when
     * something does. The one case that stays ambiguous by construction is
     * a category folder holding exactly one compiled game and nothing
     * else: there is no evidence distinguishing it from that game's own
     * wrapper, so it takes the game's place (one entry that launches, with
     * the outer folder's name) rather than inventing a second.
     *
     * One folder keeps a nicer name than its markers deserve: the version
     * wrapper, a real confirmed shape
     * ("BeingADik/BeingADIK-0.8.3-scrappy/{renpy,game}") where a Ren'Py
     * distribution zip adds a version-named folder around the game (some
     * do, some do not -- checked against several real downloads in one
     * library, so both shapes have to work). It is recognised
     * structurally, not by guessing: the folder holds exactly one game,
     * that game is an immediate subfolder detected in its own right, and
     * its name starts with this folder's (version suffix appended to the
     * game's name). Then [DetectedGame.displayFolder] stays the outer
     * folder and only [DetectedGame.gameRoot] moves inwards. An engine or
     * category folder fails that last test, which is precisely why it does
     * not steal its games' names.
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
        candidateFolders(root, systemsById)
            .flatMap { gamesUnder(it, systemsById, defs, override, depth = 1) }
            .map { it.game }

    /**
     * The subfolders of [folder] the walk may look at: directories that
     * are not console-system folders, in name order rather than
     * [File.listFiles] order (which is filesystem-defined and can differ
     * between scans, and the walk's results must not).
     */
    private fun candidateFolders(
        folder: File,
        systemsById: Map<String, ConsoleSystemDef>,
    ): List<File> =
        (folder.listFiles() ?: emptyArray())
            .filter { it.isDirectory && isScannableFolder(it) && resolveSystem(it.name, systemsById) == null }
            .sortedBy { it.name }

    /**
     * One step of [scan]'s walk. [Walked.precise] records whether a game
     * was detected by evidence naming its own folder or only by a subtree
     * rule, which is what lets a parent tell "games live below me" from
     * "the subtree rule that matched me matched my own archive folder too".
     */
    private data class Walked(val game: DetectedGame, val precise: Boolean)

    private fun gamesUnder(
        folder: File,
        systemsById: Map<String, ConsoleSystemDef>,
        defs: List<EngineDef>,
        override: (File) -> GameEngine?,
        depth: Int,
    ): List<Walked> {
        override(folder)?.let { return listOf(Walked(DetectedGame(folder, folder, it), precise = true)) }
        // Precise evidence that THIS folder is a game root ends the
        // descent: a game's own subfolders are not further games.
        detect(folder, defs) { !it.readsUnnamedSubtree }
            ?.let { return listOf(Walked(DetectedGame(folder, folder, it), precise = true)) }

        val subtreeHere = detect(folder, defs) { it.readsUnnamedSubtree }
        val below =
            if (depth < MAX_SCAN_DEPTH) {
                candidateFolders(folder, systemsById)
                    .flatMap { gamesUnder(it, systemsById, defs, override, depth + 1) }
            } else {
                emptyList()
            }

        versionWrapper(folder, below)?.let { return listOf(Walked(it, precise = true)) }
        // The outermost folder a subtree rule matches is the game root it
        // means -- unless precise games, or more than one game, sit below.
        if (subtreeHere != null && below.size <= 1 && below.none { it.precise }) {
            return listOf(Walked(DetectedGame(folder, folder, subtreeHere), precise = false))
        }
        // A folder holding games is a wrapper, not a game of its own.
        return below
    }

    /**
     * [folder] read as a version wrapper around the single game below it --
     * see [scan]'s doc comment for the shape and for why the name test is
     * part of it. Null when [folder] is an ordinary container.
     */
    private fun versionWrapper(folder: File, below: List<Walked>): DetectedGame? {
        val inner = below.singleOrNull()?.takeIf { it.precise }?.game ?: return null
        if (inner.displayFolder.parentFile != folder) return null
        // Not an inner wrapper result in its own right: one rename only.
        if (inner.displayFolder != inner.gameRoot) return null
        if (!nameKey(inner.displayFolder.name).startsWith(nameKey(folder.name))) return null
        return DetectedGame(folder, inner.gameRoot, inner.engine)
    }

    /** Folder names compared for the version-wrapper test only. */
    private fun nameKey(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

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
     *
     * Three tiers, because "which engine is this" and "which folder is
     * the game root" are different questions and the database only
     * answers the first. A rule that reads an unnamed subtree
     * ([DetectRule.readsUnnamedSubtree] -- the compiled-Ren'Py
     * `.rpa`/`.rpyc` fallback, Unity's depth-limited player search)
     * proves an engine game is somewhere below, not that it is HERE, so
     * it must not pre-empt a precise match on an actual subfolder: the
     * wrapper folder would become its own game root and the version
     * folder inside it would never be looked at. Classification order
     * itself is untouched -- [detect] still evaluates every row in the
     * database's file order, which stays the sole precedence rule
     * droidtop and enginehost share (docs/SPEC.md 7e2b).
     */
    fun detectGame(
        folder: File,
        defs: List<EngineDef>,
        override: (File) -> GameEngine? = { null },
    ): DetectedGame? {
        override(folder)?.let { return DetectedGame(folder, folder, it) }
        // Tier 1: evidence AT this folder that this folder is the game
        // root (renpy/ + game/, RPG_RT.ldb, project.godot, ...).
        detect(folder, defs) { !it.readsUnnamedSubtree }?.let { return DetectedGame(folder, folder, it) }
        // Tier 2: the same precise question asked of each subfolder --
        // the version-folder wrapper shape. Precise there too: a
        // subtree rule matching a subfolder proves no more about that
        // subfolder than it already proved about this one.
        (folder.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isDirectory && isScannableFolder(it) }
            .sortedBy { it.name }
            .mapNotNull { nested ->
                detect(nested, defs) { !it.readsUnnamedSubtree }?.let { DetectedGame(folder, nested, it) }
            }
            .firstOrNull()
            ?.let { return it }
        // Tier 3: only now the subtree rules, which say an engine game
        // is somewhere below without naming where. Nothing more precise
        // was found, so this folder is the best root available.
        return detect(folder, defs) { it.readsUnnamedSubtree }?.let { DetectedGame(folder, folder, it) }
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
     * together -- the Gaming shell runs Games and Apps as two
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
        // Database-declared priority (EnginesDatabase, docs/SPEC.md 7e2):
        // AVAILABILITY stays [RunnerAvailability]'s own real checks -- the
        // order only decides which available strategy wins. Null keeps the
        // historical append order (and the pure-JVM unit tests untouched).
        preferredOrder: List<GameLaunchStrategy>? = null,
        // Whether the engines database declares an enginehost mapping
        // for [engine]. Defaults true so the pure JVM tests, which model
        // registry-covered engines, stay unchanged (same convention as
        // engineHostCanReachFolder above).
        enginehostSupported: Boolean = true,
    ): List<GameLaunchStrategy> = RunnerAvailability.evaluate(
        facts(
            engine = engine,
            folder = folder,
            kirikiroid2Installed = kirikiroid2Installed,
            engineHostInstalled = engineHostInstalled,
            engineHostEngineVersionKnown = engineHostEngineVersion != null,
            engineHostCanReachFolder = engineHostCanReachFolder,
            preferredOrder = preferredOrder,
            enginehostSupported = enginehostSupported,
        ),
    ).filter { it.state == RunnerState.READY }.map { it.strategy }

    /**
     * The folder half of [RunnerFacts] -- the one place a game folder is
     * read for "does this offer Windows / Linux at all", shared by the
     * launch path above and by the PC surface's availability rows, so the
     * two can never disagree about the same folder.
     *
     * The device-side parameters are left at their [RunnerFacts] defaults
     * unless a caller passes them; see that class for why "not measured"
     * is an honest answer rather than a guess.
     */
    fun facts(
        engine: GameEngine?,
        folder: File,
        kirikiroid2Installed: Boolean = false,
        engineHostInstalled: Boolean = false,
        engineHostEngineVersionKnown: Boolean = false,
        engineHostCanReachFolder: Boolean = true,
        enginehostSupported: Boolean = true,
        enginehostBundleCovers: Boolean? = null,
        windowsEnvironmentReady: Boolean = true,
        wineRendererWired: Boolean = true,
        linuxContainerAvailable: Boolean = true,
        x86TranslationRegistered: Boolean = true,
        preferredOrder: List<GameLaunchStrategy>? = null,
    ): RunnerFacts = RunnerFacts(
        engine = engine,
        hasWindowsExecutable = hasWindowsExecutable(folder),
        hasLinuxBuild = hasLinuxBuild(folder),
        enginehostSupported = enginehostSupported,
        enginehostInstalled = engineHostInstalled,
        enginehostCanReachFolder = engineHostCanReachFolder,
        // Only offered when there's an actual engineVersion to launch
        // with -- a folder with no enginehost.json of its own and no
        // per-folder override set isn't a real available option yet, see
        // resolveEngineVersion's own doc comment.
        enginehostEngineVersionKnown = engineHostEngineVersionKnown || File(folder, "enginehost.json").isFile,
        enginehostBundleCovers = enginehostBundleCovers,
        kirikiroid2Installed = kirikiroid2Installed,
        windowsEnvironmentReady = windowsEnvironmentReady,
        wineRendererWired = wineRendererWired,
        linuxContainerAvailable = linuxContainerAvailable,
        x86TranslationRegistered = x86TranslationRegistered,
        preferredOrder = preferredOrder.orEmpty(),
    )

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
    GameEngine.HTML -> LibraryEntryKind.HTML
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
                    // Same three arguments the resolve() above already
                    // takes -- carried instead of re-derived. See
                    // GameMediaLocator.
                    mediaLocator = GameMediaLocator(
                        root.absolutePath,
                        detected.engine.esDeSystemName(),
                        detected.displayFolder.name,
                    ),
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
                    title = entry.title,
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
