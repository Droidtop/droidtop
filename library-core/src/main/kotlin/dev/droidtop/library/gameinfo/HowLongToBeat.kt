package dev.droidtop.library.gameinfo

import android.content.Context
import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.ScrapeRefusals
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One game of HowLongToBeat, with the community's times in seconds (0 where nobody has reported one). */
data class HltbGame(
    val id: Int,
    val name: String,
    val alias: String,
    val mainSeconds: Int,
    val extraSeconds: Int,
    val completionistSeconds: Int,
    val allSeconds: Int,
) {
    val pageUrl: String get() = "https://howlongtobeat.com/game/$id"
}

/** What a play-time lookup came to. */
sealed interface HltbResult {
    /** The lookups are turned off in Settings. */
    data object Off : HltbResult

    data object NotFound : HltbResult

    data class Failed(val message: String) : HltbResult

    /** [exact] is false when HowLongToBeat's title only comes close to the library's; the screen says so. */
    data class Found(val game: HltbGame, val exact: Boolean) : HltbResult
}

/**
 * HowLongToBeat's public site search (docs/SPEC.md 7h, "Game info"). The site has no published API; the request
 * shape is the one the public client libraries document (howlongtobeatpy, MIT): a GET of `api/s/init?t=<ms>`
 * answers a token and a key/value pair, then a POST of the search to `api/s/` carries them as the `x-auth-token`,
 * `x-hp-key` and `x-hp-val` headers and again as a field of the body. Two requests, sent only when a game's own
 * page opens and nothing is cached for its title, with an honest user agent; if the site changes the shape the
 * row says it could not be reached and nothing else is affected.
 */
internal object HltbClient {
    const val SOURCE = "HowLongToBeat"
    private const val BASE = "https://howlongtobeat.com/"
    private const val SEARCH_PATH = "api/s/"
    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "droidtop (Android game launcher; https://github.com/Droidtop/droidtop)"

    class Auth(val token: String, val key: String, val value: String)

