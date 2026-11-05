package dev.droidtop.stores.indiegala

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
 * Indiegala as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335): the games of the account's
 * collection, installed as Humble's and itch.io's are ([SingleFileInstall]: one file, unpacked or kept). Signed in on
 * Indiegala's own page; its session is several cookies its client's code never names, so the whole cookie header is
 * what droidtop keeps ([StoreSignIn.WebPage.ALL_COOKIES]). A game Indiegala serves only through its own client is
 * listed and says so when installed.
 */
class IndiegalaStore : StoreLibrary {
    override val id = "indiegala"
    override val label = "Indiegala"
    override val siteUrl = "https://www.indiegala.com/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "https://www.indiegala.com/",
        hosts = listOf("indiegala.com"),
        searchPage = "https://www.indiegala.com/search/",
        accountLibrary = "https://www.indiegala.com/library",
    )

    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dir(context: Context) = File(context.filesDir, "indiegala")
    private fun authFile(context: Context) = File(dir(context), "session.json")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    private fun session(context: Context): String? =
        authFile(context).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).optString("cookies", "") }.getOrNull() }?.takeIf { it.isNotBlank() }

    override fun signedIn(context: Context): Boolean = authFile(context).isFile

    /** Indiegala's sign-in page; once it moves on to any other Indiegala page, the cookies it set are the sign-in. */
    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = "${IndiegalaApi.SITE}/login",
        isReturnPage = { address -> isReturnPage(address) },
        codeInUrl = { null },
        codeInCookie = StoreSignIn.WebPage.ALL_COOKIES,
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            // The cookies are kept only once they read the collection: a page reached before signing in is no sign-in.
            IndiegalaApi.collection(secret)
            AtomicWrite.text(authFile(context), JSONObject().put("cookies", secret).toString())
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@IndiegalaStore).onFailure { Timber.tag(TAG).w(it, "First Indiegala library read failed") }
        Result.success(null)
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            authFile(context).delete()
            write(context, read(context).filter { it.installPath.isNotBlank() })
        }
    }

    /** One row of the kept library: the product, and where and at which version it is installed. */
    internal data class Row(val product: IndiegalaApi.Product, val installPath: String = "", val installedVersion: String = "", val installed: Boolean = false)

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val session = session(context) ?: error("Not signed in to Indiegala")
            val products = IndiegalaApi.collection(session)
            val known = read(context).associateBy { it.product.key }
            val rows = products.map { product -> known[product.key]?.copy(product = product) ?: Row(product) }
            val kept = known.values.filter { it.installed && products.none { p -> p.key == it.product.key } }
            write(context, rows + kept)
            rows.size
        }.also { result -> if (result.exceptionOrNull() is IndiegalaApi.SignedOut) authFile(context).delete() }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        read(context).map { row ->
            StoreGame(
                store = id,
                gameId = row.product.key,
                title = row.product.title,
                installed = row.installed,
                installPath = row.installPath.takeIf { row.installed && it.isNotBlank() },
                sizeBytes = 0L,
                artUrl = row.product.coverUrl,
                installedVersion = row.installedVersion.takeIf { row.installed && it.isNotBlank() },
            )
        }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val session = withContext(Dispatchers.IO) { session(context) } ?: error("Sign in to Indiegala under Stores first")
        val row = withContext(Dispatchers.IO) { read(context).firstOrNull { it.product.key == gameId } } ?: error("Indiegala no longer lists this game")
        check(!row.product.clientOnly) { "Indiegala gives ${row.product.title} only through its own client" }
        progress.report(-1f, "Asking Indiegala for the files")
        val downloads = withContext(Dispatchers.IO) { IndiegalaApi.downloads(session, row.product) }
            ?: error("Indiegala did not give the downloads of ${row.product.title}")
        val url = downloads.windows ?: downloads.linux ?: error("Indiegala has no Windows or Linux download of ${row.product.title}")
        val installDir = File(row.installPath.ifBlank { StoreFiles.freshFolder(root, row.product.title, "indiegala-$gameId").absolutePath })
        update(context, gameId) { it.copy(installPath = installDir.absolutePath) }
        val info = newDownloadInfo()
        runJobDownload(info, progress) {
            SingleFileInstall.run(context, SingleFileInstall.areaName(id, gameId), url, IndiegalaApi.fileName(url), 0L, installDir, info)
        }.getOrElse { throw it }
        val version = downloads.version.ifBlank { row.product.version }
        update(context, gameId) { it.copy(installed = true, installPath = installDir.absolutePath, installedVersion = version) }
        return "Installed ${row.product.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val row = read(context).firstOrNull { it.product.key == gameId } ?: return@withContext
            DownloadJobs.discardForJob(context, SingleFileInstall.areaName(id, gameId))
            if (row.installed || row.installPath.isBlank()) return@withContext
            val folder = File(row.installPath)
            folder.parentFile?.let { SafeDelete.deleteWithin(it, folder) }
            update(context, gameId) { it.copy(installPath = "") }
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val row = read(context).firstOrNull { it.product.key == gameId } ?: return@withContext Result.failure(IllegalStateException("Indiegala no longer lists this game"))
        runCatchingCancellable {
            if (row.installPath.isNotBlank()) {
                val folder = File(row.installPath)
                val parent = folder.parentFile ?: error("${row.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, folder)) { "Could not remove ${row.installPath}" }
            }
            update(context, gameId) { it.copy(installed = false, installPath = "", installedVersion = "") }
        }
    }

    /** The version installed against the newest Indiegala's collection lists, by its word; no call. */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = withContext(Dispatchers.IO) {
        val row = read(context).firstOrNull { it.product.key == gameId }?.takeIf { it.installed } ?: return@withContext null
        updateState(row.installedVersion, row.product.version)?.let { StoreUpdateCheck(it, row.product.version) }
    }

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context)))

    @Synchronized
    private fun update(context: Context, gameId: String, change: (Row) -> Row) {
        write(context, read(context).map { if (it.product.key == gameId) change(it) else it })
    }

    private fun read(context: Context): List<Row> = libraryFile(context).takeIf { it.isFile }?.let { decode(it.readText()) }.orEmpty()

    private fun write(context: Context, rows: List<Row>) {
        AtomicWrite.text(libraryFile(context), encode(rows))
    }

    internal companion object {
        private const val TAG = "IndiegalaStore"

        /** Any Indiegala page that is not its sign-in or sign-out page: where the sign-in has gone on to. */
        fun isReturnPage(address: String): Boolean = runCatching {
            val parsed = Uri.parse(address)
            val path = parsed.path.orEmpty()
            parsed.host.equals("www.indiegala.com", ignoreCase = true) && !path.startsWith("/login") && !path.startsWith("/logout") &&
                !path.startsWith("/signup")
        }.getOrDefault(false)

        /** A different version from the one installed is an update; unknown when either is blank. Pure. */
        fun updateState(installed: String, listed: String): StoreUpdate? {
            if (installed.isBlank() || listed.isBlank()) return null
            return if (installed == listed) StoreUpdate.CURRENT else StoreUpdate.AVAILABLE
        }

        fun encode(rows: List<Row>): String = JSONArray(rows.map { row ->
            val p = row.product
            JSONObject().put("key", p.key).put("title", p.title).put("namespace", p.namespace).put("slug", p.slug)
                .put("cover", p.coverUrl ?: "").put("version", p.version).put("clientOnly", p.clientOnly)
                .put("installPath", row.installPath).put("installedVersion", row.installedVersion).put("installed", row.installed)
        }).toString()

        fun decode(text: String): List<Row> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Row(
                    product = IndiegalaApi.Product(
                        key = o.getString("key"),
                        title = o.optString("title", o.getString("key")),
                        namespace = o.optString("namespace", ""),
                        slug = o.optString("slug", ""),
                        coverUrl = o.optString("cover", "").ifBlank { null },
                        version = o.optString("version", ""),
                        clientOnly = o.optBoolean("clientOnly", false),
                    ),
                    installPath = o.optString("installPath", ""),
                    installedVersion = o.optString("installedVersion", ""),
                    installed = o.optBoolean("installed", false),
                )
            }
        }.getOrDefault(emptyList())
    }
}
