package dev.droidtop.stores.amazon

import android.content.Context
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.stores.data.AmazonGame
import dev.droidtop.stores.data.DownloadInfo
import dev.droidtop.stores.util.Marker
import dev.droidtop.stores.util.ChunkRetry
import dev.droidtop.stores.util.MarkerUtils
import dev.droidtop.stores.util.StoreHttp
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber

/** Handles native downloading of Amazon games (GameNative's, GPL-3.0, without its dependency injection). */
internal class AmazonDownloadManager(
    private val amazonManager: AmazonManager,
) {

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 6
        private const val TAG = "Amazon"

        /**
         * The start of the downloads-area names of [productId]'s files, so a discarded install deletes its own partial
         * files and not another game's. Pure.
         */
        fun areaPrefix(productId: String): String = "amazon_" + productId.replace(Regex("[^A-Za-z0-9._-]"), "_") + "_"

        /** The downloads-area name of [productId]'s file with the SHA-256 [hashHex]. Pure. */
        fun areaName(productId: String, hashHex: String): String = areaPrefix(productId) + hashHex
    }

    /** Download and install an Amazon game. */
    suspend fun downloadGame(
        context: Context,
        game: AmazonGame,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val productId = game.productId
        try {
            Timber.tag(TAG).i("Starting download for ${game.title} → $installPath")

            File(installPath).mkdirs()
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            // Helper to cleanup marker on early failure
            fun cleanupOnFailure() {
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            }

            // ── 1. Credentials ───────────────────────────────────────────────
            if (game.entitlementId.isBlank()) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Game '${game.title}' has no entitlement ID — re-sync library first"))
            }
            val bearerToken = amazonManager.getBearerToken()
            if (bearerToken == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("No Amazon credentials stored"))
            }

            // ── 2. Download spec ─────────────────────────────────────────────
            downloadInfo.updateStatusMessage("Fetching download information…")
            val spec = AmazonApiClient.fetchGameDownload(game.entitlementId, bearerToken)
            if (spec == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to fetch download spec from Amazon"))
            }

            Timber.tag(TAG).d("Download spec: url=${spec.downloadUrl}, version=${spec.versionId}")

            // ── 3. Manifest ──────────────────────────────────────────────────
            downloadInfo.updateStatusMessage("Fetching manifest…")
            val manifestUrl = appendPath(spec.downloadUrl, "manifest.proto")
            Timber.tag(TAG).d("Manifest URL: $manifestUrl")
            val manifestBytes = fetchBytes(manifestUrl)
            if (manifestBytes == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to download manifest.proto"))
            }

            val manifest = try {
                AmazonManifest.parse(manifestBytes)
            } catch (e: Exception) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to parse manifest: ${e.message}", e))
            }

            val files = manifest.allFiles
            if (files.isEmpty()) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Manifest contains no files"))
            }

            Timber.tag(TAG).i(
                "Manifest: ${files.size} file(s), ${manifest.totalInstallSize / 1_000_000} MB total"
            )

            // ── 4. Progress setup ────────────────────────────────────────────
            downloadInfo.setTotalExpectedBytes(manifest.totalInstallSize)
            downloadInfo.setProgress(0f)
            downloadInfo.emitProgressChange()

            // ── 5. Download files, six at a time ─────────────────────────────
            // A file starts as soon as any of the six before it ends: one slow file never holds back the rest.
            val installDir = File(installPath)
            val baseUrl = spec.downloadUrl
            val completedFiles = AtomicInteger(0)
            val totalFiles = files.size
            val slots = Semaphore(MAX_PARALLEL_DOWNLOADS)
            // After one file fails the install has failed: the files still waiting are not started.
            val firstError = AtomicReference<Throwable?>(null)
            coroutineScope {
                files.map { file ->
                    async {
                        slots.withPermit {
                            if (!downloadInfo.isActive()) throw CancellationException("Download cancelled")
                            if (firstError.get() != null) return@withPermit
                            downloadFileWithRetry(
                                context = context,
                                productId = productId,
                                baseUrl = baseUrl,
                                file = file,
                                installDir = installDir,
                                downloadInfo = downloadInfo,
                            ).onSuccess {
                                downloadInfo.updateStatusMessage("Downloading (${completedFiles.incrementAndGet()}/$totalFiles files)…")
                                downloadInfo.emitProgressChange()
                                downloadInfo.persistProgressSnapshot()
                            }.onFailure { firstError.compareAndSet(null, it) }
                            Unit
                        }
                    }
                }.awaitAll()
            }
            firstError.get()?.let { error ->
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                return@withContext Result.failure(error)
            }

            // ── 6. Cache manifest ────────────────────────────────────────
            try {
                val manifestDir = File(context.filesDir, "manifests/amazon")
                manifestDir.mkdirs()
                val manifestFile = File(manifestDir, "$productId.proto")
                manifestFile.writeBytes(manifestBytes)
                Timber.tag(TAG).i("Cached manifest: ${manifestFile.absolutePath} (${manifestBytes.size} bytes)")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to cache manifest (non-fatal)")
            }

            // ── 7. Persist installed state ───────────────────────────────────
            Timber.tag(TAG).i("Persisting install: productId=$productId, version=${spec.versionId}")
            amazonManager.markInstalled(productId, installPath, manifest.totalInstallSize, spec.versionId)

            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)

            downloadInfo.updateStatusMessage("Complete")
            // Clamp bytes so progress reads exactly 1.0
            val remaining = downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded()
            if (remaining > 0L) downloadInfo.updateBytesDownloaded(remaining)
            downloadInfo.setProgress(1f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            Timber.tag(TAG).i("Download complete for ${game.title}")
            Result.success(Unit)

        } catch (e: Exception) {
            if (e is CancellationException) {
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                downloadInfo.persistProgressSnapshot()
                downloadInfo.setActive(false)
                throw e
            }
            Timber.tag(TAG).e(e, "Download failed for ${game.title}: ${e.message}")
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1f)
            downloadInfo.setActive(false)
            Result.failure(e)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** One file, once more if it arrived damaged ([ChunkRetry]: the engine has already retried the transfer). */
    private suspend fun downloadFileWithRetry(
        context: Context,
        productId: String,
        baseUrl: String,
        file: AmazonManifest.ManifestFile,
        installDir: File,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = ChunkRetry.run(tag = TAG, what = "File ${file.unixPath}", isActive = { downloadInfo.isActive() }) {
        downloadFile(context, productId, baseUrl, file, installDir, downloadInfo)
    }

    /**
     * One manifest file on droidtop's download engine (docs/SPEC.md 12a, rollout step 4): fetched into the downloads area
     * under its hash, checked against its SHA-256, then moved into place, so a stopped install carries on from the
     * partial file next time. The job's bytes are counted here, across all the files.
     */
    private suspend fun downloadFile(
        context: Context,
        productId: String,
        baseUrl: String,
        file: AmazonManifest.ManifestFile,
        installDir: File,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val destFile = File(installDir, file.unixPath).canonicalFile
        val tmpFile = File(installDir, "${file.unixPath}.tmp").canonicalFile
        val installDirCanonical = installDir.canonicalPath

        // Security check: prevent path traversal attacks
        if (!destFile.path.startsWith(installDirCanonical) || !tmpFile.path.startsWith(installDirCanonical)) {
            Timber.tag(TAG).e("Path traversal attempt blocked: ${file.unixPath}")
            return@withContext Result.failure(SecurityException("Invalid file path"))
        }

        try {
            // Skip already-complete files (resume-friendly)
            if (destFile.exists() && destFile.length() == file.size) {
                // Count recovered bytes for progress without skewing transfer speed ETA.
                downloadInfo.updateBytesDownloaded(file.size, trackSpeed = false)
                return@withContext Result.success(Unit)
            }

            destFile.parentFile?.mkdirs()
            tmpFile.parentFile?.mkdirs()

            // nile uses /files/{hash_hex} per downloading/manager.py, NOT the unix path
            val hashHex = file.hashHex
            val url = appendPath(baseUrl, "files/$hashHex")
            val areaName = areaName(productId, hashHex)
            var counted = 0L
            val fetched = DownloadJobs.fetchForJob(
                context, url, areaName, headers = mapOf("User-Agent" to "nile/0.1 Amazon"), tellJob = false,
            ) { percent, _ ->
                // The engine reports a percent; the job's byte count is this file's share of it.
                if (percent < 0) return@fetchForJob
                val now = file.size * percent / 100
                if (now > counted) {
                    downloadInfo.updateBytesDownloaded(now - counted)
                    counted = now
                    downloadInfo.emitProgressChange()
                }
            }
            if (counted < file.size) downloadInfo.updateBytesDownloaded(file.size - counted)
            DownloadJobs.moveFile(fetched, tmpFile)
            DownloadJobs.discardForJob(context, areaName)

            // Verify SHA-256 hash (algorithm 0) when present
            if (!file.matches(tmpFile)) {
                tmpFile.delete()
                return@withContext Result.failure(Exception("SHA-256 mismatch for ${file.unixPath}"))
            }

            if (destFile.exists()) destFile.delete()
            tmpFile.renameTo(destFile)

            Result.success(Unit)
        } catch (e: CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: Exception) {
            tmpFile.delete()
            Timber.tag(TAG).w(e, "Error downloading ${file.unixPath}")
            Result.failure(e)
        }
    }

    /** Append [segment] to [baseUrl] path before any query string. */
    private fun appendPath(baseUrl: String, segment: String): String {
        val qIdx = baseUrl.indexOf('?')
        return if (qIdx == -1) {
            "$baseUrl/$segment"
        } else {
            val path = baseUrl.substring(0, qIdx)
            val query = baseUrl.substring(qIdx)
            "$path/$segment$query"
        }
    }

    /** Download raw bytes from [url] on droidtop's shared store client, returning null on error. */
    private fun fetchBytes(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).build()
        StoreHttp.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes()
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "fetchBytes failed for $url")
        null
    }
}
