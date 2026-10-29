package dev.droidtop.library.gameinfo

import android.content.Context
import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.ScrapeRefusals
import dev.droidtop.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
 * `x-hp-key` and `x-hp-val` headers and again as a field of the body. Sent only when a game's own page opens and
 * nothing is cached for its title, with an honest user agent; if the site changes the shape the row says it could
 * not be reached and nothing else is affected.
 *
 * The search path is not fixed: the site has renamed it (`api/s`, `api/search/site`, `api/finder/v2`), and the
 * library above reads it from the site's own script, where the search is the one `fetch` that POSTs. So the path
 * is found the same way, once per run of the app, and `api/s/` is only what is tried when the script says nothing.
 */
internal object HltbClient {
    const val SOURCE = "HowLongToBeat"
    private const val BASE = "https://howlongtobeat.com/"
    private const val FALLBACK_PATH = "api/s/"
    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "droidtop (Android game launcher; https://github.com/Droidtop/droidtop)"

    /** At most this many of the home page's scripts are read for the search path. */
    private const val MAX_SCRIPTS = 24
    private const val MAX_SCRIPT_BYTES = 4L * 1024 * 1024

    @Volatile
    private var foundPath: String? = null

    class Auth(val token: String, val key: String, val value: String)

    fun search(title: String): ScrapeLookup<List<HltbGame>> {
        var note = ""
        val path = foundPath ?: discoverPath().let { found -> note = found.second; found.first?.also { foundPath = it } } ?: FALLBACK_PATH
        val (auth, why) = token(path)
        if (auth == null) {
            // The path may have moved since it was found: look again next time.
            foundPath = null
            return ScrapeLookup.Refused(SOURCE, 0, listOf(note, why).filter { it.isNotBlank() }.joinToString("; "))
        }
        var answer = post(path, title, auth)
        if (answer.status == 403) {
            // A token the site no longer takes (it expires): one new token and one more try, no more.
            val (fresh, freshWhy) = token(path)
            if (fresh == null) return ScrapeLookup.Refused(SOURCE, 0, "the search answered HTTP 403 and a new token failed: $freshWhy")
            answer = post(path, title, fresh)
        }
        return if (answer.status == 200) {
            ScrapeLookup.Found(parseResults(answer.text()))
        } else {
            ScrapeRefusals.refused(SOURCE, answer, emptyList(), "search")
        }
    }

    /** The search, with the token in `x-auth-token` and its key and value as headers and in the body. */
    private fun post(path: String, title: String, auth: Auth): Http.Response = send(
        BASE + path,
        "POST",
        mapOf(
            "content-type" to "application/json",
            "x-auth-token" to auth.token,
            "x-hp-key" to auth.key,
            "x-hp-val" to auth.value,
        ),
        payload(title, auth).toByteArray(Charsets.UTF_8),
    )

    /** The token, or why there is none: the address asked and what it answered, for the row and the log. */
    private fun token(path: String): Pair<Auth?, String> {
        val address = path.trimEnd('/') + "/init"
        // As the page's own script asks it: ?t=<epoch ms>, with the site's Referer (and Origin) that send() adds.
        val answer = send(BASE + address + "?t=" + System.currentTimeMillis(), "GET", emptyMap(), null)
        val body = answer.text()
        val auth = if (answer.status != 200) null else parseToken(body)
        if (auth != null) return auth to ""
        val why = tokenFailure(address, answer.status, body)
        android.util.Log.w("droidtop.Scraper", "$SOURCE search token: $why")
        return null to why
    }

    /**
     * Pure, for the JVM tests: why a token request gave none, with its HTTP status always in it. A refusal carries the
     * server's own sentence (bounded and with markup stripped); a 200 that holds no token names the fields it did hold,
     * never their values.
     */
    fun tokenFailure(address: String, status: Int, body: String): String {
        if (status != 200) {
            val said = ScrapeRefusals.summarizeErrorBody(body.take(512), emptyList())
            return "$address answered HTTP $status" + (said?.let { ": $it" } ?: "")
        }
        val fields = runCatching { JSONObject(body).keys().asSequence().toList() }.getOrNull()
        return "$address answered HTTP 200 but gave no token (" +
            (if (fields == null) "not JSON" else "fields: " + fields.joinToString().ifEmpty { "none" }) + ")"
    }

