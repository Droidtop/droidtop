package dev.droidtop.library.consoles

import android.content.Context
import android.content.Intent
import dev.droidtop.library.LibraryEntry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Add the update and DLC in the emulator" for a Switch game
 * (docs/SPEC.md 7m, "Switch content"; Droidtop/tracker#13). No tested
 * emulator exposes an install intent, and handing it the file as a VIEW
 * intent would start the file as a game, so droidtop cannot install
 * anything. What it can honestly do is open the door: start the emulator
 * the game would launch with, at its own launcher activity, and say which
 * of the game's files to pick in the emulator's own install-content
 * picker. The files are only named, never copied, moved or opened.
 */
object SwitchContentHandoff {
    /** The update and DLC files of [entry]'s game, as the grouping found them (the update first, then the DLC). */
    fun filesToPick(entry: LibraryEntry): List<File> {
        val facts = entry.switchFacts ?: return emptyList()
        if (facts.loose) return emptyList()
        return (facts.updatePaths + facts.dlcPaths).distinct().map(::File)
    }

    /** Whether [entry] is a Switch game that has files to add; the page shows the row only then. */
    fun applies(entry: LibraryEntry): Boolean = filesToPick(entry).isNotEmpty()

    /** The sentence that names what to pick, for the [emulator] that was opened and the [files] to choose. Pure. */
    fun instruction(emulator: String, files: List<String>): String {
        val shown = files.take(MAX_NAMED)
        val more = files.size - shown.size
        return "Opened $emulator. In its install-content picker, choose: " +
            shown.joinToString(", ") + if (more > 0) " and $more more" else ""
    }

    /** Opens the emulator and returns the sentence to show. Reads preferences and the package manager: off the main thread. */
    suspend fun open(context: Context, entry: LibraryEntry): String = withContext(Dispatchers.IO) {
        val files = filesToPick(entry)
        if (files.isEmpty()) return@withContext "This game has no update or DLC files to add."
        val system = ConsoleSystemsRepository.allSystems(context).firstOrNull { it.id == SwitchContent.SYSTEM_ID }
            ?: return@withContext "Switch games aren't set up on this device."
        val player = resolvePlayer(context, system, entry.altEmulator)
            ?: return@withContext noEmulatorInstalledMessage(context, system)
        val launch = context.packageManager.getLaunchIntentForPackage(player.packageName)
            ?: return@withContext "${player.name} has no screen droidtop can open."
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            return@withContext "${player.name} could not be opened."
        } catch (e: SecurityException) {
            return@withContext "${player.name} could not be opened."
        }
        instruction(player.name, files.map { it.name })
    }

    private const val MAX_NAMED = 4
}
