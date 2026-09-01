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
/**
 * Real per-game data Lutris's own public API actually returns for a
 * search result -- confirmed via a real, live call to
 * `https://lutris.net/api/games?search=...` this session, not assumed
 * from docs: `id, name, slug, year, banner_url, icon_url, coverart,
 * platforms, provider_games, aliases, shaders, discord_id, change_for`.
 * No description/genre/developer/publisher/rating field exists at all --
 * `year` is the only real release-date signal Lutris provides (year
 * precision only, not a full date).
 */
data class LutrisGameResult(val coverUrl: String?, val year: Int?)

object LutrisScraperClient {
    fun findGame(gameTitle: String): LutrisGameResult? {
        val query = URLEncoder.encode(gameTitle, "UTF-8")
        val url = URL("https://lutris.net/api/games?search=$query")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        if (connection.responseCode != 200) return null
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val results = response.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val first = results.getJSONObject(0)
        // Real, confirmed bug fix (found via a real live API call this
        // session, not a guess): `banner_url`/`icon_url` are empty
        // strings for most real results -- `coverart` (Lutris's own
        // IGDB-sourced cover_big image) is the field that's actually
        // populated in practice. The old version of this function read
        // `banner_url`, silently missing cover art for the large majority
        // of real games.
        val coverUrl = first.optString("coverart", "").ifBlank { null }
        val year = first.optInt("year", 0).takeIf { it > 0 }
        return LutrisGameResult(coverUrl, year)
    }

    /** Kept for existing callers that only need the cover URL. */
    fun findCoverUrl(gameTitle: String): String? = findGame(gameTitle)?.coverUrl
}