    /**
     * The search path the site's own scripts use, and a note on how it was found or why not. Every script is read until
     * one holds a call that looks like the search; a script's other POSTs (the site reports its errors by one) do not
     * end the search.
     */
    private fun discoverPath(): Pair<String?, String> {
        val home = send(BASE, "GET", emptyMap(), null)
        if (home.status != 200) {
            val note = "the home page answered HTTP ${home.status}"
            android.util.Log.w("droidtop.Scraper", "$SOURCE search path: $note")
            return null to note
        }
        val seen = linkedSetOf<String>()
        var read = 0
        for (script in scriptSources(home.text()).take(MAX_SCRIPTS)) {
            val text = runCatching { send(script, "GET", emptyMap(), null, MAX_SCRIPT_BYTES) }.getOrNull()
                ?.takeIf { it.status == 200 }?.text() ?: continue
            read++
            val calls = searchCallsIn(text)
            seen += calls
            bestSearchPath(calls)?.let { return it to "" }
        }
        val note = "$read scripts read, POSTs seen: ${seen.joinToString().ifEmpty { "none" }}"
        android.util.Log.w("droidtop.Scraper", "$SOURCE search path: $note; trying $FALLBACK_PATH")
        return null to note
    }

    /** HowLongToBeat wants an honest user agent of ours and the site's own Referer and Origin. */
    private fun send(address: String, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Long = MAX_BYTES): Http.Response =
        Http.request(
            address,
            method = method,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to BASE,
                "Origin" to BASE.trimEnd('/'),
                "accept" to "*/*",
            ) + headers,
            body = body,
            timeouts = Http.Timeouts(connectMs = TIMEOUT_MS, readMs = TIMEOUT_MS),
            maxBytes = maxBytes,
        )

    private val SCRIPT_TAG = Regex("""<script[^>]+src=["']([^"']*/_next/static/chunks/[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val FETCH_START = Regex("""fetch\s*\(\s*["']/(api/[a-zA-Z0-9_/]+)[^"']*["']\s*,""", RegexOption.IGNORE_CASE)
    private val POST_METHOD = Regex("""method\s*:\s*["']POST["']""", RegexOption.IGNORE_CASE)

    /** How far past a `fetch(` its options are looked at for the method (never into the next `fetch(`). */
    private const val OPTIONS_WINDOW = 400

    /** Pure, for the JVM tests: the addresses of the page's chunk scripts, on howlongtobeat.com only. */
    fun scriptSources(html: String): List<String> =
        SCRIPT_TAG.findAll(html).map { it.groupValues[1] }.mapNotNull { src ->
            when {
                src.startsWith(BASE) -> src
                src.startsWith("/") && !src.startsWith("//") -> BASE + src.removePrefix("/")
                else -> null
            }
        }.distinct().toList()

    /** Pure, for the JVM tests: the path of every `fetch("/api/...", {method: "POST", ...})` in [script], such as `api/finder/v2`. */
    fun searchCallsIn(script: String): List<String> =
        FETCH_START.findAll(script).filter { start ->
            val from = start.range.last + 1
            val next = script.indexOf("fetch(", from).let { if (it < 0) script.length else it }
            POST_METHOD.containsMatchIn(script.substring(from, minOf(next, from + OPTIONS_WINDOW, script.length)))
        }.map { it.groupValues[1].trimEnd('/') }.distinct().toList()

    /** Segments of a path that name a call other than the search: the site reports errors and manages accounts by POST. */
    private val NOT_SEARCH = setOf("error", "errors", "user", "users", "logout", "login", "log", "auth", "register", "signup", "session", "report", "contact", "submit", "vote", "analytics", "events", "telemetry")

    /** Segments that name the search (the site has called it `s`, `search/site`, `find` and `finder/v2`). */
    private val SEARCH_WORDS = setOf("s", "search", "find", "finder", "lookup", "query")

    /**
     * Pure, for the JVM tests: which of the POSTs in the site's scripts is the search: the first whose path has a
     * segment that names a search and none that names something else, or null when none does. The first POST at all
     * would be the error report.
     */
    fun bestSearchPath(calls: List<String>): String? = calls.firstOrNull { call ->
        val segments = call.removePrefix("api/").lowercase().split('/')
        segments.none { it in NOT_SEARCH } && segments.any { it in SEARCH_WORDS }
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

    private const val MAX_BYTES = 2L * 1024 * 1024
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
                is ScrapeLookup.Refused -> failed(file, now, refusalMessage(answer.httpStatus, answer.reason))
            }
        } catch (e: IOException) {
            failed(file, now, "No connection to HowLongToBeat")
        }
    }

    /**
     * Status 0 is no answer at all (no search token), which has no number to show a person; what failed goes in the
     * row instead, because a log line is not always read.
     */
    internal fun refusalMessage(httpStatus: Int, reason: String? = null): String =
        if (httpStatus > 0) "HowLongToBeat did not answer the search (HTTP $httpStatus" + (reason?.takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" } ?: "") + ")"
        else "HowLongToBeat did not give a way to search" + (reason?.takeIf { it.isNotBlank() }?.let { " (${it.take(160)})" } ?: "")

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
