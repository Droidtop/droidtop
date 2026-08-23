package dev.droidtop.library

import android.content.Context
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.resolveSystem
import java.io.File

/** Which engine a game folder was built with — decoupled from how it gets launched (see [GameLaunchStrategy]/[GameLaunchStrategyResolver]): several launch paths can exist for the same engine. */
enum class GameEngine { RENPY, RPG_MAKER_MV, RPG_MAKER_MZ, RPG_MAKER_VX_ACE, KIRIKIRI }

/**
 * Signatures ported from the user's own Pythia project
 * (G:\Support\GameManagement\RenPyPatch\pythia), verified there against
 * real games in their library, not guessed:
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
    /** Hand off to the third-party JoiPlay interpreter — see [JoiPlay]. Real, wired to an actual launch today. */
    JOIPLAY,

    /** Hand off to the third-party Kirikiroid2/krkr2 interpreter — see [Kirikiroid2]. Real, wired today, but generic-open-only (opens the app, not a specific game — see that class's own doc comment for why). */
    KIRIKIROID2,

    /** Run inside a Wine prefix (`:runtime-windows`'s `WineSession`) — works for any engine's Windows/.exe export. Recognized as available; not wired to an actual running container/prefix yet (needs a real `WineSession` instance, which nothing in `library-core` has access to). */
    WINE_PREFIX,

    /** Run as a native process inside a Linux container (`runtime-common`'s `NativeLinuxGameSession`) — only meaningful for an engine/export that actually has a Linux build. Recognized as available; not wired to an actual running container yet, same gap as [WINE_PREFIX]. */
    LINUX_CONTAINER,
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
    fun resolve(
        engine: GameEngine,
        folder: File,
        joiPlayInstalled: Boolean,
        kirikiroid2Installed: Boolean = false,
    ): List<GameLaunchStrategy> {
        val strategies = mutableListOf<GameLaunchStrategy>()
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

    override suspend fun launch(entry: LibraryEntry) {
        val displayFolder = File(entry.id)
        // Re-detect rather than caching gameRoot on LibraryEntry -- cheap
        // (a handful of listFiles() calls), and keeps LibraryEntry's shape
        // shared/uniform across every provider rather than growing an
        // engine-games-only field. See GameEngineDetector.scan's own doc
        // comment for why gameRoot isn't always displayFolder itself.
        val gameRoot = GameEngineDetector.detect(displayFolder)?.let { displayFolder }
            ?: (displayFolder.listFiles() ?: emptyArray())
                .firstOrNull { it.isDirectory && GameEngineDetector.detect(it) != null }
            ?: displayFolder
        val engine = GameEngineDetector.detect(gameRoot)
            ?: error("Couldn't re-detect an engine for ${gameRoot.absolutePath}")

        // Real bug this fixes: launch() used to hardcode exactly one path
        // per kind (JoiPlay, or Kirikiroid2 for KIRIKIRI) regardless of
        // what GameLaunchStrategyResolver actually says is available --
        // silently forcing a game into whichever third-party interpreter
        // this provider happened to hardcode, even when a Windows .exe or
        // a real Linux build made Wine/a Linux container a genuinely
        // available (if not yet actually wired) alternative. Real per-entry
        // choice (a UI picker, same shape as ConsoleSystemsActivity's real
        // PlayerPicker for ROMs) is the natural next step, not built yet --
        // this at least stops the wrong-path-forcing and picks in a real,
        // documented priority order with an honest error for the two
        // strategies that exist architecturally but have no running
        // session to launch into yet.
        val available = GameLaunchStrategyResolver.resolve(
            engine = engine,
            folder = gameRoot,
            joiPlayInstalled = JoiPlay.isInstalled(context),
            kirikiroid2Installed = Kirikiroid2.isInstalled(context),
        )
        val overrideStrategy = LaunchStrategyOverridePrefs.get(context, entry.id)
        val strategy = available.firstOrNull { it.name == overrideStrategy } ?: available.firstOrNull()
            ?: error(
                "No way to launch ${entry.title} -- install JoiPlay (Ren'Py/RPG Maker) or " +
                    "Kirikiroid2 (Kirikiri), or point it at a Windows .exe (Wine) or a Linux " +
                    "build (Linux container) once those are wired to a running session.",
            )

        when (strategy) {
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
