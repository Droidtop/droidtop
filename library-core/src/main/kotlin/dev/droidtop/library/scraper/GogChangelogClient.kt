package dev.droidtop.library.scraper

import dev.droidtop.net.Http
import org.json.JSONObject

/** A GOG game's changelog: the newest entry as plain text, and the game's page on GOG, where the whole changelog is. */
data class GogChangelog(val latest: String, val pageUrl: String?)

/**
 * "What's new" for a GOG game (docs/SPEC.md 7i, Droidtop/tracker#229): the changelog GOG keeps for the product, from
 * its public products API (`api.gog.com/products/<id>?expand=changelog`, no key, no account; only the product id is
 * sent). GOG writes it as HTML, newest first, one heading or bold line per version; droidtop shows the newest entry
 * as plain text. Read-only and asked for, like ProtonDB and Steam's news.
 */
object GogChangelogClient {

    /** Network: never on the main thread. */
    fun latest(productId: Long): ScrapeLookup<GogChangelog> {
        val response = Http.request("https://api.gog.com/products/$productId?expand=changelog", maxBytes = MAX_BYTES)
        return when (response.status) {
            200 -> parse(JSONObject(response.text()))
            // GOG answers a product it does not list with 404.
            404 -> ScrapeLookup.NoMatch
            else -> ScrapeRefusals.refused("GOG changelog", response, emptyList(), "product $productId")
        }
    }

    /** Pure, for the JVM tests. */
    internal fun parse(product: JSONObject): ScrapeLookup<GogChangelog> {
        val html = product.optString("changelog").takeIf { it.isNotBlank() && it != "null" } ?: return ScrapeLookup.NoMatch
        val latest = newestEntry(html).ifEmpty { return ScrapeLookup.NoMatch }
        val page = product.optJSONObject("links")?.optString("product_card")?.takeIf { it.startsWith("https://") }
        return ScrapeLookup.Found(GogChangelog(latest, page))
    }

    /**
     * The newest entry of a changelog: the text up to the second version heading (`<h1>`..`<h6>`, or a paragraph that
     * opens in bold), tags dropped, entities read, whitespace folded, cut at [EXCERPT_CHARS] on a word. Pure.
     */
    internal fun newestEntry(html: String): String {
        val headings = HEADING.findAll(html).map { it.range.first }.toList()
        val first = headings.firstOrNull() ?: 0
        val end = headings.firstOrNull { it > first } ?: html.length
        val text = html.substring(first, end)
            .replace(Regex("""<\s*(br|/p|/li|/h[1-6])\s*/?>""", RegexOption.IGNORE_CASE), ". ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+\."""), ".")
            .replace(Regex("""(\s*\.\s*){2,}"""), ". ")
            .trim().trim('.', ' ')
        if (text.length <= EXCERPT_CHARS) return text
        val cut = text.take(EXCERPT_CHARS)
        return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > EXCERPT_CHARS / 2 } ?: cut.length).trimEnd(',', ';', ':', '.') + "…"
    }

    private val HEADING = Regex("""<h[1-6][\s>]|<p>\s*<(b|strong)>""", RegexOption.IGNORE_CASE)
    private const val EXCERPT_CHARS = 280
    private const val MAX_BYTES = 1024L * 1024
}
