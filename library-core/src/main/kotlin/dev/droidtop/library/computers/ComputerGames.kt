package dev.droidtop.library.computers

import android.content.Context
import dev.droidtop.library.GameNaming
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.SetVersions
import dev.droidtop.library.groupingPath
import dev.droidtop.net.peer.AgentNative
import dev.droidtop.net.peer.Computer
import dev.droidtop.net.peer.Computers
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * A game's version copied between this device and a computer (docs/SPEC.md
 * 7o, "Game updates"; Droidtop/tracker#469 part 3; owner, 2026-10-10: "If I
 * install an update to a game on my desktop, I should be able to sync it to
 * the handheld and version-manage it automatically").
 *
 * A version is a folder (7m), so a copy is a new folder beside the game's
 * others, named for its version. The previous version stays playable, and
 * rolling back is choosing it on the Versions tab. Nothing is changed in
 * place. The copy runs as a job in Downloads. A copy that stopped picks up
 * where it stopped the next time (the agent core keeps the part folder).
 */
object ComputerGames {
    private const val JOB_KIND = "computer_game_copy"
    private const val ARG_DIRECTION = "direction"
    private const val ARG_COMPUTER = "computer"
    private const val ARG_KEY = "key"
    private const val ARG_TITLE = "title"
    private const val ARG_FOLDER = "folder"
    private const val ARG_NAME = "name"
    private const val ARG_VERSION = "version"
    private const val GET = "get"
    private const val SEND = "send"

    /** The library a finished copy is indexed into, set once at start. */
    @Volatile
    private var library: ((Context) -> Library)? = null

    /** Registers the job runner; called once at process start. */
    fun register(context: Context, libraryOf: (Context) -> Library) {
        library = libraryOf
        val app = context.applicationContext
        PluginJobsCenter.registerNative(JOB_KIND, download = true) { args, _, report -> run(app, args, report) }
    }

    /**
     * The folder name a version copied here gets: the game's name and the
     * version, the way [GameNaming] reads a version folder ("Eternum v0.9.6").
     */
    fun folderName(entry: LibraryEntry, version: String): String {
        val base = entry.groupingPath()?.let { GameNaming.derive(it).name } ?: entry.title
        val v = version.trim().removePrefix("v").removePrefix("V")
        return "${GameNaming.displayName(base)} v$v".replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
    }

    /** Copies [computer]'s [version] of [entry]'s game here, beside [entry]'s folder. */
    fun get(context: Context, computer: Computer, entry: LibraryEntry, version: String) {
        val folder = entry.groupingPath() ?: return
        start(context, GET, computer, entry, folder, folderName(entry, version), version, "${GameNaming.displayName(entry.title)}: v${version.removePrefix("v")} from ${computer.name}")
    }

    /** Copies [entry]'s folder (this version) to [computer]'s game folder. */
    fun send(context: Context, computer: Computer, entry: LibraryEntry) {
        val folder = entry.groupingPath() ?: return
        val version = SetVersions.shown(entry, SetVersions.get(context, entry.id))?.first.orEmpty()
        start(context, SEND, computer, entry, folder, File(folder).name, version, "${GameNaming.displayName(entry.title)} to ${computer.name}")
    }

    private fun start(context: Context, direction: String, computer: Computer, entry: LibraryEntry, folder: String, name: String, version: String, title: String) {
        PluginJobsCenter.startNative(
            context,
            JOB_KIND,
            title,
            mapOf(
                ARG_DIRECTION to direction,
                ARG_COMPUTER to computer.id,
                ARG_KEY to ComputerLibrary.keyOf(entry),
                ARG_TITLE to entry.title,
                ARG_FOLDER to folder,
                ARG_NAME to name,
                ARG_VERSION to version,
            ),
            owner = "Computers",
            pausable = false,
        )
    }

    private suspend fun run(context: Context, args: Map<String, String>, report: (Int, String, String?) -> Unit): String = coroutineScope {
        val computer = Computers.list(context).firstOrNull { it.id == args[ARG_COMPUTER] } ?: error("That computer is no longer paired")
        val folder = File(args[ARG_FOLDER] ?: error("This copy names no folder"))
        val name = args[ARG_NAME] ?: error("This copy names no version folder")
        val getting = args[ARG_DIRECTION] == GET
        val progress = File(context.cacheDir, "game-copy-${System.nanoTime()}.json")
        val call = JSONObject()
            .put("game", JSONObject().put("key", args[ARG_KEY]).put("title", args[ARG_TITLE]))
            .put("folder_name", name)
            .put("progress", progress.absolutePath)
            .apply {
                if (getting) put("parent", folder.parentFile?.absolutePath) else put("source", folder.absolutePath).put("version", args[ARG_VERSION])
            }
        report(-1, if (getting) "Asking ${computer.name}" else "Sending to ${computer.name}", null)
        val work = async(Dispatchers.IO) { Computers.call(context, computer, if (getting) "game_pull" else "game_push", call) }
        while (!work.isCompleted) {
            delay(500)
            runCatching { JSONObject(progress.readText()) }.getOrNull()?.let { p ->
                val (done, total) = p.optLong("done") to p.optLong("total")
                if (total > 0) report((done * 100 / total).toInt(), "${done / MB} of ${total / MB} MB", null)
            }
        }
        progress.delete()
        val reply = work.await()
        AgentNative.failure(reply)?.let { error(it) }
        if (reply.has("unreachable")) error("${computer.name} did not answer")
        val placed = reply.optString("folder")
        if (getting) {
            // Seen at once, without a walk: the new folder is the game's newest version.
            library?.invoke(context)?.let { lib -> withContext(Dispatchers.IO) { lib.indexPaths(added = listOf(File(placed))) } }
            "$name is here, beside the version you had (${reply.optInt("files")} files)"
        } else {
            "${folder.name} is on ${computer.name} in ${placed.ifBlank { "its game folder" }}"
        }
    }

    private const val MB = 1024L * 1024L
}
