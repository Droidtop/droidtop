package dev.droidtop.stores.itch

import android.content.Context
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.stores.data.DownloadInfo
import dev.droidtop.stores.data.ItchUpload
import dev.droidtop.runtime.util.ArchiveExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Downloads one itch.io upload and installs it -- one file, unlike GOG,
 * Epic and Amazon's manifest-driven, many-file installs, because itch
 * uploads are exactly that: one archive or one executable, with no
 * per-file patch protocol.
 *
 * Extraction goes through [ArchiveExtractor], the archive-bomb-guarded
 * zip/7z/rar extractor GameNative's mods pipeline used -- one mechanism for
 * "unpack an untrusted archive into a game folder", not a second one
 * written for itch. Lifted from droidtop's fork of GameNative
 * (app.gamenative.service.itch, GPL-3.0).
 */
internal object ItchDownloadManager {
    private const val TAG = "ItchDownloadManager"

    /**
     * The downloads-area name of [gameId]'s upload while it downloads: one per game, so a paused or restarted install
     * carries on from its partial file ([DownloadJobs.fetchForJob]) and Cancel finds it ([ItchStore.discardPartial]).
     */
    fun areaName(gameId: String): String = "itch_" + gameId.replace(Regex("[^A-Za-z0-9]"), "_") + ".download"

    /**
     * Downloads [upload] on droidtop's download engine (docs/SPEC.md 12a, rollout step 4) into the downloads area as
     * [areaName], then extracts it into [installPath]. Progress and the line go to [downloadInfo], the shared object
     * every store service here reports through; the bytes and waits go to the install's own job.
     */
    suspend fun downloadAndInstall(
        context: Context,
        areaName: String,
        apiKey: String,
        downloadKeyId: String,
        upload: ItchUpload,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            downloadInfo.updateStatusMessage("Resolving download...")
            val url = ItchApiClient.resolveDownloadUrl(apiKey, upload.id, downloadKeyId)
                .getOrElse { return@withContext Result.failure(it) }

            val installDir = File(installPath)
            installDir.mkdirs()
            downloadInfo.setTotalExpectedBytes(upload.sizeBytes)
            downloadInfo.updateStatusMessage("Downloading ${upload.filename}...")

            // The link itch.io resolves is short-lived, so it is asked for again on each try; the engine carries on
            // from the partial file under the same name whatever the link.
            val downloaded = DownloadJobs.fetchForJob(context, url, areaName) { percent, line ->
                if (percent >= 0) downloadInfo.setProgress(percent / 100f)
                downloadInfo.updateStatusMessage(line)
                downloadInfo.emitProgressChange()
            }

            downloadInfo.updateStatusMessage("Installing...")
            installDownloadedFile(downloaded, upload, installDir)
            DownloadJobs.discardForJob(context, areaName)

            downloadInfo.setProgress(1f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()
            Result.success(Unit)
        } catch (e: CancellationException) {
            downloadInfo.setActive(false)
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "itch download/install failed")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setActive(false)
            Result.failure(e)
        }
    }

    /**
     * Extracts a real archive (zip/7z/rar) into [installDir]; a plain
     * executable or APK upload is copied in as-is, since there is
     * nothing to unpack. The extension, not the upload's declared
     * platform, decides which -- an upload can be a single .exe with no
     * archive around it at all.
     */
    private suspend fun installDownloadedFile(downloaded: File, upload: ItchUpload, installDir: File) {
        val extension = upload.filename.substringAfterLast('.', "").lowercase()
        when (extension) {
            "zip", "7z", "rar" -> ArchiveExtractor.extract(downloaded, installDir)
            else -> {
                // A bare executable, .apk, or another single-file upload:
                // keep its own filename inside the install directory so
                // the runner's own executable search still finds it.
                installDir.mkdirs()
                val target = File(installDir, upload.filename.ifBlank { downloaded.name })
                downloaded.copyTo(target, overwrite = true)
            }
        }
    }
}
