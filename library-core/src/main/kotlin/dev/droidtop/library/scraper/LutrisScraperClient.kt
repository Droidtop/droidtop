package dev.droidtop.library.scraper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * One result of Lutris's public search, `GET https://lutris.net/api/games?search=`.
 * Checked against live answers (2026-09-24 and 2026-09-25): each result
 * carries `id, name, slug, year, banner_url, icon_url, coverart, platforms,
 * provider_games, aliases, shaders, discord_id, change_for` and nothing
 * else. The search itself has no description, genre, developer, publisher
 * or rating field, and `year` is a year with no month or day.
 *
 * [steamAppId] and [gogId] come from `provider_games`, Lutris's own list
 * of the same game in other services (`{"name", "slug", "service"}`, where
 * the slug of a `steam` row is the Steam app id and of a `gog` row the GOG
 * product id: "Hollow Knight" lists steam 367520 and gog 1308320804). They
 * are what lets a name match become an identity: once Lutris names the
 * Steam app, the Steam store and IGDB answer for that exact game.
 */
data class LutrisGameResult(
    val name: String,
    val slug: String,
    val coverUrl: String?,
    val year: Int?,
    val steamAppId: Int? = null,
    val gogId: String? = null,
)

/**
 * The flavour Lutris's per-game record carries, `GET https://lutris.net/api/games/<slug>`
 * (keyless JSON, checked live 2026-09-25: `name, slug, year, platforms,
 * genres, aliases, description, banner_url, icon_url, coverart, is_public,
 * updated, steamid, gogslug, humblestoreid, id, user_count, installers,
 * shaders, discord_id`). A description and genres, and still no
 * developer, publisher, full date or rating.
 */
data class LutrisGameDetails(val description: String?, val genre: String?)

/**
 * Real Lutris (lutris.net) game database client -- genuinely keyless,
 * confirmed by reading Lutris's own real client source
 * (github.com/lutris/lutris, `lutris/api.py`: `GET {SITE_URL}/api/games?
 * search=<query>`, `SITE_URL` defaulting to `https://lutris.net`), not a
 * third-party wrapper's docs. No account, no registration, no waiting on
 * anyone's approval -- Lutris's own desktop client calls this exact same
 * public endpoint on every search.
 *
 * That keylessness is why it is the DEFAULT source for PC and engine
 * games: it is the one path that works on a fresh install with nothing
 * configured. Lutris is a PC/Wine database first, so its coverage of
 * exactly this category is also the best of the sources droidtop can
 * reach without a manually-approved developer account.
 *
 * **What Lutris can and cannot supply, definitively.** Its search gives a
 * cover and a year; its per-game record ([details]) adds a description
 * and genres. Neither endpoint has a developer, a publisher, a full
 * release date, a rating, a series or links, so those come from IGDB and
 * the Steam store (docs/SPEC.md 7h). The Lutris survey of 2026-09-24
 * expected the per-game record to be HTML only; a live call on 2026-09-25
 * found it is JSON. `gogslug` in that record is not used: for Hollow
 * Knight it names the soundtrack, where `provider_games` names the game.
 */
object LutrisScraperClient {
    private const val MAX_RESULTS = 10

    /**
     * Every result Lutris returns for [gameTitle], newest-first as the
     * API orders them, capped at [MAX_RESULTS].
     *
     * A LIST rather than a single best guess on purpose: a PC game's
     * folder name is a far weaker query than a ROM's No-Intro filename
     * ("Eternum-0.9.5-pc" is not a title), so the caller decides whether
     * any result is confident enough to apply on its own or whether the
     * user has to pick -- see [dev.droidtop.library.scraper.PcMatching].
     *
     * A non-200 is a [ScrapeLookup.Refused], never an empty list: an empty
     * list would be reported as "Lutris has nothing by that name", which is
     * a claim about the game the server never made (docs/SPEC.md 7h).
     */
    fun search(gameTitle: String): ScrapeLookup<List<LutrisGameResult>> {
        val query = URLEncoder.encode(gameTitle, "UTF-8")
        val url = URL("https://lutris.net/api/games?search=$query")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        val status = connection.responseCode
        if (status != 200) return ScrapeRefusals.refused("Lutris", connection, status, emptyList(), gameTitle)
        return parse(JSONObject(connection.inputStream.bufferedReader().readText()))
    }

    /**
     * The description and genres of the game Lutris files under [slug].
     * A 404 is Lutris saying it has no such game; any other non-200 is a
     * refusal.
     */
    fun details(slug: String): ScrapeLookup<LutrisGameDetails> {
        val path = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        val url = URL("https://lutris.net/api/games/$path")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        val status = connection.responseCode
        if (status == 404) return ScrapeLookup.NoMatch
        if (status != 200) return ScrapeRefusals.refused("Lutris", connection, status, emptyList(), slug)
        return parseDetails(JSONObject(connection.inputStream.bufferedReader().readText()))
    }

    /** Pure, for the JVM tests. */
    internal fun parse(response: JSONObject): ScrapeLookup<List<LutrisGameResult>> {
        val results = response.optJSONArray("results") ?: return ScrapeLookup.NoMatch
        val parsed = (0 until minOf(results.length(), MAX_RESULTS)).mapNotNull { index ->
            val row = results.getJSONObject(index)
            val name = row.optString("name", "").ifBlank { null } ?: return@mapNotNull null
            val providers = row.optJSONArray("provider_games")
            fun provided(service: String): String? = providers?.let { array ->
                (0 until array.length()).firstNotNullOfOrNull { i ->
                    array.optJSONObject(i)
                        ?.takeIf { it.optString("service", "") == service }
                        ?.optString("slug", "")?.trim()?.ifBlank { null }
                }
            }
            LutrisGameResult(
                name = name,
                slug = row.optString("slug", ""),
                // Real, confirmed bug fix (found via a real live API call,
                // not a guess): `banner_url`/`icon_url` are empty strings
                // for most real results -- `coverart` (Lutris's own
                // IGDB-sourced cover_big image) is the field that is
                // actually populated in practice.
                coverUrl = row.optString("coverart", "").ifBlank { null },
                year = row.optInt("year", 0).takeIf { it > 0 },
                steamAppId = provided("steam")?.toIntOrNull(),
                gogId = provided("gog")?.takeIf { id -> id.all { it.isDigit() } },
            )
        }
        return if (parsed.isEmpty()) ScrapeLookup.NoMatch else ScrapeLookup.Found(parsed)
    }

    /** Pure, for the JVM tests. */
    internal fun parseDetails(record: JSONObject): ScrapeLookup<LutrisGameDetails> {
        val description = record.optString("description", "").trim().ifBlank { null }
        val genre = record.optJSONArray("genres")?.let { genres ->
            (0 until genres.length())
                .mapNotNull { genres.optJSONObject(it)?.optString("name", "")?.trim()?.ifBlank { null } }
                .joinToString(", ").ifBlank { null }
        }
        return if (description == null && genre == null) {
            ScrapeLookup.NoMatch
        } else {
            ScrapeLookup.Found(LutrisGameDetails(description, genre))
        }
    }
}
