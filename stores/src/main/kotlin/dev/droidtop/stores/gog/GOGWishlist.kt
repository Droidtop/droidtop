package dev.droidtop.stores.gog

import android.content.Context
import dev.droidtop.library.stores.Wishlist
import dev.droidtop.library.stores.WishlistGame
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * GOG's wishlist (docs/SPEC.md 7g, "Wishlist", Droidtop/tracker#134): the product ids GOG keeps for the signed-in
 * account at `embed.gog.com/user/wishlist.json` (asked for with the same bearer token as the owned-game ids), then a
 * name and a cover for each id droidtop has not seen before, from the same product and GamesDB endpoints the library
 * already uses. The result is droidtop's own copy ([Wishlist]); the library reads that, never the network. Read-only:
 * nothing is added to or removed from the person's wishlist.
 */
internal object GOGWishlist {
    private const val TAG = "GOG"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The wished product ids in a `wishlist.json` body: `{"wishlist":{"1207658691":true,...}}`. Sorted, so the order is the same every sync. */
    internal fun parseIds(body: String): List<String> {
        val wishlist = JSONObject(body).optJSONObject("wishlist") ?: return emptyList()
        return wishlist.keys().asSequence().filter { wishlist.optBoolean(it, false) }.sorted().toList()
    }

    /**
     * Reads the wishlist again and rewrites droidtop's copy. A game already in the copy keeps its name and cover (no
     * request for it); a new one is looked up, and one whose lookup fails is left for the next sync. Success carries
     * how many games the copy holds. Network and disk work.
     */
    suspend fun refresh(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val token = GOGAuthManager.getStoredCredentials(context).getOrThrow().accessToken
            check(token.isNotEmpty()) { "No valid GOG sign-in" }
            val request = Request.Builder()
                .url("${GOGConstants.GOG_EMBED_URL}/user/wishlist.json")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "GameNative/1.0")
                .get()
                .build()
            val ids = http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "GOG's wishlist answered HTTP ${response.code}" }
                parseIds(response.body?.string().orEmpty())
            }.take(Wishlist.LIMIT)
            val known = Wishlist.read(context, "gog").associateBy { it.gameId }
            val games = ids.mapNotNull { id -> known[id] ?: lookup(context, id) }
            Wishlist.write(context, "gog", games)
            Timber.tag(TAG).i("wishlist: ${games.size} of ${ids.size} games")
            games.size
        }.onFailure { Timber.tag(TAG).w(it, "Reading the GOG wishlist failed") }
    }

    private suspend fun lookup(context: Context, id: String): WishlistGame? {
        val details = GOGApiClient.getGameById(context, id, expanded = emptyList()).getOrNull() ?: return null
        if (details.title.isBlank()) return null
        val art = GOGApiClient.getVerticalCoverUrl(id).ifEmpty { details.imageUrl }.ifEmpty { details.iconUrl }
        return WishlistGame(id, details.title, art.takeIf { it.isNotBlank() })
    }
}
