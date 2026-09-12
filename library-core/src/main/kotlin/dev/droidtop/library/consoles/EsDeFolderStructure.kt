package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.GameEngineDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ES-DE's "create the system directory structure" repair, which droidtop
 * offers whenever a games folder turns out to hold nothing (docs/SPEC.md
 * 7b, "No games yet"). ES-DE's desktop build offers three concrete
 * choices there -- pick a different folder, generate the structure,
 * continue empty -- and its Android build degrades to an information
 * dialog; droidtop takes the desktop behaviour everywhere.
 *
 * What it writes is ES-DE's own: one folder per system, named by the
 * same `es_systems.xml` id droidtop already adopted as its platform
 * taxonomy, plus a `bios` folder, plus a `systeminfo.txt` inside each
 * system folder so an empty folder explains itself rather than looking
 * like a mistake.
 *
 * Safe to re-run, which is the whole reason it can be offered from a
 * repair prompt: an existing folder is left exactly as it is and an
 * existing `systeminfo.txt` is never overwritten. It creates folders and
 * nothing else -- it never moves, renames or deletes a file.
 */
object EsDeFolderStructure {

    /** What a run actually did, so the step can report it rather than claim it. */
    data class Created(val systemFolders: List<String>, val alreadyThere: List<String>, val biosFolder: Boolean)

    suspend fun generate(context: Context, root: File): Created = withContext(Dispatchers.IO) {
        val systems = ConsoleSystemsRepository.allSystems(context).sortedBy { it.id }
        val made = mutableListOf<String>()
        val existing = mutableListOf<String>()

        systems.forEach { system ->
            val folder = File(root, system.id)
            if (folder.isDirectory) {
                existing += system.id
            } else if (folder.mkdirs()) {
                made += system.id
            }
            writeSystemInfo(folder, system)
        }

        val bios = File(root, "bios")
        val biosMade = !bios.isDirectory && bios.mkdirs()

        Created(systemFolders = made, alreadyThere = existing, biosFolder = biosMade)
    }

    /**
     * ES-DE writes one of these into every system folder it generates, so
     * a person browsing an empty folder on a PC can tell what belongs in
     * it. Never overwritten: the file is the person's once it exists.
     */
    private fun writeSystemInfo(folder: File, system: ConsoleSystemDef) {
        if (!folder.isDirectory) return
        val info = File(folder, "systeminfo.txt")
        if (info.exists()) return
        val extensions = system.extensions.sorted().joinToString(" ") { ".$it" }
        runCatching {
            info.writeText(
                buildString {
                    appendLine("System name:")
                    appendLine(system.id)
                    appendLine()
                    appendLine("Full system name:")
                    appendLine(system.displayName)
                    appendLine()
                    appendLine("Supported file extensions:")
                    appendLine(if (extensions.isBlank()) "(any)" else extensions)
                    appendLine()
                    appendLine("Put this system's games in this folder.")
                },
            )
        }
    }

    /** The one sentence the repair step reports back. */
    fun describe(created: Created): String {
        val made = created.systemFolders.size
        return when {
            made == 0 && created.biosFolder -> "Every system folder was already there; added a bios folder."
            made == 0 -> "Every system folder was already there, so nothing changed."
            else -> "Created $made system folders" +
                (if (created.biosFolder) " and a bios folder" else "") +
                ". Put a system's games in the folder named after it."
        }
    }

    /**
     * Whether [root] looks like it has already been given the structure,
     * so the offer can say "again" honestly rather than pretending this
     * is the first time.
     */
    fun alreadyStructured(root: File): Boolean =
        (root.listFiles() ?: emptyArray()).count {
            it.isDirectory && dev.droidtop.library.ScanPrune.isScannableFolder(it) && File(it, "systeminfo.txt").isFile
        } > 0
}
