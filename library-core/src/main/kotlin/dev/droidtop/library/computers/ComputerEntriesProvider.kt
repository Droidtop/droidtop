package dev.droidtop.library.computers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.LibraryProvider
import dev.droidtop.library.PcInfo
import dev.droidtop.library.PcSource
import dev.droidtop.net.peer.Computers
import dev.droidtop.net.peer.DeviceIdentity

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
     * Nothing here runs it: it is on the computer. Streaming it is windowcast's
     * (docs/SPEC.md 7a), and installing it here is the game's own store or
     * copy; the person is told which computer has it.
     */
    override suspend fun launch(entry: LibraryEntry) {
        val computerId = entry.id.removePrefix("computer:").substringBefore(':')
        val name = Computers.list(context).firstOrNull { it.id == computerId }?.name ?: "one of your computers"
        val text = "${entry.title} is installed on $name, not on this device"
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        /** The store keys the agent uses that are stores droidtop knows. */
        val STORES = setOf("steam", "gog", "epic", "amazon", "itch")
    }
}
