package dev.droidtop.library.scraper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Steam's own public storefront record for one app, read by app id
 * (docs/SPEC.md section 7h, "PC and engine games").
 *
 * This is an IDENTITY lookup, not a title search: a Steam entry's id is
 * `steam:<appid>`, and the storefront's `appdetails` endpoint answers for
 * exactly that app. It is to a Steam game what a file hash is to a ROM --
 * the match is certain by construction, so it is applied without a picker
 * and counted apart from name matches.
 *
 * Keyless: `GET https://store.steampowered.com/api/appdetails?appids=<id>`
 * is the endpoint Steam's own store pages use, and it needs no account.
 * Its response is `{"<id>": {"success": bool, "data": {...}}}`; `success`
 * false is Steam saying it has no public store page for that id (a
 * delisted game, a tool, a private app), which is a real miss. The
 * storefront rate-limits by address (roughly 200 requests per five
 * minutes); a 429 comes back as [ScrapeLookup.Refused] with Steam's own
 * body, and the scrape's refusal rule ends the pass.
 */
object SteamStoreClient {

    private const val SOURCE = "Steam store"

    /** `steam:<appid>` from an entry id or a [dev.droidtop.library.PcInfo.storeId], or null for anything else. */
    fun appIdOf(storeId: String?): Int? =
        storeId?.takeIf { it.startsWith("steam:") }?.removePrefix("steam:")?.toIntOrNull()

    fun appDetails(appId: Int): ScrapeLookup<PcMatch> {
        val url = URL("https://store.steampowered.com/api/appdetails?appids=$appId&l=english")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        val status = connection.responseCode
        if (status != 200) return ScrapeRefusals.refused(SOURCE, connection, status, emptyList(), "app $appId")
        return parse(appId, JSONObject(connection.inputStream.bufferedReader().readText()))
    }

    /** Pure, so the JVM tests exercise it against captured response shapes. */
    internal fun parse(appId: Int, response: JSONObject): ScrapeLookup<PcMatch> {
        val app = response.optJSONObject(appId.toString()) ?: return ScrapeLookup.NoMatch
        if (!app.optBoolean("success", false)) return ScrapeLookup.NoMatch
        val data = app.optJSONObject("data") ?: return ScrapeLookup.NoMatch
        val name = data.optString("name", "").ifBlank { null } ?: return ScrapeLookup.NoMatch
        fun names(key: String, field: String? = null): String? = data.optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val value = if (field == null) array.optString(i, "") else array.optJSONObject(i)?.optString(field, "")
                value?.trim()?.ifBlank { null }
            }.joinToString(", ").ifBlank { null }
        }
        val releaseDate = data.optJSONObject("release_date")
            ?.takeUnless { it.optBoolean("coming_soon", false) }
            ?.optString("date", "")
            ?.let { parseReleaseDate(it) }
        return ScrapeLookup.Found(
            PcMatch(
                name = name,
                sourceLabel = SOURCE,
                year = releaseDate?.take(4)?.toIntOrNull(),
                // The portrait library capsule is the cover a shelf of
                // games is drawn with. Not every app has one on the legacy
                // path, so the landscape header the record itself names
                // is the second choice, tried only if the first fails.
                coverUrl = "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900.jpg",
                alternateCoverUrl = data.optString("header_image", "").ifBlank { null },
                description = data.optString("short_description", "").ifBlank { null }?.let { decodeEntities(it) },
                developer = names("developers"),
                publisher = names("publishers"),
                genre = names("genres", "description"),
                releaseDate = releaseDate,
            ),
        )
    }

    /**
     * Steam writes the date for the store's region even with `l=english`:
     * "Oct 10, 2007" or "10 Oct, 2007". Anything else ("Q1 2025", "Coming
     * soon", "2007") is not a full date, and a year alone is never widened
     * into an invented January 1st -- the same rule the Lutris path keeps.
     */
    internal fun parseReleaseDate(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        for (pattern in listOf("MMM d, yyyy", "d MMM, yyyy")) {
            val input = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = runCatching { input.parse(text) }.getOrNull() ?: continue
            val output = SimpleDateFormat("yyyyMMdd'T'000000", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return output.format(parsed)
        }
        return null
    }

    // The storefront's short description is plain text with HTML entities
    // left in ("&quot;", "&amp;"); these are the ones it actually uses.
    private fun decodeEntities(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .trim()
}
