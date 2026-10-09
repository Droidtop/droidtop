package dev.droidtop.stores.amazon

import android.content.Context
import android.net.Uri
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
import dev.droidtop.stores.db.StoresDatabase
import dev.droidtop.stores.util.Marker
import dev.droidtop.stores.util.MarkerUtils
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.newDownloadInfo
import dev.droidtop.stores.util.runJobDownload
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Amazon Games as a [StoreLibrary] (docs/SPEC.md 7g, "Stores"): what
 * GameNative's `AmazonService` did (sign in with Amazon's own page and a
 * PKCE code, read the entitlements, install from the manifest, verify against
 * it, compare the installed version with the live one, uninstall), run by
 * droidtop with droidtop's own screens. GameNative ran it as an Android
 * Service that no droidtop manifest declared, so none of it ever ran here.
 *
 * Not carried: GameNative's Amazon Games Services SDK deployment into a Wine
 * prefix (`AmazonSdkManager`), which belongs to the launch path, not the store.
 */
class AmazonStore : StoreLibrary {
    override val id = "amazon"
    override val label = PcStoreNames.AMAZON
    override val signInKind = StoreSignInKind.WEB_PAGE
    override val canVerify = true

    private fun dao(context: Context) = StoresDatabase.get(context).amazonGameDao()
    private fun manager(context: Context) = AmazonManager(dao(context), context.applicationContext)

    override fun signedIn(context: Context): Boolean = AmazonAuthManager.hasStoredCredentials(context)

