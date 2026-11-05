package dev.droidtop.stores.util

import android.content.Context
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.runtime.util.ArchiveExtractor
import dev.droidtop.stores.data.DownloadInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The install of a store that sells a game as one file (docs/SPEC.md 7g, "Stores"): itch.io's uploads, Humble's
 * DRM-free downloads. The file comes down on droidtop's download engine into the downloads area under one name per
 * game ([areaName]), so a paused or restarted install carries on from its partial file and Cancel finds it, then a
 * real archive (zip/7z/rar) is unpacked into the game's folder through [ArchiveExtractor] (archive-bomb guarded, the
 * one mechanism for "unpack an untrusted archive into a game folder") and anything else, a lone exe or installer, is
 * kept in it under its own name so the runner's executable search finds it.
 */
internal object SingleFileInstall {
    private const val TAG = "SingleFileInstall"

    /** The downloads-area name of [store]'s [gameId] while it downloads. */
    fun areaName(store: String, gameId: String): String = store + "_" + gameId.replace(Regex("[^A-Za-z0-9]"), "_") + ".download"

    /** Whether [fileName] is an archive [unpack] opens rather than keeps. Pure. */
    fun isArchive(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in setOf("zip", "7z", "rar")

    /**
     * Downloads [url] as [areaName] and puts it into [installDir]. [fileName] decides archive or not; [sizeBytes] is
     * shown while it downloads; [md5], when the store gives one, is checked before anything is unpacked. Progress and
     * the line go to [downloadInfo], the object every store here reports through.
     */
    suspend fun run(
        context: Context,
        areaName: String,
        url: String,
        fileName: String,
        sizeBytes: Long,
        installDir: File,
        downloadInfo: DownloadInfo,
        md5: String? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            installDir.mkdirs()
            downloadInfo.setTotalExpectedBytes(sizeBytes)
            downloadInfo.updateStatusMessage("Downloading $fileName...")
            val downloaded = DownloadJobs.fetchForJob(context, url, areaName) { percent, line ->
                if (percent >= 0) downloadInfo.setProgress(percent / 100f)
                downloadInfo.updateStatusMessage(line)
                downloadInfo.emitProgressChange()
            }
            if (!md5.isNullOrBlank() && !md5Of(downloaded).equals(md5, ignoreCase = true)) {
                // A damaged file is not kept for a resume: the next try starts it again.
                DownloadJobs.discardForJob(context, areaName)
                error("$fileName did not arrive whole; try the install again")
            }
            downloadInfo.updateStatusMessage("Installing...")
            unpack(downloaded, fileName, installDir)
            DownloadJobs.discardForJob(context, areaName)
            downloadInfo.setProgress(1f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()
            Result.success(Unit)
        } catch (e: CancellationException) {
            downloadInfo.setActive(false)
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download or install of $fileName failed")
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setActive(false)
            Result.failure(e)
        }
    }

    /** The MD5 of [file] as hex. */
    fun md5Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** An archive unpacked into [installDir]; any other file copied in under [fileName] (its own name). */
    suspend fun unpack(downloaded: File, fileName: String, installDir: File) {
        if (isArchive(fileName)) {
            ArchiveExtractor.extract(downloaded, installDir)
        } else {
            installDir.mkdirs()
            downloaded.copyTo(File(installDir, File(fileName).name.ifBlank { downloaded.name }), overwrite = true)
        }
    }
}
