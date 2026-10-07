package dev.droidtop.stores.amazon

import android.content.Context
import dev.droidtop.stores.db.dao.AmazonGameDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Amazon library manager and DB bridge (GameNative's, GPL-3.0, without its dependency injection). */
internal class AmazonManager(
    private val amazonGameDao: AmazonGameDao,
    private val context: Context,
) {

    /** Mark a game as installed and persist install metadata. */
    suspend fun markInstalled(productId: String, installPath: String, installSize: Long, versionId: String = "") =
        withContext(Dispatchers.IO) {
            amazonGameDao.markAsInstalled(productId, installPath, installSize, versionId)
            Timber.i("[Amazon] Marked installed: $productId at $installPath (${installSize}B, version=$versionId)")
        }

    /** Get the stored bearer token. */
    suspend fun getBearerToken(): String? = withContext(Dispatchers.IO) {
        AmazonAuthManager.getStoredCredentials(context).getOrNull()?.accessToken
    }

}
