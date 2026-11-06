package dev.droidtop.stores.zoom

import android.content.Context
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreSyncs
import dev.droidtop.library.stores.StoreUpdateCheck
import dev.droidtop.net.AtomicWrite
import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.util.SingleFileInstall
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.newDownloadInfo
import dev.droidtop.stores.util.runJobDownload
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * ZOOM Platform as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335): the account's DRM-free games,
 * installed the way Humble's are ([SingleFileInstall], once per installer file: ZOOM may split one installer into
 * several). Signed in on ZOOM's own page, which returns with a launcher token; the token is all droidtop keeps.
 * Download links are fresh for each install. ZOOM names no versions, so an update is a change in the installer files'
 * names or sizes ([ZoomApi.signature]).
 */
class ZoomStore : StoreLibrary {
    override val id = "zoom"
    override val label = "ZOOM Platform"
    override val siteUrl = "${ZoomApi.SITE}/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "${ZoomApi.SITE}/",
        hosts = listOf("zoom-platform.com"),
        searchPage = "${ZoomApi.SITE}/search?q=",
    )

    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dir(context: Context) = File(context.filesDir, "zoom")
    private fun authFile(context: Context) = File(dir(context), "session.json")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    private fun token(context: Context): String? =
        authFile(context).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).optString("token", "") }.getOrNull() }?.takeIf { it.isNotBlank() }

    override fun signedIn(context: Context): Boolean = authFile(context).isFile

    /** ZOOM's sign-in page; the page it returns to carries the launcher token in its address. */
    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = ZoomApi.signInUrl,
        isReturnPage = { address -> ZoomApi.tokenIn(address) != null },
        codeInUrl = { address -> ZoomApi.tokenIn(address) },
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        val name = runCatchingCancellable {
            // The token is checked before it is kept: one ZOOM does not answer to is no sign-in.
            ZoomApi.accountName(secret).also { AtomicWrite.text(authFile(context), JSONObject().put("token", secret).toString()) }
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@ZoomStore).onFailure { Timber.tag(TAG).w(it, "First ZOOM Platform library read failed") }
        Result.success(name)
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            authFile(context).delete()
            // Installed games stay listed: their folders are the person's.
            write(context, read(context).filter { it.installPath.isNotBlank() })
        }
    }

    /** One row of the kept library: the game, and where and from which files it is installed. */
    internal data class Row(
        val game: ZoomApi.Game,
        val installPath: String = "",
        val installedFrom: String = "",
        val installed: Boolean = false,
        /** How many installer files the last install fetched, so a Cancel finds every partial one. */
        val fileCount: Int = 0,
        /** What ZOOM lists now ([ZoomApi.signature]), read at install time and by an update check. */
        val listed: String = "",
    )

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val token = token(context) ?: error("Not signed in to ZOOM Platform")
            val games = ZoomApi.games(token)
            val known = read(context).associateBy { it.game.id }
            val rows = games.map { game -> known[game.id]?.copy(game = game) ?: Row(game) }
            // A game installed here that the account no longer lists stays, as with every store.
            val kept = known.values.filter { it.installed && games.none { g -> g.id == it.game.id } }
            write(context, rows + kept)
            rows.size
        }.also { result -> result.exceptionOrNull()?.let { if (it is ZoomApi.SignedOut) authFile(context).delete() } }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        read(context).map { row ->
            StoreGame(
                store = id,
                gameId = row.game.id,
                title = row.game.title,
                installed = row.installed,
                installPath = row.installPath.takeIf { row.installed && it.isNotBlank() },
                sizeBytes = 0L,
                artUrl = row.game.posterUrl,
            )
        }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val token = withContext(Dispatchers.IO) { token(context) } ?: error("Sign in to ZOOM Platform under Stores first")
        val row = withContext(Dispatchers.IO) { read(context).firstOrNull { it.game.id == gameId } } ?: error("ZOOM Platform no longer lists this game")
        progress.report(-1f, "Asking ZOOM Platform for the files")
        val files = withContext(Dispatchers.IO) { ZoomApi.installers(token, gameId) }
        if (files.isEmpty()) error("ZOOM Platform has no Windows or Linux build of ${row.game.title}")
        val installDir = File(row.installPath.ifBlank { StoreFiles.freshFolder(root, row.game.title, "zoom-$gameId").absolutePath })
        // Recorded before the download, so a Cancel knows which folder and files are the unfinished ones.
        update(context, gameId) { it.copy(installPath = installDir.absolutePath, fileCount = files.size, listed = ZoomApi.signature(files)) }
        files.forEachIndexed { index, file ->
            val url = withContext(Dispatchers.IO) { ZoomApi.downloadUrl(token, file) }
            val info = newDownloadInfo()
            if (files.size > 1) info.updateStatusMessage("File ${index + 1} of ${files.size}: ${file.fileName}")
            runJobDownload(info, progress) {
                SingleFileInstall.run(context, areaName(gameId, index), url, file.fileName, file.sizeBytes, installDir, info)
            }.getOrElse { throw it }
        }
        update(context, gameId) { it.copy(installed = true, installPath = installDir.absolutePath, installedFrom = ZoomApi.signature(files)) }
        return "Installed ${row.game.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val row = read(context).firstOrNull { it.game.id == gameId } ?: return@withContext
            (0 until row.fileCount.coerceAtLeast(1)).forEach { DownloadJobs.discardForJob(context, areaName(gameId, it)) }
            if (row.installed || row.installPath.isBlank()) return@withContext
            val folder = File(row.installPath)
            folder.parentFile?.let { SafeDelete.deleteWithin(it, folder) }
            update(context, gameId) { it.copy(installPath = "") }
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val row = read(context).firstOrNull { it.game.id == gameId } ?: return@withContext Result.failure(IllegalStateException("ZOOM Platform no longer lists this game"))
        runCatchingCancellable {
            if (row.installPath.isNotBlank()) {
                val folder = File(row.installPath)
                val parent = folder.parentFile ?: error("${row.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, folder)) { "Could not remove ${row.installPath}" }
            }
            update(context, gameId) { it.copy(installed = false, installPath = "", installedFrom = "") }
        }
    }

    /** The installer files the game came from against the ones ZOOM lists now. */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = withContext(Dispatchers.IO) {
        val row = read(context).firstOrNull { it.game.id == gameId }?.takeIf { it.installed } ?: return@withContext null
        val token = token(context) ?: return@withContext null
        val listed = runCatching { ZoomApi.signature(ZoomApi.installers(token, gameId)) }.getOrNull() ?: return@withContext null
        update(context, gameId) { it.copy(listed = listed) }
        updateState(row.installedFrom, listed)?.let { StoreUpdateCheck(it) }
    }

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context)))

    @Synchronized
    private fun update(context: Context, gameId: String, change: (Row) -> Row) {
        write(context, read(context).map { if (it.game.id == gameId) change(it) else it })
    }

    private fun read(context: Context): List<Row> =
        libraryFile(context).takeIf { it.isFile }?.let { decode(it.readText()) }.orEmpty()

    private fun write(context: Context, rows: List<Row>) {
        AtomicWrite.text(libraryFile(context), encode(rows))
    }

    private fun areaName(gameId: String, index: Int): String = SingleFileInstall.areaName(id, "${gameId}_$index")

    internal companion object {
        private const val TAG = "ZoomStore"

        /** Other installer files from the ones installed are a newer build; unknown when either is missing. Pure. */
        fun updateState(installedFrom: String, listed: String?): StoreUpdate? {
            if (installedFrom.isBlank() || listed.isNullOrBlank()) return null
            return if (installedFrom == listed) StoreUpdate.CURRENT else StoreUpdate.AVAILABLE
        }

        fun encode(rows: List<Row>): String = JSONArray(rows.map { row ->
            JSONObject()
                .put("id", row.game.id)
                .put("title", row.game.title)
                .put("poster", row.game.posterUrl ?: "")
                .put("store", row.game.storeUrl ?: "")
                .put("systems", JSONArray(row.game.systems))
                .put("installPath", row.installPath)
                .put("installedFrom", row.installedFrom)
                .put("installed", row.installed)
                .put("fileCount", row.fileCount)
                .put("listed", row.listed)
        }).toString()

        fun decode(text: String): List<Row> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val systems = o.optJSONArray("systems")
                Row(
                    game = ZoomApi.Game(
                        id = o.getString("id"),
                        title = o.optString("title", o.getString("id")),
                        posterUrl = o.optString("poster", "").ifBlank { null },
                        storeUrl = o.optString("store", "").ifBlank { null },
                        systems = (0 until (systems?.length() ?: 0)).map { systems!!.optString(it) },
                    ),
                    installPath = o.optString("installPath", ""),
                    installedFrom = o.optString("installedFrom", ""),
                    installed = o.optBoolean("installed", false),
                    fileCount = o.optInt("fileCount", 0),
                    listed = o.optString("listed", ""),
                )
            }
        }.getOrDefault(emptyList())
    }
}
