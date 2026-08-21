package dev.droidtop.library

import android.content.Context
import java.io.File

/** Which engine a game folder was built with — JoiPlay is one launcher that covers all of these today, not the only possible one. */
enum class GameEngine { RENPY, RPG_MAKER_MV, RPG_MAKER_MZ, RPG_MAKER_VX_ACE }

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
        else -> null
    }

    private fun isRenPy(folder: File): Boolean =
        File(folder, "renpy").isDirectory && File(folder, "game").isDirectory

    private fun hasCoreScript(folder: File, filename: String): Boolean =
        File(folder, "js/$filename").isFile || File(folder, "www/js/$filename").isFile

    private fun isRpgMakerVxAce(folder: File): Boolean =
        folder.listFiles()?.any { it.isFile && it.name.lowercase().contains(".rgss3a") } == true

    /** Every immediate subdirectory of [root] that [detect]s as some [GameEngine]. */
    fun scan(root: File): List<Pair<File, GameEngine>> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { folder -> detect(folder)?.let { folder to it } }
}

private fun GameEngine.toLibraryEntryKind(): LibraryEntryKind = when (this) {
    GameEngine.RENPY -> LibraryEntryKind.RENPY
    GameEngine.RPG_MAKER_MV -> LibraryEntryKind.RPG_MAKER_MV
    GameEngine.RPG_MAKER_MZ -> LibraryEntryKind.RPG_MAKER_MZ
    GameEngine.RPG_MAKER_VX_ACE -> LibraryEntryKind.RPG_MAKER_VX_ACE
}

/**
 * [LibraryProvider] for JoiPlay, droidtop's emulator/interpreter for
 * Ren'Py and RPG Maker games — same category of integration as any other
 * emulator (RetroArch, DuckStation): detect games, hand them off to launch.
 * Scans [gamesRoot] via [GameEngineDetector]; launch fires an
 * `ACTION_VIEW` at the game's executable targeting JoiPlay directly, the
 * same as a file manager's "Open With".
 */
class JoiPlayGameProvider(
    private val context: Context,
    private val gamesRoot: File,
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = GameEngine.entries.map { it.toLibraryEntryKind() }.toSet()

    override suspend fun scan(): List<LibraryEntry> =
        GameEngineDetector.scan(gamesRoot).map { (folder, engine) ->
            LibraryEntry(id = folder.absolutePath, title = folder.name, kind = engine.toLibraryEntryKind())
        }

    override suspend fun launch(entry: LibraryEntry) {
        val folder = File(entry.id)
        val executable = File(folder, "${folder.name}.exe")
        JoiPlay.launchViaJoiPlay(context, executable, JoiPlay.FILE_PROVIDER_AUTHORITY)
    }
}
