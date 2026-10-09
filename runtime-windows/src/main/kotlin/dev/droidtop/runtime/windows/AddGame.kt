package dev.droidtop.runtime.windows

import android.content.Context
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.WindowsPrograms
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemFolders
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.runtime.windows.utils.CustomGameScanner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Add a game" from a file or a folder (docs/SPEC.md 7g, "Adding a game by hand", Droidtop/tracker#407): the person
 * points at a game where it already is, and it becomes a library row like any other. The files are never copied,
 * moved or written to; droidtop records only where the game is.
 *
 * - Inside one of the person's game folders, the path is indexed where it is, by the same targeted indexing a change
 *   droidtop makes uses ([LibraryPaths]): a game folder, a Windows program or an HTML game's folder by the PC folder
 *   rule, a ROM by the ROM walk's own rules for the system folder it is in.
 * - Outside them, a game's folder (the picked folder, or the folder of a picked `.exe` or `.html`) joins the folder
 *   scanner's list of folders it was given by hand ([PrefManager.customGameManualFolders]), which the store part of
 *   the PC library already reads; engine detection looks inside it through the PC library's known installs, so an
 *   engine game (Ren'Py, RPG Maker, an HTML game) is found there as it would be in a game folder. A picked `.exe` is
 *   also made the program the game runs ([WindowsPrograms.choose]).
 * - A ROM outside every game folder is not listed: ROMs are read from the system folders of game folders, and the
 *   answer says so.
 */
object AddGame {
    /** What a picked file or folder is to the library. */
    enum class Kind { FOLDER, WINDOWS_PROGRAM, WEB_PAGE, ROM, UNKNOWN }

    private val WINDOWS_PROGRAMS = setOf("exe", "bat")
    private val WEB_PAGES = setOf("html", "htm")

    /** What [name] is: a folder, a Windows program, an HTML game's page, a ROM of a system droidtop knows, or none. */
    fun kindOf(isDirectory: Boolean, name: String, romExtensions: Set<String>): Kind {
        if (isDirectory) return Kind.FOLDER
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension.isEmpty() -> Kind.UNKNOWN
            extension in WINDOWS_PROGRAMS -> Kind.WINDOWS_PROGRAM
            extension in WEB_PAGES -> Kind.WEB_PAGE
            extension in romExtensions -> Kind.ROM
            else -> Kind.UNKNOWN
        }
    }

    /** True when [path] is [folder] or below it. */
    fun isUnder(path: File, folder: File): Boolean {
        val base = folder.absolutePath.trimEnd('/')
        return path.absolutePath == base || path.absolutePath.startsWith("$base/")
    }

    /**
     * Why [folder] cannot be one game, or null when it can: a whole volume, or a folder that holds one of the
     * person's game folders, would make every game under it one entry.
     */
    fun refusalFor(folder: File, roots: List<File>): String? = when {
        isVolume(folder) -> "That is a whole drive. Pick the folder of one game"
        roots.any { isUnder(it, folder) } -> "That folder holds your game folders. Pick the folder of one game"
        else -> null
    }

    /** True for the root of a volume: `/`, `/sdcard`, `/storage/emulated/0`, an SD card's `/storage/<id>`. */
    fun isVolume(folder: File): Boolean {
        val parts = folder.absolutePath.trim('/').split('/').filter { it.isNotEmpty() }
        return parts.isEmpty() || parts == listOf("sdcard") ||
            (parts.first() == "storage" && (parts.size <= 2 || (parts[1] == "emulated" && parts.size <= 3)))
    }

    /**
     * Adds what the person picked; the line the row shows (what happened, or why nothing did). Disk work, run on the IO
     * dispatcher whatever the caller.
     */
    suspend fun add(context: Context, picked: File): String = withContext(Dispatchers.IO) {
        if (!picked.exists() || !picked.canRead()) return@withContext "droidtop cannot read that. Pick a file or folder on this device's storage"
        val systems = runCatching { ConsoleSystemsRepository.allSystems(context) }.getOrDefault(emptyList())
        val kind = kindOf(picked.isDirectory, picked.name, systems.flatMap { it.extensions }.mapTo(HashSet()) { it.lowercase() })
        val roots = GamesRoots.current(context)
        val inGameFolder = roots.any { isUnder(picked, it) && picked.absolutePath.trimEnd('/') != it.absolutePath.trimEnd('/') }
        when (kind) {
            Kind.UNKNOWN -> "droidtop does not know ${picked.name} as a game. Pick the game's folder, its .exe, its .html page or a ROM"
            Kind.ROM -> when {
                !inGameFolder ->
                    "${picked.name} is outside your game folders. ROMs are listed from the system folders of your game folders: add the folder that holds it under Game folders"
                SystemFolders.systemFolderFor(context, roots, picked, systems.associateBy { it.id }) == null ->
                    "${picked.name} is not in a system's folder. Put it in its system's folder (snes, psx, ...) in your game folder"
                else -> {
                    LibraryPaths.report(context, PathChange(added = listOf(picked.absolutePath)))
                    "Added ${picked.nameWithoutExtension}"
                }
            }
            Kind.FOLDER, Kind.WINDOWS_PROGRAM, Kind.WEB_PAGE -> {
                val folder = (if (picked.isDirectory) picked else picked.parentFile) ?: return@withContext "droidtop cannot read that folder"
                refusalFor(folder, roots)?.let { return@withContext it }
                if (inGameFolder) {
                    // Where the person keeps games already: the folder rule decides which folder is the game.
                    LibraryPaths.report(context, PathChange(added = listOf(folder.absolutePath)))
                } else {
                    PrefManager.addCustomGameManualFolder(folder.absolutePath)
                    if (kind == Kind.WINDOWS_PROGRAM) {
                        CustomGameScanner.createLibraryItemFromFolder(folder.absolutePath)?.let { item ->
                            WindowsPrograms.choose(context, "folder:${item.appId}", picked.absolutePath.removePrefix(folder.absolutePath.trimEnd('/') + "/"))
                        }
                    }
                    // The store part lists the scanner's folders; reading it again lists this one, and the folder
                    // is then indexed as a known install, which is where engine detection finds an engine game.
                    StoreChanges.announce(context, also = listOf(folder.absolutePath))
                }
                "Added ${folder.name}"
            }
        }
    }
}
