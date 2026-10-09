package dev.droidtop.stores.itch

import android.content.Context
import dev.droidtop.library.PcStoreNames
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreSyncs
import dev.droidtop.library.stores.StoreUpdateCheck
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.stores.data.ItchGame
import dev.droidtop.stores.data.ItchUpload
import dev.droidtop.stores.db.StoresDatabase
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.newDownloadInfo
import dev.droidtop.stores.util.runJobDownload
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * itch.io as a [StoreLibrary] (docs/SPEC.md 7g, "Stores"): what GameNative's
 * `ItchService` did (sync the owned keys, install one upload, uninstall),
 * run by droidtop with droidtop's own screens. An install is one upload,
 * downloaded and unpacked into the folder the person picked; itch.io names
 * no version and keeps no file list, so there is no verify, and an update is
 * known only by the upload's own stamp ([checkUpdate]).
 */
class ItchStore : StoreLibrary {
    override val id = "itch"
    override val label = PcStoreNames.ITCH

    private fun dao(context: Context) = StoresDatabase.get(context).itchGameDao()

    override fun signedIn(context: Context): Boolean = ItchAuthManager.hasStoredCredentials(context)

    override fun accountName(context: Context): String? = ItchAuthManager.storedUsername(context)

    override val signInKind = StoreSignInKind.API_KEY

