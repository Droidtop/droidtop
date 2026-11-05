package dev.droidtop.stores.humble

import android.content.Context
import android.net.Uri
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
 * Humble Bundle as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335): the DRM-free games of the
 * account's orders, installed the way itch.io's are ([SingleFileInstall]: one file, unpacked or kept). Signed in on
 * Humble's own page; its session cookie is all droidtop keeps. Steam keys in the orders are not rows: they are
 * Steam's once redeemed. Download links are signed and short-lived, so the kept library holds them without their
 * signature and an install reads the order again for a fresh one.
 */
class HumbleStore : StoreLibrary {
    override val id = "humble"
    override val label = "Humble"
    override val siteUrl = "https://www.humblebundle.com/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "https://www.humblebundle.com/",
        hosts = listOf("humblebundle.com"),
        searchPage = "https://www.humblebundle.com/store/search?search=",
        accountLibrary = "https://www.humblebundle.com/home/library",
    )

    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dir(context: Context) = File(context.filesDir, "humble")
    private fun authFile(context: Context) = File(dir(context), "session.json")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    private fun session(context: Context): String? =
        authFile(context).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).optString("session", "") }.getOrNull() }?.takeIf { it.isNotBlank() }

    override fun signedIn(context: Context): Boolean = authFile(context).isFile

    /** Humble's sign-in page, returning to the library; the session cookie it leaves is the sign-in. */
    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = "${HumbleApi.SITE}/login?goto=%2Fhome%2Flibrary",
        isReturnPage = { address -> isReturnPage(address) },
        codeInUrl = { null },
        codeInCookie = HumbleApi.SESSION_COOKIE,
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            // The session is checked before it is kept: a cookie that does not read the orders is no sign-in.
            HumbleApi.orderKeys(secret)
            AtomicWrite.text(authFile(context), JSONObject().put("session", secret).toString())
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@HumbleStore).onFailure { Timber.tag(TAG).w(it, "First Humble library read failed") }
        Result.success(null)
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            authFile(context).delete()
            // Installed games stay listed: their folders are the person's.
            write(context, read(context).filter { it.installPath.isNotBlank() })
        }
    }

    /** One row of the kept library: the product, and where and from which file it is installed. */
    internal data class Row(val product: HumbleApi.Product, val installPath: String = "", val installedMd5: String = "", val installed: Boolean = false)

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val session = session(context) ?: error("Not signed in to Humble")
            val answers = HumbleApi.orders(session, HumbleApi.orderKeys(session))
            val products = answers.flatMap(HumbleApi::parseProducts).distinctBy { it.id }
            val known = read(context).associateBy { it.product.id }
            val rows = products.map { product -> known[product.id]?.copy(product = product) ?: Row(product) }
            // A game installed here that the account no longer lists stays, as with every store.
            val kept = known.values.filter { it.installed && products.none { p -> p.id == it.product.id } }
            write(context, rows + kept)
            rows.size
        }.also { result -> result.exceptionOrNull()?.let { if (it is HumbleApi.SignedOut) authFile(context).delete() } }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        read(context).map { row ->
            StoreGame(
                store = id,
                gameId = row.product.id,
                title = row.product.title,
                installed = row.installed,
                installPath = row.installPath.takeIf { row.installed && it.isNotBlank() },
                sizeBytes = HumbleApi.choose(row.product.downloads)?.sizeBytes ?: 0L,
                artUrl = row.product.iconUrl,
            )
        }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val session = withContext(Dispatchers.IO) { session(context) } ?: error("Sign in to Humble under Stores first")
        val row = withContext(Dispatchers.IO) { read(context).firstOrNull { it.product.id == gameId } } ?: error("Humble no longer lists this game")
        progress.report(-1f, "Asking Humble for the files")
        // A download link is signed and short-lived: the order is read again for a fresh one.
        val fresh = withContext(Dispatchers.IO) { HumbleApi.parseProducts(HumbleApi.order(session, row.product.gameKey)) }
            .firstOrNull { it.id == gameId } ?: row.product
        val download = HumbleApi.choose(fresh.downloads) ?: error("Humble has no Windows or Linux build of ${row.product.title}")
        val installDir = File(row.installPath.ifBlank { StoreFiles.freshFolder(root, row.product.title, "humble-$gameId").absolutePath })
        // Recorded before the download, so a Cancel knows which folder is the unfinished one.
        update(context, gameId) { it.copy(product = fresh, installPath = installDir.absolutePath) }
        val info = newDownloadInfo()
        runJobDownload(info, progress) {
            SingleFileInstall.run(context, SingleFileInstall.areaName(id, gameId), download.url, HumbleApi.fileName(download.url), download.sizeBytes, installDir, info, md5 = download.md5)
        }.getOrElse { throw it }
        update(context, gameId) { it.copy(installed = true, installPath = installDir.absolutePath, installedMd5 = download.md5) }
        return "Installed ${row.product.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val row = read(context).firstOrNull { it.product.id == gameId } ?: return@withContext
            DownloadJobs.discardForJob(context, SingleFileInstall.areaName(id, gameId))
            if (row.installed || row.installPath.isBlank()) return@withContext
            val folder = File(row.installPath)
            folder.parentFile?.let { SafeDelete.deleteWithin(it, folder) }
            update(context, gameId) { it.copy(installPath = "") }
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val row = read(context).firstOrNull { it.product.id == gameId } ?: return@withContext Result.failure(IllegalStateException("Humble no longer lists this game"))
        runCatchingCancellable {
            if (row.installPath.isNotBlank()) {
                val folder = File(row.installPath)
                val parent = folder.parentFile ?: error("${row.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, folder)) { "Could not remove ${row.installPath}" }
            }
            update(context, gameId) { it.copy(installed = false, installPath = "", installedMd5 = "") }
        }
    }

    /** The file the install came from against the one Humble lists now, by its MD5 (Humble names no versions). */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = withContext(Dispatchers.IO) {
        val row = read(context).firstOrNull { it.product.id == gameId }?.takeIf { it.installed } ?: return@withContext null
        updateState(row.installedMd5, HumbleApi.choose(row.product.downloads)?.md5)?.let { StoreUpdateCheck(it) }
    }

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context)))

    @Synchronized
    private fun update(context: Context, gameId: String, change: (Row) -> Row) {
        write(context, read(context).map { if (it.product.id == gameId) change(it) else it })
    }

    private fun read(context: Context): List<Row> =
        libraryFile(context).takeIf { it.isFile }?.let { decode(it.readText()) }.orEmpty()

    private fun write(context: Context, rows: List<Row>) {
        AtomicWrite.text(libraryFile(context), encode(rows))
    }

    internal companion object {
        private const val TAG = "HumbleStore"

        /** Whether [address] is Humble's library page, where its sign-in returns. */
        fun isReturnPage(address: String): Boolean = runCatching {
            val parsed = Uri.parse(address)
            parsed.host.equals("www.humblebundle.com", ignoreCase = true) && parsed.path.orEmpty().startsWith("/home/library")
        }.getOrDefault(false)

        /** A different MD5 from the one installed is a newer file; unknown when either is missing. Pure. */
        fun updateState(installedMd5: String, liveMd5: String?): StoreUpdate? {
            if (installedMd5.isBlank() || liveMd5.isNullOrBlank()) return null
            return if (installedMd5.equals(liveMd5, ignoreCase = true)) StoreUpdate.CURRENT else StoreUpdate.AVAILABLE
        }

        fun encode(rows: List<Row>): String = JSONArray(rows.map { row ->
            JSONObject()
                .put("id", row.product.id)
                .put("title", row.product.title)
                .put("icon", row.product.iconUrl ?: "")
                .put("gameKey", row.product.gameKey)
                .put("installPath", row.installPath)
                .put("installedMd5", row.installedMd5)
                .put("installed", row.installed)
                .put("downloads", JSONArray(row.product.downloads.map { d ->
                    // The link without its signature: an install asks for a fresh one.
                    JSONObject().put("platform", d.platform).put("name", d.name).put("url", d.url.substringBefore('?')).put("size", d.sizeBytes).put("md5", d.md5)
                }))
        }).toString()

        fun decode(text: String): List<Row> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val downloads = o.optJSONArray("downloads") ?: JSONArray()
                Row(
                    product = HumbleApi.Product(
                        id = o.getString("id"),
                        title = o.optString("title", o.getString("id")),
                        iconUrl = o.optString("icon", "").ifBlank { null },
                        gameKey = o.optString("gameKey", ""),
                        downloads = (0 until downloads.length()).map { j ->
                            val d = downloads.getJSONObject(j)
                            HumbleApi.Download(d.optString("platform"), d.optString("name"), d.optString("url"), d.optLong("size"), d.optString("md5"))
                        },
                    ),
                    installPath = o.optString("installPath", ""),
                    installedMd5 = o.optString("installedMd5", ""),
                    installed = o.optBoolean("installed", false),
                )
            }
        }.getOrDefault(emptyList())
    }
}
