package dev.droidtop.library

import android.content.Context
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

    /** Every immediate subdirectory of [root] that [detect]s as some [GameEngine]. */
    fun scan(root: File): List<Pair<File, GameEngine>> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { folder -> detect(folder)?.let { folder to it } }
}

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

    /** Run inside a Wine prefix (`:runtime-windows`'s `WineSession`) — works for any engine's Windows/.exe export. Recognized as available; not wired to an actual running container/prefix yet (needs a real `WineSession` instance, which nothing in `library-core` has access to). */
    WINE_PREFIX,

    /** Run as a native process inside a Linux container (`runtime-common`'s `NativeLinuxGameSession`) — only meaningful for an engine/export that actually has a Linux build. Recognized as available; not wired to an actual running container yet, same gap as [WINE_PREFIX]. */
    LINUX_CONTAINER,
}

/**
 * Engines JoiPlay is known to interpret (per its own advertised feature
 * set) — Kirikiri is deliberately excluded, not an oversight: nothing
 * found in this session's research indicates JoiPlay covers it.
 */
private val JOIPLAY_ENGINES = setOf(GameEngine.RENPY, GameEngine.RPG_MAKER_MV, GameEngine.RPG_MAKER_MZ, GameEngine.RPG_MAKER_VX_ACE)

/**
 * Determines which [GameLaunchStrategy] options are actually plausible for
 * one detected game folder — real per-entry facts, not a fixed table keyed
 * only on [GameEngine]. Two games of the same engine can have different
 * available strategies (one shipped a Linux build, the other didn't).
 */
object GameLaunchStrategyResolver {
    fun resolve(engine: GameEngine, folder: File, joiPlayInstalled: Boolean): List<GameLaunchStrategy> {
        val strategies = mutableListOf<GameLaunchStrategy>()
        if (joiPlayInstalled && engine in JOIPLAY_ENGINES) strategies += GameLaunchStrategy.JOIPLAY
        if (hasWindowsExecutable(folder)) strategies += GameLaunchStrategy.WINE_PREFIX
        // Kirikiri is a Windows-native engine with no official Linux port
        // (confirmed, not assumed) -- LINUX_CONTAINER is never offered for
        // it regardless of folder contents, unlike the other engines here.
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
 * [LibraryProvider] for detected engine games — currently only ever
 * launches via [GameLaunchStrategy.JOIPLAY] (the one strategy with a real
 * implementation, see [GameLaunchStrategyResolver]'s own doc comment for
 * the other two's status), regardless of what [GameLaunchStrategyResolver]
 * says is available for a given entry. Picking among multiple available
 * strategies (a real UI concern, not solved here) is the next real gap.
 */
class JoiPlayGameProvider(
    private val context: Context,
    private val gamesRoot: File,
    // ES-DE's own downloaded_media root convention (see EsDeArtwork's doc
    // comment) -- defaults to a sibling of gamesRoot so an out-of-the-box
    // install has somewhere sensible to look, but a user pointing droidtop
    // at an existing ES-DE data directory can pass the real one in.
    private val esDeMediaRoot: File = File(gamesRoot.parentFile, "ES-DE/downloaded_media"),
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = GameEngine.entries.map { it.toLibraryEntryKind() }.toSet()

    override suspend fun scan(): List<LibraryEntry> =
        GameEngineDetector.scan(gamesRoot).map { (folder, engine) ->
            LibraryEntry(
                id = folder.absolutePath,
                title = folder.name,
                kind = engine.toLibraryEntryKind(),
                artworkUri = EsDeArtwork.resolve(esDeMediaRoot, engine.esDeSystemName(), folder.name),
            )
        }

    override suspend fun launch(entry: LibraryEntry) {
        val folder = File(entry.id)
        val executable = File(folder, "${folder.name}.exe")
        JoiPlay.launchViaJoiPlay(context, executable, JoiPlay.FILE_PROVIDER_AUTHORITY)
    }
}
