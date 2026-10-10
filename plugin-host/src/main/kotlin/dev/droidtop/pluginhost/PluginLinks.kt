package dev.droidtop.pluginhost

import java.net.URI
import org.json.JSONObject

/**
 * One link pattern a plugin handles (docs/plugin-api.md 3 F3, Droidtop/tracker#459): a `provides` entry may carry
 * `links: [{scheme, host?, pathPrefix?, pathSuffix?, query?}]`, and droidtop's one link router hands a matching
 * link to that entry's point (op `open_link`) through the broker. A plugin never gets an Android entry point of
 * its own; droidtop receives every link and routes it ([PluginLinks]).
 */
data class LinkPattern(
    val scheme: String,
    val host: String? = null,
    val pathPrefix: String? = null,
    val pathSuffix: String? = null,
    /** A query parameter the link must carry (an F-Droid repository's `fingerprint`). */
    val query: String? = null,
) {
    /** Whether [uri] is a link this pattern names. Scheme and host ignore case; paths do not. */
    fun matches(uri: URI): Boolean {
        val uriScheme = uri.scheme ?: return false
        if (!scheme.equals(uriScheme, ignoreCase = true)) return false
        if (host != null) {
            val uriHost = uri.host ?: uri.rawAuthority ?: return false
            if (!hostMatches(host, uriHost)) return false
        }
        val path = (uri.rawPath ?: "").trimEnd('/')
        if (pathPrefix != null && !(path == pathPrefix.trimEnd('/') || path.startsWith(pathPrefix.trimEnd('/') + "/"))) return false
        if (pathSuffix != null && !path.endsWith(pathSuffix.trimEnd('/'))) return false
        if (query != null && PluginLinks.queryParameter(uri, query) == null) return false
        return true
    }

    /** The key a remembered choice is stored under: scheme and host, the part a person recognises. */
    val key: String get() = scheme.lowercase() + "://" + (host?.lowercase() ?: "*") + (pathSuffix ?: "")

    /** Plain words for the approval list and the choice screen. */
    val label: String get() = when {
        host == null && scheme !in setOf("http", "https") -> "$scheme:// links"
        host == null -> "web links ending in ${pathSuffix ?: "/"}" + (query?.let { " with a $it" } ?: "")
        else -> "links to $host" + (pathPrefix?.let { it } ?: "")
    }

    companion object {
        private fun hostMatches(pattern: String, host: String): Boolean {
            val p = pattern.lowercase()
            val h = host.lowercase()
            return if (p.startsWith("*.")) h == p.removePrefix("*.") || h.endsWith(p.removePrefix("*")) else h == p
        }

        fun fromJson(json: JSONObject): LinkPattern? {
            val scheme = json.optString("scheme").trim().lowercase().takeIf { it.matches(Regex("[a-z][a-z0-9+.-]{0,31}")) } ?: return null
            fun opt(name: String) = json.optString(name).trim().takeIf { it.isNotEmpty() && it.length <= 200 }
            return LinkPattern(scheme, opt("host")?.lowercase(), opt("pathPrefix"), opt("pathSuffix"), opt("query"))
        }
    }
}

/**
 * The patterns plugins declare (docs/plugin-api.md 3 F3, docs/SPEC.md 12a "Links", Droidtop/tracker#459). Pure.
 *
 * Owner's decisions (2026-10-10): droidtop answers its own `droidtop://` links, every https link as a general
 * web-link handler (browser-style, no host named) and shared links; a plugin registers the patterns it handles in
 * its manifest, official or not. https links no plugin claims go on to the person's browser. Common link schemes and
 * every scheme an official plugin uses or plausibly will ([MANIFEST_SCHEMES]) are declared in droidtop's manifest as
 * activity-aliases that stay disabled until an installed plugin registers that scheme ([schemesWanted]) and are
 * disabled again when none does, so droidtop never offers to open links nobody handles. Any other scheme (an
 * unofficial plugin's) will reach droidtop through a links helper droidtop generates on the device (a separate
 * slice); until then such a link arrives only from inside droidtop (a QR code, a pasted address).
 */
object PluginLinks {
    /** droidtop's own links, which built-in handlers take: never a plugin's. */
    const val OWN_SCHEME = "droidtop"
    const val OWN_WEB_HOST = "droidtop.github.io"

    /** The patterns [entry] declares, in its order; unreadable ones are dropped. */
    fun declared(entry: ProvidedPoint): List<LinkPattern> {
        val array = runCatching { JSONObject(entry.extra).optJSONArray("links") }.getOrNull() ?: return emptyList()
        return (0 until minOf(array.length(), MAX_PATTERNS)).mapNotNull { i -> array.optJSONObject(i)?.let(LinkPattern::fromJson) }
    }

    /**
     * Whether plugin [pluginId] may claim [pattern]: never droidtop's own scheme (a plugin declares link-callable
     * actions for that, docs/SPEC.md 12a "Action links"); on the web only a named site (a host, `*.example.org` for its subdomains) or, for links published on any host,
     * a path ending ([LinkPattern.pathSuffix], an F-Droid repository's `/fdroid/repo`), never every web link and
     * never droidtop's own site; any other scheme is the plugin's to claim.
     */
    fun supported(pattern: LinkPattern, pluginId: String): Boolean = when (pattern.scheme) {
        // droidtop:// is droidtop's action-link grammar; a plugin adds actions to it (ActionLinks), never a pattern.
        OWN_SCHEME -> false
        "https", "http" -> !OWN_WEB_HOST.equals(pattern.host, ignoreCase = true) &&
            ((pattern.host != null && pattern.host != "*") || !pattern.pathSuffix.isNullOrBlank())
        else -> true
    }

    /**
     * The schemes droidtop's manifest declares as switchable activity-aliases (app/src/main/AndroidManifest.xml,
     * `dev.droidtop.app.links.*`): F-Droid repositories (fdroidrepos is https, fdroidrepo http, as F-Droid's own
     * client reads them), torrents, Steam and store-style app links. Adding one is a manifest alias plus a line here.
     */
    val MANIFEST_SCHEMES: List<String> = listOf("fdroidrepos", "fdroidrepo", "magnet", "steam", "market")

    /** The manifest schemes some installed, runnable plugin registers: the aliases to switch on. Pure. */
    fun schemesWanted(records: List<PluginRecord>): Set<String> = records.filter { it.runnable() }
        .flatMap { record -> record.manifest.v2.provides.flatMap { entry -> declared(entry).filter { supported(it, record.manifest.id) } } }
        .map { it.scheme }.filter { it in MANIFEST_SCHEMES }.toSet()

    /** Whether droidtop receives links of [pattern]'s scheme from other apps (its own, the web, a manifest alias). */
    fun receivedFromOutside(pattern: LinkPattern): Boolean = pattern.scheme in setOf(OWN_SCHEME, "https") || pattern.scheme in MANIFEST_SCHEMES

    /** [link] as a URI with a scheme, or null. */
    fun parse(link: String): URI? = runCatching { URI(link.trim()) }.getOrNull()?.takeIf { it.scheme != null }

    /** The first value of query parameter [name] (name ignoring case), decoded; null when absent. */
    fun queryParameter(uri: URI, name: String): String? = uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
        if (pair.substringBefore('=').equals(name, ignoreCase = true)) {
            runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
        } else {
            null
        }
    }

    const val MAX_PATTERNS = 16

    /** The op droidtop calls on the point that declared the matching pattern, with args `{link}`. */
    const val OP_OPEN = "open_link"

    /** The permission a plugin needs granted before any link is handed to it. */
    const val PERMISSION = "intents.in"
}