    /**
     * Amazon's sign-in page for a fresh PKCE session. After the person signs
     * in, Amazon sends the page to `https://www.amazon.com/?openid.assoc_handle=
     * amzn_sonic_games_launcher&openid.oa2.authorization_code=...`
     * (GameNative's AmazonOAuthActivity).
     */
    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = AmazonAuthManager.startAuthFlow(),
        isReturnPage = { url ->
            (url.startsWith("https://www.amazon.com/") || url.startsWith("https://amazon.com/")) &&
                url.contains("openid.assoc_handle=${AmazonConstants.OPENID_ASSOC_HANDLE}")
        },
        codeInUrl = { url -> runCatching { Uri.parse(url).getQueryParameter("openid.oa2.authorization_code") }.getOrNull() },
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> {
        AmazonAuthManager.authenticateWithCode(context, secret).getOrElse { return Result.failure(it) }
        StoreSyncs.run(context, this).onFailure { Timber.tag(TAG).w(it, "First Amazon library read failed") }
        // Amazon's sign-in names no account.
        return Result.success(null)
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            AmazonAuthManager.logout(context)
            dao(context).deleteAllNonInstalledGames()
        }
    }

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val credentials = AmazonAuthManager.getStoredCredentials(context).getOrElse { return@withContext Result.failure(it) }
        val games = AmazonApiClient.getEntitlements(bearerToken = credentials.accessToken, deviceSerial = credentials.deviceSerial)
        // An empty answer is also what a failed request gives; the rows already read stay.
        if (games.isEmpty()) return@withContext Result.failure(IllegalStateException("Amazon listed no games. Try again in a moment"))
        dao(context).upsertPreservingInstallStatus(games)
        Result.success(games.size)
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        // An entitlement that is not a game (DLC, in-game items) is not a library entry; an installed row stays.
        dao(context).getAllAsList().filter { it.isInstalled || isGame(it.productJson) }.map { game ->
            StoreGame(
                store = id,
                gameId = game.productId,
                title = game.title,
                installed = game.isInstalled,
                installPath = game.installPath.takeIf { game.isInstalled && it.isNotBlank() },
                sizeBytes = if (game.isInstalled) game.installSize else game.downloadSize,
                artUrl = game.artUrl.takeIf { it.isNotBlank() },
            )
        }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val dao = dao(context)
        val game = withContext(Dispatchers.IO) { dao.getByProductId(gameId) } ?: error("Amazon no longer lists this game")
        val installPath = game.installPath.ifBlank { StoreFiles.freshFolder(root, game.title, "amazon-${game.appId}").absolutePath }
        val wasInstalled = game.isInstalled
        withContext(Dispatchers.IO) {
            // Recorded before the download, so a Cancel knows which folder is the unfinished one.
            if (game.installPath != installPath) dao.insertAll(listOf(game.copy(installPath = installPath)))
            // A fresh run of an install or update clears the old finished marker first.
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
        }
        // A resumed download counts the files already there as it skips them,
        // so it starts from zero rather than from a saved byte count.
        val info = newDownloadInfo().apply { setPersistencePath(installPath) }
        val result = runJobDownload(info, progress) {
            AmazonDownloadManager(manager(context)).downloadGame(context, game, installPath, info)
        }
        result.onFailure { error ->
            // A first install that failed leaves nothing half-made behind (GameNative's
            // cleanupFailedInstall); an update that failed keeps the game it had.
            if (!wasInstalled) discardPartial(context, gameId)
            throw error
        }
        info.clearPersistedBytesDownloaded(installPath)
        return "Installed ${game.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val dao = dao(context)
            val game = dao.getByProductId(gameId) ?: return@withContext
            if (game.isInstalled || game.installPath.isBlank()) return@withContext
            val dir = File(game.installPath)
            dir.parentFile?.let { SafeDelete.deleteWithin(it, dir) }
            dao.insertAll(listOf(game.copy(installPath = "")))
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val game = dao.getByProductId(gameId) ?: return@withContext Result.failure(IllegalStateException("Amazon no longer lists this game"))
        runCatching {
            if (game.installPath.isNotBlank()) {
                val dir = File(game.installPath)
                val parent = dir.parentFile ?: error("${game.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, dir)) { "Could not remove ${game.installPath}" }
            }
            manifestFile(context, gameId).delete()
            dao.markAsUninstalled(gameId)
        }.onFailure { Timber.tag(TAG).e(it, "Failed to uninstall Amazon game $gameId") }
    }

    /** Checks every installed file's size and SHA-256 against the manifest kept at install time. */
    override suspend fun verify(context: Context, gameId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val game = dao(context).getByProductId(gameId) ?: error("Amazon no longer lists this game")
            check(game.isInstalled && game.installPath.isNotBlank()) { "${game.title} is not installed" }
            val installDir = File(game.installPath)
            check(installDir.isDirectory) { "${game.installPath} is not there" }
            val manifest = manifestFile(context, gameId).takeIf { it.isFile }
                ?: error("droidtop kept no file list for this install. Install it again to check it later")
            val files = AmazonManifest.parse(manifest.readBytes()).allFiles
            var missing = 0
            var changed = 0
            for (file in files) {
                val local = File(installDir, file.unixPath)
                when {
                    !local.isFile -> missing++
                    local.length() != file.size -> changed++
                    file.hashAlgorithm == 0 && file.hashBytes.isNotEmpty() && !sha256(local).contentEquals(file.hashBytes) -> changed++
                }
            }
            verifyLine(files.size, missing, changed)
        }
    }

    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = withContext(Dispatchers.IO) {
        val game = dao(context).getByProductId(gameId) ?: return@withContext null
        if (!game.isInstalled || game.versionId.isEmpty()) return@withContext null
        val token = AmazonAuthManager.getStoredCredentials(context).getOrNull()?.accessToken ?: return@withContext null
        when (AmazonApiClient.isUpdateAvailable(gameId, game.versionId, token)) {
            true -> StoreUpdateCheck(StoreUpdate.AVAILABLE)
            false -> StoreUpdateCheck(StoreUpdate.CURRENT)
            null -> null
        }
    }

    override fun changeStamp(context: Context): Long =
        StoreFiles.stamp(StoresDatabase.files(context) + File(File(context.filesDir, "amazon"), "credentials.json"))

    /** Where the install's manifest is kept, the file list verify and uninstall read (GameNative's place for it). */
    private fun manifestFile(context: Context, productId: String) = File(context.filesDir, "manifests/amazon/$productId.proto")

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    internal companion object {
        private const val TAG = "AmazonStore"

        /**
         * Whether an entitlement's product is a game: Amazon marks DLC and
         * in-game items with a product line naming an entitlement
         * ("Twitch:FuelEntitlement"), games with "Twitch:FuelGame". A product
         * that names no line is taken for a game.
         */
        fun isGame(productJson: String): Boolean {
            val line = runCatching { org.json.JSONObject(productJson).optString("productLine", "") }.getOrDefault("")
            return !line.contains("Entitlement", ignoreCase = true)
        }

        /** What a verify found, in one line. */
        fun verifyLine(total: Int, missing: Int, changed: Int): String = when {
            missing == 0 && changed == 0 -> "All $total files match"
            else -> buildList {
                if (missing > 0) add("$missing missing")
                if (changed > 0) add("$changed changed")
            }.joinToString(", ", postfix = " of $total files. Install again to repair them")
        }    }
}
