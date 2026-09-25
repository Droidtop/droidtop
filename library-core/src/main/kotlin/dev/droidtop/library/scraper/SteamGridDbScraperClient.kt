package dev.droidtop.library.scraper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One game SteamGridDB's search returned: its own id, its name, and the year when it has one. */
data class SteamGridDbGame(val id: Int, val name: String, val year: Int?)

/**
 * How SteamGridDB is asked about one game: by its own id, or by a store's
 * id for the game (`steam`, `gog`), which is an identity lookup rather than
 * a search -- the same thing [SteamStoreClient] is to a Steam game.
 */
data class SteamGridDbRef(val platform: String, val id: String) {
    companion object {
        fun game(id: Int) = SteamGridDbRef("game", id.toString())
        fun steam(appId: Int) = SteamGridDbRef("steam", appId.toString())
        fun gog(productId: String) = SteamGridDbRef("gog", productId)
    }
}

/** The best image of each kind SteamGridDB holds for one game; null where it holds none. */
data class SteamGridDbArt(val gridUrl: String?, val heroUrl: String?, val logoUrl: String?, val iconUrl: String?) {
    val isEmpty: Boolean get() = gridUrl == null && heroUrl == null && logoUrl == null && iconUrl == null
}

/**
 * droidtop's own SteamGridDB (steamgriddb.com) client: grids (covers),
 * heroes, logos and icons for PC and engine games (docs/SPEC.md 7h,
 * "PC games get PC-native sources").
 *
 * This is NOT the copy inside vendored gamenative
 * (`app/gamenative/utils/SteamGridDB.kt`), which takes the first search
 * hit by name, only fetches grids, heroes and logos for gamenative's own
 * custom-game screens, and reads a key from gamenative's own BuildConfig.
 * droidtop's scrape needs the three honest outcomes every source answers
 * in ([ScrapeLookup]), the user's own key, store-id lookups, and icons.
 *
 * It needs the USER's own key: SteamGridDB's API takes a bearer key that
 * belongs to an account (free, instant, at
 * steamgriddb.com/profile/preferences/api). droidtop ships none and
 * invents none; with none set the scrape says where to get one
 * ([ScraperReadiness]) and never calls this.
 *
 * The API, as its own official clients use it (github.com/SteamGridDB/
 * node-steamgriddb `src/index.ts`): base `https://www.steamgriddb.com/api/v2`,
 * `Authorization: Bearer <key>`; `GET /search/autocomplete/<term>` returns
 * games (`id`, `name`, `release_date` in Unix seconds, `verified`);
 * `GET /{grids,heroes,logos,icons}/<platform>/<id>` returns images sorted
 * best-first (`url`, `width`, `height`, `style`), `<platform>` being `game`
 * for SteamGridDB's own id or a store (`steam`, `gog`, `egs`, `origin`,
 * `uplay`, `bnet`). Every answer is `{"success": bool, "data": ...}`, and a
 * refusal is `{"success": false, "errors": ["..."]}` (a wrong key: HTTP
 * 401 "Invalid key format", checked live). The grid filter asks for the
 * portrait sizes only (600x900, 342x482, 660x930), because a grid is what
 * droidtop shows as a cover.
 */
object SteamGridDbScraperClient {

    const val SOURCE = "SteamGridDB"

    private const val BASE_URL = "https://www.steamgriddb.com/api/v2"
    private const val MAX_RESULTS = 10

    /**
     * Games whose name matches [title], best first as SteamGridDB orders
     * them. A list, not a best guess: the caller decides whether one is
     * certain enough to apply ([PcMatching]).
     */
    fun search(apiKey: String, title: String): ScrapeLookup<List<SteamGridDbGame>> {
        // A path segment, so a space is %20, never URLEncoder's "+".
        val term = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        return when (val answer = get(apiKey, "/search/autocomplete/$term", title)) {
            is ScrapeLookup.Found -> parseSearch(answer.value)
            ScrapeLookup.NoMatch -> ScrapeLookup.NoMatch
            is ScrapeLookup.Refused -> answer
        }
    }

