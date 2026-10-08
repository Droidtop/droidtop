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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
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
        val totalBytes = plan.all.values.sumOf { SteamDepots.downloadBytes(it, branch).coerceAtLeast(1L) }
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
            depots.values.forEachIndexed { index, depot -> setWeight(index, SteamDepots.downloadBytes(depot, branch).coerceAtLeast(1L)) }
            setTotalExpectedBytes(totalBytes)
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
                    downloader.getCompletion().await()
                } finally {
                    runCatching { downloader.close() }
                    SafeDelete.deleteWithin(staging.parentFile ?: context.cacheDir, staging)
                }
            }
        }
        listener.failure?.let { throw IllegalStateException("Steam could not download ${app.name}: ${it.message ?: it.javaClass.simpleName}", it) }

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
    private class Listener(private val info: DownloadInfo, private val depotIndex: Map<Int, Int>) : IDownloadListener {
        private val compressed = HashMap<Int, Long>()

        @Volatile
        var failure: Throwable? = null
            private set

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
            info.failedToDownload()
        }

        override fun onStatusUpdate(message: String) {
            info.updateStatusMessage(message)
        }

        override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
            add(depotId, compressedBytes)
            depotIndex[depotId]?.let { info.setProgress(depotPercentComplete, it) }
            info.persistProgressSnapshot()
        }

        override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
            add(depotId, compressedBytes)
            depotIndex[depotId]?.let { info.setProgress(1f, it) }
            info.persistProgressSnapshot()
        }

        /** The downloader reports each depot's bytes so far; the job counts the difference. */
        private fun add(depotId: Int, soFar: Long) {
            val delta = soFar - (compressed.put(depotId, soFar) ?: 0L)
            if (delta > 0L) info.updateBytesDownloaded(delta, System.currentTimeMillis())
        }
    }
}
