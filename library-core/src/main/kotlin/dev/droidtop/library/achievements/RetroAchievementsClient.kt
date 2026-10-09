package dev.droidtop.library.achievements

import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.ScrapeRefusals
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One game of RetroAchievements' list for a console, with the file hashes it is known by. */
data class RaGame(
    val id: Int,
    val title: String,
    val consoleId: Int,
    val numAchievements: Int,
    val points: Int,
    val hashes: List<String>,
)

/** One achievement of a game, with the person's own earn dates (null when not earned). */
data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgeName: String,
    val displayOrder: Int,
    val earned: String?,
    val earnedHardcore: String?,
)

/** A game's achievements and the signed-in person's progress through them. */
data class RaProgress(
    val gameId: Int,
    val title: String,
    val achievements: List<RaAchievement>,
) {
    val total: Int get() = achievements.size
    val earned: Int get() = achievements.count { it.earned != null || it.earnedHardcore != null }
    val earnedHardcore: Int get() = achievements.count { it.earnedHardcore != null }
    val pointsTotal: Int get() = achievements.sumOf { it.points }
    val pointsEarned: Int get() = achievements.filter { it.earned != null || it.earnedHardcore != null }.sumOf { it.points }
}

/**
 * RetroAchievements' public web API (docs/SPEC.md 7h, "RetroAchievements"), the person's own account: their
 * username and their own Web API key, which every request carries as `y`. Request shapes are those of
 * api-docs.retroachievements.org (API_GetUserProfile, API_GetGameList, API_GetGameInfoAndUserProgress).
 * Every call is made off the main thread by the caller and only ever on a game's page or a key test, never while
 * a list draws. droidtop ships no RetroAchievements key of its own: this API takes none but the person's.
 */
object RetroAchievementsClient {

    const val SOURCE = "RetroAchievements"
    private const val BASE = "https://retroachievements.org/API/"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_CHARS = 24 * 1024 * 1024

    /** The key test: the person's own profile. Found when the username and key are accepted. */
    fun profile(user: String, apiKey: String): ScrapeLookup<String> {
        val text = when (val answer = get("API_GetUserProfile.php", apiKey, "u" to user)) {
            is Answer.Body -> answer.text
            is Answer.Failed -> return answer.refusal
        }
        val name = runCatching { JSONObject(text).optString("User") }.getOrDefault("")
        return if (name.isBlank()) ScrapeLookup.NoMatch else ScrapeLookup.Found(name)
    }

    /** Every game of a console that has achievements, with its hashes (one request per console, cached by the caller). */
    fun gameList(apiKey: String, consoleId: Int): ScrapeLookup<List<RaGame>> =
        when (val answer = get("API_GetGameList.php", apiKey, "i" to consoleId.toString(), "h" to "1", "f" to "1")) {
            is Answer.Body -> ScrapeLookup.Found(parseGameList(answer.text))
            is Answer.Failed -> answer.refusal
        }

    /** One game's achievements with [user]'s progress, as the service wrote it (the caller caches that text and reads it with [parseProgress]). */
    fun progressText(user: String, apiKey: String, gameId: Int): ScrapeLookup<String> =
        when (val answer = get("API_GetGameInfoAndUserProgress.php", apiKey, "u" to user, "g" to gameId.toString())) {
            is Answer.Body -> ScrapeLookup.Found(answer.text)
            is Answer.Failed -> answer.refusal
        }

    /** The page on retroachievements.org for a game. */
    fun gameUrl(gameId: Int): String = "https://retroachievements.org/game/$gameId"

    /** Pure, for the JVM tests. */
    internal fun parseGameList(text: String): List<RaGame> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val id = row.optInt("ID", 0)
                val title = row.optString("Title")
                if (id <= 0 || title.isBlank()) continue
                val hashes = row.optJSONArray("Hashes")
                add(
                    RaGame(
                        id = id,
                        title = title,
                        consoleId = row.optInt("ConsoleID", 0),
                        numAchievements = row.optInt("NumAchievements", 0),
                        points = row.optInt("Points", 0),
                        hashes = if (hashes == null) emptyList() else (0 until hashes.length()).map { hashes.optString(it).lowercase() },
                    ),
                )
            }
        }
    }

    /** Pure, for the JVM tests. Null when the answer names no game. */
    internal fun parseProgress(text: String): RaProgress? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = root.optInt("ID", 0)
        if (id <= 0) return null
        val map = root.optJSONObject("Achievements")
        val list = buildList {
            if (map != null) {
                val keys = map.keys()
                while (keys.hasNext()) {
                    val row = map.optJSONObject(keys.next()) ?: continue
                    add(
                        RaAchievement(
                            id = row.optInt("ID", 0),
                            title = row.optString("Title"),
                            description = row.optString("Description"),
                            points = row.optInt("Points", 0),
                            badgeName = row.optString("BadgeName"),
                            displayOrder = row.optInt("DisplayOrder", 0),
                            earned = row.optString("DateEarned").ifBlank { null },
                            earnedHardcore = row.optString("DateEarnedHardcore").ifBlank { null },
                        ),
                    )
                }
            }
        }.sortedWith(compareBy({ it.displayOrder }, { it.id }))
        return RaProgress(id, root.optString("Title"), list)
    }

    private sealed interface Answer {
        class Body(val text: String) : Answer
        class Failed(val refusal: ScrapeLookup.Refused) : Answer
    }

    private fun get(endpoint: String, apiKey: String, vararg query: Pair<String, String>): Answer {
        val params = (query.toList() + ("y" to apiKey)).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = (URL("$BASE$endpoint?$params").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            if (status == 200) {
                Answer.Body(connection.inputStream.bufferedReader().use { it.readText().take(MAX_CHARS) })
            } else {
                Answer.Failed(ScrapeRefusals.refused(SOURCE, connection, status, listOf(apiKey), endpoint))
            }
        } finally {
            connection.disconnect()
        }
    }
}
