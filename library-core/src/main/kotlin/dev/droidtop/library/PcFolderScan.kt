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
 *     `Ghost Recon Breakpoint/benchmark` as a game of its own. The
 *     engine walk states the same rule from its own side
 *     ([GameEngineDetector.isPlainPcGameFolder]): a folder holding an
 *     executable and no engine evidence is a PC game and the engine walk
 *     neither claims it nor descends into it. This scan lists a folder
 *     that holds an executable AND engine evidence too; `PcGameProvider`
 *     drops that entry through the same shared rule
 *     ([GameEngineDetector.engineOwnsInstall]), so the folder is listed
 *     once, by the walk that knows what it is.
 *  3. **A folder that only holds other folders is a container**, whatever
 *     it is called: it contributes the games found below it and never
 *     itself. `EA`, `Ubisoft` and `roms` are containers; so is a games
 *     root.
 *  4. A folder that holds files of its own AND games below it is those
 *     games' root, not their container: this is the
 *     `<Game>/Binaries/Win64/Game.exe` shape, where the executable sits
 *     two levels down but the game is plainly the outer folder. The rig
 *     showed why this cannot be restricted to ONE game below: every
 *     Ubisoft install (`Far Cry 5/{bin,bin_plus}/FarCry5.exe`) keeps its
 *     executables in two payload folders, so the one-game form listed
 *     `bin` and `bin_plus` and never Far Cry 5. It does not apply inside
 *     a store tree, where `steamapps` holding one installed game must
 *     still yield the game and not `steamapps`.
 *  5. A folder with no executable anywhere below it is not a PC game.
 *     That is what makes an empty store folder contribute nothing
 *     instead of an entry a user cannot launch.
 *  6. **A folder with two or more ENGINE games directly below it is a
 *     container**, before any of the above is asked
 *     ([GameEngineDetector.holdsSeveralGames]). `adult/godot` holds two
 *     Godot games and a loose Godot Linux build left beside them, so
 *     rule 2 read it as a game and hid both. Engine evidence is what
 *     tells that shape from an install's payload folders: `bin` and
 *     `bin_plus` hold an executable and no engine at all.
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
     *
     * [defs] are the engine-detection rules, used for rule 6 only -- an
     * empty list simply means "no folder is known to hold engine games",
     * which is what a caller with no database loaded should get.
     */
    fun gamesUnder(root: File, defs: List<EngineDef> = emptyList()): List<File> =
        gamesByTopLevelFolder(root, defs).flatMap { it.games }

    /** One top-level folder of a games root, and the PC games under it. */
    data class TopLevelFolder(val folder: File, val games: List<File>)

    /**
     * The same walk, kept in the shape the index merges in: one entry per
     * top-level folder of [root], so the caller can replace that folder's
     * games on their own and say which folders the root still has
     * (docs/SPEC.md 7g). A folder with no games under it is still listed,
     * with none: "this folder is here and holds no games" and "this
     * folder is gone" are different answers.
     */
    fun gamesByTopLevelFolder(root: File, defs: List<EngineDef> = emptyList()): List<TopLevelFolder> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            childrenOf(root).map { child -> TopLevelFolder(child, walk(child, defs, depth = 1)) }
        }

    private fun walk(folder: File, defs: List<EngineDef>, depth: Int): List<File> {
        if (!folder.isDirectory || !ScanPrune.isScannableFolder(folder)) return emptyList()
        // A store's own install root is the store's business, never a
        // game, however many executables its client drops in it.
        val isStoreRoot = ScanPrune.storeRootOwner(folder) != null
        // Rule 6. Lazy: it costs one directory listing per child, and
        // only a folder that would otherwise BE an entry needs the
        // answer. A folder holding engine games is a category folder,
        // and anything it holds of its own is a stray.
        val holdsEngineGames by lazy { defs.isNotEmpty() && GameEngineDetector.holdsSeveralGames(folder, defs) }
        if (!isStoreRoot && GameExecutableResolver.hasExecutable(folder) && !holdsEngineGames) return listOf(folder)

        // The engine walk's depth rule, asked of the same names: a part or
        // version folder is one game's structure and costs no depth
        // (GameNaming.isStructuralFolderName, docs/SPEC.md 7m).
        val below = childrenOf(folder).flatMap { child ->
            when {
                GameNaming.isStructuralFolderName(child.name) -> walk(child, defs, depth)
                depth < MAX_SCAN_DEPTH -> walk(child, defs, depth + 1)
                else -> emptyList()
            }
        }
        if (below.isEmpty()) return emptyList()
        val holdsOwnFiles = (folder.listFiles() ?: emptyArray()).any { it.isFile && !it.name.startsWith(".") }
        val insideStoreTree = isStoreRoot || ScanPrune.storeTreeRoot(folder) != null
        // A part or version folder with a stray file of its own is still
        // not the game; the game is the folder below it that has one.
        val structural = GameNaming.isStructuralFolderName(folder.name)
        return if (holdsOwnFiles && !insideStoreTree && !holdsEngineGames && !structural) listOf(folder) else below
    }

    private fun childrenOf(folder: File): List<File> =
        (folder.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .sortedBy { it.name.lowercase() }
}
