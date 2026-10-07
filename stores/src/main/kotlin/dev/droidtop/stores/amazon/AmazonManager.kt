package dev.droidtop.stores.amazon

import android.content.Context
import dev.droidtop.stores.data.AmazonGame
import dev.droidtop.stores.db.dao.AmazonGameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Amazon library manager and DB bridge (GameNative's, GPL-3.0, without its dependency injection). */
internal class AmazonManager(
    private val amazonGameDao: AmazonGameDao,
    private val context: Context,
) {

    /** Refresh the Amazon library from API and persist it in DB. */
    suspend fun refreshLibrary() = withContext(Dispatchers.IO) {
        Timber.i("[Amazon] Starting library refresh…")

        val credentialsResult = AmazonAuthManager.getStoredCredentials(context)
        if (credentialsResult.isFailure) {
            Timber.w("[Amazon] No stored credentials — ${credentialsResult.exceptionOrNull()?.message}")
            return@withContext
        }
        val credentials = credentialsResult.getOrNull()!!

        val games = AmazonApiClient.getEntitlements(
            bearerToken = credentials.accessToken,
            deviceSerial = credentials.deviceSerial,
        )

        if (games.isEmpty()) {
            Timber.w("[Amazon] No entitlements returned from API")
            return@withContext
        }

        amazonGameDao.upsertPreservingInstallStatus(games)
        Timber.i("[Amazon] Library refresh complete — ${games.size} game(s) in DB")
    }

    /** Look up a game by product ID. */
    suspend fun getGameById(productId: String): AmazonGame? = withContext(Dispatchers.IO) {
        amazonGameDao.getByProductId(productId)
    }

    /** Look up a game by auto-generated appId. */
    suspend fun getGameByAppId(appId: Int): AmazonGame? = withContext(Dispatchers.IO) {
        amazonGameDao.getByAppId(appId)
    }

    /** Return all Amazon games from DB. */
    suspend fun getAllGames(): List<AmazonGame> = withContext(Dispatchers.IO) {
        amazonGameDao.getAllAsList()
    }

    /** Return non-installed Amazon games from DB. */
    suspend fun getNonInstalledGames(): List<AmazonGame> = withContext(Dispatchers.IO) {
        amazonGameDao.getNonInstalledGames()
    }

    /** Mark a game as installed and persist install metadata. */
    suspend fun markInstalled(productId: String, installPath: String, installSize: Long, versionId: String = "") =
        withContext(Dispatchers.IO) {
            amazonGameDao.markAsInstalled(productId, installPath, installSize, versionId)
            Timber.i("[Amazon] Marked installed: $productId at $installPath (${installSize}B, version=$versionId)")
        }

    /** Mark a game as not installed. */
    suspend fun markUninstalled(productId: String) = withContext(Dispatchers.IO) {
        amazonGameDao.markAsUninstalled(productId)
        Timber.i("[Amazon] Marked uninstalled: $productId")
    }

    /** Update cached download size for a game. */
    suspend fun updateDownloadSize(productId: String, size: Long) = withContext(Dispatchers.IO) {
        amazonGameDao.updateDownloadSize(productId, size)
        Timber.i("[Amazon] Updated download size for $productId: $size bytes")
    }

    /** Get the stored bearer token. */
    suspend fun getBearerToken(): String? = withContext(Dispatchers.IO) {
        AmazonAuthManager.getStoredCredentials(context).getOrNull()?.accessToken
    }

    /** Delete all non-installed Amazon games on logout. */
    suspend fun deleteAllNonInstalledGames() = withContext(Dispatchers.IO) {
        amazonGameDao.deleteAllNonInstalledGames()
        Timber.i("[Amazon] Deleted all non-installed games from DB")
    }
}
