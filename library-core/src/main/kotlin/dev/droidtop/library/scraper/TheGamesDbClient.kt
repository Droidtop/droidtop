package dev.droidtop.library.scraper

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Real per-game data TheGamesDB's own API actually returns, already
 * mapped onto real ES-DE's own MetaData field conventions (releaseDate
 * as "YYYYMMDDT000000", players as a plain string) -- confirmed against
 * real ES-DE source, not guessed (see [TheGamesDbClient]'s own doc
 * comment).
 */
data class TheGamesDbMetadata(
    val name: String?,
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val releaseDate: String?,
    val players: String?,
    val coverUrl: String?,
)

/**
 * Real TheGamesDB (thegamesdb.net) client -- the other real scraper ES-DE
 * itself uses alongside ScreenScraper, ported from ES-DE's own actual
 * source (`es-app/src/scrapers/GamesDBJSONScraper.cpp`/
 * `GamesDBJSONScraperResources.cpp`, cloned locally at
 * /root/es-de-reference for ongoing reference), not guessed.
 *
 * Real, confirmed two-part shape:
 *  1. `GET /v1/Games/ByGameName` (name + `filter[platform]`) returns
 *     basic fields, but developer/publisher/genre come back as bare
 *     NUMERIC ids -- real names only exist in three SEPARATE real
 *     reference-list endpoints (`/Developers`, `/Publishers`, `/Genres`),
 *     fetched once and cached to disk (same real fetch-once-cache
 *     pattern ES-DE itself uses -- synchronous here rather than ES-DE's
 *     own async-polling `ensureResources()`, since every real caller of
 *     this client already runs off the main thread).
 *  2. A separate `GET /v1/Games/Images` call, keyed by the game's own
 *     numeric id from step 1, for the actual cover-art URL -- confirmed
 *     real ES-DE never gets cover art from the search response itself.
 *
 * **Real, own API key required**: ES-DE's own real source has ITS OWN
 * key hardcoded in `GamesDBJSONScraperResources.cpp` (public, since
 * ES-DE is MIT-licensed) -- reusing another project's key here isn't
 * appropriate (a real rate-limit/ToS risk for both projects). droidtop
 * needs its own, obtained the same real self-service way IGDB's key is
 * (a free account at thegamesdb.net, no manual approval needed, unlike
 * ScreenScraper's forum-gated devid/devpassword).
 */
object TheGamesDbClient {
    private const val BASE_URL = "https://api.thegamesdb.net/v1"

    /** The name a refusal is reported under (docs/SPEC.md section 7h). */
    private const val SOURCE = "TheGamesDB"

    private fun fetchReferenceList(apiKey: String, endpoint: String, resourceName: String): Map<Int, String> {
        val url = URL("$BASE_URL$endpoint?apikey=${URLEncoder.encode(apiKey, "UTF-8")}")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        if (connection.responseCode != 200) return emptyMap()
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val listObj = response.optJSONObject("data")?.optJSONObject(resourceName) ?: return emptyMap()
        val out = mutableMapOf<Int, String>()
        for (idKey in listObj.keys()) {
            val entry = listObj.optJSONObject(idKey) ?: continue
            val id = entry.optInt("id", -1)
            val name = entry.optString("name", "")
            if (id >= 0 && name.isNotBlank()) out[id] = name
        }
        return out
    }

    /**
     * Real, disk-cached reference list -- fetched once per real cache
     * file (same real ES-DE convention: never re-fetched once a real
     * cache file exists, since these lists change rarely). [cacheDir]
     * matches this codebase's own existing convention of a caller-
     * supplied [File] destination (see [IgdbScraperClient.downloadImage]),
     * not a stored Context.
     */
    private fun cachedReferenceList(apiKey: String, endpoint: String, resourceName: String, cacheFile: File): Map<Int, String> {
        if (cacheFile.exists()) {
            val cached = try {
                val json = JSONObject(cacheFile.readText())
                json.keys().asSequence().associate { it.toInt() to json.getString(it) }
            } catch (t: Throwable) {
                emptyMap()
            }
            if (cached.isNotEmpty()) return cached
        }
        val fresh = fetchReferenceList(apiKey, endpoint, resourceName)
        if (fresh.isNotEmpty()) {
            try {
                cacheFile.parentFile?.mkdirs()
                val json = JSONObject()
                fresh.forEach { (id, name) -> json.put(id.toString(), name) }
                cacheFile.writeText(json.toString())
            } catch (t: Throwable) {
                // Best-effort cache -- a failed write just means re-fetching next time.
            }
        }
        return fresh
    }

