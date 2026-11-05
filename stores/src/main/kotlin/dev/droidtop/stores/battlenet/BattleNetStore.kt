package dev.droidtop.stores.battlenet

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
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Battle.net as a [StoreLibrary] (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335): the games of the account, read
 * the way Playnite's BattleNetLibrary reads them (MIT, JosefNemec/PlayniteExtensions,
 * `Services/BattleNetAccountClient.cs`): `account.battle.net/api/games-and-subs` and `/api/classic-games` with the
 * account site's session, which the person's sign-in on Blizzard's page leaves. Blizzard serves games only through its
 * own Battle.net app (its download protocol is its own), so this store lists them, links each to the same game in
 * other stores, and does not install them here. A game the Battle.net app installed keeps Battle.net's own folder
 * marker (`battle.net:<product code>`, `StoreMarkers`), a folder entry that one-game grouping joins with this row.
 */
class BattleNetStore : StoreLibrary {
    override val id = "battlenet"
    override val label = "Battle.net"
    override val siteUrl = "https://www.blizzard.com/"

    override val webPages = dev.droidtop.library.stores.StoreWebPages(
        home = "https://shop.battle.net/",
        hosts = listOf("battle.net", "blizzard.com"),
        searchPage = "https://shop.battle.net/search?q=",
        accountLibrary = "https://account.battle.net/games",
    )

    override val signInKind = StoreSignInKind.WEB_PAGE

    private fun dir(context: Context) = File(context.filesDir, "battlenet")
    private fun authFile(context: Context) = File(dir(context), "session.json")
    private fun libraryFile(context: Context) = File(dir(context), "library.json")

    private fun session(context: Context): String? =
        authFile(context).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()).optString("cookies", "") }.getOrNull() }?.takeIf { it.isNotBlank() }

    override fun signedIn(context: Context): Boolean = authFile(context).isFile

    /** Blizzard's account site: it signs in and lands on the account overview, whose cookies are the sign-in. */
    override fun signIn(context: Context): StoreSignIn = StoreSignIn.WebPage(
        url = "$ACCOUNT/login",
        isReturnPage = { address -> isReturnPage(address) },
        codeInUrl = { null },
        codeInCookie = StoreSignIn.WebPage.ALL_COOKIES,
    )

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            // Kept only once it reads the games: a page reached before signing in is no sign-in.
            gamesOf(get(secret, GAMES))
            AtomicWrite.text(authFile(context), JSONObject().put("cookies", secret).toString())
        }.getOrElse { return@withContext Result.failure(it) }
        StoreSyncs.run(context, this@BattleNetStore).onFailure { Timber.tag(TAG).w(it, "First Battle.net library read failed") }
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
            val session = session(context) ?: error("Not signed in to Battle.net")
            val games = gamesOf(get(session, GAMES)) +
                runCatchingCancellable { classicsOf(get(session, CLASSICS)) }.getOrDefault(emptyList())
            val unique = games.distinctBy { it.id }
            AtomicWrite.text(libraryFile(context), encode(unique))
            unique.size
        }.also { result -> if (result.exceptionOrNull() is SignedOut) authFile(context).delete() }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        libraryFile(context).takeIf { it.isFile }?.let { decode(it.readText()) }.orEmpty().map { game ->
            StoreGame(store = id, gameId = game.id, title = game.title, installed = false, installPath = null, sizeBytes = 0L, artUrl = null)
        }
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String =
        error("Battle.net games install through Blizzard's own Battle.net app, which droidtop does not run")

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> =
        Result.failure(IllegalStateException("droidtop did not install this game; the Battle.net app did"))

    override fun changeStamp(context: Context): Long = StoreFiles.stamp(listOf(authFile(context), libraryFile(context)))

    /** One game of the account: Blizzard's title id as the id, and its name. */
    internal data class Game(val id: String, val title: String)

    class SignedOut : IllegalStateException("Sign in to Battle.net again")

    private fun get(cookies: String, url: String): String {
        val request = Request.Builder().url(url).header("Cookie", cookies).header("Accept", "application/json").build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "Battle.net did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    internal companion object {
        private const val TAG = "BattleNetStore"
        private const val ACCOUNT = "https://account.battle.net"
        private const val GAMES = "$ACCOUNT/api/games-and-subs"
        private const val CLASSICS = "$ACCOUNT/api/classic-games"

        /** The account overview, where Blizzard's sign-in lands (Playnite's check). */
        fun isReturnPage(address: String): Boolean =
            address.substringBefore('?').trimEnd('/') in setOf("https://account.battle.net/overview", "https://account.battle.net")

        /**
         * The games of a `games-and-subs` answer (`gameAccounts`: `titleId`, `localizedGameName`), each title once;
         * an account that is not signed in answers without them, which is [SignedOut]. Pure.
         */
        fun gamesOf(json: String): List<Game> {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: throw SignedOut()
            val accounts = root.optJSONArray("gameAccounts") ?: if (root.has("gameAccounts") || root.has("subscriptions")) JSONArray() else throw SignedOut()
            return (0 until accounts.length()).mapNotNull { i ->
                val account = accounts.optJSONObject(i) ?: return@mapNotNull null
                val titleId = account.optLong("titleId", 0L).takeIf { it > 0 } ?: return@mapNotNull null
                val name = account.optString("localizedGameName", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Game(titleId.toString(), name)
            }.distinctBy { it.id }
        }

        /** The classic games of a `classic-games` answer (Warcraft III, Diablo II: `classicGames`), by their name. Pure. */
        fun classicsOf(json: String): List<Game> {
            val items = runCatching { JSONObject(json).optJSONArray("classicGames") }.getOrNull() ?: return emptyList()
            return (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("localizedGameName", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val icon = item.optString("regionalGameFranchiseIconFilename", "")
                val key = when {
                    icon.contains("warcraft-iii") -> "classic-w3"
                    icon.contains("diablo-ii") -> "classic-d2"
                    else -> "classic-" + StoreFiles.idPart(name.lowercase())
                }
                Game(key, name)
            }
        }

        fun encode(games: List<Game>): String = JSONArray(games.map { JSONObject().put("id", it.id).put("title", it.title) }).toString()

        fun decode(text: String): List<Game> = runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i -> array.getJSONObject(i).let { Game(it.getString("id"), it.getString("title")) } }
        }.getOrDefault(emptyList())
    }
}
