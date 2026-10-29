package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.SteamStoreClient
import dev.droidtop.library.stores.Wishlist
import dev.droidtop.library.stores.WishlistGame
import dev.droidtop.net.Http
import java.net.URLEncoder
import org.json.JSONObject
import timber.log.Timber

/**
 * Steam's wishlist (docs/SPEC.md 7g, "Wishlist", Droidtop/tracker#134): the app ids Steam keeps for the signed-in
 * account, from the Web API's `IWishlistService/GetWishlist` with the account's own access token (so a private
 * wishlist reads too), in Steam's own order. A name for each id droidtop has not seen before comes from the
 * storefront's keyless `appdetails` ([SteamStoreClient], paced to Steam's limit), at most [NEW_PER_SYNC] a sync so a
 * long wishlist fills over a few syncs instead of holding one up for minutes; the cover is the app's library
 * capsule. The result is droidtop's own copy ([Wishlist]). Read-only: nothing is added to or removed from it.
 */
internal object SteamWishlist {
    private const val TAG = "SteamWishlist"
    const val STORE = "steam"

    /** New games named per sync: each is one storefront request, 1.5 s apart. */
    const val NEW_PER_SYNC = 40

    /** The wished app ids in a `GetWishlist` answer, `{"response":{"items":[{"appid":..,"priority":..}]}}`, in Steam's order (priority, then the order given). */
    internal fun parseIds(body: String): List<Int> {
        val items = JSONObject(body).optJSONObject("response")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length())
            .mapNotNull { i -> items.optJSONObject(i) }
            .mapIndexedNotNull { order, item ->
                item.optInt("appid", 0).takeIf { it > 0 }?.let { Triple(it, item.optInt("priority", Int.MAX_VALUE), order) }
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }
            .distinct()
    }

    /**
     * The copy for [ids]: a game already in [known] keeps its name and cover; up to [NEW_PER_SYNC] others are named
     * by [lookup] (null leaves it for the next sync). Pure apart from [lookup].
     */
    internal fun merge(ids: List<Int>, known: Map<String, WishlistGame>, lookup: (Int) -> WishlistGame?): List<WishlistGame> {
        var asked = 0
        return ids.take(Wishlist.LIMIT).mapNotNull { id ->
            known[id.toString()] ?: if (asked < NEW_PER_SYNC) {
                asked++
                lookup(id)
            } else {
                null
            }
        }
    }

    /** Reads the wishlist of [steamId64] with [accessToken] and rewrites droidtop's copy; how many games it holds. Network and disk. */
    fun refresh(context: Context, steamId64: Long, accessToken: String): Result<Int> = runCatching {
        val token = URLEncoder.encode(accessToken, "UTF-8")
        val response = Http.request("https://api.steampowered.com/IWishlistService/GetWishlist/v1/?steamid=$steamId64&access_token=$token")
        check(response.status == 200) { "Steam's wishlist answered HTTP ${response.status}" }
        val ids = parseIds(response.text())
        val known = Wishlist.read(context, STORE).associateBy { it.gameId }
        val games = merge(ids, known) { id ->
            (SteamStoreClient.appDetails(id) as? ScrapeLookup.Found)?.value?.let { match ->
                WishlistGame(id.toString(), match.name, match.coverUrl)
            }
        }
        Wishlist.write(context, STORE, games)
        Timber.tag(TAG).i("wishlist: ${games.size} of ${ids.size} games")
        games.size
    }.onFailure { Timber.tag(TAG).w(it, "Reading the Steam wishlist failed") }
}
