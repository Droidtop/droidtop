package dev.droidtop.library.scraper

import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

/** One developer post about a Steam game: its title, when it was posted, a plain-text start of it, and its page. */
data class SteamNewsItem(
    val title: String,
    val postedAtMillis: Long,
    val excerpt: String,
    val url: String?,
    /** Whether the developer tagged it as patch notes. */
    val patchNotes: Boolean,
)

/**
 * "What's new" for a Steam game (docs/SPEC.md 7i, Droidtop/tracker#229):
 * the developer's own announcements from Steam's public news endpoint
 * (`ISteamNews/GetNewsForApp/v2`, no key, no account), the latest post
 * tagged as patch notes first, else the latest announcement. Read-only and
 * asked for, like ProtonDB: nothing is fetched until the person selects the
 * row, and the full history is Steam's own news page for the app.
 */
object SteamNewsClient {

    /** Steam's news page for the app: every post, newest first. */
    fun pageUrl(appId: Long): String = "https://store.steampowered.com/news/app/$appId"

    /** Network: never on the main thread. */
    fun latest(appId: Long): ScrapeLookup<SteamNewsItem> {
        val url = URL(
            "https://api.steampowered.com/ISteamNews/GetNewsForApp/v2/" +
                "?appid=$appId&count=$COUNT&maxlength=$EXCERPT_CHARS&feeds=steam_community_announcements&format=json",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            when (val status = connection.responseCode) {
                200 -> parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText().take(MAX_CHARS) }))
                // Steam answers an app it does not know with 403 or 404.
                403, 404 -> ScrapeLookup.NoMatch
                else -> ScrapeRefusals.refused("Steam news", connection, status, emptyList(), "app $appId")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Pure, for the JVM tests: the post to show from Steam's answer. */
    internal fun parse(response: JSONObject): ScrapeLookup<SteamNewsItem> {
        val items = response.optJSONObject("appnews")?.optJSONArray("newsitems") ?: return ScrapeLookup.NoMatch
        val posts = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim().ifEmpty { return@mapNotNull null }
            val tags = item.optJSONArray("tags")
            val patchNotes = tags != null && (0 until tags.length()).any { tags.optString(it) == "patchnotes" }
            SteamNewsItem(
                title = title,
                postedAtMillis = item.optLong("date", 0L) * 1000L,
                excerpt = plainText(item.optString("contents")),
                url = item.optString("url").takeIf { it.startsWith("https://") },
                patchNotes = patchNotes,
            )
        }
        val chosen = posts.filter { it.patchNotes }.maxByOrNull { it.postedAtMillis } ?: posts.maxByOrNull { it.postedAtMillis }
        return chosen?.let { ScrapeLookup.Found(it) } ?: ScrapeLookup.NoMatch
    }

    /** Steam's BBCode and the odd HTML tag dropped, Steam's image placeholders gone, whitespace folded. Pure. */
    internal fun plainText(contents: String): String = contents
        .replace(Regex("""\{STEAM_CLAN_IMAGE\}\S*"""), " ")
        .replace(Regex("""\[/?[a-zA-Z0-9*]+(=[^\]]*)?]"""), " ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private const val COUNT = 10
    private const val EXCERPT_CHARS = 280
    private const val TIMEOUT_MS = 15_000
    private const val MAX_CHARS = 256 * 1024
}

/** "Patch notes, 3 Oct 2026" or "News, 3 Oct 2026"; the kind alone when Steam gave no date. */
fun SteamNewsItem.kindAndDate(format: DateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)): String {
    val kind = if (patchNotes) "Patch notes" else "News"
    return if (postedAtMillis > 0) "$kind, ${format.format(Date(postedAtMillis))}" else kind
}
