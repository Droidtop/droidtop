package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemFolders
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a source result in the one search downloads to (docs/SPEC.md 12a
 * "One search", Droidtop/tracker#485). A console list passes its own system
 * and folder. A list with no system of its own (the PC library, the
 * launcher, the Desktop Start menu) works it out from the result: the
 * platform the source reported names a console system with a games folder, or
 * it is a PC game, which goes into the PC folder: a `windows` or `pc` system
 * folder when the person has one, else the first games folder they named
 * (the PC walk finds a game under any of them). Never a made-up path: when no
 * folder exists the pick says which one is missing.
 */
object SourceDestinations {
    /** Where one result goes: the system for the plugin's context, and its folder (null: none exists yet). */
    class Pick(val systemId: String?, val systemName: String?, val folder: File?)

    private val PC_IDS = setOf("pc", "windows")
    private val PLATFORM_SPLIT = Regex("[,/;|&·]")

    /** [pick] with the system and folder lists read off the main thread: only when a result is picked, never while typing. */
    suspend fun resolve(context: Context, listSystemId: String?, listFolder: File?, platform: String?): Pick {
        if (listFolder != null) return Pick(listSystemId, null, listFolder)
        return withContext(Dispatchers.IO) {
            val systems = ConsoleSystemsRepository.allSystems(context)
            val folders = SystemFolders.all(context, systems.associateBy { it.id })
            pick(listSystemId, null, platform, systems, folders, GamesRoots.configured(context))
        }
    }

    /** The rule, over lists already read: pure, so it is unit-tested. */
    fun pick(
        listSystemId: String?,
        listFolder: File?,
        platform: String?,
        systems: List<ConsoleSystemDef>,
        folders: List<Pair<File, ConsoleSystemDef>>,
        pcRoots: List<File>,
    ): Pick {
        fun nameOf(id: String?) = systems.firstOrNull { it.id == id }?.displayName ?: id
        if (listFolder != null) return Pick(listSystemId, nameOf(listSystemId), listFolder)
        // A console list knows its system even before its folder is found.
        if (listSystemId != null && listSystemId !in PC_IDS) {
            return Pick(listSystemId, nameOf(listSystemId), folders.firstOrNull { it.second.id == listSystemId }?.first)
        }
        val words = platform.orEmpty().split(PLATFORM_SPLIT).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val console = words.firstNotNullOfOrNull { word ->
            systems.firstOrNull { it.id !in PC_IDS && (it.id.lowercase() == word || it.displayName.lowercase() == word) }
        }
        if (console != null) return Pick(console.id, console.displayName, folders.firstOrNull { it.second.id == console.id }?.first)
        val pcFolder = folders.firstOrNull { it.second.id in PC_IDS }?.first ?: pcRoots.firstOrNull()
        return Pick(PC_SYSTEM_ID, "PC", pcFolder)
    }

    /**
     * Whether a source result belongs in a PC list: one that names a console system (and no PC platform) is not a PC game.
     * A result that names no platform, or one nobody knows, stays (a PC source rarely says). Pure.
     */
    fun isPcResult(platform: String?, consoleNames: Set<String>): Boolean {
        val words = platform.orEmpty().split(PLATFORM_SPLIT).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        if (words.any { it in PC_WORDS }) return true
        return words.none { it in consoleNames }
    }

    /** The names (ids and display names, lower case) of every console system that is not the PC: what [isPcResult] weeds out. */
    fun consoleNamesOf(systems: List<ConsoleSystemDef>): Set<String> =
        systems.filter { it.id !in PC_IDS }.flatMap { listOf(it.id.lowercase(), it.displayName.lowercase()) }.toSet()

    private const val PC_SYSTEM_ID = "pc"
    private val PC_WORDS = setOf("pc", "windows", "win", "linux", "mac", "macos", "osx", "steam")
}
