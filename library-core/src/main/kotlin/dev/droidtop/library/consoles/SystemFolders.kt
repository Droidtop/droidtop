package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.ScanPrune
import dev.droidtop.library.ScanSkips
import java.io.File

/**
 * Where the console system folders are: the ONE walk that answers it.
 *
 * The library's scan, the Console systems settings page, "Scrape all
 * systems" (in Settings and in the gamelist menu), a gamelist's own
 * scrape and the orphaned-media check all need this answer. Each of the
 * others used to walk one level below each root by itself, so on a
 * library laid out as `<root>/roms/<system>` they found no system at all,
 * and the settings page, guessing from file extensions instead, listed
 * every store folder as "Unrecognized" (UI pass 2026-09-24, H6).
 */
object SystemFolders {

    /** The system folders under one root, and the folders the walk refused and why. */
    class Scan(val found: List<Pair<File, ConsoleSystemDef>>, val skipped: ScanSkips)

    /**
     * Every console system folder under [root].
     *
     * Two levels deep ([MAX_SYSTEM_SEARCH_DEPTH]), because a real root is
     * often the user's whole library, stores and ROMs side by side, with
     * the ROMs at `<root>/roms/<system>`. Not deeper, deliberately:
     * platform ids are ordinary words (`android`, `pc`, `windows`,
     * `flash`), and a folder three levels down is inside a game, where a
     * subfolder named like a platform would invent a system that does not
     * exist. A folder the person assigned a system to explicitly
     * ([SystemOverridePrefs]) counts wherever it sits.
     */
    fun under(context: Context, root: File, systemsById: Map<String, ConsoleSystemDef>): Scan {
        val skipped = ScanSkips()
        val found = mutableListOf<Pair<File, ConsoleSystemDef>>()

        fun walk(folder: File, depth: Int) {
            val children = (folder.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }
            for (child in children) {
                val pruned = ScanPrune.skipReason(child)
                val storeOwner = ScanPrune.storeRootOwner(child)
                when {
                    pruned != null -> skipped.add(child, pruned)
                    // A store's install tree is the PC library's, whatever
                    // the folder is called -- see ScanPrune.storeRootOwner.
                    storeOwner != null ->
                        skipped.add(child, "$storeOwner owns this tree — its games are the PC library's, not a ROM system")
                    else -> {
                        val system = SystemOverridePrefs.resolveForFolder(context, child.absolutePath, child.name, systemsById)
                        when {
                            // A system folder's contents are ROMs, so the
                            // search stops here and the scan takes over.
                            system != null -> found += child to system
                            depth < MAX_SYSTEM_SEARCH_DEPTH -> walk(child, depth + 1)
                        }
                    }
                }
            }
        }

        walk(root, 1)
        val rootPrefix = root.absolutePath.trimEnd('/') + "/"
        for ((path, systemId) in SystemOverridePrefs.assigned(context)) {
            if (!path.startsWith(rootPrefix)) continue
            if (found.any { (folder, _) -> path == folder.absolutePath || path.startsWith(folder.absolutePath + "/") }) continue
            val folder = File(path)
            val system = systemsById[systemId]?.takeIf { it.canResolveFromFolder() } ?: continue
            if (folder.isDirectory) found += folder to system
        }
        return Scan(found, skipped)
    }

    /** [under] for every games root, system folders only. */
    fun all(context: Context, systemsById: Map<String, ConsoleSystemDef>): List<Pair<File, ConsoleSystemDef>> =
        GamesRoots.current(context).flatMap { root -> under(context, root, systemsById).found }

    /**
     * The folders the person picked to choose a system for and has not
     * chosen yet ([SystemOverridePrefs.NOT_SET]), under any games root.
     */
    fun awaitingSystem(context: Context): List<File> {
        val roots = GamesRoots.current(context).map { it.absolutePath.trimEnd('/') + "/" }
        return SystemOverridePrefs.assigned(context)
            .filter { (path, systemId) -> systemId == SystemOverridePrefs.NOT_SET && roots.any { path.startsWith(it) } }
            .map { (path, _) -> File(path) }
            .filter { it.isDirectory }
    }
}
