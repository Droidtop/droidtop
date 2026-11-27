package dev.droidtop.stores.ea

import android.content.Context
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreSyncs
import dev.droidtop.net.AtomicWrite
import dev.droidtop.runtime.util.runCatchingCancellable
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.StoreHttp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * (`gameProducts`, `legacyOffers` for each one's content id). EA serves games only through its own EA app, so this
 * store lists them and does not install them here.
 */
class EAStore : StoreLibrary {
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
        libraryFile(context).takeIf { it.isFile }?.let { decode(it.readText()) }.orEmpty().map { game ->
            StoreGame(store = id, gameId = game.id, title = game.title, installed = false, installPath = null, sizeBytes = 0L, artUrl = game.artUrl)
        }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String =
        error("EA games install through EA's own EA app, which droidtop does not run")

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> =
        Result.failure(IllegalStateException("droidtop did not install this game; the EA app did"))

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context)))

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
