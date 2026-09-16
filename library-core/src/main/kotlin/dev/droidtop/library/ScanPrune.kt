package dev.droidtop.library

import java.io.File

/**
 * THE one rule for "may a library scan descend into this directory", in
 * one place. Every walk that enumerates folders looking for games asks
 * this and nothing else: [GameEngineDetector.scan]'s candidate folders and
 * its nested search, [dev.droidtop.library.consoles.RomScanWalk]'s ROM
 * walk, [GamesRootReport]'s preview of a root, and
 * [dev.droidtop.library.consoles.EsDeFolderStructure]'s system probe.
 * There used to be two half-rules (the detector's hidden/marker test and
 * the ROM walk's add-on-directory test, the second calling the first), and
 * nothing covered a store's own tree at all.
 *
 * Three rules, in the order they are checked, each from a real failure:
 *
 *  1. **Hidden folders and filesystem bookkeeping.** A leading dot is how
 *     Syncthing, Android's own caches and every Unix tool mark "not for
 *     you" (`.stfolder`, `.stversions`, `.trash-1000`, `.thumbnails`,
 *     `.gamenative`), plus the handful of Windows markers that never
 *     carry a dot. The rig showed this directly: a Syncthing marker
 *     folder inside a games root was listed as ".STFOLDER [PC]".
 *
 *  2. **A store's own install tree belongs to the store provider.** This
 *     is the rule behind the reported scan failure (rig, build 523, games
 *     root = the user's whole `G:\games`): the root's `Steam` folder
 *     resolves to the real ES-DE platform id `steam` ("Valve Steam",
 *     extensions `desktop`/`sh`), so the ROM provider walked the entire
 *     Steam install — 1434 directories under `steamapps/workshop` alone —
 *     and the whole provider's results were discarded when its budget ran
 *     out. Only 11 games surfaced.
 *
 *     A store library root is recognised by the store's own marker, and
 *     under it a generic scan follows ONLY the subtree that holds
 *     installed games. Expressed that way rather than as a list of folder
 *     names to avoid, because the list is long, version-dependent and
 *     unknowable, while the games subtree is one documented path: the
 *     same rule prunes `workshop`, `downloading`, `shadercache`, `temp`
 *     and `sourcemods` and the 19 client directories of a real Steam
 *     install (`appcache`, `bin`, `clientui`, `config`, `controller_base`,
 *     `depotcache`, `dumps`, `friends`, `graphics`, `logs`, `music`,
 *     `package`, `public`, `resource`, `steam`, `steamui`, `tenfoot`,
 *     `userdata`, …) without naming any of them.
 *
 *     `steamapps/common` itself stays open, deliberately: a
 *     store-installed engine game must flow through the same detection,
 *     grouping and launch resolution as one in a games folder (docs/SPEC.md
 *     §7g, yardstick item 5). Engine detection may look inside
 *     `steamapps/common`; nothing looks inside `workshop`.
 *
 *  3. **A store's per-game payload folder is not a game.** Only names
 *     verified on the user's own library, for the same reason the ROM
 *     walk's marker list is short: a rule that hides real games to tidy a
 *     list is worse than the bug it fixes.
 *
 * What is deliberately NOT here, and why, because both were checked
 * against the real library on 2026-09-11 rather than assumed:
 *
 * - **`Launcher` is not pruned.** A real GOG game in this library ships
 *   its own `Launcher` folder (`GOG/Prison Architect/Launcher`), so the
 *   name proves nothing about a store.
 * - **Epic, EA, Game Pass, Humble, battle.net and Ubisoft need no entry.**
 *   In the real layout these roots hold game folders and hidden marker
 *   files and nothing else; the generic walk is already right there. They
 *   get an entry when a real tree shows one, not before.
 */
object ScanPrune {

    /**
     * Windows-side bookkeeping directories that never carry a leading
     * dot. Everything that does is covered by the dot rule instead.
     */
    private val NEVER_A_GAME_FOLDER = setOf(
        "system volume information",
        "\$recycle.bin",
        "recycler",
        "lost+found",
        "found.000",
        // Java/Adobe AIR packaging metadata. Verified on the rig: an AIR
        // game (`Bunnycop-049-WINDOWS`) ships its native extensions as
        // `META-INF/AIR/extensions/<ext>/META-INF/ANE/default/library.swf`,
        // and the Flash rule matched four of those `library.swf` files, so
        // the library listed four entries named after the extension or
        // after the whole path to it -- and never the game itself.
        "meta-inf",
    )

    /**
     * A store's per-game payload directory: shipped inside the game's own
     * folder, carrying the store's installer or redistributables rather
     * than the game. `__Installer` is EA's, verified in this library
     * (`EA/SimCity/__Installer`).
     */
    private val STORE_PAYLOAD_FOLDERS = setOf("__installer")

