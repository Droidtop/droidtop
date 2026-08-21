package dev.droidtop.library.consoles

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
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

    override suspend fun scan(): List<LibraryEntry> =
        romsRoots.flatMap { root ->
            (root.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .mapNotNull { systemFolder -> resolveSystem(systemFolder.name)?.let { systemFolder to it } }
                .flatMap { (systemFolder, system) ->
                    if (DefaultPlayers.retroArch(system) == null) return@flatMap emptyList()
                    (systemFolder.listFiles() ?: emptyArray())
                        .filter { it.isFile && it.extension.lowercase() in system.extensions }
                        .map { romFile ->
                            LibraryEntry(
                                id = romFile.absolutePath,
                                title = romFile.nameWithoutExtension,
                                kind = LibraryEntryKind.CONSOLE_ROM,
                                systemId = system.id,
                            )
                        }
                }
        }

    override suspend fun launch(entry: LibraryEntry) {
        val romFile = File(entry.id)
        val system = resolveSystem(romFile.parentFile?.name ?: "")
            ?: error("Couldn't resolve a console system for ${entry.id}")
        val player = DefaultPlayers.retroArch(system)
            ?: error("No Player configured for system ${system.id}")
        val intent = AmStartCommandToIntentConverter.toIntent(player.argumentsTemplate, romFile.absolutePath)
        context.startActivity(intent)
    }
}
