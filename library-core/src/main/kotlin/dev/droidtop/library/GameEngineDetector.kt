package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.resolveSystem
import java.io.File
import java.io.RandomAccessFile

/** Which engine a game folder was built with — decoupled from how it gets launched (see [GameLaunchStrategy]/[GameLaunchStrategyResolver]): several launch paths can exist for the same engine. */
enum class GameEngine {
    RENPY, RPG_MAKER_MV, RPG_MAKER_MZ, RPG_MAKER_VX_ACE, KIRIKIRI,
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
 */
object GameEngineDetector {
    fun detect(folder: File): GameEngine? = when {
        isRenPy(folder) -> GameEngine.RENPY
        hasCoreScript(folder, "rpg_core.js") -> GameEngine.RPG_MAKER_MV
        hasCoreScript(folder, "rmmz_core.js") -> GameEngine.RPG_MAKER_MZ
        isRpgMakerVxAce(folder) -> GameEngine.RPG_MAKER_VX_ACE
        isKirikiri(folder) -> GameEngine.KIRIKIRI
        isAugust(folder) -> GameEngine.AUGUST
        isBuriko(folder) -> GameEngine.BURIKO
        isCatSystem2(folder) -> GameEngine.CATSYSTEM2
        isCmvs(folder) -> GameEngine.CMVS
        isFlashAir(folder) -> GameEngine.FLASH_AIR
        isGodot(folder) -> GameEngine.GODOT
        isTwine(folder) -> GameEngine.TWINE
        isUnreal(folder) -> GameEngine.UNREAL
        isUnity(folder) -> GameEngine.UNITY
        else -> null
    }

    private fun isRenPy(folder: File): Boolean =
        File(folder, "renpy").isDirectory && File(folder, "game").isDirectory

    private fun hasCoreScript(folder: File, filename: String): Boolean =
        File(folder, "js/$filename").isFile || File(folder, "www/js/$filename").isFile

    private fun isRpgMakerVxAce(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.name.lowercase().contains(".rgss3a") } == true

    private fun isKirikiri(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.extension.lowercase() == "xp3" } == true

    private fun isAugust(folder: File): Boolean =
        (folder.listFiles()?.count { it.isDirectory && it.name.lowercase().startsWith("aug") } ?: 0) >= 2

    private fun isBuriko(folder: File): Boolean =
        File(folder, "BGI.gdb").isFile || File(folder, "BGI.hvl").isFile

    private fun isCatSystem2(folder: File): Boolean =
        File(folder, "cs2conf.dll").isFile

    private val CMVS_MARKER_NAMES = setOf("cmvs32.exe", "cmvs64.exe", "cmvs.cfg")

    private fun isCmvs(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.name.lowercase() in CMVS_MARKER_NAMES } == true

    private fun isFlashAir(folder: File): Boolean =
        File(folder, "META-INF").isDirectory && File(folder, "mimetype").isFile

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

    private fun isUnreal(folder: File): Boolean =
        File(folder, "Engine/Binaries").isDirectory

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
    fun scan(root: File, systemsById: Map<String, ConsoleSystemDef>): List<DetectedGame> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && resolveSystem(it.name, systemsById) == null }
            .mapNotNull { top ->
                detect(top)?.let { DetectedGame(top, top, it) }
                    ?: (top.listFiles() ?: emptyArray())
                        .asSequence()
                        .filter { it.isDirectory }
                        .mapNotNull { nested -> detect(nested)?.let { DetectedGame(top, nested, it) } }
                        .firstOrNull()
            }
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
     * for the 11 VN-shaped engines it covers ([ENGINEHOST_ENGINE_IDS]):
     * the user reported JoiPlay's own direct launch isn't reliably
     * working on their real device, which is the actual motivation for
     * making this the priority strategy rather than [JOIPLAY] — not a
     * decision to remove JoiPlay support, which stays available below.
     */
    ENGINEHOST,

