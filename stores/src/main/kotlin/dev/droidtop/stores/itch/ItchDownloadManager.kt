package dev.droidtop.stores.itch

import android.content.Context
import dev.droidtop.stores.data.DownloadInfo
import dev.droidtop.stores.data.ItchUpload
import dev.droidtop.stores.util.SingleFileInstall
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads one itch.io upload and installs it: one file, unlike GOG, Epic and Amazon's manifest-driven, many-file
 * installs, because itch uploads are exactly that, one archive or one executable, with no per-file patch protocol.
 * The download and the unpacking are [SingleFileInstall], the one path every one-file store uses (Humble too).
 * Lifted from droidtop's fork of GameNative (app.gamenative.service.itch, GPL-3.0).
 */
internal object ItchDownloadManager {
    /** The downloads-area name of [gameId]'s upload while it downloads ([SingleFileInstall.areaName]). */
    fun areaName(gameId: String): String = SingleFileInstall.areaName("itch", gameId)

    /**
     * Asks itch.io for [upload]'s short-lived link, then downloads and installs it into [installPath]
     * ([SingleFileInstall.run]).
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
        downloadInfo.updateStatusMessage("Resolving download...")
        val url = ItchApiClient.resolveDownloadUrl(apiKey, upload.id, downloadKeyId).getOrElse { return@withContext Result.failure(it) }
        SingleFileInstall.run(context, areaName, url, upload.filename, upload.sizeBytes, File(installPath), downloadInfo)
    }
}
