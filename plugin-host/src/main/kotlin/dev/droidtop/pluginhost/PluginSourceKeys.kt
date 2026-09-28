package dev.droidtop.pluginhost

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Fetches a plugin source's PUBLISHED origin key (docs/SPEC.md 12a "The
 * primary path: trust-on-first-use from a source") -- the owner's
 * revision of the keys design (2026-09-28): users should not normally
 * have to paste keys by hand, they add the SOURCE and droidtop fetches
 * the key it publishes. Nothing this object returns is trusted by
 * itself: the caller shows origin + fingerprint and the user confirms.
 */
object PluginSourceKeys {
    /** The well-known file a plugin source publishes its key in (docs/SPEC.md 12a's key file format). */
    const val KEY_FILE_NAME = "droidtop-plugin-key.json"

    /** A source's published key: the origin id it claims and its P-256 SPKI, base64. */
    data class PublishedKey(val origin: String, val keyBase64: String)

    sealed interface FetchResult {
        data class Fetched(val key: PublishedKey) : FetchResult
        data class Failed(val reason: String) : FetchResult
    }

    /**
     * The https URLs a source's key can live at, tried in order. A
     * github.com repo address becomes the raw.githubusercontent.com
     * path of the well-known file on the named branch (or the default
     * branch via HEAD), so the user pastes the address a browser
     * actually shows them; a URL ending in `.json` is taken as the
     * catalog index / key file itself; anything else is a plain https
     * root with the well-known file expected at its top. Empty when
     * the input is not a URL, or would have to be fetched over
     * plaintext http: a key fetched in the clear makes the TOFU step
     * itself the attack, so it is refused rather than upgraded, except
     * the github.com case, where the derived raw URL is always https.
     */
    fun keyUrlsFor(sourceUrl: String): List<String> {
        val trimmed = sourceUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()
        val isHttps = trimmed.startsWith("https://", ignoreCase = true)
        val isHttp = trimmed.startsWith("http://", ignoreCase = true)
        if (!isHttps && !isHttp) return emptyList()
        val body = trimmed.substringAfter("://")
        if (isHttps && body.endsWith(".json", ignoreCase = true)) return listOf(trimmed)

        val host = runCatching { URI("https://$body").host?.lowercase() }.getOrNull() ?: return emptyList()
        if (host == "github.com" || host == "www.github.com") {
            val segments = runCatching { URI("https://$body").path.orEmpty().trim('/').split('/') }.getOrDefault(emptyList())
            if (segments.size < 2) return emptyList()
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            // A /tree/<branch> (or /blob/<branch>) URL names the branch
            // to read the key file from; the bare repo URL reads the
            // default branch via raw.githubusercontent.com's HEAD.
            val branch = if (segments.size >= 4 && (segments[2] == "tree" || segments[2] == "blob")) segments[3] else "HEAD"
            return listOf("https://raw.githubusercontent.com/$owner/$repo/$branch/$KEY_FILE_NAME")
        }
        if (isHttp) return emptyList()
        return listOf("https://$body/$KEY_FILE_NAME")
    }

    /**
     * Parses a published key file / catalog index: a JSON object with
     * `origin` and `key` string fields -- the entire documented
     * contract, so anything else is "doesn't publish a key", not a
     * versioned format to guess at.
     */
    fun parsePublishedKey(text: String): PublishedKey? {
        val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null
        val origin = json.optString("origin").trim().takeIf { it.isNotBlank() } ?: return null
        val key = json.optString("key").trim().takeIf { it.isNotBlank() } ?: return null
        return PublishedKey(origin, key)
    }

    /**
     * Fetches and validates the source's key; [httpGet] is injectable
     * so the unit tests exercise the real URL-derivation and parsing
     * chain with no network. Validation before returning: the key must
     * be a P-256 SPKI and the origin id must be one a user-trusted
     * origin may hold ([UserOriginKeys.originProblem]) -- a source
     * publishing origin "droidtop" is the "may not claim the official
     * id" attack and is refused before it ever reaches a prompt.
     */
    fun fetchKey(sourceUrl: String, httpGet: (String) -> ByteArray? = ::httpGet): FetchResult {
        val candidates = keyUrlsFor(sourceUrl)
        if (candidates.isEmpty()) {
            return FetchResult.Failed(
                "That doesn't look like a plugin source address. Use a GitHub repo (https://github.com/<owner>/<repo>), " +
                    "a catalog index ending in .json, or a plain https address -- plaintext http is refused.",
            )
        }
        var lastProblem = "no candidate to fetch"
        for (url in candidates) {
            val bytes = runCatching { httpGet(url) }.getOrNull()
            if (bytes == null) {
                lastProblem = "couldn't fetch $url"
                continue
            }
            val published = parsePublishedKey(bytes.toString(Charsets.UTF_8))
            if (published == null) {
                lastProblem = "$url has no \"origin\" and \"key\" fields"
                continue
            }
            if (PluginOriginKeys.parseSpki(published.keyBase64) == null) {
                lastProblem = "the key $url publishes is not a valid P-256 public key"
                continue
            }
            UserOriginKeys.originProblem(published.origin)?.let {
                return FetchResult.Failed("Origin \"${published.origin}\" can't be trusted: $it.")
            }
            return FetchResult.Fetched(published)
        }
        return FetchResult.Failed(
            "Couldn't fetch a usable key from $sourceUrl ($lastProblem). " +
                "A plugin source publishes $KEY_FILE_NAME with \"origin\" and \"key\" at its root; " +
                "a catalog index carries the same two fields.",
        )
    }

    /** The one real network leg: [PythonRuntimeManager]/[FlutterRuntimeManager]'s HttpURLConnection shape, capped at 64 KiB (a key file is well under 1 KiB). */
    private fun httpGet(url: String): ByteArray? = runCatching {
        val connection = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        connection.connect()
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > 64 * 1024) return@runCatching null
            }
            out.toByteArray()
        }
    }.getOrNull()
}