    /**
     * The best grid, hero, logo and icon for one game. Four requests; the
     * first refusal ends it, since the rest would be refused the same way.
     * A game SteamGridDB holds no image of at all is a [ScrapeLookup.NoMatch].
     */
    fun art(apiKey: String, ref: SteamGridDbRef): ScrapeLookup<SteamGridDbArt> {
        val subject = "${ref.platform} ${ref.id}"
        fun first(kind: String, query: String): ScrapeLookup<String?> =
            when (val answer = get(apiKey, "/$kind/${ref.platform}/${ref.id}?$query", subject)) {
                is ScrapeLookup.Found -> ScrapeLookup.Found(firstImageUrl(answer.value))
                ScrapeLookup.NoMatch -> ScrapeLookup.Found(null)
                is ScrapeLookup.Refused -> answer
            }
        val grid = when (val r = first("grids", "dimensions=600x900,342x482,660x930&types=static")) {
            is ScrapeLookup.Refused -> return r
            else -> r.foundOrNull
        }
        val hero = when (val r = first("heroes", "types=static")) {
            is ScrapeLookup.Refused -> return r
            else -> r.foundOrNull
        }
        val logo = when (val r = first("logos", "types=static&mimes=image/png")) {
            is ScrapeLookup.Refused -> return r
            else -> r.foundOrNull
        }
        val icon = when (val r = first("icons", "types=static&mimes=image/png")) {
            is ScrapeLookup.Refused -> return r
            else -> r.foundOrNull
        }
        val art = SteamGridDbArt(grid, hero, logo, icon)
        return if (art.isEmpty) ScrapeLookup.NoMatch else ScrapeLookup.Found(art)
    }

    /**
     * One GET. A 404 is SteamGridDB saying it has no game under that id,
     * which is a real miss, not a refusal; every other non-200 is a
     * refusal carrying the server's own sentence, and so is a 200 whose
     * body says `"success": false`.
     */
    private fun get(apiKey: String, path: String, subject: String): ScrapeLookup<JSONObject> {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val status = connection.responseCode
        if (status == 404) {
            android.util.Log.i("droidtop.Scraper", "$SOURCE has nothing under $subject (HTTP 404)")
            return ScrapeLookup.NoMatch
        }
        if (status != 200) return ScrapeRefusals.refused(SOURCE, connection, status, listOf(apiKey), subject)
        val body = JSONObject(connection.inputStream.bufferedReader().readText())
        return unwrap(body)
    }

    /** Pure, for the JVM tests: the `data` of a successful answer, or the refusal a `success: false` body is. */
    internal fun unwrap(body: JSONObject): ScrapeLookup<JSONObject> {
        if (!body.optBoolean("success", false)) {
            val reason = body.optJSONArray("errors")?.let { errors ->
                (0 until errors.length()).mapNotNull { errors.optString(it, "").ifBlank { null } }.joinToString("; ")
            }?.ifBlank { null }
            return ScrapeLookup.Refused(SOURCE, 200, reason)
        }
        return ScrapeLookup.Found(body)
    }

    /** Pure, for the JVM tests. */
    internal fun parseSearch(body: JSONObject): ScrapeLookup<List<SteamGridDbGame>> {
        val data = body.optJSONArray("data") ?: return ScrapeLookup.NoMatch
        val games = (0 until minOf(data.length(), MAX_RESULTS)).mapNotNull { index ->
            val row = data.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val name = row.optString("name", "").ifBlank { null } ?: return@mapNotNull null
            // Unix seconds; only the year is used, and only to help a
            // person tell two candidates apart. Nothing writes it as a date.
            val year = row.optLong("release_date", 0L).takeIf { it > 0 }?.let { seconds ->
                java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                    .apply { timeInMillis = seconds * 1000 }
                    .get(java.util.Calendar.YEAR)
            }
            SteamGridDbGame(id, name, year)
        }
        return if (games.isEmpty()) ScrapeLookup.NoMatch else ScrapeLookup.Found(games)
    }

    /** Pure, for the JVM tests: the first image's URL in an image list answer, best-first as the server sorts. */
    internal fun firstImageUrl(body: JSONObject): String? {
        val data = body.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val url = data.optJSONObject(i)?.optString("url", "")?.ifBlank { null }
            if (url != null) return url
        }
        return null
    }
}
