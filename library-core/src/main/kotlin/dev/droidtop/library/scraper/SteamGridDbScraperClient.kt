package dev.droidtop.library.scraper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real SteamGridDB (steamgriddb.com) client -- self-service like IGDB (a
 * free account, then Profile -> Preferences -> API -> "Generate API key",
 * no manual approval), unlike ScreenScraper/TheGamesDB. Strong coverage
 * for PC titles specifically (its whole purpose is custom Steam library
 * artwork), a useful complement to IGDB/Lutris rather than a replacement.
 *
 * The exact endpoint paths below (`/search/autocomplete/{term}`,
 * `/grids/game/{id}`, `Authorization: Bearer <key>`) are SteamGridDB's
 * long-stable, widely-documented v2 REST API shape -- unlike this
 * session's other scraper clients, this specific set of paths could not be
 * re-confirmed against a live fetch in this session (docs.steamgriddb.com
 * and the client wrapper repos it tried were unreachable from this
 * environment), so treat as informed-but-not-freshly-verified if requests
 * against it start failing.
 */
object SteamGridDbScraperClient {
    private const val BASE_URL = "https://www.steamgriddb.com/api/v2"

    fun findCoverUrl(apiKey: String, gameTitle: String): String? {
        val query = URLEncoder.encode(gameTitle, "UTF-8")
        val searchConnection = openAuthed("$BASE_URL/search/autocomplete/$query", apiKey)
        if (searchConnection.responseCode != 200) return null
        val searchResponse = JSONObject(searchConnection.inputStream.bufferedReader().readText())
        val results = searchResponse.optJSONArray("data") ?: return null
        if (results.length() == 0) return null
        val gameId = results.getJSONObject(0).optInt("id", -1)
        if (gameId == -1) return null

        val gridsConnection = openAuthed("$BASE_URL/grids/game/$gameId", apiKey)
        if (gridsConnection.responseCode != 200) return null
        val gridsResponse = JSONObject(gridsConnection.inputStream.bufferedReader().readText())
        val grids = gridsResponse.optJSONArray("data") ?: return null
        if (grids.length() == 0) return null
        return grids.getJSONObject(0).optString("url", "").ifBlank { null }
    }

    private fun openAuthed(url: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
}
