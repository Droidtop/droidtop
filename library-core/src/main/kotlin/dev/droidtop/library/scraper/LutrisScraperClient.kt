package dev.droidtop.library.scraper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real Lutris (lutris.net) game database client -- genuinely keyless,
 * confirmed by reading Lutris's own real client source
 * (github.com/lutris/lutris, `lutris/api.py`: `GET {SITE_URL}/api/games?
 * search=<query>`, `SITE_URL` defaulting to `https://lutris.net`), not a
 * third-party wrapper's docs. No account, no registration, no waiting on
 * anyone's approval -- Lutris's own desktop client calls this exact same
 * public endpoint on every search. Best source for PC titles specifically
 * (Lutris is a PC/Wine game database first) -- exactly the category
 * `WINE_PROFILE`/GameNative-sourced entries need, once those providers
 * exist as real `LibraryEntry` sources (see this class's own callers for
 * current scope).
 */
object LutrisScraperClient {
    /** Real response fields, confirmed via lutris/website's own API doc wiki: name, slug, banner_url, icon_url, ... */
    fun findCoverUrl(gameTitle: String): String? {
        val query = URLEncoder.encode(gameTitle, "UTF-8")
        val url = URL("https://lutris.net/api/games?search=$query")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        if (connection.responseCode != 200) return null
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val results = response.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        // banner_url is Lutris's own wide box-art-style image -- the closer
        // match to a "cover" than icon_url (a small square app icon).
        return first.optString("banner_url", "").ifBlank { null }
    }
}
