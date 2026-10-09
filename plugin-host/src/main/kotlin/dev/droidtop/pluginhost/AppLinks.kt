package dev.droidtop.pluginhost

/**
 * The links a plugin may hand to another app (`apps.view`, docs/plugin-api.md 3 F2): a web page (`https`, `http`) or a
 * magnet link, never `file`, `content`, `intent`, `javascript`, droidtop's own schemes or anything else, so a plugin
 * cannot make droidtop start an arbitrary component or read a file for it. Pure, for the tests.
 */
object AppLinks {
    const val MAX_LENGTH = 8192
    private val WEB = setOf("https", "http")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** Why [uri] may not be opened, or null when it may. */
    fun refusal(uri: String): String? {
        if (uri.isBlank()) return "uri is required"
        if (uri.length > MAX_LENGTH) return "uri is longer than $MAX_LENGTH characters"
        if (uri.any { it.isWhitespace() || it.isISOControl() }) return "uri must not hold spaces or control characters"
        val scheme = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase() ?: return "uri needs a scheme: https, http or magnet"
        return when (scheme) {
            in WEB -> {
                val host = runCatching { java.net.URI(uri).host }.getOrNull()
                if (host.isNullOrBlank()) "that web address has no host" else null
            }
            "magnet" -> {
                val query = uri.substring("magnet:".length)
                if (query.startsWith("?") && Regex("(?i)[?&]xt=urn:bt(ih|mh):").containsMatchIn(query)) null else "that is not a magnet link"
            }
            else -> "$scheme links cannot be opened; only https, http and magnet"
        }
    }

    /** What the activity log shows for [uri]: the kind of link and, for a web page, its host; never a query or a name. */
    fun target(uri: String): String {
        val scheme = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase() ?: return ""
        if (scheme !in WEB) return scheme
        return "$scheme://" + (runCatching { java.net.URI(uri).host }.getOrNull().orEmpty())
    }
}
