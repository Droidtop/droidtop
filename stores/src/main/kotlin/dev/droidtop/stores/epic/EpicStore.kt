package dev.droidtop.stores.epic

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
import dev.droidtop.library.stores.StoreUpdateCheck
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.stores.data.EpicGame
import dev.droidtop.stores.db.StoresDatabase
import dev.droidtop.stores.epic.manifest.EpicManifest
import dev.droidtop.stores.epic.manifest.ManifestUtils
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.StoreLanguage
import dev.droidtop.stores.util.newDownloadInfo
import dev.droidtop.stores.util.runJobDownload
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The Epic Games Store as a [StoreLibrary] (docs/SPEC.md 7g, "Stores"): what
 * GameNative's `EpicService` did (sign in on Epic's page, read the library,
 * download a build's chunks with its DLCs, uninstall), run by droidtop with
 * droidtop's own screens, plus what droidtop adds: the installed build is
 * recorded from its manifest, so a newer one can be found (Legendary's
 * check), the program and command line Epic's manifest names start the game,
 * and Epic's launch arguments go with them. GameNative ran it as an Android
 * Service that no droidtop manifest declared, so none of it ever ran here.
 *
 * A game's id is Epic's catalog id ("epic:<catalogId>"); the database's
 * integer row id stays internal.
 */
class EpicStore : StoreLibrary {
    override val id = "epic"
    override val label = PcStoreNames.EPIC
    override val signInKind = StoreSignInKind.WEB_PAGE
    override val canVerify = true

    private fun dao(context: Context) = StoresDatabase.get(context).epicGameDao()
    private fun manager(context: Context) = EpicManager(dao(context))

    override fun signedIn(context: Context): Boolean = EpicAuthManager.hasStoredCredentials(context)

