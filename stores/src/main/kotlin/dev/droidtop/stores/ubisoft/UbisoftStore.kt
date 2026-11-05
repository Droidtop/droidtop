package dev.droidtop.stores.ubisoft

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
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Ubisoft Connect's library as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335), after Lutris's
 * Ubisoft service (GPL-3.0, `lutris/services/ubisoft.py`, `lutris/util/ubisoft/client.py` and `consts.py`, itself from
 * the Galaxy integration by Rasmus Luund): the person signs in on Ubisoft's own page, which keeps the session in the
 * page's storage (`localStorage` `PRODloginData`: ticket, session id, remember-me ticket); the session is renewed as
 * Ubisoft's site renews it (`profiles/sessions`) and the owned games come from Ubisoft's GraphQL service
 * (`AllGames`, owned only). Every call names Ubisoft Club's own public application id. Ubisoft serves games only
 * through Ubisoft Connect, so this store lists them and does not install them here.
 */
class UbisoftStore : StoreLibrary {
    override val id = "ubisoft"
    override val label = "Ubisoft Connect"
    override val siteUrl = "https://store.ubisoft.com/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "https://store.ubisoft.com/",
        hosts = listOf("ubisoft.com"),
        searchPage = "https://store.ubisoft.com/search?q=",
        accountLibrary = "https://store.ubisoft.com/my-games",
    )

    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dir(context: Context) = File(context.filesDir, "ubisoft")
    private fun authFile(context: Context) = File(dir(context), "session.json")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    override fun signedIn(context: Context): Boolean = authFile(context).isFile

    override fun accountName(context: Context): String? = readSession(context)?.name

    /** Ubisoft's sign-in page; on its ready page the session is read from the page's storage (Lutris's steps). */
    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = LOGIN,
        isReturnPage = { address -> isReturnPage(address) },
        codeInUrl = { null },
        codeInScript = SESSION_SCRIPT,
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        val session = runCatchingCancellable {
            val fromPage = sessionOf(secret) ?: error("Ubisoft's page gave no session; sign in again")
            // The site's own next step: the session is opened for the client's application, which also proves it.
            renewed(fromPage, open(fromPage)).also { write(context, it) }
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@UbisoftStore).onFailure { Timber.tag(TAG).w(it, "First Ubisoft library read failed") }
        Result.success(session.name)
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
            val session = fresh(context, readSession(context) ?: error("Not signed in to Ubisoft Connect"))
            val body = JSONObject().put("operationName", "AllGames").put("variables", JSONObject().put("owned", true)).put("query", ALL_GAMES).toString()
            val request = authed(Request.Builder().url(GRAPHQL), session)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val answer = StoreHttp.http.newCall(request).execute().use { response ->
                if (response.code == 401) throw SignedOut()
                check(response.isSuccessful) { "Ubisoft did not answer (HTTP ${response.code})" }
                response.body?.string().orEmpty()
            }
            val games = gamesOf(answer)
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
        error("Ubisoft games install through Ubisoft's own Ubisoft Connect, which droidtop does not run")

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> =
        Result.failure(IllegalStateException("droidtop did not install this game; Ubisoft Connect did"))

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context)))

    /** The kept session: Ubisoft's ticket, session id, account, remember-me ticket and when it must be renewed (epoch ms). */
    internal data class Session(val ticket: String, val sessionId: String, val userId: String, val name: String?, val rememberMe: String?, val renewAtMs: Long)

    /** One game of the account: Ubisoft's space id (else its id), title and cover. */
    internal data class Game(val id: String, val title: String, val artUrl: String?)

    class SignedOut : IllegalStateException("Sign in to Ubisoft Connect again")

    private fun readSession(context: Context): Session? =
        authFile(context).takeIf { it.isFile }?.let { runCatching { decodeSession(it.readText()) }.getOrNull() }

    private fun write(context: Context, session: Session) {
        AtomicWrite.text(authFile(context), encodeSession(session))
    }

    private fun authed(builder: Request.Builder, session: Session): Request.Builder = builder
        .header("Authorization", "Ubi_v1 t=${session.ticket}")
        .header("Ubi-SessionId", session.sessionId)
        .header("Ubi-AppId", APP_ID)
        .header("User-Agent", USER_AGENT)

    /** Opens the session for Ubisoft's application (`POST /v2/profiles/sessions`); its answer, or null when it gives none. */
    private fun open(session: Session): JSONObject? {
        val request = authed(Request.Builder().url("$SERVICES/v2/profiles/sessions"), session)
            .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
            .build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            if (response.code == 401) throw SignedOut()
            check(response.isSuccessful) { "Ubisoft did not open the session (HTTP ${response.code})" }
            runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
        }
    }

    /**
     * [session], renewed when it is due: the ticket first (`PUT /v3/profiles/sessions`), else the remember-me ticket
     * (`POST` with `rm_v1`), the way Ubisoft's site keeps a sign-in; kept again when renewed.
     */
    private fun fresh(context: Context, session: Session): Session {
        if (System.currentTimeMillis() < session.renewAtMs) return session
        fun call(authorization: String, method: String, body: String?): JSONObject? {
            val builder = Request.Builder().url("$SERVICES/v3/profiles/sessions")
                .header("Authorization", authorization)
                .header("Ubi-AppId", APP_ID)
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://connect.ubisoft.com")
                .header("Referer", "https://connect.ubisoft.com")
            val request = when (method) {
                "PUT" -> builder.put(ByteArray(0).toRequestBody("application/json".toMediaType()))
                else -> builder.post((body ?: "{}").toRequestBody("application/json".toMediaType()))
            }.build()
            return StoreHttp.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            }
        }
        val answer = call("Ubi_v1 t=${session.ticket}", "PUT", null)
            ?: session.rememberMe?.let { call("rm_v1 t=$it", "POST", JSONObject().put("rememberMe", true).toString()) }
            ?: throw SignedOut()
        return renewed(session, answer).also { write(context, it) }
    }

    internal companion object {
        private const val TAG = "UbisoftStore"

        /** Ubisoft Club's public application and genome ids, which Ubisoft's own site sends (Lutris's `consts.py`). */
        const val APP_ID = "82b650c0-6cb3-40c0-9f41-25a53b62b206"
        private const val GENOME_ID = "42d07c95-9914-4450-8b38-267c4e462b21"
        private const val SERVICES = "https://public-ubiservices.ubi.com"
        private const val GRAPHQL = "$SERVICES/v1/profiles/me/uplay/graphql"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/72.0.3626.121 Safari/537.36"
        const val LOGIN = "https://connect.ubisoft.com/login?appId=$APP_ID&genomeId=$GENOME_ID&lang=en-US&nextUrl=https:%2F%2Fconnect.ubisoft.com%2Fready"
        private const val READY = "https://connect.ubisoft.com/ready"
        private const val CHANGE_DOMAIN = "https://connect.ubisoft.com/change_domain/"

        /**
         * On the ready page it moves on to the page that holds the stored session; there it answers the session: the
         * three stored objects Lutris reads (`PRODloginData`, `PRODrememberMe`, `PRODlastProfile`) as one, the first
         * holding a key giving it, or null while no ticket is stored.
         */
        const val SESSION_SCRIPT = "(function(){if(location.href.indexOf('$READY')===0){location.replace('$CHANGE_DOMAIN');return null;}" +
            "var o={};['PRODloginData','PRODrememberMe','PRODlastProfile'].forEach(function(k){try{var v=JSON.parse(localStorage.getItem(k));" +
            "if(v&&typeof v==='object'){for(var p in v){if(!(p in o)){o[p]=v[p];}}}}catch(e){}});return o.ticket?JSON.stringify(o):null;})();"

        private const val ALL_GAMES = "query AllGames { viewer { id ...ownedGamesList } } " +
            "fragment gameProps on Game { id spaceId name coverUrl } " +
            "fragment ownedGamesList on User { ownedGames: games(filterBy: {isOwned: true}) { totalCount nodes { ...gameProps } } }"

        fun isReturnPage(address: String): Boolean = address.startsWith(READY) || address.startsWith(CHANGE_DOMAIN)

        /** The session in the page's `PRODloginData`; null when it holds none. Pure. */
        fun sessionOf(text: String): Session? {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val ticket = json.optString("ticket", "").takeIf { it.isNotBlank() } ?: return null
            val sessionId = json.optString("sessionId", "").takeIf { it.isNotBlank() } ?: return null
            return Session(
                ticket = ticket,
                sessionId = sessionId,
                userId = json.optString("userId", ""),
                name = json.optString("nameOnPlatform", "").ifBlank { null },
                rememberMe = json.optString("rememberMeTicket", "").ifBlank { null },
                renewAtMs = 0L,
            )
        }

        /**
         * [session] with what a `profiles/sessions` answer gives in its place (ticket, session id, account, remember-me
         * ticket), renewed again before it expires: its lifetime is `expiration` less `serverTime`, so a device clock
         * that is off does not matter. An answer without a lifetime keeps an hour. Pure.
         */
        fun renewed(session: Session, answer: JSONObject?, nowMs: Long = System.currentTimeMillis()): Session {
            answer ?: return session.copy(renewAtMs = nowMs + 60 * 60 * 1000L)
            val life = runCatching { Instant.parse(answer.getString("expiration")).toEpochMilli() - Instant.parse(answer.getString("serverTime")).toEpochMilli() }
                .getOrNull()?.takeIf { it > 0 } ?: (60 * 60 * 1000L)
            return Session(
                ticket = answer.optString("ticket", "").ifBlank { session.ticket },
                sessionId = answer.optString("sessionId", "").ifBlank { session.sessionId },
                userId = answer.optString("userId", "").ifBlank { session.userId },
                name = answer.optString("nameOnPlatform", "").ifBlank { session.name },
                rememberMe = answer.optString("rememberMeTicket", "").ifBlank { session.rememberMe },
                // Renewed a little before it runs out.
                renewAtMs = nowMs + life - minOf(life / 10, 5 * 60 * 1000L),
            )
        }

        fun encodeSession(s: Session): String = JSONObject().put("ticket", s.ticket).put("sessionId", s.sessionId).put("userId", s.userId)
            .put("name", s.name ?: "").put("rememberMe", s.rememberMe ?: "").put("renewAt", s.renewAtMs).toString()

        fun decodeSession(text: String): Session {
            val o = JSONObject(text)
            return Session(o.getString("ticket"), o.getString("sessionId"), o.optString("userId"), o.optString("name").ifBlank { null },
                o.optString("rememberMe").ifBlank { null }, o.optLong("renewAt", 0L))
        }

        /** The owned games of an `AllGames` answer, each once, by space id (else id). A GraphQL error fails the sync. Pure. */
        fun gamesOf(json: String): List<Game> {
            val root = JSONObject(json)
            root.optJSONArray("errors")?.let { errors -> error("Ubisoft: ${errors.optJSONObject(0)?.optString("message") ?: "an error"}") }
            val nodes = root.optJSONObject("data")?.optJSONObject("viewer")?.optJSONObject("ownedGames")?.optJSONArray("nodes") ?: return emptyList()
            return (0 until nodes.length()).mapNotNull { i ->
                val node = nodes.optJSONObject(i) ?: return@mapNotNull null
                val title = node.optString("name", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val id = node.optString("spaceId", "").takeIf { it.isNotBlank() && it != "null" } ?: node.optString("id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Game(id, title, node.optString("coverUrl", "").takeIf { it.startsWith("https://") })
            }.distinctBy { it.id }
        }

        fun encode(games: List<Game>): String =
            JSONArray(games.map { JSONObject().put("id", it.id).put("title", it.title).put("art", it.artUrl ?: "") }).toString()

        fun decode(text: String): List<Game> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { Game(it.getString("id"), it.getString("title"), it.optString("art", "").ifBlank { null }) } }
        }.getOrDefault(emptyList())
    }
}
