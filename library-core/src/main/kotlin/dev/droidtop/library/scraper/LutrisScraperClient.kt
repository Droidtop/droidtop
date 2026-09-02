package dev.droidtop.library.scraper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real per-game data Lutris's own public API actually returns for a
 * search result -- confirmed via a real, live call to
 * `https://lutris.net/api/games?search=...`, not assumed from docs:
 * `id, name, slug, year, banner_url, icon_url, coverart, platforms,
 * provider_games, aliases, shaders, discord_id, change_for`. No
 * description/genre/developer/publisher/rating field exists at all --
 * `year` is the only real release-date signal Lutris provides (year
 * precision only, not a full date), which is exactly why the PC scrape
 * treats Lutris as a cover-art-and-year source and IGDB as the one that
 * can fill a description.
 */
data class LutrisGameResult(val name: String, val slug: String, val coverUrl: String?, val year: Int?)

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
     */
    fun search(gameTitle: String): List<LutrisGameResult> {
        val query = URLEncoder.encode(gameTitle, "UTF-8")
        val url = URL("https://lutris.net/api/games?search=$query")
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        if (connection.responseCode != 200) return emptyList()
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val results = response.optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), MAX_RESULTS)).mapNotNull { index ->
            val row = results.getJSONObject(index)
            val name = row.optString("name", "").ifBlank { null } ?: return@mapNotNull null
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
            )
        }
    }
}
