package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.stores.data.DownloadInfo
import dev.droidtop.stores.util.DownloadSpeedConfig
import dev.droidtop.stores.util.Marker
import dev.droidtop.stores.util.MarkerUtils
import dev.droidtop.stores.util.StoreDiskSpace
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.StoreLanguage
import dev.droidtop.stores.util.runJobDownload
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.types.DepotManifest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import timber.log.Timber

/**
 * One Steam install or update, run inside droidtop's install job
 * (docs/SPEC.md 7g, "Stores"): GameNative's SteamService.downloadApp and
 * completeAppDownload (GPL-3.0) without its own job list, notification or
 * service. The depots come from [SteamDepots.plan], the files from
 * JavaSteam's depot downloader, which fetches only what is missing or
 * changed, so a stopped download continues and an update moves only the
 * difference.
 */
internal object SteamDownload {
    private const val TAG = "SteamDownload"
    const val BRANCH = "public"

    /**
     * Installs (or updates in place) [appId] under [root], the Steam folder of
     * the person's game folder. Logged on already; [app] has fresh product
     * info. Returns the outcome line; throws with the reason.
     */
    suspend fun run(context: Context, app: SteamApp, root: File, progress: StoreProgress, choice: SteamChoice = SteamChoice()): String {
        val db = SteamDatabase.get(context)
        val existing = withContext(Dispatchers.IO) { db.installs().find(app.id) }
        val installed = existing?.isDownloaded == true && existing.installPath.isNotBlank()
        val branch = choice.branch
        val plan = withContext(Dispatchers.IO) { planFor(db, app, choice) }
        if (plan.isEmpty) error("Steam offers no Windows files of ${app.name.ifBlank { "this game" }} for this account")

        val installDir = withContext(Dispatchers.IO) {
            existing?.installPath?.takeIf { it.isNotBlank() }?.let(::File)
                ?: StoreFiles.freshFolder(root, app.folderName, "steam-${app.id}")
        }
        withContext(Dispatchers.IO) {
            if (!installed) {
                StoreDiskSpace.shortfall(installDir, SteamDepots.installBytes(plan.all.values, branch), context.cacheDir)?.let { error(it) }
            }
            installDir.mkdirs()
            // Recorded before the download, so a Cancel knows which folder is the unfinished one.
            db.installs().upsert((existing ?: AppInfo(app.id)).copy(installPath = installDir.absolutePath))
            MarkerUtils.addMarker(installDir.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
        }

        if (installed) {
            withContext(Dispatchers.IO) { removeTurnedOffDlc(db, app, choice, existing, installDir) }
        }

        val licences = withContext(Dispatchers.IO) { db.cachedLicenses().all().mapNotNull { SteamLicenses.fromJson(it.licenseJson) } }
            .ifEmpty { SteamSession.licences() }
        // The chunks are put together in the app's own cache, not on the card
        // the game goes to: a removable card cannot size a file in advance.
        val staging = File(context.cacheDir, "steam_chunks/${app.id}")
        val depots = plan.all
        val info = DownloadInfo(jobCount = depots.size, gameId = app.id, downloadingAppIds = CopyOnWriteArrayList()).apply {
            setPersistencePath(installDir.absolutePath)
            // The bar is each depot's own share done, weighted by its size: the downloader's percent is of the bytes
            // that depot needs after its manifest (what an update or a resume actually moves), where a total from the
            // product info counted the whole game and the bar ended low, then jumped (console, 0.2.0-dev.2127).
            depots.values.forEachIndexed { index, depot -> setWeight(index, SteamDepots.downloadBytes(depot, branch).coerceAtLeast(1L)) }
            loadPersistedBytesDownloaded(installDir.absolutePath).takeIf { it > 0L }?.let { initializeBytesDownloaded(it) }
            updateStatusMessage("Preparing")
        }
        val listener = Listener(info, depots.keys.withIndex().associate { (index, depot) -> depot to index })
        runJobDownload(info, progress) {
            withContext(Dispatchers.IO) {
                SafeDelete.deleteWithin(staging.parentFile ?: context.cacheDir, staging)
                staging.mkdirs()
                val speed = DownloadSpeedConfig()
                val downloader = DepotDownloader(
                    SteamSession.connectedClient(context),
                    licences,
                    debug = false,
                    androidEmulation = true,
                    maxDownloads = speed.maxDownloads,
                    maxDecompress = speed.maxDecompress,
                    parentJob = coroutineContext[Job],
                    autoStartDownload = false,
                    skipLargeFileAllocation = true,
                    filesystem = CaseInsensitiveFileSystem(chunkStagingRedirect = staging.absolutePath.toPath()),
                )
                try {
                    downloader.addListener(listener)
                    if (plan.mainDepots.isNotEmpty()) {
                        downloader.add(AppItem(app.id, installDirectory = installDir.absolutePath, depot = plan.mainDepots.keys.sorted(), branch = branch, branchPassword = choice.password))
                    }
                    for (dlcAppId in plan.dlcAppIds) {
                        val dlcDepots = plan.dlcDepots.filterValues { it.dlcAppId == dlcAppId }.keys.sorted()
                        downloader.add(AppItem(dlcAppId, installDirectory = installDir.absolutePath, depot = dlcDepots, branch = BRANCH, branchPassword = null))
                    }
                    downloader.finishAdding()
                    downloader.startDownloading()
                    // The downloader reports a failed item but never completes its future for it, so waiting on the
                    // future alone left the job in its last state for ever. Whichever comes first ends the wait.
                    coroutineScope {
                        val done = async { downloader.getCompletion().await() }
                        val failed = select<Boolean> {
                            done.onAwait { false }
                            listener.failed.onAwait { true }
                        }
                        if (failed) done.cancel()
                    }
                } finally {
                    runCatching { downloader.close() }
                    SafeDelete.deleteWithin(staging.parentFile ?: context.cacheDir, staging)
                }
            }
        }
        listener.failure?.let {
            Timber.tag(TAG).e(it, "Steam download of ${app.id} ended with a failure")
            throw IllegalStateException("Steam could not download ${app.name}: ${dev.droidtop.runtime.FailureText.reason(it)}", it)
        }

        withContext(Dispatchers.IO) {
            val mainDlc = plan.mainDepots.values.map { it.dlcAppId }.filter { it != SteamIds.INVALID_APP_ID }
            db.installs().upsert(
                (db.installs().find(app.id) ?: AppInfo(app.id)).copy(
                    isDownloaded = true,
                    downloadedDepots = plan.mainDepots.keys.sorted(),
                    dlcDepots = (plan.dlcAppIds + mainDlc).distinct().sorted(),
                    branch = branch,
                    installPath = installDir.absolutePath,
                ),
            )
            for (dlcAppId in plan.dlcAppIds) {
                db.installs().upsert(
                    AppInfo(
                        dlcAppId,
                        isDownloaded = true,
                        downloadedDepots = plan.dlcDepots.filterValues { it.dlcAppId == dlcAppId }.keys.sorted(),
                        branch = BRANCH,
                        installPath = installDir.absolutePath,
                    ),
                )
            }
            info.clearPersistedBytesDownloaded(installDir.absolutePath)
            MarkerUtils.removeMarker(installDir.absolutePath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installDir.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)
        }
        return if (installed) "Updated ${app.name}" else "Installed ${app.name}"
    }

    /** Removes the files of DLC that were installed and are turned off in [choice], and their install rows. */
    private suspend fun removeTurnedOffDlc(db: SteamDatabase, app: SteamApp, choice: SteamChoice, existing: AppInfo?, installDir: File) {
        val installedDlc = existing?.dlcDepots.orEmpty().toSet()
        if (installedDlc.isEmpty()) return
        val everything = planFor(db, app, SteamChoice(branch = choice.branch))
        val available = SteamContent.dlcIn(everything, choice.branch) { "" }.mapTo(HashSet()) { it.appId }
        val removed = SteamContent.removedDlc(choice, available, installedDlc)
        if (removed.isEmpty()) return
        val own = removed.associateWith { id -> db.installs().find(id)?.downloadedDepots.orEmpty() }
        removeDlcFiles(installDir, SteamContent.depotsOf(removed, app.depots, own))
        db.installs().delete(removed.toList())
    }

    /**
     * The DLC the account owns for [app] with content to download, and the
     * download of the base game with them all on: what the picker lists
     * ([SteamContent.dlcIn]) and the plan [planFor] narrows by the choice.
     */
    suspend fun planFor(db: SteamDatabase, app: SteamApp, choice: SteamChoice = SteamChoice()): SteamDepots.Plan {
        val licences = db.licenses()
        val mainPackage = licences.find(app.packageId)
        val sharedPackage = licences.find(0)
        val dlcIdsInDepots = app.depots.values.map { it.dlcAppId }.filter { it != SteamIds.INVALID_APP_ID }.distinct()
        val dlcApps = db.apps().ownedDlcWithDepots(app.id)
        // A DLC app has a row only when a licence the account holds names it.
        // The ones the person turned off are left out here, so their depots
        // and what their packages grant are left out of the plan with them.
        val ownedDlc = (db.apps().knownIds(dlcIdsInDepots) + dlcApps.map { it.id }).toSet() - choice.excludedDlc
        val dlcPackages = ownedDlc.mapNotNull { dlcId ->
            db.apps().find(dlcId)?.packageId?.takeIf { it != SteamIds.INVALID_PKG_ID }?.let { licences.find(it) }?.let { dlcId to it.depotIds }
        }.toMap()
        val licensed = buildSet {
            mainPackage?.depotIds?.let(::addAll)
            sharedPackage?.depotIds?.let(::addAll)
            dlcPackages.values.forEach(::addAll)
        }.takeIf { mainPackage != null && it.isNotEmpty() }
        return SteamDepots.plan(
            app = app,
            language = StoreLanguage.current(),
            ownedDlcAppIds = ownedDlc,
            licensedDepotIds = licensed,
            mainPackageDepots = mainPackage?.depotIds.orEmpty().toSet(),
            dlcPackageDepots = dlcPackages,
            dlcApps = dlcApps.map { dlc ->
                val dlcLicence = licences.find(dlc.packageId)?.depotIds?.toSet()?.takeIf { it.isNotEmpty() }
                SteamDepots.DlcApp(dlc, dlcLicence?.let { it + sharedPackage?.depotIds.orEmpty() })
            },
            alreadyDownloaded = null,
        )
    }

    /**
     * Takes the files of DLC the person turned off out of [installDir]: the
     * files their depots' manifests list that no depot that stays lists, and
     * the depot downloader's record of those depots, so turning the DLC on
     * again downloads it. The manifests are the ones the install kept in the
     * game's folder. Blocking IO; the caller dispatches.
     */
    fun removeDlcFiles(installDir: File, removedDepots: Set<Int>) {
        if (removedDepots.isEmpty()) return
        val builds = SteamInstalls.installedBuilds(installDir)
        fun filesOf(depots: Collection<Int>): List<String> = depots.flatMap { depot ->
            val gid = builds[depot] ?: return@flatMap emptyList()
            val manifest = runCatching { DepotManifest.loadFromFile(SteamInstalls.manifestFile(installDir, depot, gid).absolutePath) }.getOrNull()
                ?: return@flatMap emptyList()
            manifest.files.orEmpty().filterNot(SteamManifests::isDirectory).map { it.fileName }
        }
        val gone = SteamContent.filesToDelete(filesOf(builds.keys.filter { it in removedDepots }), filesOf(builds.keys.filter { it !in removedDepots }))
        for (name in gone) {
            val file = StoreFiles.findCaseInsensitive(installDir, name) ?: continue
            file.parentFile?.let { parent -> SafeDelete.deleteWithin(parent, file) }
        }
        for (depot in removedDepots) builds[depot]?.let { SteamInstalls.manifestFile(installDir, depot, it).delete() }
        val config = File(installDir, "${SteamInstalls.CONFIG_DIR}/depot.config")
        if (config.isFile) config.writeText(SteamContent.withoutDepots(config.readText(), removedDepots))
    }

    /** The depot downloader's progress, into the job's [DownloadInfo] (GameNative's AppDownloadListener). */
    internal class Listener(private val info: DownloadInfo, private val depotIndex: Map<Int, Int>) : IDownloadListener {
        // The downloader sends every event from its own coroutine, so they arrive at once and out of order.
        private val progress = DepotProgress()

        @Volatile
        var failure: Throwable? = null
            private set

        /** Completed with the first failure the downloader reports. */
        val failed = CompletableDeferred<Throwable>()

        override fun onItemAdded(item: DownloadItem) {}

        override fun onDownloadStarted(item: DownloadItem) {
            info.updateStatusMessage("Downloading")
        }

        override fun onDownloadCompleted(item: DownloadItem) {
            Timber.tag(TAG).i("Steam app ${item.appId} downloaded")
        }

        override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
            Timber.tag(TAG).e(error, "Steam app ${item.appId} failed to download")
            failure = error
            failed.complete(error)
            info.failedToDownload()
        }

        override fun onStatusUpdate(message: String) {
            // The phase, not the file: one line per file ("Allocating file: <name>") changed the row thousands of
            // times while the files were being prepared.
            val phase = DepotProgress.phaseOf(message)
            if (phase != info.getCurrentStatusMessage()) info.updateStatusMessage(phase)
        }

        override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
            record(depotId, depotPercentComplete, compressedBytes)
        }