    override fun signIn(context: Context): StoreSignIn = StoreSignIn.ApiKey(ItchConstants.API_KEY_PAGE)

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> {
        val username = ItchAuthManager.signIn(context, secret).getOrElse { return Result.failure(it) }
        // Signed in either way; a library that failed to read says so on the next sync.
        StoreSyncs.run(context, this).onFailure { Timber.tag(TAG).w(it, "First itch.io library read failed") }
        return Result.success(username)
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(ItchAuthManager.signOut(context)) { "itch.io would not clear its sign-in" }
            dao(context).deleteAllNonInstalledGames()
        }
    }

    /**
     * Refreshes the owned-games table from `/profile/owned-keys`. Install
     * status, path and chosen upload survive the refresh
     * (ItchGameDao.upsertPreservingInstallStatus).
     */
    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val apiKey = ItchAuthManager.getStoredApiKey(context)
            ?: return@withContext Result.failure(IllegalStateException("Not signed in to itch.io"))
        val owned = ItchApiClient.ownedKeys(apiKey).getOrElse { return@withContext Result.failure(it) }
        val games = owned.map { key ->
            ItchGame(
                id = key.gameId,
                title = key.gameTitle,
                downloadKeyId = key.downloadKeyId,
                coverUrl = key.coverUrl,
                url = key.gameUrl,
            )
        }
        dao(context).upsertPreservingInstallStatus(games)
        Timber.tag(TAG).i("Synced ${games.size} itch.io games")
        Result.success(games.size)
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        dao(context).getAllAsList().map { game ->
            StoreGame(
                store = id,
                gameId = game.id,
                title = game.title,
                installed = game.isInstalled,
                // A row that is not installed may name the folder a download
                // is going to; that folder is not an install.
                installPath = game.installPath.takeIf { game.isInstalled && it.isNotBlank() },
                sizeBytes = game.sizeBytes,
                artUrl = game.coverUrl.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Downloads the upload the game last installed, or else the first one
     * built for Windows or Linux that is not a demo, or else the first, and
     * unpacks it ([ItchDownloadManager]).
     */
    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val dao = dao(context)
        val game = withContext(Dispatchers.IO) { dao.getById(gameId) } ?: error("itch.io no longer lists this game")
        val apiKey = ItchAuthManager.getStoredApiKey(context) ?: error("Sign in to itch.io under Stores first")
        progress.report(-1f, "Asking itch.io for the files")
        val uploads = ItchApiClient.uploads(apiKey, gameId, game.downloadKeyId).getOrElse { throw it }
        val upload = chooseUpload(uploads, game.installedUploadId) ?: error("itch.io lists no files for ${game.title}")
        val installPath = game.installPath.ifBlank { StoreFiles.freshFolder(root, game.title, "itch-$gameId").absolutePath }
        // Recorded before the download, so a Cancel knows which folder is the unfinished one.
        if (game.installPath != installPath) withContext(Dispatchers.IO) { dao.update(game.copy(installPath = installPath)) }
        val info = newDownloadInfo()
        runJobDownload(info, progress) {
            ItchDownloadManager.downloadAndInstall(apiKey, game.downloadKeyId, upload, installPath, info)
        }.getOrElse { throw it }
        withContext(Dispatchers.IO) {
            dao.update(
                game.copy(
                    isInstalled = true,
                    installPath = installPath,
                    installedUploadId = upload.id,
                    sizeBytes = upload.sizeBytes,
                    installedStamp = upload.updatedAt,
                ),
            )
        }
        return "Installed ${game.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val dao = dao(context)
            val game = dao.getById(gameId) ?: return@withContext
            if (game.isInstalled || game.installPath.isBlank()) return@withContext
            val dir = File(game.installPath)
            val parent = dir.parentFile ?: return@withContext
            File(parent, "${dir.name}.download.tmp").delete()
            SafeDelete.deleteWithin(parent, dir)
            dao.update(game.copy(installPath = ""))
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val game = dao.getById(gameId) ?: return@withContext Result.failure(IllegalStateException("itch.io no longer lists this game"))
        runCatching {
            if (game.installPath.isNotBlank()) {
                val dir = File(game.installPath)
                val parent = dir.parentFile ?: error("${game.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, dir)) { "Could not remove ${game.installPath}" }
            }
            dao.update(game.copy(isInstalled = false, installPath = "", installedUploadId = 0, installedStamp = ""))
        }.onFailure { Timber.tag(TAG).e(it, "Failed to uninstall itch.io game $gameId") }
    }

    /**
     * The upload the install came from against what itch lists for it now.
     * itch keeps an upload's id when its developer pushes a new build, so
     * the answer is the upload's `updated_at`. An install with no stamp
     * recorded, or whose upload itch no longer lists, stays unknown.
     */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = withContext(Dispatchers.IO) {
        val game = dao(context).getById(gameId) ?: return@withContext null
        if (!game.isInstalled || game.installedStamp.isBlank()) return@withContext null
        val apiKey = ItchAuthManager.getStoredApiKey(context) ?: return@withContext null
        val uploads = ItchApiClient.uploads(apiKey, gameId, game.downloadKeyId).getOrNull() ?: return@withContext null
        val state = updateState(game.installedStamp, uploads.firstOrNull { it.id == game.installedUploadId }) ?: return@withContext null
        StoreUpdateCheck(state)
    }

    override fun changeStamp(context: Context): Long =
        StoreFiles.stamp(StoresDatabase.files(context) + File(ItchConstants.getAuthConfigPath(context)))

    internal companion object {
        private const val TAG = "ItchStore"

        /**
         * Whether the upload an install came from has a newer build: its
         * stamp now against the one recorded. Null (not known) when no stamp
         * was recorded, the upload is no longer listed or names no stamp.
         */
        fun updateState(installedStamp: String, live: ItchUpload?): StoreUpdate? {
            if (installedStamp.isBlank() || live == null || live.updatedAt.isBlank()) return null
            return if (live.updatedAt == installedStamp) StoreUpdate.CURRENT else StoreUpdate.AVAILABLE
        }

        /**
         * The upload an install takes: the one the game last installed, else
         * the first Windows or Linux build that is not a demo, else the first.
         */
        fun chooseUpload(uploads: List<ItchUpload>, installedUploadId: Long): ItchUpload? =
            uploads.firstOrNull { installedUploadId != 0L && it.id == installedUploadId }
                ?: uploads.firstOrNull { (it.platformWindows || it.platformLinux) && !it.isDemo }
                ?: uploads.firstOrNull()
    }
}
