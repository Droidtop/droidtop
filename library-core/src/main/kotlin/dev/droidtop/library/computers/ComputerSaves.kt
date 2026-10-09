package dev.droidtop.library.computers

import android.content.Context
import dev.droidtop.library.stores.SaveChoice
import dev.droidtop.library.stores.SaveConflict
import dev.droidtop.library.stores.SaveConflictResolver
import dev.droidtop.library.stores.SaveSide
import dev.droidtop.library.stores.SaveSyncPhase
import dev.droidtop.library.stores.SaveSyncResult
import dev.droidtop.library.stores.WinePrefixLocation
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A game's saves kept in step with the person's paired computers (docs/SPEC.md
 * 7o "Computers"), on the same launch seam as store cloud saves
 * ([dev.droidtop.library.stores.StoreSaves]): before the game starts and after
 * it ends. The computer says where the game keeps its saves (from the person's
 * own entries or the Ludusavi manifest), droidtop resolves those places in the
 * game's Wine prefix and folder here, and the agent core decides with the rule
 * the Steam Cloud sync uses. A conflict goes to the same "Saves differ"
 * question, naming the computer; the side that loses is archived first.
 */
object ComputerSaves {
    /** The folder each game was launched from this run, so the sync after it ends knows `<base>`. */
    private val bases = ConcurrentHashMap<String, String>()

    fun rememberBase(entryId: String, base: File?) {
        base?.let { bases[entryId] = it.absolutePath }
    }

    /** A game key as a file name, the way the agent names its own folders. */
    fun fileKey(key: String): String = key.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }.joinToString("")

    /**
     * Syncs [entryId]'s saves with every paired computer. Null when no computer
     * is paired or none knows the game. Disk and network work: never on the
     * main thread.
     */
    suspend fun sync(
        context: Context,
        entryId: String,
        title: String,
        phase: SaveSyncPhase,
        prefix: WinePrefixLocation,
        resolver: SaveConflictResolver?,
    ): SaveSyncResult? {
        val results = Computers.list(context).mapNotNull { one(context, it, entryId, title, phase, prefix, resolver) }
        if (results.isEmpty()) return null
        return SaveSyncResult(
            line = results.joinToString("; ") { it.line },
            uploaded = results.sumOf { it.uploaded },
            downloaded = results.sumOf { it.downloaded },
            removed = results.sumOf { it.removed },
            failed = results.any { it.failed },
            unresolved = results.any { it.unresolved },
        )
    }

    /**
     * What leaving the saves in the cloud folder did. The computer applies the
     * set only while its own saves still match the set this device last knew
     * it had; otherwise it keeps the set aside and says so in the folder, and
     * the next live sync asks the person.
     */
    private fun posted(computer: Computer, reply: JSONObject?, phase: SaveSyncPhase): SaveSyncResult? = when {
        reply == null -> null
        reply.has("error") -> SaveSyncResult("Saves for ${computer.name}: ${reply.optString("error")}", failed = true)
        else -> when (reply.optString("outcome")) {
            "posted" -> SaveSyncResult("Saves: ${reply.optInt("files")} left in your cloud folder for ${computer.name}", uploaded = reply.optInt("files"))
            "up_to_date" -> SaveSyncResult("Saves match what ${computer.name} has")
            // Never synced live with this computer, so where the saves are is not known here yet.
            else -> SaveSyncResult("${computer.name} did not answer", failed = phase == SaveSyncPhase.MANUAL)
        }
    }

    private fun side(o: JSONObject?): SaveSide =
        SaveSide(timestampMs = o?.optLong("newest_ms") ?: 0L, files = o?.optInt("files") ?: 0, bytes = o?.optLong("bytes") ?: 0L)

    private suspend fun one(
        context: Context,
        computer: Computer,
        entryId: String,
        title: String,
        phase: SaveSyncPhase,
        prefix: WinePrefixLocation,
        resolver: SaveConflictResolver?,
    ): SaveSyncResult? {
        val dir = Computers.stateDir(context, computer)
        val key = fileKey(entryId)
        fun args(choice: String?) = JSONObject()
            .put("game", JSONObject().put("key", entryId).put("title", title))
            .put("prefix", File(prefix.prefixDir, "drive_c").absolutePath)
            .put("user", prefix.user)
            .put("baseline", File(dir, "saves/$key.json").absolutePath)
            .put("archive", File(dir, "archive/$key").absolutePath)
            .apply {
                bases[entryId]?.let { put("base", it) }
                choice?.let { put("choice", it) }
            }
        var reply = Computers.call(context, computer, "sync_saves", args(null))
        if (reply.optString("outcome") == "conflict") {
            val conflict = SaveConflict(local = side(reply.optJSONObject("here")), cloud = side(reply.optJSONObject("there")), cloudLabel = computer.name)
            val choice = resolver?.resolve(title, conflict)
            if (choice == null) {
                val line = "Saves differ from ${computer.name}; nothing was changed"
                Computers.noteSync(context, computer.id, line)
                return SaveSyncResult(line, unresolved = true)
            }
            reply = Computers.call(context, computer, "sync_saves", args(if (choice == SaveChoice.LOCAL) "here" else "there"))
        }
        val result = when {
            // Away from the computer after playing: the saves wait in the person's cloud folder, when one is set.
            reply.has("unreachable") && phase != SaveSyncPhase.BEFORE_LAUNCH && ComputerShare.folder(context) != null ->
                posted(computer, ComputerShare.postSaves(context, computer, args(null)), phase)
            // Away from the computer is normal: said on the screen only when the person asked for the sync.
            reply.has("unreachable") -> SaveSyncResult("${computer.name} did not answer", failed = phase == SaveSyncPhase.MANUAL)
            reply.has("error") -> SaveSyncResult("Saves with ${computer.name}: ${reply.optString("error")}", failed = true)
            else -> when (reply.optString("outcome")) {
                "up_to_date" -> SaveSyncResult("Saves match ${computer.name}")
                "copied" -> {
                    val files = reply.optInt("files")
                    val removed = reply.optInt("removed")
                    if (reply.optString("from") == "there") {
                        SaveSyncResult("Saves: $files from ${computer.name}", downloaded = files, removed = removed)
                    } else {
                        SaveSyncResult("Saves: $files sent to ${computer.name}", uploaded = files, removed = removed)
                    }
                }
                // The computer knows no save location for this game.
                else -> null
            }
        }
        result?.let { Computers.noteSync(context, computer.id, it.line) }
        return result
    }
}