    fun search(title: String): ScrapeLookup<List<HltbGame>> {
        val auth = token() ?: return ScrapeLookup.Refused(SOURCE, 0, "no search token")
        val connection = open(BASE + SEARCH_PATH, "POST").apply {
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-auth-token", auth.token)
            setRequestProperty("x-hp-key", auth.key)
            setRequestProperty("x-hp-val", auth.value)
            doOutput = true
        }
        return try {
            connection.outputStream.use { it.write(payload(title, auth).toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 200) {
                ScrapeLookup.Found(parseResults(connection.inputStream.bufferedReader().use { it.readText().take(MAX_CHARS) }))
            } else {
                ScrapeRefusals.refused(SOURCE, connection, status, emptyList(), "search")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun token(): Auth? {
        val connection = open(BASE + SEARCH_PATH + "init?t=" + System.currentTimeMillis(), "GET")
        return try {
            if (connection.responseCode != 200) null else parseToken(connection.inputStream.bufferedReader().use { it.readText().take(MAX_CHARS) })
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String, method: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", BASE)
            setRequestProperty("Origin", BASE.trimEnd('/'))
            setRequestProperty("accept", "*/*")
        }

    /** Pure, for the JVM tests: the token answer carries `token` and one field with "key" and one with "val" in its name. */
    fun parseToken(text: String): Auth? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val token = json.optString("token").ifBlank { return null }
        var key: String? = null
        var value: String? = null
        val names = json.keys()
        while (names.hasNext()) {
            val name = names.next()
            val lower = name.lowercase()
            if ("key" in lower) key = json.optString(name) else if ("val" in lower) value = json.optString(name)
        }
        return if (key == null || value == null) null else Auth(token, key, value)
    }

    /** Pure, for the JVM tests: the body the site's own search form sends, the terms being the title's words. */
    fun payload(title: String, auth: Auth): String {
        val games = JSONObject()
            .put("userId", 0).put("platform", "").put("sortCategory", "popular").put("rangeCategory", "main")
            .put("rangeTime", JSONObject().put("min", 0).put("max", 0))
            .put("gameplay", JSONObject().put("perspective", "").put("flow", "").put("genre", "").put("difficulty", ""))
            .put("rangeYear", JSONObject().put("max", "").put("min", ""))
            .put("modifier", "")
        return JSONObject()
            .put("searchType", "games")
            .put("searchTerms", JSONArray(title.split(Regex("\\s+")).filter { it.isNotEmpty() }))
            .put("searchPage", 1)
            .put("size", 20)
            .put(
                "searchOptions",
                JSONObject()
                    .put("games", games)
                    .put("users", JSONObject().put("sortCategory", "postcount"))
                    .put("lists", JSONObject().put("sortCategory", "follows"))
                    .put("filter", "")
                    .put("sort", 0)
                    .put("randomizer", 0),
            )
            .put("useCache", true)
            .put(auth.key, auth.value)
            .toString()
    }

    /** Pure, for the JVM tests: `data[]` with `comp_main`, `comp_plus`, `comp_100` and `comp_all` in seconds. */
    fun parseResults(text: String): List<HltbGame> {
        val data = runCatching { JSONObject(text).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let { parseGame(it) } }
    }

    fun parseGame(row: JSONObject): HltbGame? {
        val id = row.optInt("game_id", 0)
        val name = row.optString("game_name")
        if (id <= 0 || name.isBlank()) return null
        return HltbGame(
            id = id,
            name = name,
            alias = row.optString("game_alias"),
            mainSeconds = row.optInt("comp_main", 0),
            extraSeconds = row.optInt("comp_plus", 0),
            completionistSeconds = row.optInt("comp_100", 0),
            allSeconds = row.optInt("comp_all", 0),
        )
    }

    private const val MAX_CHARS = 2 * 1024 * 1024
}

/** Choosing the game among a search's answers: the title, or its alias, must be the library's title. */
internal object HltbMatch {

    /** The first game (they come most popular first) whose name or alias normalises to [title]'s; null when none does. */
    fun best(title: String, games: List<HltbGame>): Pair<HltbGame, Boolean>? {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return null
        games.firstOrNull { normalize(it.name) == wanted || (it.alias.isNotBlank() && it.alias.split(',', ';').any { alias -> normalize(alias) == wanted }) }
            ?.let { return it to true }
        // Close, never guessed: the same words in another order or with one extra word, with the same numbers.
        val words = tokens(title)
        val close = games.filter { game ->
            val other = tokens(game.name)
            other.isNotEmpty() && words.isNotEmpty() &&
                numbers(other) == numbers(words) &&
                (other.intersect(words).size.toDouble() / other.union(words).size.toDouble()) >= 0.8
        }
        return close.firstOrNull()?.let { it to false }
    }

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ").replace("&", " and ").filter { it.isLetterOrDigit() }

    private fun tokens(text: String): Set<String> =
        text.lowercase().replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ").replace("&", " and ")
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it != "the" }.toSet()

    private fun numbers(words: Set<String>): Set<String> = words.filter { w -> w.all { it.isDigit() } }.toSet()
}

/**
 * Play times for a library game, cached: a found game for thirty days, "not on HowLongToBeat" for a week, a
 * failure for an hour, so opening a page again does not ask again. Blocking, with file and network work: called
 * off the main thread and only for a game's own page (never while a list draws).
 */
object HowLongToBeat {
    private const val FOUND_TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val MISSING_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val FAILED_TTL_MS = 60L * 60 * 1000

    fun lookup(context: Context, title: String, now: Long = System.currentTimeMillis()): HltbResult {
        if (!GameInfoPrefs.lookupsOn(context)) return HltbResult.Off
        val wanted = HltbMatch.normalize(title)
        if (wanted.isEmpty()) return HltbResult.NotFound
        val directory = File(context.cacheDir, "gameinfo/hltb").also { it.mkdirs() }
        val file = File(directory, "${wanted.take(80)}.json")
        readCache(file, now)?.let { return it }
        return try {
            when (val answer = HltbClient.search(title)) {
                is ScrapeLookup.Found -> {
                    val match = HltbMatch.best(title, answer.value)
                    val result = if (match == null) HltbResult.NotFound else HltbResult.Found(match.first, match.second)
                    writeCache(file, result, now)
                    result
                }
                ScrapeLookup.NoMatch -> HltbResult.NotFound
                is ScrapeLookup.Refused -> failed(file, now, "HowLongToBeat did not answer the search (HTTP ${answer.httpStatus})")
            }
        } catch (e: IOException) {
            failed(file, now, "No connection to HowLongToBeat")
        }
    }

    private fun failed(file: File, now: Long, message: String): HltbResult {
        val result = HltbResult.Failed(message)
        writeCache(file, result, now)
        return result
    }

    private fun writeCache(file: File, result: HltbResult, now: Long) {
        val json = JSONObject().put("at", now)
        when (result) {
            is HltbResult.Found -> json.put("state", "found").put("exact", result.exact).put(
                "game",
                JSONObject().put("game_id", result.game.id).put("game_name", result.game.name).put("game_alias", result.game.alias)
                    .put("comp_main", result.game.mainSeconds).put("comp_plus", result.game.extraSeconds)
                    .put("comp_100", result.game.completionistSeconds).put("comp_all", result.game.allSeconds),
            )
            HltbResult.NotFound -> json.put("state", "missing")
            is HltbResult.Failed -> json.put("state", "failed").put("message", result.message)
            HltbResult.Off -> return
        }
        runCatching { file.writeText(json.toString()) }
    }

    private fun readCache(file: File, now: Long): HltbResult? {
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val age = now - json.optLong("at", 0L)
        return when (json.optString("state")) {
            "found" -> {
                if (age >= FOUND_TTL_MS) return null
                val game = json.optJSONObject("game")?.let { HltbClient.parseGame(it) } ?: return null
                HltbResult.Found(game, json.optBoolean("exact", true))
            }
            "missing" -> if (age < MISSING_TTL_MS) HltbResult.NotFound else null
            "failed" -> if (age < FAILED_TTL_MS) HltbResult.Failed(json.optString("message")) else null
            else -> null
        }
    }

    /** "12 h", "12.5 h", "45 min": the way a time reads on a row. */
    fun formatTime(seconds: Int): String {
        if (seconds < 3600) return "${maxOf(1, (seconds + 30) / 60)} min"
        val tenths = (seconds / 360.0).let { Math.round(it).toInt() }
        return if (tenths % 10 == 0) "${tenths / 10} h" else "${tenths / 10}.${tenths % 10} h"
    }

    /** The row's value: the times that exist, "Main 12 h, extras 15 h, all of it 24 h". */
    fun summary(game: HltbGame): String = listOfNotNull(
        game.mainSeconds.takeIf { it > 0 }?.let { "Main ${formatTime(it)}" },
        game.extraSeconds.takeIf { it > 0 }?.let { "extras ${formatTime(it)}" },
        game.completionistSeconds.takeIf { it > 0 }?.let { "all of it ${formatTime(it)}" },
    ).joinToString(", ")
}
