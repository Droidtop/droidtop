package dev.droidtop.library.scraper

import org.json.JSONArray
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real IGDB (igdb.com) metadata/cover-art client -- chosen over
 * ScreenScraper.fr (ES-DE's own default scraper) and TheGamesDB after real
 * research this session: both of those require a manual, human-reviewed
 * forum request for developer API credentials before ANY app-level access
 * exists at all (ScreenScraper: "contact us via the forum to present your
 * software and obtain your identifiers"; TheGamesDB: same, via
 * forums.thegamesdb.net) -- droidtop has no such registration and there's
 * no way for this session to obtain one autonomously. IGDB, by contrast,
 * is fully self-service: a free Twitch developer account, a few clicks at
 * dev.twitch.tv/console, no waiting on anyone's manual approval -- the
 * only realistic path to a scraper that actually works for a user tonight
 * rather than a client built against credentials that don't exist yet.
 *
 * Real, documented API shape:
 *  - Auth: `POST https://id.twitch.tv/oauth2/token` with
 *    `client_id`/`client_secret`/`grant_type=client_credentials` (Twitch's
 *    own OAuth2 client-credentials flow) returns a bearer `access_token`.
 *  - Query: `POST https://api.igdb.com/v4/games` with headers
 *    `Client-ID: <clientId>` and `Authorization: Bearer <token>`, body in
 *    IGDB's own "Apicalypse" query language (not JSON) -- e.g.
 *    `search "Mega Man Legends"; fields name,cover.url; limit 1;`.
 *  - Image URLs come back as protocol-relative (`//images.igdb.com/...`)
 *    and need a size token substituted into the path (`t_thumb` ->
 *    `t_cover_big` for a real usable cover image, IGDB's own documented
 *    image-sizing convention).
 *
 * Writes straight into ES-DE's own real `downloaded_media` layout
 * ([dev.droidtop.library.EsDeArtwork] already reads that exact layout) --
 * scraping and display are already fully wired together with no further
 * glue needed once an image lands on disk.
 */
object IgdbScraperClient {
    class ScraperException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun fetchToken(clientId: String, clientSecret: String): String {
        val url = URL("https://id.twitch.tv/oauth2/token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
        }
        val body = "client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
            "&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}" +
            "&grant_type=client_credentials"
        OutputStreamWriter(connection.outputStream).use { it.write(body) }
        if (connection.responseCode != 200) {
            throw ScraperException("Twitch token request failed: HTTP ${connection.responseCode} -- check the Client ID/Secret")
        }
        val response = connection.inputStream.bufferedReader().readText()
        return org.json.JSONObject(response).getString("access_token")
    }

    /**
     * Searches IGDB for [gameTitle] and returns a real, ready-to-download
     * cover-image URL for the closest match, or null if IGDB has nothing
     * for that title. [gameTitle] is typically a ROM's own filename
     * (without extension) -- real-world ROM filenames often carry region/
     * revision tags ("Mega Man Legends 2 (USA)") that reduce match
     * quality; no filename cleanup is attempted here yet, a real known
     * limitation, not silently pretended away.
     */
    fun findCoverUrl(clientId: String, clientSecret: String, gameTitle: String): String? {
        val token = fetchToken(clientId, clientSecret)
        val url = URL("https://api.igdb.com/v4/games")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Client-ID", clientId)
            setRequestProperty("Authorization", "Bearer $token")
        }
        val escapedTitle = gameTitle.replace("\"", "\\\"")
        val query = "search \"$escapedTitle\"; fields name,cover.url; limit 1;"
        OutputStreamWriter(connection.outputStream).use { it.write(query) }
        if (connection.responseCode != 200) {
            throw ScraperException("IGDB search failed: HTTP ${connection.responseCode}")
        }
        val response = connection.inputStream.bufferedReader().readText()
        val results = JSONArray(response)
        if (results.length() == 0) return null
        val cover = results.getJSONObject(0).optJSONObject("cover") ?: return null
        val rawUrl = cover.optString("url", "").ifBlank { return null }
        // IGDB's own real image-sizing convention: swap the default
        // thumbnail size token for a larger one, and upgrade the
        // protocol-relative URL to https.
        val sized = rawUrl.replace("t_thumb", "t_cover_big")
        return if (sized.startsWith("//")) "https:$sized" else sized
    }

    /** Downloads [imageUrl] straight to [destination], creating parent directories as needed. */
    fun downloadImage(imageUrl: String, destination: File) {
        destination.parentFile?.mkdirs()
        val connection = (URL(imageUrl).openConnection() as HttpURLConnection)
        if (connection.responseCode != 200) {
            throw ScraperException("Image download failed: HTTP ${connection.responseCode}")
        }
        connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    }
}
