package dev.droidtop.runtime.windows.utils

import android.content.Context
import android.net.Uri
import dev.droidtop.runtime.windows.R
import dev.droidtop.runtime.windows.RuntimeDownloads
import dev.droidtop.runtime.windows.WineBuilds
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ManifestInstallResult(
    val success: Boolean,
    val message: String,
)

/**
 * Installs one catalog item ([ComponentCatalog]): a driver zip through
 * adrenotools, a raw DXVK/VKD3D archive into the dxwrapper cache, anything
 * else (.wcp) into the contents store, a Wine/Proton build after
 * [WineBuilds.prepare]. Every download is checked against the item's
 * SHA-256, tried at each of its locations in turn. Adapted from
 * GameNative's ManifestInstaller (GPL-3.0).
 */
object ManifestInstaller {
    suspend fun downloadAndInstallDriver(
        context: Context,
        entry: ManifestEntry,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        var destFile: File? = null
        try {
            destFile = File(context.cacheDir, entry.url.substringAfterLast("/"))
            RuntimeDownloads.fetchUrls(entry.downloadUrls(), destFile, entry.sha256, onProgress)
            val uri = Uri.fromFile(destFile)
            val name = AdrenotoolsManager(context).installDriver(uri)
            if (name.isEmpty()) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }
            if (name != entry.id) Timber.w("ManifestInstaller: driver %s installed as %s", entry.id, name)
            return@withContext ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: driver install failed")
            return@withContext ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        } finally {
            destFile?.delete()
        }
    }

    /**
     * Installs a single catalog entry (driver or content).
     *
     * UI layers should provide [onProgress] to update their own state and then
     * handle the returned [ManifestInstallResult] (e.g. to show a Toast or
     * refresh installed-content lists).
     */
    suspend fun installManifestEntry(
        context: Context,
        entry: ManifestEntry,
        isDriver: Boolean,
        contentType: ContentProfile.ContentType? = null,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult {
        return if (isDriver) {
            downloadAndInstallDriver(context, entry, onProgress)
        } else {
            val type = contentType
                ?: throw IllegalArgumentException("contentType must be provided when installing manifest content")
            downloadAndInstallContent(context, entry, type, onProgress)
        }
    }

    /**
     * dxvk/vkd3d distributed as raw .tzst archives have no ContentProfile; they are extracted
     * directly into the wine prefix at launch. "Installing" one just means caching the .tzst in
     * the DXWrapperDownloader cache dir so the launch path finds it instead of downloading.
     */
    private fun isTzstEntry(entry: ManifestEntry): Boolean = entry.url.endsWith(".tzst")

    private suspend fun installTzstToCache(
        context: Context,
        entry: ManifestEntry,
        onProgress: (Float) -> Unit,
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.filesDir, "assets/dxwrapper")
            cacheDir.mkdirs()
            val dest = File(cacheDir, entry.url.substringAfterLast("/"))
            RuntimeDownloads.fetchUrls(entry.downloadUrls(), dest, entry.sha256, onProgress)
            if (!dest.exists() || dest.length() == 0L) {
                dest.delete()
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }
            ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: tzst install failed")
            ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        }
    }

    suspend fun downloadAndInstallContent(
        context: Context,
        entry: ManifestEntry,
        expectedType: ContentProfile.ContentType,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        if (isTzstEntry(entry)) {
            return@withContext installTzstToCache(context, entry, onProgress)
        }
        var destFile: File? = null
        try {
            destFile = File(context.cacheDir, entry.url.substringAfterLast("/"))
            RuntimeDownloads.fetchUrls(entry.downloadUrls(), destFile, entry.sha256, onProgress)
            val mgr = ContentsManager(context)

            var (profile, _) = extract(mgr, Uri.fromFile(destFile))
            if (profile == null) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }
            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                profile = WineBuilds.prepare(context, mgr, profile, entry.id)
            }

            if (!finish(mgr, profile)) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }

            return@withContext ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: content install failed")
            return@withContext ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        } finally {
            destFile?.delete()
        }
    }

    /** Unpacks a content package into the contents store's staging folder: its profile, or why not. Disk. */
    fun extract(
        mgr: ContentsManager,
        uri: Uri,
    ): Pair<ContentProfile?, ContentsManager.InstallFailedReason?> {
        var profile: ContentProfile? = null
        var failReason: ContentsManager.InstallFailedReason? = null
        val latch = CountDownLatch(1)
        try {
            mgr.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    failReason = reason
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    profile = profileArg
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: extract failed")
            latch.countDown()
        }
        if (!latch.await(240, TimeUnit.SECONDS)) {
            Timber.w("ManifestInstaller: extract timed out after 240 seconds")
        }
        return profile to failReason
    }

    /** Moves an extracted package into the contents store; false when it could not (already there, no space). Disk. */
    fun finish(
        mgr: ContentsManager,
        profile: ContentProfile,
    ): Boolean {
        var success = false
        val latch = CountDownLatch(1)
        try {
            mgr.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    success = true
                    latch.countDown()
                }
            })
        } catch (_: Exception) {
            latch.countDown()
        }
        if (!latch.await(240, TimeUnit.SECONDS)) {
            Timber.w("ManifestInstaller: finishInstall timed out after 240 seconds")
            return false
        }
        return success
    }
}
