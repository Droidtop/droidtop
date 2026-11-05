package dev.droidtop.stores.legacy


import dev.droidtop.stores.util.StoreHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Legacy Games' launcher API (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335), as its own launcher calls it and as the
 * two open-source clients that keep people's games read it (borgx/legacygames-backup and gpuccio87/legacy-games-buyout,
 * both MIT): the account's purchases and giveaways, and each game's installer link. Every call carries the account's
 * e-mail and password (`usertoken: Basic ...`), which is why droidtop keeps them, sealed, and the launcher's fixed
 * `Authorization` value. Installers are Windows exe files.
 */
internal object LegacyApi {
    private const val API = "https://api.legacygames.com"

    /** The launcher's own Authorization value, the same for every person (both clients send it as it is). */
    private const val LAUNCHER_AUTHORIZATION = "?token?"

    /** One game the account holds: a purchase ([productId], [gameId]) or a giveaway ([installerUuid]). */
    data class Game(
        val gameId: String,
        val title: String,
        val productId: String?,
        val installerUuid: String?,
        val giveaway: Boolean,
        val coverUrl: String?,
        val sizeText: String,
    )

    class SignedOut : IllegalStateException("Sign in to Legacy Games again")

    fun basic(email: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$email:$password".toByteArray(Charsets.UTF_8))

    private fun get(email: String, password: String, url: String): String {
        val request = Request.Builder().url(url)
            .header("Authorization", LAUNCHER_AUTHORIZATION)
            .header("usertoken", basic(email, password))
            .header("Accept", "application/json")
            .build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "Legacy Games did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    /** The account's id, which proves the e-mail and password; [SignedOut] when Legacy Games refuses them. */
    fun userId(email: String, password: String): String = userIdOf(get(email, password, "$API/users/login"))

    /** Every game the account holds: its purchases from the catalogue, then its giveaways, each game once. */
    fun games(email: String, password: String, userId: String): List<Game> {
        val bought = productIdsOf(get(email, password, url("/users/downloads", "userId" to userId)))
        val catalog = catalogOf(get(email, password, "$API/products/catalog"))
        val purchases = catalog.filter { it.productId in bought }
        val giveaways = giveawaysOf(get(email, password, url("/users/getgiveawaycatalogbyemail", "email" to email)))
        return (purchases + giveaways).distinctBy { it.gameId }
    }

    /** The installer link of [game], fresh (links may expire). */
    fun installerUrl(email: String, password: String, game: Game): String {
        val body = if (game.giveaway) {
            get(email, password, url("/products/giveawaydownload", "installerUuid" to (game.installerUuid ?: error("This giveaway has no installer"))))
        } else {
            get(email, password, url("/products/download", "productId" to (game.productId ?: error("This game has no product")), "gameId" to game.gameId))
        }
        return fileOf(body) ?: error("Legacy Games gave no installer for ${game.title}")
    }

    private fun url(path: String, vararg query: Pair<String, String>): String =
        "$API$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()

    /** `users/login`'s `data.userId`. Pure. */
    fun userIdOf(json: String): String {
        val data = runCatching { JSONObject(json).optJSONObject("data") }.getOrNull() ?: throw SignedOut()
        return data.opt("userId")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: throw SignedOut()
    }

    /** The product ids of `users/downloads` (`data[].product_id`): what the account bought. Pure. */
    fun productIdsOf(json: String): Set<String> {
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptySet()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.opt("product_id")?.toString()?.takeIf { id -> id.isNotBlank() } }.toSet()
    }

    /** `products/catalog`'s games, each with its product: a list of `{product_id, games: [...]}`. Pure. */
    fun catalogOf(json: String): List<Game> {
        val products = runCatching { JSONArray(json) }.getOrNull()
            ?: runCatching { JSONObject(json).optJSONArray("data") }.getOrNull()
            ?: return emptyList()
        return (0 until products.length()).flatMap { i ->
            val product = products.optJSONObject(i) ?: return@flatMap emptyList()
            val productId = product.opt("product_id")?.toString()?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
            gamesOf(product.optJSONArray("games"), productId, giveaway = false)
        }
    }

    /** `users/getgiveawaycatalogbyemail`'s packs (`data[].games[]`): the account's giveaways. Pure. */
    fun giveawaysOf(json: String): List<Game> {
        val packs = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until packs.length()).flatMap { i -> gamesOf(packs.optJSONObject(i)?.optJSONArray("games"), null, giveaway = true) }
    }

    private fun gamesOf(array: JSONArray?, productId: String?, giveaway: Boolean): List<Game> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { j ->
            val g = array.optJSONObject(j) ?: return@mapNotNull null
            val id = g.opt("game_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val title = g.optString("game_name", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val installer = g.optString("installer_uuid", "").takeIf { it.isNotBlank() }
            if (giveaway && installer == null) return@mapNotNull null
            Game(
                gameId = id,
                title = title,
                productId = productId,
                installerUuid = installer,
                giveaway = giveaway,
                coverUrl = g.optString("game_coverart", "").takeIf { it.startsWith("https://") },
                sizeText = g.optString("game_installed_size", ""),
            )
        }
    }

    /** An installer link from `products/download` (`data.file`) or `products/giveawaydownload` (`file`, or `data.file`), https only. Pure. */
    fun fileOf(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val file = root.optJSONObject("data")?.optString("file", "")?.takeIf { it.isNotBlank() } ?: root.optString("file", "")
        return file.trim().takeIf { it.startsWith("https://") }
    }

    /** "218.1 MB" as bytes (the launcher's own size text); 0 when it says none. Pure. */
    fun bytesOf(text: String): Long {
        val match = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?B)", RegexOption.IGNORE_CASE).find(text) ?: return 0L
        val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = when (match.groupValues[2].uppercase()) {
            "KB" -> 1L shl 10
            "MB" -> 1L shl 20
            "GB" -> 1L shl 30
            "TB" -> 1L shl 40
            else -> 1L
        }
        return (number * unit).toLong()
    }
}