    /**
     * Epic's sign-in page with a fresh state value. Epic returns to its
     * `id/api/redirect` page, which carries the code as the
     * `authorizationCode` field of the JSON it shows (GameNative's
     * EpicOAuthActivity). A return naming a different state is ignored.
     */
    override fun signIn(context: Context): StoreSignIn {
        val (url, state) = EpicConstants.LoginUrlWithState()
        return StoreSignIn.WebPage(
            url = url,
            isReturnPage = { address ->
                isReturnPage(address) &&
                    runCatching { Uri.parse(address).getQueryParameter("state") }.getOrNull().let { it == null || it == state }
            },
            codeInUrl = { address -> runCatching { Uri.parse(address).getQueryParameter("code") }.getOrNull() },
            codeInBody = "authorizationCode",
        )
    }

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> {
        val credentials = EpicAuthManager.authenticateWithCode(context, secret).getOrElse { return Result.failure(it) }
        sync(context).onFailure { Timber.tag(TAG).w(it, "First Epic library read failed") }
        return Result.success(credentials.displayName.takeIf { it.isNotBlank() })
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            EpicAuthManager.logout(context).getOrThrow()
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
                gameId = game.catalogId,
                title = game.title,
                installed = game.isInstalled,
                installPath = game.installPath.takeIf { game.isInstalled && it.isNotBlank() },
                sizeBytes = if (game.isInstalled) game.installSize else game.downloadSize,
                artUrl = game.iconUrl.takeIf { it.isNotBlank() },
                installedVersion = game.version.takeIf { game.isInstalled && it.isNotBlank() },
            )
        }
    }

    private suspend fun row(context: Context, catalogId: String): EpicGame? = withContext(Dispatchers.IO) { dao(context).getByCatalogId(catalogId) }

    /**
     * Downloads the live Windows build with every owned DLC, in the device's
     * language, with redistributables in `_CommonRedist`, as GameNative's
     * EpicService did. A resumed download hashes the files already there and
     * fetches only what is missing or wrong.
     */
    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val dao = dao(context)
        val manager = manager(context)
        val game = row(context, gameId) ?: error("Epic no longer lists this game")
        val installPath = game.installPath.ifBlank { StoreFiles.freshFolder(root, game.title, "epic-${game.id}").absolutePath }
        // Recorded before the download, so a Cancel knows which folder is the unfinished one.
        val recorded = game.copy(installPath = installPath)
        if (game.installPath != installPath) withContext(Dispatchers.IO) { dao.update(recorded) }
        val dlcIds = manager.getDLCForTitle(game.id).map { it.id }
        val info = newDownloadInfo().apply { setPersistencePath(installPath) }
        runJobDownload(info, progress) {
            EpicDownloadManager(manager, context.applicationContext).downloadGame(
                context = context.applicationContext,
                game = recorded,
                installPath = installPath,
                downloadInfo = info,
                containerLanguage = StoreLanguage.current(),
                dlcIds = dlcIds,
                commonRedistDir = File(installPath, "_CommonRedist"),
            )
        }.getOrElse { throw it }
        return "Installed ${game.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val dao = dao(context)
            val game = dao.getByCatalogId(gameId) ?: return@withContext
            if (game.installPath.isBlank()) return@withContext
            dropChunkCache(context, game.installPath)
            if (game.isInstalled) return@withContext
            val dir = File(game.installPath)
            dir.parentFile?.let { SafeDelete.deleteWithin(it, dir) }
            dao.update(game.copy(installPath = ""))
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val game = dao.getByCatalogId(gameId) ?: return@withContext Result.failure(IllegalStateException("Epic no longer lists this game"))
        runCatching {
            if (game.installPath.isNotBlank()) {
                val dir = File(game.installPath)
                val parent = dir.parentFile ?: error("${game.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, dir)) { "Could not remove ${game.installPath}" }
                dropChunkCache(context, game.installPath)
            }
            dao.update(game.copy(isInstalled = false, installPath = "", installSize = 0, launchExe = "", launchCommand = ""))
        }.onFailure { Timber.tag(TAG).e(it, "Failed to uninstall Epic game $gameId") }
    }

    /** Checks each file of the live build's manifest, for this device's language, by size and SHA-1. */
    override suspend fun verify(context: Context, gameId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val game = row(context, gameId) ?: error("Epic no longer lists this game")
            check(game.isInstalled && game.installPath.isNotBlank()) { "${game.title} is not installed" }
            val manager = manager(context)
            val data = manager.fetchManifestFromEpic(context, game.namespace, game.catalogId, game.appName).getOrThrow()
            val manifest = EpicManifest.readAll(data.manifestBytes)
            val files = ManifestUtils.getFilesForSelectedInstallTags(
                manifest,
                EpicConstants.containerLanguageToEpicInstallTags(StoreLanguage.current()),
            )
            val checker = EpicDownloadManager(manager, context.applicationContext)
            val installDir = File(game.installPath)
            val bad = files.count { file -> !checker.fileExistsWithCorrectHash(File(installDir, file.filename), file.fileSize, file.hash) }
            if (bad == 0) "All ${files.size} files match" else "$bad of ${files.size} files are missing or changed. Update or install again to repair them"
        }
    }

    /** The installed build against the one Epic serves now (Legendary's check); unknown for an install with no recorded build. */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? {
        val game = row(context, gameId) ?: return null
        if (!game.isInstalled || game.version.isBlank()) return null
        val live = manager(context).fetchLiveBuildVersion(context, game.namespace, game.catalogId, game.appName) ?: return null
        return StoreUpdateCheck(if (live == game.version) StoreUpdate.CURRENT else StoreUpdate.AVAILABLE, live)
    }

    /**
     * The program the installed build's manifest names, its command line, and
     * Epic's launch arguments (an exchange code, the account and, for a game
     * that needs one, an ownership token). Null when the install predates the
     * recorded program, so droidtop finds the program in the folder. Signed
     * out or offline, the game starts without Epic's arguments.
     */
    override suspend fun launch(context: Context, gameId: String): StoreLaunch? {
        val game = row(context, gameId) ?: return null
        if (!game.isInstalled || game.installPath.isBlank() || game.launchExe.isBlank()) return null
        val installDir = File(game.installPath)
        val exe = withContext(Dispatchers.IO) { StoreFiles.findCaseInsensitive(installDir, game.launchExe) } ?: return null
        val epicArguments = EpicGameLauncher.buildLaunchParameters(context, manager(context), game, Locale.getDefault().toLanguageTag())
            .onFailure { Timber.tag(TAG).w(it, "Starting ${game.title} without Epic's launch arguments") }
            .getOrDefault(emptyList())
        return StoreLaunch(
            executable = exe,
            workingDir = exe.parentFile ?: installDir,
            arguments = EpicGameLauncher.tokenizeArgs(game.launchCommand) + epicArguments,
        )
    }

    override fun changeStamp(context: Context): Long =
        StoreFiles.stamp(StoresDatabase.files(context) + File(File(context.filesDir, "epic"), "credentials.json"))

    /** The chunks a download keeps for resuming, in the app's own cache. */
    private fun dropChunkCache(context: Context, installPath: String) {
        val chunks = EpicDownloadManager.chunkCacheDirFor(context, installPath)
        chunks.parentFile?.let { SafeDelete.deleteWithin(it, chunks) }
    }

    internal companion object {
        private const val TAG = "EpicStore"

        /** Whether [address] is Epic's sign-in return page ([EpicConstants.EPIC_REDIRECT_URI]): same scheme, host and path. */
        fun isReturnPage(address: String): Boolean = runCatching {
            val parsed = Uri.parse(address)
            val expected = Uri.parse(EpicConstants.EPIC_REDIRECT_URI)
            parsed.scheme.equals(expected.scheme, ignoreCase = true) &&
                parsed.host.equals(expected.host, ignoreCase = true) &&
                parsed.path == expected.path
        }.getOrDefault(false)
    }
}
