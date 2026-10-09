package dev.droidtop.library.stores

import android.content.Context
import android.util.Log
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.settings.LibraryPaths
import dev.droidtop.library.settings.PathChange
import dev.droidtop.pluginhost.DownloadGate
import dev.droidtop.pluginhost.PluginJobsCenter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * A store install or update as a job in the one jobs list (docs/SPEC.md 12a
 * "Jobs", 7k "Places": the Downloads place), so it has the same Pause,
 * Resume and Cancel as every other long-running action, survives a restart
 * paused, and is started from ONE place whichever surface asked: the game
 * page's primary button, the game menu, or the Stores place.
 *
 * The job's checkpoint is the store row's key ("gog:1207658691") and
 * nothing else: the stores resume from what is on disk (each download
 * manager skips the files it already has), so there is no byte offset to
 * carry. The key is also what [StoreDownloads] files the job's progress
 * under, which is how a capsule and the game page say Downloading.
 */
object StoreInstallJob {
    const val KIND = "store_install"
    private const val ARG_KEY = "key"
    private const val ARG_ROOT = "root"
    private const val TAG = "droidtop.StoreInstall"
    private const val PUBLISHER = "store_jobs"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The folder a store's games go in inside one of the person's game
     * folders (Settings > Game folders, [dev.droidtop.library.GamesRoots]):
     * "<game folder>/<store>", one folder per store, so an uninstall can
     * prove the folder it deletes is a store install and a library walk
     * knows a store's tree by its name (docs/SPEC.md 7g, "Where a store
     * installs"). Never droidtop's own Android/data folder.
     */
    fun rootFor(gamesFolder: String, store: StoreLibrary): File = File(gamesFolder, folderName(store))

    /** The name of [store]'s folder inside a game folder: its label with what a card's filesystem refuses dropped, or its id. */
    fun folderName(store: StoreLibrary): String = store.label.replace(Regex("[^A-Za-z0-9 ._-]"), "").ifBlank { store.id }

    /**
     * The file a store keeps in a game's folder while its download runs
     * (GameNative's name, so its installs read the same). A library walk
     * passes over a folder that holds it ([dev.droidtop.library.ScanPrune]):
     * half a download is not a game.
     */
    const val IN_PROGRESS_MARKER = ".download_in_progress"

    /** Registers the runner and starts publishing running installs. Called once at process start. */
    fun register(context: Context) {
        val app = context.applicationContext
        PluginJobsCenter.registerNative(
            kind = KIND,
            onCancel = { args, _ -> discard(app, args[ARG_KEY]) },
            download = true,
        ) { args, _, report -> execute(app, args, report) }
        scope.launch {
            PluginJobsCenter.entries()
                .map { entries ->
                    entries.filter { it.nativeKind == KIND && !it.done }
                        .mapNotNull { entry ->
                            entry.resumePayload?.let { key ->
                                key to StoreDownloads.Progress((entry.percent.coerceAtLeast(0)) / 100f, paused = entry.paused)
                            }
                        }.toMap()
                }
                .distinctUntilChanged()
                .collect { StoreDownloads.publish(PUBLISHER, it) }
        }
    }

    /**
     * Starts installing (or updating) the store row [key] under [root], or
     * returns the job already doing it. Null when no store owns [key]. [sizeBytes] (0 when unknown) and [automatic]
     * (an update nobody just asked for, [StoreAutoUpdates]) are what the download policy decides on
     * ([dev.droidtop.pluginhost.DownloadGate]).
     */
    fun start(context: Context, key: String, title: String, root: File, sizeBytes: Long = 0L, automatic: Boolean = false): String? {
        val store = StoreLibraries.forKey(key) ?: return null
        return PluginJobsCenter.startNative(
            context = context,
            kind = KIND,
            title = title,
            args = buildMap {
                put(ARG_KEY, key)
                put(ARG_ROOT, root.absolutePath)
                if (sizeBytes > 0) put(DownloadGate.ARG_BYTES, sizeBytes.toString())
                if (automatic) put(DownloadGate.ARG_AUTOMATIC, "1")
            },
            owner = store.label,
        )
    }

    /** The running or paused job for [key], if there is one. */
    fun jobFor(key: String): PluginJobsCenter.Entry? =
        PluginJobsCenter.entries().value.firstOrNull { it.nativeKind == KIND && !it.done && it.resumePayload == key }

    /** Resumes [key]'s paused download; false when there is none to resume. */
    fun resume(key: String): Boolean = jobFor(key)?.takeIf { it.paused }?.let { PluginJobsCenter.resume(it.jobId) } ?: false

    private suspend fun execute(
        context: Context,
        args: Map<String, String>,
        report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
    ): String {
        val key = args[ARG_KEY] ?: error("This download names no game")
        val root = args[ARG_ROOT]?.let(::File) ?: error("This download names no folder")
        val store = StoreLibraries.forKey(key) ?: error("No store in this build owns $key")
        // The key goes in as the checkpoint at once, so the game's own
        // capsule says Downloading from the first moment, not the first byte.
        report(0, "Starting", key)
        val outcome = store.install(context, key.substringAfter(':'), root) { fraction, line ->
            report(if (fraction < 0f) -1 else (fraction.coerceIn(0f, 1f) * 100f).toInt(), line, key)
        }
        // The new install is in the library now, said as the folder it is, by the job and not by any screen: the
        // library looks at that folder and walks nothing (docs/SPEC.md 7g, "Targeted indexing"). A store that cannot
        // say where it put the game is the store's own folder, which holds only what stores installed.
        withContext(Dispatchers.IO) {
            val installed = runCatching { store.installedPath(context, key.substringAfter(':')) }.getOrNull()
            LibraryPaths.report(context, PathChange.added(installed?.let(::File) ?: root))
        }
        return outcome
    }

    private fun discard(context: Context, key: String?) {
        val store = StoreLibraries.forKey(key) ?: return
        // onCancel already runs off the main thread; the store's own IO is
        // dispatched inside, so blocking this worker until it ends is the
        // honest way to say the files are gone before the row is.
        runBlocking {
            withContext(Dispatchers.IO) {
                runCatching { store.discardPartial(context, key!!.substringAfter(':')) }
                    .onFailure { Log.w(TAG, "Could not remove what the cancelled download of $key left", it) }
            }
        }
    }
}
