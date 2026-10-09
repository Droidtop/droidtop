package dev.droidtop.stores.gog

import android.content.Context
import android.net.Uri
import dev.droidtop.library.PcStoreNames
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLaunch
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreSyncs
import dev.droidtop.library.stores.StoreUpdateCheck
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.stores.db.StoresDatabase
import dev.droidtop.stores.gog.api.GOGApiClient
import dev.droidtop.stores.gog.api.GOGManifestParser
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.StoreLanguage
import dev.droidtop.stores.util.newDownloadInfo
import dev.droidtop.stores.util.runJobDownload
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * GOG as a [StoreLibrary] (docs/SPEC.md 7g, "Stores"): what GameNative's
 * `GOGService` did (sign in on GOG's page, read the library, download a
 * build's depots with their redistributables, uninstall), run by droidtop
 * with droidtop's own screens. GameNative ran it as an Android Service that
 * no droidtop manifest declared, so none of it ever ran here.
 *
 * A game starts the way GOG's own client starts it: the primary play task of
 * the game's `goggame-<id>.info` file ([GOGManager.primaryPlayTask]).
 */
class GOGStore : StoreLibrary {
    override val id = "gog"
    override val label = PcStoreNames.GOG
    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dao(context: Context) = StoresDatabase.get(context).gogGameDao()
    private fun manager(context: Context) = GOGManager(dao(context))

    override fun signedIn(context: Context): Boolean = GOGAuthManager.hasStoredCredentials(context)

    /**
     * GOG's Galaxy sign-in page with a fresh state value; GOG returns to its
     * `on_login_success` page with the code and the same state on the
     * address (GameNative's GOGOAuthActivity; a different state is ignored).
     */
    override fun signIn(context: Context): StoreSignIn {
        val (url, state) = GOGConstants.LoginUrlWithState()
        return StoreSignIn.WebPage(
            url = url,
            isReturnPage = { address -> isReturnPage(address) && runCatching { Uri.parse(address).getQueryParameter("state") }.getOrNull() == state },
            codeInUrl = { address -> runCatching { Uri.parse(address).getQueryParameter("code") }.getOrNull() },
        )
    }

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> {
        val credentials = GOGAuthManager.authenticateWithCode(context, secret).getOrElse { return Result.failure(it) }
        StoreSyncs.run(context, this).onFailure { Timber.tag(TAG).w(it, "First GOG library read failed") }
        return Result.success(credentials.username.takeIf { it.isNotBlank() })
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(GOGAuthManager.clearStoredCredentials(context)) { "GOG would not clear its sign-in" }
            dao(context).deleteAllNonInstalledGames()
        }
    }

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        manager(context).refreshLibrary(context).map { dao(context).getAllAsList().size }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        dao(context).getAllAsList().map { game ->
            StoreGame(
                store = id,
                gameId = game.id,
                title = game.title,
                installed = game.isInstalled,
                installPath = game.installPath.takeIf { game.isInstalled && it.isNotBlank() },
                sizeBytes = if (game.isInstalled) game.installSize else game.downloadSize,
                artUrl = game.verticalCoverUrl.ifEmpty { game.imageUrl }.ifEmpty { game.iconUrl }.takeIf { it.isNotBlank() },
                installedVersion = game.installedVersionName.takeIf { game.isInstalled && it.isNotBlank() },
            )
        }
    }

    /**
     * Downloads the newest Windows build (Gen 2, else Gen 1) with its DLCs in
     * the device's language, its redistributables into `_CommonRedist`, the
     * way GameNative's GOGService did. A stopped or failed download keeps its
     * chunk cache, so the next run continues from it.
     */
    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val dao = dao(context)
        val game = withContext(Dispatchers.IO) { dao.getById(gameId) } ?: error("GOG no longer lists this game")
        val installDir = File(game.installPath.ifBlank { StoreFiles.freshFolder(root, game.title, "gog-$gameId").absolutePath })
        // Recorded before the download, so a Cancel knows which folder is the unfinished one.
        if (game.installPath != installDir.absolutePath) withContext(Dispatchers.IO) { dao.update(game.copy(installPath = installDir.absolutePath)) }
        val parser = GOGManifestParser()
        val downloads = GOGDownloadManager(GOGApiClient(context.applicationContext, parser), parser, manager(context), context.applicationContext)
        val info = newDownloadInfo().apply { setPersistencePath(installDir.absolutePath) }
        runJobDownload(info, progress) {
            downloads.downloadGame(
                gameId = gameId,
                installPath = installDir,
                downloadInfo = info,
                language = StoreLanguage.current(),
                withDlcs = true,
                supportDir = File(installDir, "_CommonRedist"),
            )
        }.getOrElse { throw it }
        return "Installed ${game.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val dao = dao(context)
            val game = dao.getById(gameId) ?: return@withContext
            dropChunkCache(context, gameId)
            if (game.isInstalled || game.installPath.isBlank()) return@withContext
            val dir = File(game.installPath)
            dir.parentFile?.let { SafeDelete.deleteWithin(it, dir) }
            dao.update(game.copy(installPath = ""))
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val game = dao.getById(gameId) ?: return@withContext Result.failure(IllegalStateException("GOG no longer lists this game"))
        runCatching {
            if (game.installPath.isNotBlank()) {
                val dir = File(game.installPath)
                val parent = dir.parentFile ?: error("${game.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, dir)) { "Could not remove ${game.installPath}" }
            }
            dropChunkCache(context, gameId)
            dao.update(game.copy(isInstalled = false, installPath = "", installSize = 0, installedBuildId = "", installedVersionName = ""))
        }.onFailure { Timber.tag(TAG).e(it, "Failed to uninstall GOG game $gameId") }
    }

    /**
     * The build the install was made from against the newest Windows build
     * GOG lists now, by GOG's build id (the way its own client decides). An
     * install made before the build was recorded cannot be judged, so it
     * stays unknown until it is next installed or updated.
     */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = withContext(Dispatchers.IO) {
        val game = dao(context).getById(gameId) ?: return@withContext null
        if (!game.isInstalled || game.installedBuildId.isBlank()) return@withContext null
        val client = GOGApiClient(context.applicationContext, GOGManifestParser())
        val live = client.latestWindowsBuild(gameId).getOrNull() ?: return@withContext null
        StoreUpdateCheck(updateState(game.installedBuildId, live.buildId), live.versionName.takeIf { it.isNotBlank() })
    }

    override suspend fun launch(context: Context, gameId: String): StoreLaunch? =
        manager(context).primaryPlayTask(gameId)?.let { StoreLaunch(it.executable, it.workingDir, it.arguments) }

    override fun changeStamp(context: Context): Long =
        StoreFiles.stamp(StoresDatabase.files(context) + File(GOGAuthManager.getAuthConfigPath(context)))

    /** The chunks a download keeps for resuming, in the app's own cache. */
    private fun dropChunkCache(context: Context, gameId: String) {
        val chunks = File(context.cacheDir, "gog_chunks/$gameId")
        chunks.parentFile?.let { SafeDelete.deleteWithin(it, chunks) }
    }

    internal companion object {
        private const val TAG = "GOGStore"

        /** A different build id from the one the install was made from is a newer build (GOG's own client decides the same way). */
        fun updateState(installedBuildId: String, liveBuildId: String): StoreUpdate =
            if (liveBuildId == installedBuildId) StoreUpdate.CURRENT else StoreUpdate.AVAILABLE

        /** Whether [address] is GOG's sign-in return page ([GOGConstants.GOG_REDIRECT_URI]): same scheme, host and path. */
        fun isReturnPage(address: String): Boolean = runCatching {
            val parsed = Uri.parse(address)
            val expected = Uri.parse(GOGConstants.GOG_REDIRECT_URI)
            parsed.scheme.equals(expected.scheme, ignoreCase = true) &&
                parsed.host.equals(expected.host, ignoreCase = true) &&
                parsed.path == expected.path
        }.getOrDefault(false)
    }
}
