package dev.droidtop.library.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real per-game metadata IGDB's own `/v4/games` endpoint returns in one
 * query, mapped onto real ES-DE's own MetaData field set (confirmed
 * against `es-app/src/MetaData.cpp`'s real `gameDecls` table, cloned
 * locally at /root/es-de-reference) -- `releaseDate`/`rating` are already
 * converted to ES-DE's own real conventions (see [IgdbScraperClient.
 * search]'s own doc comment), not IGDB's raw units.
 *
 * [name] is IGDB's own title for the row, which is what makes a match
 * reviewable: the whole point of the manual picker is a person reading
 * the candidate names, so a result whose name the user cannot see is not
 * a candidate at all.
 */
data class IgdbGameMetadata(
    val name: String,
    val coverUrl: String?,
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Float?,
)

/**
 * Real IGDB (igdb.com) metadata/cover-art client for PC and engine
 * games, sitting beside the keyless [LutrisScraperClient] as the
 * credentialed source that can also return descriptions, developer,
 * publisher and genre.
 *
 * It needs the USER's own credentials and can never carry droidtop's:
 * IGDB authenticates through a Twitch developer application, which is
 * free and self-service (a few clicks at dev.twitch.tv/console, with no
 * manual approval queue like ScreenScraper's or TheGamesDB's), and an
 * account's client ID/secret are exactly that account's. droidtop ships
 * none, invents none, and stores whatever the user enters in
 * [ScraperPrefs]; with nothing configured this client is never called at
 * all and the scrape says what to configure instead.
 *
 * Real, documented API shape:
 *  - Auth: `POST https://id.twitch.tv/oauth2/token` with
 *    `client_id`/`client_secret`/`grant_type=client_credentials`
 *    (Twitch's own OAuth2 client-credentials flow) returns a bearer
 *    `access_token` and its lifetime in seconds.
 *  - Query: `POST https://api.igdb.com/v4/games` with headers
 *    `Client-ID: <clientId>` and `Authorization: Bearer <token>`, body in
 *    IGDB's own "Apicalypse" query language (not JSON) -- e.g.
 *    `search "Mega Man Legends"; fields name,cover.url; limit 1;`.
 *  - Image URLs come back protocol-relative (`//images.igdb.com/...`)
 *    with a size token in the path (`t_thumb` -> `t_cover_big` for a
 *    real usable cover, IGDB's own documented image-sizing convention).
 */
object IgdbScraperClient {
    class ScraperException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * The bearer token, cached until shortly before it expires.
     *
     * Twitch's client-credentials tokens last around 60 days, and every
     * search would otherwise burn a token request -- scraping a hundred
     * games meant a hundred logins. In memory only: a token is a
     * credential, it is cheap to re-fetch, and it has no business
     * outliving the process on disk.
     */
    private data class CachedToken(val clientId: String, val token: String, val expiresAtMs: Long)

    @Volatile
    private var cached: CachedToken? = null

    private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L

