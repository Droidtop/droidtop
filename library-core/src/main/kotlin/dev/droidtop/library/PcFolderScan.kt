package dev.droidtop.library

import java.io.File

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
 *  2. **A folder that directly holds an executable is the game.** Its own
 *     subfolders are not further games -- that is what listed
 *     `Ghost Recon Breakpoint/benchmark` as a game of its own.
 *  3. **A folder that only holds other folders is a container**, whatever
 *     it is called: it contributes the games found below it and never
 *     itself. `EA`, `Ubisoft` and `roms` are containers; so is a games
 *     root.
 *  4. A folder that holds files of its own AND exactly one game below it
 *     is that game's root, not its container: this is the
 *     `<Game>/Binaries/Win64/Game.exe` shape, where the executable sits
 *     two levels down but the game is plainly the outer folder. It does
 *     not apply inside a store tree, where `steamapps` holding one
 *     installed game must still yield the game and not `steamapps`.
 *  5. A folder with no executable anywhere below it is not a PC game.
 *     That is what makes an empty store folder contribute nothing
 *     instead of an entry a user cannot launch.
 *
 * Engine games are NOT this scan's business: [GameEngineDetector] finds
 * them, and `PcGameProvider` already drops a PC entry for any folder
 * engine detection owns (docs/SPEC.md 7g).
 */
object PcFolderScan {

    /** Same bound as [GameEngineDetector.MAX_SCAN_DEPTH], for the same reason. */
    const val MAX_SCAN_DEPTH = 4

    /**
     * Every PC game folder under [root], deepest-evidence-first. [root]
     * itself is treated as a container: a games root is never a game.
     */
    fun gamesUnder(root: File): List<File> =
        if (!root.isDirectory) emptyList() else childrenOf(root).flatMap { walk(it, depth = 1) }

    private fun walk(folder: File, depth: Int): List<File> {
        if (!folder.isDirectory || !ScanPrune.isScannableFolder(folder)) return emptyList()
        // A store's own install root is the store's business, never a
        // game, however many executables its client drops in it.
        val isStoreRoot = ScanPrune.storeRootOwner(folder) != null
        if (!isStoreRoot && GameExecutableResolver.hasExecutable(folder)) return listOf(folder)

        val below =
            if (depth < MAX_SCAN_DEPTH) childrenOf(folder).flatMap { walk(it, depth + 1) } else emptyList()
        if (below.isEmpty()) return emptyList()
        val holdsOwnFiles = (folder.listFiles() ?: emptyArray()).any { it.isFile && !it.name.startsWith(".") }
        val insideStoreTree = isStoreRoot || ScanPrune.storeTreeRoot(folder) != null
        return if (holdsOwnFiles && below.size == 1 && !insideStoreTree) listOf(folder) else below
    }

    private fun childrenOf(folder: File): List<File> =
        (folder.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
}