    /** Real ES-DE MetaData releasedate conversion -- TheGamesDB's own real date field is "YYYY-MM-DD". */
    private fun parseReleaseDate(raw: String): String? {
        if (raw.isBlank()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val parsed = format.parse(raw) ?: return null
            val out = SimpleDateFormat("yyyyMMdd'T'000000", Locale.US)
            out.timeZone = TimeZone.getTimeZone("UTC")
            out.format(parsed)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * [cacheDir] holds the three real reference-list cache files (see
     * [cachedReferenceList]'s own doc comment) -- pass a real, stable
     * app-private directory (e.g. `context.cacheDir`), same convention as
     * [TheGamesDbSystemIds]' sibling scraper clients.
     */
    // Real ES-DE strips parenthesized/bracketed groups before a NAME
    // search (Utils::String::removeParenthesis at its ScreenScraper call
    // site; the same convention applies here): "Pokemon - Emerald
    // Version (USA, Europe)" finds nothing, "Pokemon - Emerald Version"
    // does. Confirmed by a real on-device pass going 0-for-6 on a GBA
    // folder of properly named No-Intro files.
    private val DECORATION = Regex("""\s*[\(\[][^)\]]*[\)\]]""")

    fun cleanSearchName(raw: String): String = DECORATION.replace(raw, "").trim().ifEmpty { raw }

    /** One candidate from a name search, enough to tell them apart in a list. */
    data class Candidate(val id: Int, val name: String, val releaseYear: String?)

    /**
     * Every candidate a name search returns, in the API's own order.
     *
     * This exists because automatic matching is wrong often enough to
     * matter: on a real device pass "Pokemon - Crystal Version" matched
     * a fan game called "Pokemon Black and White 3: Genesis", because
     * that is what the API returned first. Ranking helps and cannot
     * fix it; a person looking at the list can.
     */
    fun searchCandidates(
        apiKey: String,
        thegamesdbSystemId: String,
        gameTitle: String,
        limit: Int = 12,
    ): ScrapeLookup<List<Candidate>> {
        val searchUrl = URL(
            "$BASE_URL/Games/ByGameName?apikey=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&fields=release_date" +
                "&name=${URLEncoder.encode(cleanSearchName(gameTitle), "UTF-8")}" +
                "&filter%5Bplatform%5D=${URLEncoder.encode(thegamesdbSystemId, "UTF-8")}",
        )
        val connection = (searchUrl.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        val status = connection.responseCode
        if (status != 200) return ScrapeRefusals.refused(SOURCE, connection, status, listOf(apiKey), gameTitle)
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val games = response.optJSONObject("data")?.optJSONArray("games") ?: return ScrapeLookup.NoMatch
        val candidates = (0 until minOf(games.length(), limit)).mapNotNull { index ->
            val game = games.optJSONObject(index) ?: return@mapNotNull null
            val id = game.optInt("id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            Candidate(
                id = id,
                name = game.optString("game_title", "").ifBlank { return@mapNotNull null },
                releaseYear = game.optString("release_date", "").take(4).ifBlank { null },
            )
        }
        return if (candidates.isEmpty()) ScrapeLookup.NoMatch else ScrapeLookup.Found(candidates)
    }

    /** The full metadata for one candidate the user picked. */
    fun metadataForId(apiKey: String, cacheDir: File, gameId: Int): ScrapeLookup<TheGamesDbMetadata> {
        val developers = cachedReferenceList(apiKey, "/Developers", "developers", File(cacheDir, "thegamesdb_developers.json"))
        val publishers = cachedReferenceList(apiKey, "/Publishers", "publishers", File(cacheDir, "thegamesdb_publishers.json"))
        val genres = cachedReferenceList(apiKey, "/Genres", "genres", File(cacheDir, "thegamesdb_genres.json"))
        val url = URL(
            "$BASE_URL/Games/ByGameID?apikey=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&fields=players,publishers,genres,overview,release_date&id=$gameId",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        val status = connection.responseCode
        if (status != 200) return ScrapeRefusals.refused(SOURCE, connection, status, listOf(apiKey), "game $gameId")
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val game = response.optJSONObject("data")?.optJSONArray("games")?.optJSONObject(0) ?: return ScrapeLookup.NoMatch
        return ScrapeLookup.Found(TheGamesDbMetadata(
            name = game.optString("game_title", "").ifBlank { null },
            description = game.optString("overview", "").ifBlank { null },
            developer = game.optJSONArray("developers")?.let { arr ->
                (0 until arr.length()).mapNotNull { developers[arr.optInt(it, -1)] }.joinToString(", ").ifBlank { null }
            },
            publisher = game.optJSONArray("publishers")?.let { arr ->
                (0 until arr.length()).mapNotNull { publishers[arr.optInt(it, -1)] }.joinToString(", ").ifBlank { null }
            },
            genre = game.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { genres[arr.optInt(it, -1)] }.joinToString(", ").ifBlank { null }
            },
            releaseDate = game.optString("release_date", "").ifBlank { null }?.let { parseReleaseDate(it) },
            players = game.optInt("players", -1).takeIf { it >= 0 }?.toString(),
            coverUrl = fetchCoverUrl(apiKey, gameId),
        ))
    }

    /** No-Intro's trailing article ("Legend of Zelda, The - ..."), moved to the front where a database title has it. */
    private val TRAILING_ARTICLE = Regex("""^(.+?), (The|A|An)\b(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * A title as compared: a No-Intro trailing article moved to the front, accents folded, case
     * dropped, every run of punctuation one space (dash versus colon).
     */
    internal fun normalizeTitle(value: String): String =
        java.text.Normalizer.normalize(TRAILING_ARTICLE.replace(value, "$2 $1$3"), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /**
     * Which of [titles] (a name search's results, in the API's order) is the game named [wanted], or null
     * when none is. An exact title wins; otherwise a title that is the other one plus further whole words
     * (a subtitle or version) counts, when the shared part is at least two words. One word is as likely
     * another game's whole title, and a blank description is better than another game's.
     */
    internal fun bestMatchIndex(titles: List<String>, wanted: String): Int? {
        val target = normalizeTitle(wanted)
        if (target.isEmpty()) return null
        var best: Int? = null
        for ((index, title) in titles.withIndex()) {
            val name = normalizeTitle(title)
            if (name == target) return index
            if (best != null) continue
            val (shorter, longer) = if (name.length <= target.length) name to target else target to name
            if (shorter.contains(' ') && longer.startsWith("$shorter ")) best = index
        }
        return best
    }

    /** Whether a search result's own platform id ([resultPlatform], blank when the API left it out) is the system searched. */
    internal fun onPlatform(resultPlatform: String, wanted: String): Boolean =
        resultPlatform.isBlank() || resultPlatform == wanted

    fun findMetadata(
        apiKey: String,
        cacheDir: File,
        thegamesdbSystemId: String,
        gameTitle: String,
    ): ScrapeLookup<TheGamesDbMetadata> {
        @Suppress("NAME_SHADOWING")
        val gameTitle = cleanSearchName(gameTitle)
        val developers = cachedReferenceList(apiKey, "/Developers", "developers", File(cacheDir, "thegamesdb_developers.json"))
        val publishers = cachedReferenceList(apiKey, "/Publishers", "publishers", File(cacheDir, "thegamesdb_publishers.json"))
        val genres = cachedReferenceList(apiKey, "/Genres", "genres", File(cacheDir, "thegamesdb_genres.json"))

        val searchUrl = URL(
            "$BASE_URL/Games/ByGameName?apikey=${URLEncoder.encode(apiKey, "UTF-8")}" +
                "&fields=players,publishers,genres,overview,release_date" +
                "&name=${URLEncoder.encode(gameTitle, "UTF-8")}" +
                "&filter%5Bplatform%5D=${URLEncoder.encode(thegamesdbSystemId, "UTF-8")}",
        )
        val searchConnection = (searchUrl.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        val searchStatus = searchConnection.responseCode
        if (searchStatus != 200) {
            return ScrapeRefusals.refused(SOURCE, searchConnection, searchStatus, listOf(apiKey), gameTitle)
        }
        val searchResponse = JSONObject(searchConnection.inputStream.bufferedReader().readText())
        val games = searchResponse.optJSONObject("data")?.optJSONArray("games") ?: return ScrapeLookup.NoMatch
        if (games.length() == 0) return ScrapeLookup.NoMatch
        // The API's fuzzy ordering puts unrelated games (a fan game) first, and
        // falling back to its first result wrote another game's description and
        // date onto the ROM (tracker#251). The response is ranked here, and a
        // response with no title that is the game is no match.
        // A result on another platform is another game whatever its title (the search's platform
        // filter is a request, the result's own platform is the fact): it is given no title, so it
        // can never be the match.
        val titles = (0 until games.length()).map { i ->
            val game = games.getJSONObject(i)
            if (onPlatform(game.optString("platform", ""), thegamesdbSystemId)) game.optString("game_title", "") else ""
        }
        val index = bestMatchIndex(titles, gameTitle) ?: return ScrapeLookup.NoMatch
        val game = games.getJSONObject(index)

        val gameId = game.optInt("id", -1)
        val name = game.optString("game_title", "").ifBlank { null }
        val description = game.optString("overview", "").ifBlank { null }
        val releaseDate = game.optString("release_date", "").ifBlank { null }?.let { parseReleaseDate(it) }
        val players = game.optInt("players", -1).takeIf { it >= 0 }?.toString()

        val developer = game.optJSONArray("developers")?.let { arr ->
            (0 until arr.length()).mapNotNull { developers[arr.optInt(it, -1)] }.joinToString(", ").ifBlank { null }
        }
        val publisher = game.optJSONArray("publishers")?.let { arr ->
            (0 until arr.length()).mapNotNull { publishers[arr.optInt(it, -1)] }.joinToString(", ").ifBlank { null }
        }
        val genre = game.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { genres[arr.optInt(it, -1)] }.joinToString(", ").ifBlank { null }
        }

        val coverUrl = if (gameId >= 0) fetchCoverUrl(apiKey, gameId) else null

        return ScrapeLookup.Found(TheGamesDbMetadata(
            name = name,
            description = description,
            developer = developer,
            publisher = publisher,
            genre = genre,
            releaseDate = releaseDate,
            players = players,
            coverUrl = coverUrl,
        ))
    }

    /**
     * Real, separate `/Games/Images` call (confirmed against real ES-DE
     * source: cover art is never returned by the initial `ByGameName`
     * search, only by this dedicated media endpoint) -- real response
     * nests a `base_url.large` prefix plus a per-game list of `{type,
     * side, filename}`; `type == "boxart" && side == "front"` is real
     * ES-DE's own front-cover selection.
     */
    private fun fetchCoverUrl(apiKey: String, gameId: Int): String? {
        val url = URL("$BASE_URL/Games/Images?apikey=${URLEncoder.encode(apiKey, "UTF-8")}&games_id=$gameId")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        if (connection.responseCode != 200) return null
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val data = response.optJSONObject("data") ?: return null
        val baseUrl = data.optJSONObject("base_url")?.optString("large", "")?.ifBlank { null } ?: return null
        val images = data.optJSONObject("images")?.optJSONArray(gameId.toString()) ?: return null
        for (i in 0 until images.length()) {
            val media = images.getJSONObject(i)
            if (media.optString("type") == "boxart" && media.optString("side") == "front") {
                val filename = media.optString("filename", "").ifBlank { null } ?: continue
                return baseUrl + filename
            }
        }
        return null
    }
}
