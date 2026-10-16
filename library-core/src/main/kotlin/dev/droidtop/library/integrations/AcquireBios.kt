package dev.droidtop.library.integrations

import dev.droidtop.library.consoles.BiosFileSpec
import java.io.File

/**
 * Where a source's download goes when it is a BIOS file (Droidtop/tracker#558): an emulator looks for it in the games
 * folder's `bios` folder under the name droidtop's BIOS database gives it, never among the games. A BIOS file from Game
 * sources was put in the folder the screen named, which for the BIOS and firmware plugin was the games root itself
 * (RP5, build 1923: Roms/ps2-0230a-20080220.bin, no Roms/bios). Pure: the caller reads the database and the roots.
 */
internal object AcquireBios {
    /**
     * The database entry [fileName] fills for its system, or null when it is not a BIOS file: its md5 is one the entry
     * lists, or, from a plugin that offers BIOS files ([providesBios], `emulator.bios`), its name is the entry's.
     */
    fun entryFor(files: List<BiosFileSpec>, fileName: String, md5: String?, providesBios: Boolean): BiosFileSpec? {
        val sum = md5?.trim()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
        files.firstOrNull { entry -> sum != null && entry.md5.any { it.equals(sum, ignoreCase = true) } }?.let { return it }
        if (!providesBios) return null
        return files.firstOrNull { entry ->
            entry.file.removePrefix("bios/").equals(fileName, ignoreCase = true) ||
                entry.file.substringAfterLast('/').equals(fileName, ignoreCase = true)
        }
    }

    /** The games folder [destination] is in (the longest of [roots] holding it, or the root itself), else its own parent. */
    fun rootFor(roots: List<File>, destination: File): File {
        val path = destination.absolutePath.trimEnd('/')
        return roots.filter { root ->
            val r = root.absolutePath.trimEnd('/')
            path == r || path.startsWith("$r/")
        }.maxByOrNull { it.absolutePath.length } ?: destination.parentFile ?: destination
    }

    /** The file [entry] names under [root]: `<root>/bios/...` as the database spells it. */
    fun targetFor(root: File, entry: BiosFileSpec): File = File(root, entry.file)
}
