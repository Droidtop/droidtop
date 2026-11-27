package dev.droidtop.stores.ea

import android.content.Context
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreSyncs
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.library.stores.StoreInstallJob
import dev.droidtop.library.stores.StoreLaunch
import dev.droidtop.net.AtomicWrite
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.StoreHttp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The EA app's library as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", mytop-project/tracker#335), after Lutris's EA app
 * service (GPL-3.0, `lutris/services/ea_app.py`): the person signs in on EA's own page using DroidDeck's EA desktop
 * client flow (PKCE, one-time state and refresh tokens), and EA's GraphQL service lists the account's PC base games (`ownedGameProducts`) and their titles and art
 * (`gameProducts`, `legacyOffers` for each one's content id). Direct installs use DroidDeck's unchanged native
 * download engine; launcher metadata comes from the build's installer manifest.
 */
class EAStore : StoreLibrary {
    private val installLocks = ConcurrentHashMap<String, Mutex>()
    private fun installLock(id: String) = installLocks.getOrPut(id) { Mutex() }
    override val id = "ea"
    override val label = "EA app"
    override val siteUrl = "https://www.ea.com/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "https://www.ea.com/games/library",
        hosts = listOf("ea.com"),
        searchPage = "https://www.ea.com/search?q=",
        accountLibrary = "https://www.ea.com/games/library",
    )

    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dir(context: Context) = File(context.filesDir, "ea")
    private fun authFile(context: Context) = EaAuth.authFile(context)
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    @Volatile private var pending: EaAuth.Pending? = null

    override fun signedIn(context: Context): Boolean = EaAuth.signedIn(context)
    override fun accountName(context: Context): String? = EaAuth.load(context)?.displayName?.takeIf { it.isNotBlank() }

    /** The qrc callback is captured by StoreSignInActivity before WebView tries to load it. */
    override fun signIn(context: Context): StoreSignIn {
        val attempt = EaAuth.begin(context)
        pending = attempt
        return StoreSignIn.WebPage(
            url = attempt.url,
            isReturnPage = ::isReturnPage,
            codeInUrl = { address -> callbackSecret(address, attempt.state) },
        )
    }

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val attempt = pending ?: error("Start the EA sign-in again")
            val reply = JSONObject(secret)
            check(reply.optString("state") == attempt.state) { "This answer is not from the EA sign-in you started" }
            val code = reply.getString("code").takeIf { it.isNotBlank() } ?: error("EA did not return a sign-in code")
            pending = null
            EaAuth.exchange(context, code, attempt.verifier)
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@EAStore).onFailure { Timber.tag(TAG).w(it, "First EA library read failed") }
        Result.success(null)
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            pending = null
            EaAuth.signOut(context)
            libraryFile(context).delete()
            Unit
        }
    }

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            var token = EaAuth.accessToken(context) ?: error("Sign in to the EA app again")
            val offers = ArrayList<String>()
            var next: String? = null
            do {
                val variables = JSONObject().put("limit", 100).apply { next?.let { put("next", it) } }
                val body = try { graphql(token, ENTITLEMENTS, variables) } catch (_: SignedOut) {
                    token = EaAuth.refreshNow(context, token) ?: error("Sign in to the EA app again")
                    graphql(token, ENTITLEMENTS, variables)
                }
                val page = entitlementsOf(body)
                offers += page.first
                next = page.second
            } while (next != null && offers.size < 10_000)
            val games = offers.distinct().chunked(100).flatMap { chunk -> productsOf(graphql(token, OFFERS, JSONObject().put("offerIds", JSONArray(chunk)))) }
                .distinctBy { it.id }
            AtomicWrite.text(libraryFile(context), encode(games))
            games.size
        }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        val owned = readLibrary(context).associateBy { it.id }
        val installs = EaInstalls.read(context).associateBy { it.id }
        (owned.keys + installs.keys).map { gameId ->
            val game = owned[gameId]
            val install = installs[gameId]
            val folder = install?.takeIf { it.complete }?.let { runCatching { EaInstalls.ownedFolder(it) }.getOrNull() }?.takeIf { it.isDirectory }
            StoreGame(store = id, gameId = gameId, title = game?.title ?: install!!.title,
                installed = folder != null, installPath = folder?.absolutePath, sizeBytes = install?.sizeBytes ?: 0,
                artUrl = game?.artUrl ?: install?.art, installedVersion = install?.version?.takeIf { it.isNotBlank() },
                holding = if (game != null) StoreHolding.OWNED else StoreHolding.NOT_OWNED)
        }
    }

    private fun readLibrary(context: Context): List<Game> = libraryFile(context).takeIf { it.isFile }?.let { decode(it.readText()) }.orEmpty()

    override suspend fun installedPath(context: Context, gameId: String): String? = withContext(Dispatchers.IO) {
        EaInstalls.read(context).firstOrNull { it.id == gameId && it.complete }
            ?.let { runCatching { EaInstalls.ownedFolder(it).absolutePath }.getOrNull() }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String = withContext(Dispatchers.IO) {
        installLock(gameId).withLock {
        val game = readLibrary(context).firstOrNull { it.id == gameId } ?: error("EA no longer lists this game. Refresh its library first.")
        check(game.offerId.isNotBlank()) { "Refresh the EA library once before installing this game" }
        var token = EaAuth.accessToken(context) ?: error("Sign in to EA before installing this game")
        progress.report(-1f, "Asking EA for the game files")
        val url = try { EaApi.downloadUrl(token, game.offerId) } catch (e: EaApiException) {
            if (e.status != 401) throw e
            token = EaAuth.refreshNow(context, token) ?: error("Sign in to EA again")
            EaApi.downloadUrl(token, game.offerId)
        }
        val offer = EaApi.offers(token, listOf(game.offerId)).firstOrNull()
        val existing = EaInstalls.read(context).firstOrNull { it.id == gameId }
        val row = if (existing != null) {
            EaInstalls.ownedFolder(existing)
            existing
        } else {
            check(root.isDirectory || root.mkdirs()) { "The EA games folder could not be created" }
            val folder = StoreFiles.freshFolder(root, game.title, "ea-${StoreFiles.idPart(gameId)}")
            check(folder.isDirectory || folder.mkdirs()) { "The game's folder could not be created" }
            check(folder.canonicalFile.parentFile == root.canonicalFile) { "The EA install folder is outside its store folder" }
            val made = EaInstall(gameId, game.offerId, game.title, game.artUrl, root.canonicalPath, folder.canonicalPath)
            File(folder, EaInstalls.PROOF).writeText(gameId)
            File(folder, StoreInstallJob.IN_PROGRESS_MARKER).writeText(gameId)
            EaInstalls.put(context, made)
            made
        }
        val outcome = EaInstaller.download(context, row, url, progress)
        val folder = EaInstalls.ownedFolder(row)
        val manifest = EaManifest.read(folder)
        val launcher = manifest?.launcher()
        val exe = listOfNotNull(launcher?.path, offer?.executePathOverride)
            .firstNotNullOfOrNull { EaPaths.relative(it).takeIf(String::isNotBlank)?.let { path -> EaPaths.relativeOnDisk(folder, path) } }.orEmpty()
        val registry = JSONArray()
        listOfNotNull(offer?.installCheckOverride, offer?.executePathOverride, launcher?.path).mapNotNull(EaPaths::registered)
            .distinctBy { it.key.lowercase() + "|" + it.value.lowercase() }
            .forEach { registry.put(JSONArray().put(it.key).put(it.value)) }
        val completed = row.copy(
            complete = true, everInstalled = true, version = offer?.gameVersion?.ifBlank { null } ?: manifest?.version.orEmpty(),
            sizeBytes = outcome.optLong("totalBytes"), executable = exe,
            arguments = EaPaths.splitArgs(listOfNotNull(offer?.executeParameters, launcher?.parameters).filter { it.isNotBlank() }.joinToString(" ")),
            extra = mapOf("contentId" to gameId, "multiplayerId" to offer?.multiplayerId.orEmpty(),
                "masterTitleId" to offer?.masterTitleId.orEmpty(), "touchup" to manifest?.touchupPath.orEmpty(),
                "touchupArgs" to manifest?.touchupParameters.orEmpty(), "contentIds" to manifest?.contentIds.orEmpty().joinToString(","),
                "locale" to (offer?.locales?.firstOrNull { it == "en_US" } ?: offer?.locales?.firstOrNull() ?: "en_US"),
                "registry" to registry.toString(), "displayName" to (manifest?.title("en_US") ?: game.title)),
        )
        check(File(folder, StoreInstallJob.IN_PROGRESS_MARKER).let { !it.exists() || it.delete() }) { "The download finished but its progress marker could not be cleared" }
        EaInstalls.put(context, completed)
        progress.report(1f, "Installed ${game.title}")
        "Installed ${game.title}"
        }
    }

    override suspend fun discardPartial(context: Context, gameId: String) = withContext(Dispatchers.IO) {
        installLock(gameId).withLock {
        val row = EaInstalls.read(context).firstOrNull { it.id == gameId } ?: return@withContext
        if (!row.everInstalled) { EaInstalls.delete(row); EaInstalls.remove(context, gameId) }
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        installLock(gameId).withLock {
        runCatchingCancellable {
            val row = EaInstalls.read(context).firstOrNull { it.id == gameId } ?: error("droidtop has no EA install for this game")
            EaInstalls.delete(row)
            EaInstalls.remove(context, gameId)
        }
        }
    }

    override suspend fun launch(context: Context, gameId: String): StoreLaunch? = withContext(Dispatchers.IO) {
        val row = EaInstalls.read(context).firstOrNull { it.id == gameId && it.complete } ?: return@withContext null
        val folder = EaInstalls.ownedFolder(row)
        if (row.executable.isBlank()) return@withContext null // the runtime's existing executable picker handles this
        val executable = EaPaths.find(folder, row.executable)?.takeIf { it.isFile }
            ?: error("EA did not name an installed program for this game")
        StoreLaunch(executable, executable.parentFile ?: folder, row.arguments)
    }

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context), EaInstalls.file(context)))

    /** One game of the account: its content id (the EA app's own), title and art. */
    internal data class Game(val id: String, val title: String, val artUrl: String?, val offerId: String = "")

    class SignedOut : IllegalStateException("Sign in to the EA app again")

    private fun graphql(token: String, query: String, variables: JSONObject): String {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val request = Request.Builder().url(API)
            .header("Authorization", "Bearer $token")
            .header("AuthToken", token)
            .header("X-AuthToken", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "EA did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    internal companion object {
        private const val TAG = "EAStore"
        private const val API = "https://service-aggregation-layer.juno.ea.com/graphql"

        private const val ENTITLEMENTS = "query getEntitlements(${'$'}limit: Int, ${'$'}next: String) { me { ownedGameProducts(locale: \"DEFAULT\", " +
            "entitlementEnabled: true, storefronts: [EA], type: [DIGITAL_FULL_GAME, PACKAGED_FULL_GAME], platforms: [PC], " +
            "paging: {limit: ${'$'}limit, next: ${'$'}next}) { next, items { originOfferId product { baseItem { gameType } } } } } }"

        private const val OFFERS = "query getOffers(${'$'}offerIds: [String!]!) { legacyOffers(offerIds: ${'$'}offerIds, locale: \"DEFAULT\") " +
            "{ offerId: id contentId } gameProducts(offerIds: ${'$'}offerIds, locale: \"DEFAULT\") { items { id originOfferId gameSlug " +
            "baseItem { keyArt { largestImage { path } } packArt { largestImage { path } } title } } } }"

        /** Only the EA desktop client's local success page completes this sign-in, never a remote lookalike. */
        fun isReturnPage(address: String): Boolean =
            address.substringBefore('?').substringBefore('#') in setOf(EaAuth.REDIRECT, "qrc:/html/login_successful.html")

        internal fun callbackSecret(address: String, expectedState: String): String? {
            if (!isReturnPage(address)) return null
            val state = EaAuth.redirectValue(address, "state") ?: return null
            if (state != expectedState) return null
            val code = EaAuth.redirectValue(address, "code")?.takeIf { it.isNotBlank() } ?: return null
            return JSONObject().put("code", code).put("state", state).toString()
        }

        /**
         * One page of `ownedGameProducts`: the offer ids of its base games, and the cursor of the next page (null at the
         * end). A GraphQL error answer fails the sync with EA's message. Pure.
         */
        fun entitlementsOf(json: String): Pair<List<String>, String?> {
            val root = JSONObject(json)
            root.optJSONArray("errors")?.let { errors -> error("EA: ${errors.optJSONObject(0)?.optString("message") ?: "an error"}") }
            val owned = root.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("ownedGameProducts") ?: return emptyList<String>() to null
            val items = owned.optJSONArray("items") ?: JSONArray()
            val offers = (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val type = item.optJSONObject("product")?.optJSONObject("baseItem")?.optString("gameType", "")
                item.optString("originOfferId", "").takeIf { it.isNotBlank() && type == "BASE_GAME" }
            }
            return offers to owned.optString("next", "").takeIf { it.isNotBlank() && it != "null" }
        }

        /** The games of a `getOffers` answer: each product with its content id (from `legacyOffers`), title and art. Pure. */
        fun productsOf(json: String): List<Game> {
            val root = JSONObject(json)
            root.optJSONArray("errors")?.let { errors -> error("EA: ${errors.optJSONObject(0)?.optString("message") ?: "an error"}") }
            val data = root.optJSONObject("data") ?: return emptyList()
            val legacy = data.optJSONArray("legacyOffers") ?: JSONArray()
            val contentIds = (0 until legacy.length()).mapNotNull { legacy.optJSONObject(it) }
                .associate { it.optString("offerId", "") to it.optString("contentId", "") }
            val items = data.optJSONObject("gameProducts")?.optJSONArray("items") ?: JSONArray()
            return (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val base = item.optJSONObject("baseItem") ?: return@mapNotNull null
                val title = base.optString("title", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val offer = item.optString("originOfferId", "").ifBlank { item.optString("id", "") }
                val id = contentIds[offer]?.takeIf { it.isNotBlank() } ?: offer.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                fun art(key: String) = base.optJSONObject(key)?.optJSONObject("largestImage")?.optString("path", "")?.takeIf { it.startsWith("https://") }
                Game(id, title, art("packArt") ?: art("keyArt"), offer)
            }
        }

        fun encode(games: List<Game>): String =
            JSONArray(games.map { JSONObject().put("id", it.id).put("title", it.title).put("art", it.artUrl ?: "").put("offer", it.offerId) }).toString()

        fun decode(text: String): List<Game> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { Game(it.getString("id"), it.getString("title"), it.optString("art", "").ifBlank { null }, it.optString("offer", "")) } }
        }.getOrDefault(emptyList())
    }
}
