package dev.droidtop.library.computers

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.PcInfo
import dev.droidtop.library.PcSource
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import dev.droidtop.net.peer.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * What the person's paired computers have that this device does not, in this
 * device's own library (docs/SPEC.md 7o, "Library"; owner, 2026-10-10: "Apps
 * should be toggleable", and "Games on the desktop should ALSO be toggleable.
 * We don't actually know if the user will want to stream stuff from the
 * computer"):
 * - a computer's games are [LibraryEntryKind.COMPUTER_GAME] entries, filed
 *   under the computer as their source ("On DESKTOP-PC");
 * - its other applications are [LibraryEntryKind.REMOTE_STREAM] entries in
 *   Apps.
 *
 * Each is shown only while both its switch for that computer and the global
 * one are on. A game this device has too is not added: it stays one entry,
 * the handheld's own. Read from the agent core's library file, which only
 * a sync writes, so it walks again on every scan (not indexed).
 */
class ComputerEntriesProvider(private val context: Context) : LibraryProvider {
    override val kinds: Set<LibraryEntryKind> = setOf(LibraryEntryKind.COMPUTER_GAME, LibraryEntryKind.REMOTE_STREAM)

    override val indexed: Boolean get() = false

    override suspend fun scan(): List<LibraryEntry> {
        val games = Computers.gamesShown(context)
        val apps = Computers.appsShown(context)
        if (!games && !apps) return emptyList()
        val mine = DeviceIdentity.id(context)
        val here = mine?.let { ComputerLibrary.installedKeys(context, it) }.orEmpty()
        val out = mutableListOf<LibraryEntry>()
        for (computer in Computers.list(context)) {
            PcSource.computerNames[computer.id] = computer.name
            if (games && computer.showGames) {
                ComputerLibrary.gamesOn(context, computer).filter { it.key !in here }.mapTo(out) { game ->
                    LibraryEntry(
                        id = "computer:${computer.id}:${game.key}",
                        title = game.title,
                        kind = LibraryEntryKind.COMPUTER_GAME,
                        pcInfo = PcInfo(
                            storeId = game.key.takeIf { PcSource.storeIdOf(it) in STORES },
                            installed = false,
                            sizeBytes = game.sizeBytes,
                        ),
                    )
                }
            }
            if (apps && computer.showApps) {
                ComputerLibrary.appsOn(context, computer).mapTo(out) { app ->
                    LibraryEntry(
                        id = "computer:${computer.id}:${app.key}",
                        title = app.title,
                        kind = LibraryEntryKind.REMOTE_STREAM,
                        description = "Installed on ${computer.name}" + (app.launcher?.let { " ($it)" } ?: ""),
                    )
                }
            }
        }
        return out
    }

    /**
     * Nothing here runs it: it is on the computer (docs/SPEC.md 7o, "Starting
     * a game on a computer"). A game that is not Steam's is asked of that
     * computer through [ComputerLaunch]; the program hosting its agent
     * (Pythia) starts its own games, and droidtop's stream screen opens (windowcast's
     * library) when it says windowcast shows the game. A Steam game is streamed with the Steam
     * module's "Stream with Steam Link", in its menu. An application opens the
     * same stream screen on its computer, at a window named like it (7a).
     */
    override suspend fun launch(entry: LibraryEntry) {
        val text = withContext(Dispatchers.IO) { answerFor(entry) }
        Handler(Looper.getMainLooper()).post { dev.droidtop.library.settings.Notices.flash(text, context = context) }
    }

    /** Reads the computers' file and may ask one over the network: on IO. */
    private fun answerFor(entry: LibraryEntry): String {
        val (computerId, key) = entry.id.removePrefix("computer:").split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val computer = Computers.list(context).firstOrNull { it.id == computerId }
        val name = computer?.name ?: "one of your computers"
        return when {
            computer != null && entry.kind == LibraryEntryKind.REMOTE_STREAM ->
                ComputerLaunch.openWindowcast(context, computer, null, entry.title, "${entry.title} is installed on $name; its window is shown if it is open there.")
            computer == null || entry.kind != LibraryEntryKind.COMPUTER_GAME -> "${entry.title} is installed on $name, not on this device"
            key.startsWith("steam:") -> "${entry.title} is installed on $name, not on this device. Stream with Steam Link is in its menu."
            else -> startOn(computer, key, entry.title)
        }
    }

    private fun startOn(computer: Computer, key: String, title: String): String {
        val (_, outcome) = ComputerLaunch.start(context, listOf(computer), key, title) ?: return "${computer.name} could not be asked."
        if (!outcome.started) {
            Timber.w("Start on a computer: %s (%s): %s", computer.name, key, outcome.line)
            return outcome.line
        }
        return when (outcome.stream) {
            ComputerLaunch.STREAM_WINDOWCAST -> ComputerLaunch.openWindowcast(context, computer, outcome.streamAddress, title, outcome.line)
            // Started, with nothing on this device to show it (no windowcast host there).
            else -> "${outcome.line} It shows on ${computer.name}'s screen; nothing here streams it."
        }
    }

    private companion object {
        /** The store keys the agent uses that are stores droidtop knows. */
        val STORES = setOf("steam", "gog", "epic", "amazon", "itch")
    }
}
