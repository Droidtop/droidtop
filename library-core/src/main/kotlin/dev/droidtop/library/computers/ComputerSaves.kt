package dev.droidtop.library.computers

import android.content.Context
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
 * game's Wine prefix and folder here, and the agent core decides: a side that
 * alone changed wins; when both changed, the person's primary computer, else
 * the newest copy (droidtop-agent docs/DESIGN.md sections 6 and 9). Nothing is
 * asked and nothing is lost: the side that is overwritten keeps its own changed
 * saves as a copy first, here in the computer's archive folder.
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
    ): SaveSyncResult? {
        val results = Computers.list(context).mapNotNull { one(context, it, entryId, title, phase, prefix) }
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
     * What leaving the saves in the cloud folder did. When the computer's own
     * saves changed too, the newest (or the primary computer's) copy wins there
     * and the other is kept as a copy; a refusal in the folder says so.
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

    private suspend fun one(
        context: Context,
        computer: Computer,
        entryId: String,
        title: String,
        phase: SaveSyncPhase,
        prefix: WinePrefixLocation,
    ): SaveSyncResult? {
        val dir = Computers.stateDir(context, computer)
        val key = fileKey(entryId)
        fun args() = JSONObject()
            .put("game", JSONObject().put("key", entryId).put("title", title))
            .put("prefix", File(prefix.prefixDir, "drive_c").absolutePath)
            .put("user", prefix.user)
            .put("baseline", File(dir, "saves/$key.json").absolutePath)
            .put("archive", File(dir, "archive/$key").absolutePath)
            .put("primary", computer.primary)
            .apply { bases[entryId]?.let { put("base", it) } }
        val reply = Computers.call(context, computer, "sync_saves", args())
        val result = when {
            // Away from the computer after playing: the saves wait in the person's cloud folder, when one is set.
            reply.has("unreachable") && phase != SaveSyncPhase.BEFORE_LAUNCH && ComputerShare.folder(context) != null ->
                posted(computer, ComputerShare.postSaves(context, computer, args()), phase)
            // Away from the computer is normal: said on the screen only when the person asked for the sync.
            reply.has("unreachable") -> SaveSyncResult("${computer.name} did not answer", failed = phase == SaveSyncPhase.MANUAL)
            reply.has("error") -> SaveSyncResult("Saves with ${computer.name}: ${reply.optString("error")}", failed = true)
            else -> when (reply.optString("outcome")) {
                "up_to_date" -> SaveSyncResult("Saves match ${computer.name}")
                "copied" -> {
                    val files = reply.optInt("files")
                    val removed = reply.optInt("removed")
                    // Both sides had changed: the other side's saves were kept as its own copy.
                    val kept = reply.optBoolean("kept")
                    if (reply.optString("from") == "there") {
                        val why = if (kept) " (${if (computer.primary) "your primary computer" else "newer"}; this device's are kept as a copy)" else ""
                        SaveSyncResult("Saves: $files from ${computer.name}$why", downloaded = files, removed = removed)
                    } else {
                        val why = if (kept) " (newer here; ${computer.name} kept its own as a copy)" else ""
                        SaveSyncResult("Saves: $files sent to ${computer.name}$why", uploaded = files, removed = removed)
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
