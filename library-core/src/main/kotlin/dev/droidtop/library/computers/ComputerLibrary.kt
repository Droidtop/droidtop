package dev.droidtop.library.computers

import android.content.Context
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.consoles.RomDatabase
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
    fun stateFile(context: Context): File = File(Computers.folder(context), "library.json")

    /** A title as the agent keys a game without a store id: lower case, letters and digits, single spaces. */
    fun titleKey(title: String): String =
        "title:" + title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** The key a game goes by on every device: its store id, a ROM's system and file name, or its title. */
    fun keyOf(entry: LibraryEntry): String {
        // A row of any registered store is known by its store id on every device.
        if (dev.droidtop.library.stores.StoreLibraries.forKey(entry.id) != null) return entry.id
        if (entry.kind == LibraryEntryKind.CONSOLE_ROM && entry.systemId != null) {
            val stem = if ('/' in entry.id) File(entry.id).nameWithoutExtension else entry.title
            return "rom:${entry.systemId}/${titleKey(stem).removePrefix("title:")}"
        }
        return titleKey(entry.title)
    }

    /** The marks droidtop keeps on a game that the agent carries, by the agent's field names. */
    private const val FAVOURITE = "favourite"
    private const val HIDDEN = "hidden"
    private const val COMPLETED = "completed"

    /**
     * Every mark this device keeps on its games, unset ones included, by key.
     * The core turns only a mark that differs from the shared one into a
     * change, so a mark that arrived from a computer and was written here is
     * not sent back (droidtop-agent docs/DESIGN.md section 7).
     */
    private fun marksOf(games: List<LibraryEntry>): JSONObject {
        val out = JSONObject()
        games.groupBy(::keyOf).forEach { (key, entries) ->
            out.put(
                key,
                JSONObject()
                    .put(FAVOURITE, entries.any { it.favorite })
                    .put(HIDDEN, entries.any { it.hidden })
                    .put(COMPLETED, entries.any { it.completed }),
            )
        }
        return out
    }

    /**
     * Writes the marks the core says arrived from elsewhere ([marks]: key,
     * then field and value) into this device's library: a favourite through
     * the library's own favourite (the one the Gaming UI's toggle uses), hidden
     * and completed into the game's `game_metadata` row, which every provider
     * merges into its entries. Returns how many games changed.
     */
    private suspend fun writeMarks(context: Context, library: Library, games: List<LibraryEntry>, marks: JSONObject?): Int {
        if (marks == null || marks.length() == 0) return 0
        val dao = RomDatabase.get(context).romDao()
        val byKey = games.groupBy(::keyOf)
        var written = 0
        marks.keys().forEach { key ->
            val fields = marks.optJSONObject(key) ?: return@forEach
            byKey[key].orEmpty().forEach { entry ->
                var changed = false
                if (fields.has(FAVOURITE) && entry.favorite != fields.optBoolean(FAVOURITE)) {
                    changed = library.toggleFavorite(entry) != null
                }
                if (fields.has(HIDDEN) || fields.has(COMPLETED)) {
                    val current = dao.getGameMetadataSingle(entry.id) ?: GameMetadataEntity(id = entry.id)
                    val next = current.copy(
                        hidden = if (fields.has(HIDDEN)) fields.optBoolean(HIDDEN) else current.hidden,
                        completed = if (fields.has(COMPLETED)) fields.optBoolean(COMPLETED) else current.completed,
                    )
                    if (next != current) {
                        dao.upsertGameMetadata(next)
                        changed = true
                    }
                }
                if (changed) written++
            }
        }
        return written
    }

    /**
     * One exchange with [computer]: this device's games and marks out, the
     * computer's changes in, and the marks that arrived written into
     * [library]. When the computer does not answer and a cloud folder is set
     * ([ComputerShare]), the same exchange goes through the folder instead.
     * Network and disk work: never on the main thread.
     */
    suspend fun sync(context: Context, computer: Computer, library: Library, games: List<LibraryEntry>): String {
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
        val marks = marksOf(games)
        fun args() = JSONObject().put("state", stateFile(context).absolutePath).put("scan", scan).put("marks", marks)
        val live = Computers.call(context, computer, "sync_library", args())
        val shared = if (live.has("unreachable")) ComputerShare.library(context, computer, args()) else null
        val reply = shared ?: live
        val written = if (AgentNative.failure(reply) == null) writeMarks(context, library, games, reply.optJSONObject("marks")) else 0
        val marked = if (written > 0) "; marks changed on $written games" else ""
        val line = AgentNative.failure(reply)?.let { "Library with ${computer.name}: $it" }
            ?: if (shared != null) {
                val refused = reply.optJSONArray("refused")?.let { r -> (0 until r.length()).mapNotNull { r.optJSONObject(it) } }.orEmpty()
                val refusals = refused.joinToString("") { "; ${computer.name} kept its own saves of ${it.optString("title").ifBlank { it.optString("key") }}: ${it.optString("reason")}" }
                val unsent = reply.optString("unsent").takeIf { it.isNotBlank() }?.let { "; not yet in the cloud folder: $it" }.orEmpty()
                "Library through your cloud folder: ${reply.optInt("posted")} changes left for ${computer.name}, ${reply.optInt("applied")} applied$marked$refusals$unsent"
            } else {
                "Library: ${reply.optInt("pushed")} changes sent to ${computer.name}, ${reply.optInt("pulled")} received$marked"
            }
        Computers.noteSync(context, computer.id, line)
        return line
    }

    /** A game installed on a computer, as the last exchange left it. */
    data class RemoteGame(val key: String, val title: String, val platform: String?, val sizeBytes: Long, val launcher: String?, val path: String?)

    /** The keys of the games [device] (this one) has installed, from the state file. Reads a file: never on the main thread. */
    fun installedKeys(context: Context, device: String): Set<String> {
        val games = runCatching { JSONObject(stateFile(context).readText()).optJSONObject("games") }.getOrNull() ?: return emptySet()
        return games.keys().asSequence().filter { key ->
            games.optJSONObject(key)?.optJSONObject("installs")?.optJSONObject(device)?.optJSONObject("install")?.optBoolean("installed") == true
        }.toSet()
    }

    /** The platform the agent gives a computer's installed applications. */
    const val APP_PLATFORM = "app"

    /** The games [computer] has, from the state file. Reads a file: never on the main thread. */
    fun gamesOn(context: Context, computer: Computer): List<RemoteGame> = installedOn(context, computer).filter { it.platform != APP_PLATFORM }

    /** The other applications [computer] has installed (the agent's `app:` entries). Reads a file: never on the main thread. */
    fun appsOn(context: Context, computer: Computer): List<RemoteGame> = installedOn(context, computer).filter { it.platform == APP_PLATFORM }

    private fun installedOn(context: Context, computer: Computer): List<RemoteGame> {
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
