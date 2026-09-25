package dev.droidtop.library.lutris

import dev.droidtop.library.scraper.ScrapeLookup
import dev.droidtop.library.scraper.ScrapeRefusals
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One published Lutris installer for a game, as lutris.net's own API
 * lists it: `slug`, `name`, `version` (the installer's label, "GOG" or
 * "CDROM + Darkplaces"), `runner`, and the `script` itself as JSON.
 */
data class LutrisInstaller(
    val slug: String,
    val name: String,
    val version: String,
    val runner: String,
    val script: JSONObject,
) {
    /** Only Wine scripts are importable (threat model, decision 10). */
    val importable: Boolean get() = runner == "wine"
}

/**
 * lutris.net's installer list for one game: `GET /api/installers/<game
 * slug>` (confirmed by a live call; the same endpoint Lutris's own client
 * reads installers from). Keyless, like the search [dev.droidtop.library.
 * scraper.LutrisScraperClient] already makes.
 *
 * The only place a script ever comes from (threat model, decision 9): the
 * slug is one the person picked from a Lutris search and is checked
 * against Lutris's own slug alphabet before it goes into the URL, and the
 * response is read to [MAX_BYTES] and refused past it.
 */
object LutrisInstallerClient {
    const val MAX_BYTES = 2 * 1024 * 1024

    private val SLUG = Regex("[a-z0-9-]{1,128}")

    fun forGame(gameSlug: String): ScrapeLookup<List<LutrisInstaller>> {
        if (!SLUG.matches(gameSlug)) throw LutrisScriptRefused("\"$gameSlug\" is not a Lutris game id")
        val connection = (URL("https://lutris.net/api/installers/$gameSlug").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            val status = connection.responseCode
            when (status) {
                200 -> parse(JSONObject(readBounded(connection)))
                // Lutris answers an unknown game with its 404 page: no installers.
                404 -> ScrapeLookup.NoMatch
                else -> ScrapeRefusals.refused("Lutris", connection, status, emptyList(), gameSlug)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(connection: HttpURLConnection): String {
        val bytes = connection.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BYTES) throw LutrisScriptRefused("lutris.net sent more than droidtop reads for one game")
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** Pure, for the JVM tests. Rows without a script object are skipped. */
    internal fun parse(response: JSONObject): ScrapeLookup<List<LutrisInstaller>> {
        val results = response.optJSONArray("results") ?: return ScrapeLookup.NoMatch
        val parsed = (0 until results.length()).mapNotNull { index ->
            val row = results.optJSONObject(index) ?: return@mapNotNull null
            val script = row.optJSONObject("script") ?: return@mapNotNull null
            LutrisInstaller(
                slug = row.optString("slug"),
                name = row.optString("name"),
                version = row.optString("version"),
                runner = row.optString("runner"),
                script = script,
            )
        }
        // Wine scripts first, the rest after, each in Lutris's own order.
        return if (parsed.isEmpty()) ScrapeLookup.NoMatch else ScrapeLookup.Found(parsed.sortedByDescending { it.importable })
    }

    private const val TIMEOUT_MS = 15_000
}
