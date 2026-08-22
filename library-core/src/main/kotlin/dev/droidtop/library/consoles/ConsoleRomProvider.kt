package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Folder-name mismatches actually confirmed between a real ROMs
 * collection (checked against a real test device this session) and
 * ES-DE's own canonical system ids -- not a guessed/exhaustive list, just
 * the real cases seen so far. Add more as they come up.
 */
private val SYSTEM_ID_ALIASES: Map<String, String> = mapOf(
    "ps1" to "psx",
    "3ds" to "n3ds",
    "nsw" to "switch",
)

private val SYSTEMS_BY_ID: Map<String, ConsoleSystemDef> =
    ES_DE_CONSOLE_SYSTEMS.associateBy { it.id }

/** Resolves a ROMs subfolder name to a known [ConsoleSystemDef], checking [SYSTEM_ID_ALIASES] first. */
internal fun resolveSystem(folderName: String): ConsoleSystemDef? {
    val id = folderName.lowercase()
    return SYSTEMS_BY_ID[SYSTEM_ID_ALIASES[id] ?: id]
}

/**
 * [LibraryProvider] for real console ROMs, scanning `<root>/<systemId>/
 * <romFile>` -- the same layout ES-DE itself uses (confirmed against a
 * real device's existing ROMs folder this session), so an existing
 * collection works with no reorganizing.
 *
 * Launch strategy per system: [DefaultPlayers.retroArch] when ES-DE's data
 * says a RetroArch core exists for that system, via the real, working
 * [AmStartCommandToIntentConverter]. Systems with no known RetroArch core
 * (standalone-emulator-only systems like PS3/Switch -- see
 * EsDeConsoleSystems.kt's own data) currently have no Player at all and
 * are skipped during scan, not shown as broken entries -- a real per-
 * system Player override (letting a user point a system at a specific
 * standalone emulator's own am-start command) is the next real gap here,
 * not attempted in this pass.
 */
class ConsoleRomProvider(
    private val context: Context,
    private val romsRoots: List<File>,
) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = setOf(LibraryEntryKind.CONSOLE_ROM)

    // Real bug this fixes, reported directly: a huge single system folder
    // (a real device's "j2me" folder had 18,126 files) made the *entire*
    // scan slow, since every system was scanned sequentially in one
    // coroutine -- one huge folder blocked every other system, fast or
    // slow, from ever returning. Each system folder now scans as its own
    // coroutine, so j2me being slow no longer holds up nes/gba/psx/etc,
    // which return as soon as their own (much smaller) folders are read.
    override suspend fun scan(): List<LibraryEntry> = coroutineScope {
        romsRoots
            .flatMap { root -> (root.listFiles() ?: emptyArray()).filter { it.isDirectory } }
            .mapNotNull { systemFolder ->
                SystemOverridePrefs.resolveForFolder(context, systemFolder.absolutePath, systemFolder.name)
                    ?.let { systemFolder to it }
            }
            .map { (systemFolder, system) -> async { scanSystemFolder(systemFolder, system) } }
            .awaitAll()
            .flatten()
    }

    // Recursive, not just the system folder's immediate children -- lets a
    // large system be reorganized into subfolders (by first letter, by
    // collection, whatever) for real, purely as a filesystem organization
    // choice, without that reorganizing ever hiding files from droidtop:
    // every file under the system folder at any depth is still found and
    // still counted as belonging to that one system.
    private fun scanSystemFolder(systemFolder: File, system: ConsoleSystemDef): List<LibraryEntry> {
        if (DefaultPlayers.retroArch(system) == null) return emptyList()
        return systemFolder.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in system.extensions }
            .map { romFile ->
                LibraryEntry(
                    id = romFile.absolutePath,
                    title = romFile.nameWithoutExtension,
                    kind = LibraryEntryKind.CONSOLE_ROM,
                    systemId = system.id,
                )
            }
            .toList()
    }

    override suspend fun launch(entry: LibraryEntry) {
        val romFile = File(entry.id)
        val parentFolder = romFile.parentFile
        val system = SystemOverridePrefs.resolveForFolder(context, parentFolder?.absolutePath ?: "", parentFolder?.name ?: "")
            ?: error("Couldn't resolve a console system for ${entry.id}")
        val player = DefaultPlayers.retroArch(system)
            ?: error("No Player configured for system ${system.id}")
        val intent = AmStartCommandToIntentConverter.toIntent(player.argumentsTemplate, romFile.absolutePath)
        context.startActivity(intent)
    }
}
