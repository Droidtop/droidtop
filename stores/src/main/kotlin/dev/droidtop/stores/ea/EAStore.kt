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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The EA app's library as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335), after Lutris's EA app
 * service (GPL-3.0, `lutris/services/ea_app.py`): the person signs in on EA's own page, the session cookies of
 * `accounts.ea.com` are traded for an access token (`connect/auth`, EA's own `ORIGIN_JS_SDK` client, a public client
 * id), and EA's GraphQL service lists the account's PC base games (`ownedGameProducts`) and their titles and art
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
    private fun authFile(context: Context) = File(dir(context), "session.json")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    private fun session(context: Context): String? =
        authFile(context).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).optString("cookies", "") }.getOrNull() }?.takeIf { it.isNotBlank() }

    override fun signedIn(context: Context): Boolean = authFile(context).isFile

    /** EA's sign-in page; when it returns to ea.com, the accounts site's cookies are the sign-in. */
    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = "https://www.ea.com/login",
        isReturnPage = { address -> isReturnPage(address) },
        codeInUrl = { null },
        codeInCookie = StoreSignIn.WebPage.ALL_COOKIES,
        cookieUrl = AUTH,
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            // Kept only once it yields a token: a page reached before signing in is no sign-in.
            token(secret)
            AtomicWrite.text(authFile(context), JSONObject().put("cookies", secret).toString())
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@EAStore).onFailure { Timber.tag(TAG).w(it, "First EA library read failed") }
        Result.success(null)
    }

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            authFile(context).delete()
            libraryFile(context).delete()
            Unit
        }
    }

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val token = token(session(context) ?: error("Not signed in to the EA app"))
            val offers = ArrayList<String>()
            var next: String? = null
            do {
                val page = entitlementsOf(graphql(token, ENTITLEMENTS, JSONObject().put("limit", 100).apply { next?.let { put("next", it) } }))
                offers += page.first
                next = page.second
            } while (next != null && offers.size < 10_000)
            val games = offers.distinct().chunked(100).flatMap { chunk -> productsOf(graphql(token, OFFERS, JSONObject().put("offerIds", JSONArray(chunk)))) }
                .distinctBy { it.id }
            AtomicWrite.text(libraryFile(context), encode(games))
            games.size
        }.also { result -> if (result.exceptionOrNull() is SignedOut) authFile(context).delete() }
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
    internal data class Game(val id: String, val title: String, val artUrl: String?)

    class SignedOut : IllegalStateException("Sign in to the EA app again")

    /** An access token from the session cookies (EA's `connect/auth`, implicit grant to `nucleus:rest`). */
    private fun token(cookies: String): String {
        val url = AUTH.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", "ORIGIN_JS_SDK")
            .addQueryParameter("response_type", "token")
            .addQueryParameter("redirect_uri", "nucleus:rest")
            .addQueryParameter("prompt", "none")
            .build()
        val body = StoreHttp.http.newCall(Request.Builder().url(url).header("Cookie", cookies).build()).execute().use { response ->
            if (response.code in 400..499) throw SignedOut()
            check(response.isSuccessful) { "EA did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
        return tokenOf(body)
    }

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
        const val AUTH = "https://accounts.ea.com/connect/auth"
        private const val API = "https://service-aggregation-layer.juno.ea.com/graphql"

        private const val ENTITLEMENTS = "query getEntitlements(${'$'}limit: Int, ${'$'}next: String) { me { ownedGameProducts(locale: \"DEFAULT\", " +
            "entitlementEnabled: true, storefronts: [EA], type: [DIGITAL_FULL_GAME, PACKAGED_FULL_GAME], platforms: [PC], " +
            "paging: {limit: ${'$'}limit, next: ${'$'}next}) { next, items { originOfferId product { baseItem { gameType } } } } } }"

        private const val OFFERS = "query getOffers(${'$'}offerIds: [String!]!) { legacyOffers(offerIds: ${'$'}offerIds, locale: \"DEFAULT\") " +
            "{ offerId: id contentId } gameProducts(offerIds: ${'$'}offerIds, locale: \"DEFAULT\") { items { id originOfferId gameSlug " +
            "baseItem { keyArt { largestImage { path } } packArt { largestImage { path } } title } } } }"

        /** Where EA's sign-in has returned to: ea.com itself, past its sign-in page. */
        fun isReturnPage(address: String): Boolean =
            address.startsWith("https://www.ea.com/") && !address.startsWith("https://www.ea.com/login")

        /** The access token of a `connect/auth` answer; an answer without one is a sign-in gone. Pure. */
        fun tokenOf(json: String): String =
            runCatching { JSONObject(json).optString("access_token", "") }.getOrDefault("").takeIf { it.isNotBlank() } ?: throw SignedOut()

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
                Game(id, title, art("packArt") ?: art("keyArt"))
            }
        }

        fun encode(games: List<Game>): String =
            JSONArray(games.map { JSONObject().put("id", it.id).put("title", it.title).put("art", it.artUrl ?: "") }).toString()

        fun decode(text: String): List<Game> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { Game(it.getString("id"), it.getString("title"), it.optString("art", "").ifBlank { null }) } }
        }.getOrDefault(emptyList())
    }
}