    private fun token(clientId: String, clientSecret: String): String {
        val now = System.currentTimeMillis()
        cached?.let { if (it.clientId == clientId && it.expiresAtMs > now) return it.token }
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
            throw ScraperException(
                "Twitch rejected the IGDB credentials (HTTP ${connection.responseCode}) -- " +
                    "check the Client ID and Secret in Settings",
            )
        }
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val accessToken = response.getString("access_token")
        val lifetimeMs = response.optLong("expires_in", 0L) * 1000
        cached = CachedToken(clientId, accessToken, now + lifetimeMs - TOKEN_EXPIRY_MARGIN_MS)
        return accessToken
    }

    /**
     * Searches IGDB for [gameTitle] and returns up to [limit] matches,
     * each fully populated in ONE query rather than a call per field.
     *
     * A list, not a best guess: the caller decides whether any of these
     * is certain enough to apply without asking (see [PcMatching]), and
     * the manual picker shows the rest by name. [gameTitle] should be a
     * cleaned title rather than a raw folder name (see
     * [PcScrapeTitle.clean]) -- real folder names carry version and
     * platform tags that IGDB's search does not understand.
     *
     * `releaseDate`/`rating` are converted to real ES-DE's own MetaData
     * conventions (confirmed against `es-app/src/MetaData.cpp` and
     * `es-app/src/scrapers/ScreenScraper.cpp`, cloned locally at
     * /root/es-de-reference), not left in IGDB's raw units: IGDB's
     * `first_release_date` (Unix epoch seconds) becomes ES-DE's
     * `"YYYYMMDDT000000"` MD_DATE string; IGDB's `rating` (0-100) is
     * normalized to ES-DE's 0.0-1.0-rounded-to-nearest-0.1 MD_RATING
     * convention with the same formula ScreenScraper.cpp itself uses
     * (`ceil((raw/scale)/0.1)/10`), just against a 100-point scale
     * instead of a 20-point one.
     */
    fun search(clientId: String, clientSecret: String, gameTitle: String, limit: Int = 10): List<IgdbGameMetadata> {
        val bearer = token(clientId, clientSecret)
        val url = URL("https://api.igdb.com/v4/games")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Client-ID", clientId)
            setRequestProperty("Authorization", "Bearer $bearer")
        }
        val escapedTitle = gameTitle.replace("\"", "\\\"")
        val query = "search \"$escapedTitle\"; fields name,cover.url,summary,first_release_date,rating," +
            "genres.name,involved_companies.company.name,involved_companies.developer,involved_companies.publisher; " +
            "limit $limit;"
        OutputStreamWriter(connection.outputStream).use { it.write(query) }
        if (connection.responseCode != 200) {
            throw ScraperException("IGDB search failed: HTTP ${connection.responseCode}")
        }
        val results = JSONArray(connection.inputStream.bufferedReader().readText())
        return (0 until results.length()).mapNotNull { index -> parse(results.getJSONObject(index)) }
    }

    /** Parses one `/v4/games` row; visible for the pure JVM tests, which exercise it against captured response shapes. */
    internal fun parse(game: JSONObject): IgdbGameMetadata? {
        val name = game.optString("name", "").ifBlank { null } ?: return null

        val coverUrl = game.optJSONObject("cover")?.optString("url", "")?.ifBlank { null }?.let { rawUrl ->
            // IGDB's own real image-sizing convention: swap the default
            // thumbnail size token for a larger one, and upgrade the
            // protocol-relative URL to https.
            val sized = rawUrl.replace("t_thumb", "t_cover_big")
            if (sized.startsWith("//")) "https:$sized" else sized
        }

        var developer: String? = null
        var publisher: String? = null
        game.optJSONArray("involved_companies")?.let { companies ->
            for (i in 0 until companies.length()) {
                val company = companies.getJSONObject(i)
                val companyName = company.optJSONObject("company")?.optString("name", "")?.ifBlank { null } ?: continue
                if (developer == null && company.optBoolean("developer", false)) developer = companyName
                if (publisher == null && company.optBoolean("publisher", false)) publisher = companyName
            }
        }

        val genre = game.optJSONArray("genres")?.let { genres ->
            (0 until genres.length())
                .mapNotNull { i -> genres.getJSONObject(i).optString("name", "").ifBlank { null } }
                .joinToString(", ").ifBlank { null }
        }

        val releaseDate = game.optLong("first_release_date", -1L).takeIf { it >= 0 }?.let { epochSeconds ->
            val format = java.text.SimpleDateFormat("yyyyMMdd'T'000000", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            format.format(java.util.Date(epochSeconds * 1000))
        }

        val rating = game.optDouble("rating", -1.0).takeIf { it >= 0 }
            ?.let { (kotlin.math.ceil((it / 100.0) / 0.1) / 10.0).toFloat() }

        return IgdbGameMetadata(
            name = name,
            coverUrl = coverUrl,
            description = game.optString("summary", "").ifBlank { null },
            developer = developer,
            publisher = publisher,
            genre = genre,
            releaseDate = releaseDate,
            rating = rating,
        )
    }
}