    /** Hand off to the third-party JoiPlay interpreter — see [JoiPlay]. Real, wired to an actual launch today. Kept as a fallback strategy (selectable via [LaunchStrategyOverridePrefs]) now that [ENGINEHOST] is the default for the engines both cover. */
    JOIPLAY,

    /** Hand off to the third-party Kirikiroid2/krkr2 interpreter — see [Kirikiroid2]. Real, wired today, but generic-open-only (opens the app, not a specific game — see that class's own doc comment for why). */
    KIRIKIROID2,

    /** Run inside a Wine prefix (`:runtime-windows`'s `WineSession`) — works for any engine's Windows/.exe export. Recognized as available; not wired to an actual running container/prefix yet (needs a real `WineSession` instance, which nothing in `library-core` has access to). */
    WINE_PREFIX,

    /** Run as a native process inside a Linux container (`runtime-common`'s `NativeLinuxGameSession`) — only meaningful for an engine/export that actually has a Linux build. Recognized as available; not wired to an actual running container yet, same gap as [WINE_PREFIX]. */
    LINUX_CONTAINER,
}

/** For a real per-entry picker UI — see [EngineGameProvider.availableStrategies]. */
fun GameLaunchStrategy.displayName(): String = when (this) {
    GameLaunchStrategy.ENGINEHOST -> "enginehost"
    GameLaunchStrategy.JOIPLAY -> "JoiPlay"
    GameLaunchStrategy.KIRIKIROID2 -> "Kirikiroid2"
    GameLaunchStrategy.WINE_PREFIX -> "Wine"
    GameLaunchStrategy.LINUX_CONTAINER -> "Linux container"
}

/**
 * Engines JoiPlay is known to interpret (per its own advertised feature
 * set) — Kirikiri is deliberately excluded, not an oversight: nothing
 * found in this session's research indicates JoiPlay covers it. Kirikiri
 * has its own real interpreter instead -- see [Kirikiroid2].
 */
private val JOIPLAY_ENGINES = setOf(GameEngine.RENPY, GameEngine.RPG_MAKER_MV, GameEngine.RPG_MAKER_MZ, GameEngine.RPG_MAKER_VX_ACE)

/**
 * Determines which [GameLaunchStrategy] options are actually plausible for
 * one detected game folder — real per-entry facts, not a fixed table keyed
 * only on [GameEngine]. Two games of the same engine can have different
 * available strategies (one shipped a Linux build, the other didn't).
 */
object GameLaunchStrategyResolver {
    /**
     * [engineHostInstalled]/[engineHostEngineVersion] are plain facts the
     * caller computes from a real `Context` before calling this ([EngineHost
     * .isInstalled]/[resolveEngineVersion]) — deliberately, matching how
     * [joiPlayInstalled]/[kirikiroid2Installed] already work: this resolver
     * stays pure/Android-free so [GameLaunchStrategyResolverTest]'s plain
     * JVM unit tests keep working with zero Robolectric/mocking setup, not
     * a Context threaded in just for this one new strategy.
     */
    fun resolve(
        engine: GameEngine,
        folder: File,
        joiPlayInstalled: Boolean,
        kirikiroid2Installed: Boolean = false,
        engineHostInstalled: Boolean = false,
        engineHostEngineVersion: String? = null,
    ): List<GameLaunchStrategy> {
        val strategies = mutableListOf<GameLaunchStrategy>()
        // Only offered when there's an actual engineVersion to launch
        // with -- a folder with no enginehost.json of its own and no
        // per-folder override set isn't a real available option yet, see
        // resolveEngineVersion's own doc comment.
        if (engineHostInstalled && engine in ENGINEHOST_ENGINE_IDS &&
            (File(folder, "enginehost.json").isFile || engineHostEngineVersion != null)
        ) {
            strategies += GameLaunchStrategy.ENGINEHOST
        }
        if (joiPlayInstalled && engine in JOIPLAY_ENGINES) strategies += GameLaunchStrategy.JOIPLAY
        if (kirikiroid2Installed && engine == GameEngine.KIRIKIRI) strategies += GameLaunchStrategy.KIRIKIROID2
        if (hasWindowsExecutable(folder)) strategies += GameLaunchStrategy.WINE_PREFIX
        // Kirikiri (the original commercial engine) is Windows-native with
        // no official Linux port of the *engine itself* (confirmed, not
        // assumed) -- LINUX_CONTAINER (running a game's own Linux export
        // inside a container) is never offered for it regardless of folder
        // contents. Unrelated to Kirikiroid2 above, a real independent
        // third-party interpreter (like JoiPlay is for Ren'Py) rather than
        // an official Linux build of the engine.
        if (engine != GameEngine.KIRIKIRI && hasLinuxLibraryBuild(folder)) strategies += GameLaunchStrategy.LINUX_CONTAINER
        return strategies
    }

