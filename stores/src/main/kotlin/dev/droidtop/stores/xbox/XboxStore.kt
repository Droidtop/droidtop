package dev.droidtop.stores.xbox

import android.content.Context
import android.net.Uri
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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Xbox (the Microsoft Store's PC games) as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335), after
 * Playnite's XboxLibrary (MIT, JosefNemec/PlayniteExtensions, `Services/XboxAccountClient.cs`): the person signs in to
 * their Microsoft account on its own page (its public desktop client id, the code returned on
 * `oauth20_desktop.srf`), the Microsoft token becomes an Xbox Live user token and then an XSTS token, and TitleHub's
 * title history lists the account's PC games. Xbox has no list of what an account owns, so the games are the ones the
 * account has played, as Playnite's are. Its games run only on Windows or an Xbox, so this store lists them and does
 * not install them.
 */
class XboxStore : StoreLibrary {
    override val id = "xbox"
    override val label = "Xbox"
    override val siteUrl = "https://www.xbox.com/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "https://www.xbox.com/games/store",
        hosts = listOf("xbox.com"),
        searchPage = "https://www.xbox.com/search?q=",
        accountLibrary = "https://www.xbox.com/play/library",
    )

    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dir(context: Context) = File(context.filesDir, "xbox")
    private fun authFile(context: Context) = File(dir(context), "tokens.json")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    override fun signedIn(context: Context): Boolean = authFile(context).isFile

    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("approval_prompt", "auto")
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("redirect_uri", REDIRECT)
            .build().toString(),
        isReturnPage = { address -> isReturnPage(address) },
        codeInUrl = { address -> runCatching { Uri.parse(address).getQueryParameter("code") }.getOrNull()?.takeIf { it.isNotBlank() } },
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val refresh = refreshTokenOf(token(FormBody.Builder().add("grant_type", "authorization_code").add("code", secret)))
            AtomicWrite.text(authFile(context), JSONObject().put("refresh", refresh).toString())
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@XboxStore).onFailure { Timber.tag(TAG).w(it, "First Xbox library read failed") }
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
            val refresh = authFile(context).takeIf { it.isFile }?.let { JSONObject(it.readText()).optString("refresh", "") }?.takeIf { it.isNotBlank() }
                ?: error("Not signed in to Xbox")
            // Each sync starts from the refresh token, as Playnite's refresh does; the new one is kept.
            val answer = token(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh))
            val access = JSONObject(answer).optString("access_token", "").takeIf { it.isNotBlank() } ?: throw SignedOut()
            refreshTokenOf(answer).let { AtomicWrite.text(authFile(context), JSONObject().put("refresh", it).toString()) }
            val xsts = xsts(userToken(access))
            val titles = get("$TITLEHUB/users/xuid(${xsts.xuid})/titles/titlehistory/decoration/detail", xsts)
            val games = gamesOf(titles)
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
        error("Xbox games run only on Windows or an Xbox; droidtop cannot install them")

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> =
        Result.failure(IllegalStateException("droidtop did not install this game"))

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context)))

    /** One PC game the account has played: its package family name, title and art. */
    internal data class Game(val id: String, val title: String, val artUrl: String?)

    /** The XSTS token and the account's user hash and Xbox user id it names. */
    internal data class Xsts(val token: String, val userHash: String, val xuid: String)

    class SignedOut : IllegalStateException("Sign in to Xbox again")

    private fun token(form: FormBody.Builder): String {
        val body = form.add("scope", SCOPE).add("client_id", CLIENT_ID).add("redirect_uri", REDIRECT).build()
        return StoreHttp.http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { response ->
            if (response.code in 400..499) throw SignedOut()
            check(response.isSuccessful) { "Microsoft did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    private fun postJson(url: String, json: JSONObject): String =
        StoreHttp.http.newCall(
            Request.Builder().url(url).header("x-xbl-contract-version", "1").header("Accept", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "Xbox Live did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }

    /** The Microsoft access token as an Xbox Live user token (`user/authenticate`, an RPS ticket "d=<token>"). */
    private fun userToken(access: String): String {
        val body = JSONObject().put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT")
            .put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", "d=$access"))
        return JSONObject(postJson(USER_AUTH, body)).optString("Token", "").takeIf { it.isNotBlank() } ?: throw SignedOut()
    }

    /** The user token as an XSTS token for Xbox Live's services, with the account's user hash and Xbox user id. */
    private fun xsts(userToken: String): Xsts {
        val body = JSONObject().put("RelyingParty", "http://xboxlive.com").put("TokenType", "JWT")
            .put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray(listOf(userToken))))
        return xstsOf(postJson(XSTS, body))
    }

    private fun get(url: String, xsts: Xsts): String =
        StoreHttp.http.newCall(
            Request.Builder().url(url)
                .header("x-xbl-contract-version", "2")
                .header("Authorization", "XBL3.0 x=${xsts.userHash};${xsts.token}")
                .header("Accept-Language", "en-US")
                .build(),
        ).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "Xbox did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }

    internal companion object {
        private const val TAG = "XboxStore"

        /** The Microsoft account desktop client id Playnite signs in with: a public identification id, not a secret. */
        const val CLIENT_ID = "38cd2fa8-66fd-4760-afb2-405eb65d5b0c"
        private const val SCOPE = "Xboxlive.signin Xboxlive.offline_access"
        const val REDIRECT = "https://login.live.com/oauth20_desktop.srf"
        private const val AUTHORIZE = "https://login.live.com/oauth20_authorize.srf"
        private const val TOKEN = "https://login.live.com/oauth20_token.srf"
        private const val USER_AUTH = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val TITLEHUB = "https://titlehub.xboxlive.com"

        /** Microsoft's desktop return page with the code on it. */
        fun isReturnPage(address: String): Boolean = address.startsWith(REDIRECT) && address.contains("code=")

        /** The refresh token of a token answer; one without it is a sign-in gone. Pure. */
        fun refreshTokenOf(json: String): String =
            runCatching { JSONObject(json).optString("refresh_token", "") }.getOrDefault("").takeIf { it.isNotBlank() } ?: throw SignedOut()

        /** An XSTS answer's token, user hash and Xbox user id (`DisplayClaims.xui[0]`). Pure. */
        fun xstsOf(json: String): Xsts {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: throw SignedOut()
            val claim = root.optJSONObject("DisplayClaims")?.optJSONArray("xui")?.optJSONObject(0) ?: throw SignedOut()
            val token = root.optString("Token", "").takeIf { it.isNotBlank() } ?: throw SignedOut()
            return Xsts(token, claim.optString("uhs", ""), claim.optString("xid", "").takeIf { it.isNotBlank() } ?: throw SignedOut())
        }

        /**
         * The PC games of a TitleHub title history (Playnite's filter: a package family name, type "Game", "PC" among
         * its devices), each once, with the names' platform suffixes dropped. Pure.
         */
        fun gamesOf(json: String): List<Game> {
            val titles = runCatching { JSONObject(json).optJSONArray("titles") }.getOrNull() ?: return emptyList()
            return (0 until titles.length()).mapNotNull { i ->
                val title = titles.optJSONObject(i) ?: return@mapNotNull null
                val pfn = title.optString("pfn", "").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                if (title.optString("type", "") != "Game") return@mapNotNull null
                val devices = title.optJSONArray("devices")?.let { d -> (0 until d.length()).map { d.optString(it) } }.orEmpty()
                if ("PC" !in devices) return@mapNotNull null
                val name = cleanName(title.optString("name", "")).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Game(pfn, name, title.optString("displayImage", "").takeIf { it.startsWith("https://") })
            }.distinctBy { it.id }
        }

        /** A title's name without the platform words Microsoft adds and the trademark signs. Pure. */
        fun cleanName(name: String): String = listOf("(PC)", "(Windows)", "for Windows 10", "- Windows 10")
            .fold(name) { text, suffix -> text.replace(suffix, "") }
            .replace(Regex("[™®©]"), "")
            .trim()

        fun encode(games: List<Game>): String =
            JSONArray(games.map { JSONObject().put("id", it.id).put("title", it.title).put("art", it.artUrl ?: "") }).toString()

        fun decode(text: String): List<Game> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { Game(it.getString("id"), it.getString("title"), it.optString("art", "").ifBlank { null }) } }
        }.getOrDefault(emptyList())
    }
}