        override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
            record(depotId, 1f, compressedBytes)
        }

        private fun record(depotId: Int, fraction: Float, soFar: Long) {
            val step = progress.record(depotId, fraction, soFar)
            if (step.newBytes > 0L) info.updateBytesDownloaded(step.newBytes, System.currentTimeMillis())
            if (step.moved) depotIndex[depotId]?.let { info.setProgress(step.fraction, it) }
            info.persistProgressSnapshot()
        }
    }
}

/**
 * Each depot's progress as the depot downloader reports it, kept so it only goes forward: its events arrive from
 * concurrent coroutines, so a lower figure can come after a higher one, and counting the difference between the
 * last two counted bytes twice and put the bar at its end early (console, 0.2.0-dev.2127: "not a functional progress
 * bar"). Thread-safe; pure apart from its own state.
 */
internal class DepotProgress {
    /** What one report changed: the depot's fraction now, whether it moved, and the bytes not counted before. */
    data class Step(val fraction: Float, val moved: Boolean, val newBytes: Long)

    private val fractions = HashMap<Int, Float>()
    private val bytes = HashMap<Int, Long>()

    @Synchronized
    fun record(depot: Int, fraction: Float, bytesSoFar: Long): Step {
        val before = fractions[depot] ?: 0f
        val now = maxOf(before, fraction.coerceIn(0f, 1f))
        fractions[depot] = now
        val counted = bytes[depot] ?: 0L
        val newBytes = (bytesSoFar - counted).coerceAtLeast(0L)
        if (newBytes > 0L) bytes[depot] = bytesSoFar
        return Step(now, now > before, newBytes)
    }

    companion object {
        /** The phase a downloader status line names, without the file it is on ("Allocating file: a.dll" is "Allocating files"). */
        fun phaseOf(message: String): String {
            val phase = message.substringBefore(':').trim()
            return when {
                phase.isEmpty() -> message.trim()
                phase.endsWith(" file") -> phase + "s"
                else -> phase
            }
        }
    }
}
