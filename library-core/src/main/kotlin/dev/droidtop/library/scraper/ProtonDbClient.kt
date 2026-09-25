package dev.droidtop.library.scraper

import dev.droidtop.library.LibraryEntry
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ProtonDB's own summary for one Steam app, as its public endpoint
 * returns it (`/api/v1/reports/summaries/<appid>.json`, confirmed by a
 * live call): `tier`, `trendingTier`, `bestReportedTier`, `confidence`,
 * `score`, `total`. Tiers are ProtonDB's words (`borked`, `bronze`,
 * `silver`, `gold`, `platinum`, `native`, `pending`).
 */
data class ProtonDbSummary(
    val tier: String,
    val total: Int,
    val confidence: String?,
    val trendingTier: String?,
)

/**
 * ProtonDB, read-only: other people's reports of Steam games under
 * Proton on Linux PCs. docs/SPEC.md 7i: compatibility is evidence, never
 * a verdict and never a gate -- this is looked up only when the person
 * asks on a game's own screen, shown as ProtonDB's own words with whose
 * results they are, and never hides, reorders or blocks anything.
 */
object ProtonDbClient {

    fun pageUrl(appId: Long): String = "https://www.protondb.com/app/$appId"

    fun summary(appId: Long): ScrapeLookup<ProtonDbSummary> {
        val connection = (URL("https://www.protondb.com/api/v1/reports/summaries/$appId.json").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        return try {
            when (val status = connection.responseCode) {
                200 -> parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText().take(MAX_CHARS) }))
                // ProtonDB answers an app nobody has reported with 404.
                404 -> ScrapeLookup.NoMatch
                else -> ScrapeRefusals.refused("ProtonDB", connection, status, emptyList(), "app $appId")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Pure, for the JVM tests. */
    internal fun parse(response: JSONObject): ScrapeLookup<ProtonDbSummary> {
        val tier = response.optString("tier").ifBlank { null } ?: return ScrapeLookup.NoMatch
        return ScrapeLookup.Found(
            ProtonDbSummary(
                tier = tier,
                total = response.optInt("total", 0),
                confidence = response.optString("confidence").ifBlank { null },
                trendingTier = response.optString("trendingTier").ifBlank { null },
            ),
        )
    }

    /**
     * The Steam app id ProtonDB knows [entry] by: its own when it is a
     * Steam game, otherwise the one Lutris lists for a game of EXACTLY
     * the same name (a similar name is a suggestion, and an automatic
     * lookup needs identity -- docs/SPEC.md 7g). Null when neither says.
     * [name] is the game's name as its own screen shows it. Network:
     * never on the main thread.
     */
    fun steamAppIdFor(entry: LibraryEntry, name: String): Long? {
        steamIdOf(entry.id)?.let { return it }
        entry.pcInfo?.storeId?.let { steamIdOf(it) }?.let { return it }
        val results = LutrisScraperClient.search(name).foundOrNull ?: return null
        val wanted = normalized(name)
        return results.firstOrNull { normalized(it.name) == wanted }?.steamAppId?.toLong()
    }

    private fun steamIdOf(id: String): Long? =
        id.takeIf { it.startsWith("steam:") }?.substringAfter(':')?.toLongOrNull()

    private fun normalized(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    private const val TIMEOUT_MS = 15_000
    private const val MAX_CHARS = 64 * 1024
}

/**
 * One line in ProtonDB's own words and whose results they are, e.g.
 * "Gold on ProtonDB, 695 reports, strong confidence".
 */
fun ProtonDbSummary.line(): String = buildString {
    append(tier.replaceFirstChar { it.uppercase() })
    append(" on ProtonDB, ")
    append(total).append(if (total == 1) " report" else " reports")
    confidence?.let { append(", ").append(it).append(" confidence") }
    trendingTier?.takeIf { it != tier }?.let { append(", lately ").append(it) }
}
