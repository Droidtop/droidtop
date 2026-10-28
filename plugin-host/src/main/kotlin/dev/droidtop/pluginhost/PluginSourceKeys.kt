package dev.droidtop.pluginhost

import dev.droidtop.net.Http
import dev.droidtop.shared.trust.PublishedKeys


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

    /** A source's published key: the origin id it claims and the anchor key to trust for it ([UserOriginKeys.anchorKey]), SPKI base64. */
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
    fun keyUrlsFor(sourceUrl: String): List<String> = PublishedKeys.keyUrlsFor(sourceUrl, KEY_FILE_NAME)

    /**
     * Parses a published key file / catalog index: a JSON object with
     * `origin` and `certificate` (the origin's certificate, PEM or base64
     * DER) string fields, or `key` (a bare SubjectPublicKeyInfo) instead of
     * `certificate` -- the entire documented contract, so anything else is
     * "doesn't publish a key", not a versioned format to guess at. The anchor
     * key returned is [UserOriginKeys.anchorKey]'s: the published root
     * certificate's own key; what cannot be read is passed through for
     * [fetchKey] to refuse.
     */
    fun parsePublishedKey(text: String): PublishedKey? = PublishedKeys.parse(text)?.let { PublishedKey(it.origin, it.keyBase64) }

    /**
     * Fetches and validates the source's key; [httpGet] is injectable
     * so the unit tests exercise the real URL-derivation and parsing
     * chain with no network. Validation before returning: the key must
     * be a usable anchor key and the origin id must be one a user-trusted
     * origin may hold ([UserOriginKeys.originProblem]) -- a source
     * publishing origin "droidtop" is the "may not claim the official
     * id" attack and is refused before it ever reaches a prompt.
     */
    fun fetchKey(
        sourceUrl: String,
        token: String? = null,
        httpGet: (String) -> ByteArray? = { fetchBytes(it, token) },
    ): FetchResult {
        return when (val selected = PublishedKeys.select(sourceUrl, listOf(KEY_FILE_NAME), setOf(PluginOriginKeys.OFFICIAL_ORIGIN), UserOriginKeys.MAX_ORIGIN_ID_LEN, httpGet)) {
            is PublishedKeys.Selection.Fetched -> FetchResult.Fetched(PublishedKey(selected.key.origin, selected.key.keyBase64))
            is PublishedKeys.Selection.Failed -> FetchResult.Failed(selected.reason)
        }
    }

    /** The one real network leg: `Http.request` with the user's GitHub token (the GitHub opener, which sends it to GitHub hosts only), capped at 64 KiB (a key file is well under 1 KiB). */
    private fun fetchBytes(url: String, token: String?): ByteArray? = runCatching {
        // A token (even an empty one) takes the GitHub opener: redirects by hand, https only, the header decided per hop.
        val answer = Http.request(url, token = token.orEmpty(), timeouts = Http.Timeouts(30_000, 30_000), maxBytes = 64L * 1024)
        if (answer.status == 200) answer.body else null
    }.getOrNull()
}
