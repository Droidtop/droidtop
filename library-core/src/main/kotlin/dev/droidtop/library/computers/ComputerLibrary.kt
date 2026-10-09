package dev.droidtop.library.computers

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.net.peer.AgentNative
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The library kept in step with the person's paired computers (docs/SPEC.md
 * 7o "Computers"). Each device is the authority on what it has installed:
 * droidtop sends the games it has, the computer sends the games its scan found
 * (Steam, GOG, Epic, Amazon, itch, Battle.net, Heroic, Lutris, game and ROM
 * folders), and each side keeps the other's as facts about that device. Only
 * what changed since the last exchange travels. The state, with every
 * device's games, is the agent core's file `files/agent/library.json`.
 */
object ComputerLibrary {
    private val STORES = setOf("steam", "gog", "epic", "amazon", "itch", "battlenet")

    fun stateFile(context: Context): File = File(Computers.folder(context), "library.json")

    /** A title as the agent keys a game without a store id: lower case, letters and digits, single spaces. */
    fun titleKey(title: String): String =
        "title:" + title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** The key a game goes by on every device: its store id, a ROM's system and file name, or its title. */
    fun keyOf(entry: LibraryEntry): String {
        val prefix = entry.id.substringBefore(':', "")
        if (prefix in STORES) return entry.id
        if (entry.kind == LibraryEntryKind.CONSOLE_ROM && entry.systemId != null) {
            val stem = if ('/' in entry.id) File(entry.id).nameWithoutExtension else entry.title
            return "rom:${entry.systemId}/${titleKey(stem).removePrefix("title:")}"
        }
        return titleKey(entry.title)
    }

    /** One exchange with [computer]: this device's games out, the computer's changes in. Network work: never on the main thread. */
    fun sync(context: Context, computer: Computer, games: List<LibraryEntry>): String {
        val scan = JSONArray()
        games.forEach { entry ->
            val install = JSONObject()
                .put("installed", true)
                .put("launcher", "droidtop")
                .put("play_seconds", entry.playtimeSeconds)
                .put("last_played_ms", entry.lastPlayedEpochMs ?: 0L)
            entry.pcInfo?.installPath?.takeIf { it.isNotBlank() }?.let { install.put("path", it) }
            scan.put(
                JSONObject()
                    .put("key", keyOf(entry))
                    .put("title", entry.title)
                    .put("platform", entry.systemId ?: if (entry.pcInfo != null) "pc" else entry.kind.name.lowercase())
                    .put("install", install),
            )
        }
        val reply = Computers.call(context, computer, "sync_library", JSONObject().put("state", stateFile(context).absolutePath).put("scan", scan))
        val line = AgentNative.failure(reply)?.let { "Library with ${computer.name}: $it" }
            ?: "Library: ${reply.optInt("pushed")} changes sent to ${computer.name}, ${reply.optInt("pulled")} received"
        Computers.noteSync(context, computer.id, line)
        return line
    }

    /** A game installed on a computer, as the last exchange left it. */
    data class RemoteGame(val key: String, val title: String, val platform: String?, val sizeBytes: Long, val launcher: String?, val path: String?)

    /** The games [computer] has, from the state file. Reads a file: never on the main thread. */
    fun gamesOn(context: Context, computer: Computer): List<RemoteGame> {
        val games = runCatching { JSONObject(stateFile(context).readText()).optJSONObject("games") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<RemoteGame>()
        games.keys().forEach { key ->
            val game = games.optJSONObject(key) ?: return@forEach
            val install = game.optJSONObject("installs")?.optJSONObject(computer.id)?.optJSONObject("install") ?: return@forEach
            if (!install.optBoolean("installed")) return@forEach
            out += RemoteGame(
                key = key,
                title = game.optString("title").ifBlank { key },
                platform = game.optString("platform").takeIf { it.isNotBlank() },
                sizeBytes = install.optLong("size"),
                launcher = install.optString("launcher").takeIf { it.isNotBlank() },
                path = install.optString("path").takeIf { it.isNotBlank() },
            )
        }
        return out.sortedBy { it.title.lowercase() }
    }
}