    /**
     * One store's install tree: how its root is recognised, and the only
     * paths under that root a generic scan follows.
     *
     * [rootName] narrows recognition to a directory with that exact name
     * where the markers alone would be ambiguous; null means the markers
     * are enough on their own.
     */
    private data class StoreTree(
        val store: String,
        val rootName: String?,
        val rootMarkers: Set<String>,
        val gameSubtrees: Set<String>,
    ) {
        /**
         * Whether [relative] (this root's path to the directory being
         * judged, `/`-joined) is on the way to, or inside, a games
         * subtree. Both directions matter: `steamapps` must be entered to
         * reach `steamapps/common`, and `steamapps/common/<game>/data`
         * must stay open once inside it.
         */
        fun allows(relative: String): Boolean = gameSubtrees.any { subtree ->
            relative.equals(subtree, ignoreCase = true) ||
                relative.startsWith("$subtree/", ignoreCase = true) ||
                subtree.startsWith("$relative/", ignoreCase = true)
        }
    }

    /**
     * Every store whose install tree has a non-game half. Each entry's
     * markers and games subtree are read off a real install, cited in this
     * object's own doc comment; a store is added here when a real tree
     * shows the need.
     */
    private val STORE_TREES = listOf(
        // A Steam library folder: the client's own install root
        // (`C:\Program Files (x86)\Steam`, which has `steamapps`) and any
        // additional library folder the user adds (the rig's
        // `G:\games\Steam`, which has `steamapps`, `libraryfolder.vdf`
        // and `steam.dll`) are the same shape and both matched.
        StoreTree(
            store = "Steam",
            rootName = null,
            rootMarkers = setOf("steamapps", "libraryfolder.vdf", "libraryfolders.vdf"),
            gameSubtrees = setOf("steamapps/common"),
        ),
        // GOG Galaxy's client install root (`Dependencies`,
        // `Dependencies-Temp`, `Games`): only `Games` holds games. Name-
        // anchored because `Dependencies` beside a `Games` folder is a
        // shape a game could have too.
        StoreTree(
            store = "GOG Galaxy",
            rootName = "gog galaxy",
            rootMarkers = setOf("Dependencies", "Games"),
            gameSubtrees = setOf("Games"),
        ),
    )

    /**
     * How far above a directory a store root is looked for. Four is
     * [GameEngineDetector.MAX_SCAN_DEPTH]; two more cover a ROM walk's
     * deeper recursion inside one folder.
     */
    private const val MAX_STORE_ROOT_SEARCH_DEPTH = 6

    /**
     * Why a library scan must not descend into [dir], phrased for a log
     * line a person reads, or null to descend. Callers that only need the
     * verdict use [isScannableFolder].
     */
    fun skipReason(dir: File): String? {
        val name = dir.name
        if (name.startsWith(".")) return "it is a hidden folder"
        val lower = name.lowercase()
        if (lower in NEVER_A_GAME_FOLDER) return "it is a filesystem or sync marker folder"
        if (lower in STORE_PAYLOAD_FOLDERS) return "it holds a store's installer payload, not a game"
        return storeManagedReason(dir)
    }

    /** [skipReason]'s verdict alone. */
    fun isScannableFolder(dir: File): Boolean = skipReason(dir) == null

    /**
     * The store whose install root [dir] IS, or null.
     *
     * A store's install tree is the PC library's business (docs/SPEC.md
     * §7g, §7i): those games are store games with store facts, runners and
     * install state. The ROM scan asks this because a store root's NAME can
     * resolve to a real ES-DE platform -- `steam` ("Valve Steam") and
     * `epic` ("Epic Games Store") are real ids in the platforms database,
     * whose extensions are `desktop`/`sh` because in ES-DE they hold
     * shortcut FILES -- and the rig showed what that costs when the folder
     * is an actual Steam library instead: the ROM provider walked
     * `steamapps/common` for 20 seconds and listed five Linux launch
     * scripts as ROMs of system "steam". A store root is not a system
     * folder, whatever it is called.
     */
    fun storeRootOwner(dir: File): String? = storeRootAt(dir)?.store

    /**
     * The store install root at or above [dir], or null when [dir] is not
     * inside one. [PcFolderScan] asks this because the "a folder with its
     * own files and one game below it IS that game" shape is true of a
     * game and false of `steamapps`.
     */
    fun storeTreeRoot(dir: File): File? {
        var candidate: File? = dir
        var depth = 0
        while (candidate != null && depth <= MAX_STORE_ROOT_SEARCH_DEPTH) {
            if (storeRootAt(candidate) != null) return candidate
            candidate = candidate.parentFile
            depth++
        }
        return null
    }

    /**
     * Why [dir] is a store's own business rather than a place games live.
     * Walks up to the nearest store root and judges the path from there,
     * so the rule is stated once per store instead of once per folder name
     * a store happens to use.
     */
    private fun storeManagedReason(dir: File): String? {
        var relative = dir.name
        var parent = dir.parentFile
        var depth = 0
        while (parent != null && depth < MAX_STORE_ROOT_SEARCH_DEPTH) {
            val store = storeRootAt(parent)
            if (store != null) {
                return if (store.allows(relative)) {
                    null
                } else {
                    "${store.store} owns this tree -- its games are listed from " +
                        store.gameSubtrees.joinToString(", ")
                }
            }
            relative = "${parent.name}/$relative"
            parent = parent.parentFile
            depth++
        }
        return null
    }

    /** The store whose install root [dir] is, or null. */
    private fun storeRootAt(dir: File): StoreTree? = STORE_TREES.firstOrNull { store ->
        (store.rootName == null || dir.name.lowercase() == store.rootName) &&
            store.rootMarkers.any { File(dir, it).exists() }
    }
}