    private fun hasWindowsExecutable(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.extension.lowercase() == "exe" } == true

    /** Ren'Py's own `lib/<prefix>linux-<arch>` convention (the same folder-naming rule the user's Pythia project inspects) is the one real, checkable Linux-build signal available here — not assumed present just because the engine generally supports it. */
    private fun hasLinuxLibraryBuild(folder: File): Boolean =
        File(folder, "lib").listFiles()?.any { it.isDirectory && it.name.contains("linux") } == true
}

private fun GameEngine.toLibraryEntryKind(): LibraryEntryKind = when (this) {
    GameEngine.RENPY -> LibraryEntryKind.RENPY
    GameEngine.RPG_MAKER_MV -> LibraryEntryKind.RPG_MAKER_MV
    GameEngine.RPG_MAKER_MZ -> LibraryEntryKind.RPG_MAKER_MZ
    GameEngine.RPG_MAKER_VX_ACE -> LibraryEntryKind.RPG_MAKER_VX_ACE
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
 * real interpreter actually handles the entry's [GameEngine] ([JoiPlay]
 * for Ren'Py/RPG Maker, [Kirikiroid2] for Kirikiri; [GameLaunchStrategy.
 * WINE_PREFIX]/[GameLaunchStrategy.LINUX_CONTAINER] are recognized by
 * [GameLaunchStrategyResolver] but not wired to an actual running session
 * yet), not a single hardcoded path for every kind — a real bug fixed
 * this session: every entry used to route through JoiPlay regardless of
 * kind, silently misfiring for Kirikiri (which JoiPlay doesn't support)
 * on top of being named after a launcher it doesn't exclusively use
 * (hence "EngineGameProvider", not "JoiPlayGameProvider" anymore). Picking
 * among multiple *available* strategies when more than one applies (a
 * real UI concern) is the next real gap.
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
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = GameEngine.entries.map { it.toLibraryEntryKind() }.toSet()

    override suspend fun scan(): List<LibraryEntry> {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        return GamesRoots.current(context).flatMap { root ->
            GameEngineDetector.scan(root, systemsById).map { detected ->
                LibraryEntry(
                    id = detected.displayFolder.absolutePath,
                    title = detected.displayFolder.name,
                    kind = detected.engine.toLibraryEntryKind(),
                    artworkUri = EsDeArtwork.resolve(root, detected.engine.esDeSystemName(), detected.displayFolder.name),
                )
            }
        }
    }

    /** [gameRoot] to [detectedEngine] -- see [GameEngineDetector.scan]'s own doc comment for why [gameRoot] isn't always [entry]'s own [LibraryEntry.id] folder. */
    private data class ResolvedEntry(val gameRoot: File, val detectedEngine: GameEngine)

    private fun resolveEntry(entry: LibraryEntry): ResolvedEntry {
        val displayFolder = File(entry.id)
        // Re-detect rather than caching gameRoot on LibraryEntry -- cheap
        // (a handful of listFiles() calls), and keeps LibraryEntry's shape
        // shared/uniform across every provider rather than growing an
        // engine-games-only field.
        val gameRoot = GameEngineDetector.detect(displayFolder)?.let { displayFolder }
            ?: (displayFolder.listFiles() ?: emptyArray())
                .firstOrNull { it.isDirectory && GameEngineDetector.detect(it) != null }
            ?: displayFolder
        val engine = GameEngineDetector.detect(gameRoot)
            ?: error("Couldn't re-detect an engine for ${gameRoot.absolutePath}")
        return ResolvedEntry(gameRoot, engine)
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
            joiPlayInstalled = JoiPlay.isInstalled(context),
            kirikiroid2Installed = Kirikiroid2.isInstalled(context),
            engineHostInstalled = EngineHost.isInstalled(context),
            engineHostEngineVersion = resolveEngineVersion(context, gameRoot, engine),
        )
    }

    override suspend fun launch(entry: LibraryEntry) {
        val (gameRoot, engine) = resolveEntry(entry)
        val available = availableStrategies(entry)
        val overrideStrategy = LaunchStrategyOverridePrefs.get(context, entry.id)
        val strategy = available.firstOrNull { it.name == overrideStrategy } ?: available.firstOrNull()
            ?: error(
                "No way to launch ${entry.title} -- install enginehost or JoiPlay " +
                    "(Ren'Py/RPG Maker/etc) or Kirikiroid2 (Kirikiri), or point it at a Windows " +
                    ".exe (Wine) or a Linux build (Linux container) once those are wired to a " +
                    "running session.",
            )

        when (strategy) {
            GameLaunchStrategy.ENGINEHOST -> {
                // engineVersion may be null here (a folder with its own
                // enginehost.json doesn't need one) -- EngineHost.launch
                // itself only requires it when actually building a config
                // extra, and fails loudly then, not before.
                val engineId = ENGINEHOST_ENGINE_IDS.getValue(engine)
                EngineHost.launch(context, gameRoot, engineId, resolveEngineVersion(context, gameRoot, engine))
            }
            GameLaunchStrategy.JOIPLAY -> {
                val executable = findJoiPlayExecutable(gameRoot)
                    ?: error("No JoiPlay-launchable file (.sh/.exe/.py/.html/.swf) found in ${gameRoot.absolutePath}")
                JoiPlay.launchViaJoiPlay(context, executable, JoiPlay.FILE_PROVIDER_AUTHORITY)
            }
            GameLaunchStrategy.KIRIKIROID2 -> Kirikiroid2.open(context)
            GameLaunchStrategy.WINE_PREFIX -> error(
                "Wine/Box64 launching isn't wired to a running session yet (needs a real " +
                    "WineSession instance, which library-core has no access to -- see SPEC.md §5a).",
            )
            GameLaunchStrategy.LINUX_CONTAINER -> error(
                "Linux-container launching isn't wired to a running session yet (needs a real " +
                    "ContainerRuntime instance, which library-core has no access to -- see SPEC.md §5a).",
            )
        }
    }

    companion object {
        // Real, exact extensions JoiPlay's own manifest matches (confirmed
        // via `adb shell dumpsys package cyou.joiplay.joiplay`'s real
        // intent-filter path patterns against a real installed copy this
        // session) -- not guessed. Order matters: `sh` first since every
        // real Ren'Py download checked this session shipped one and it's
        // Ren'Py's own intended cross-platform launcher; `exe` next since
        // it's the only one RPG Maker MV/MZ (Electron/NW.js) and Kirikiri
        // exports ship at all.
        private val JOIPLAY_EXTENSIONS = listOf("sh", "exe", "py", "html", "swf")

        private fun findJoiPlayExecutable(gameRoot: File): File? =
            JOIPLAY_EXTENSIONS.firstNotNullOfOrNull { ext ->
                gameRoot.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() == ext }
            }
    }
}
