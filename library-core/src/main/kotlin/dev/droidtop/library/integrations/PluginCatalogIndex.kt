package dev.droidtop.library.integrations

import dev.droidtop.runtime.util.Sha256
import org.json.JSONObject
import java.time.Instant

/**
 * One entry of the plugin catalog index droidtop-platforms ships at
 * `droidtop-plugins/index.json` (docs/SPEC.md 12a "The catalog"): a
 * plugin's downloadable release. The index is UNTRUSTED display data --
 * [dev.droidtop.pluginhost.PluginBundleInstaller] re-verifies the
 * manifest signature against the app's own pinned key and every payload
 * hash before any of this is acted on, and [dev.droidtop.library.integrations.
 * PluginCatalog.originOffered] cross-checks the origin's key document
 * against the app's pin before the origin is offered at all.
 */
data class PluginCatalogBundle(val name: String, val url: String, val size: Long, val sha256: String)

data class PluginCatalogRelease(
    /** The manifest's own version string: display only, never compared (the digest is the identity). */
    val version: String,
    /** "stable" | "testing" | "unstable"; droidtop offers stable-stream releases only. */
    val stream: String,
    /** ISO-8601 UTC, or null when the publisher did not say when. */
    val publishedAt: String?,
    /** SHA-256 of the signed manifest.json bytes inside the bundle -- what "an update is available" compares against the installed record's digest. */
    val manifestSha256: String,
    val bundle: PluginCatalogBundle,
) {
    /** Newer first; missing/unparseable stamps sort as oldest, list order breaks ties. */
    fun publishedAtMillis(): Long =
        if (publishedAt == null) 0L else runCatching { Instant.parse(publishedAt).toEpochMilli() }.getOrDefault(0L)
}

data class PluginCatalogPlugin(
    val id: String,
    val label: String,
    val description: String?,
    val releases: List<PluginCatalogRelease>,
)

data class PluginCatalogOrigin(
    val origin: String,
    /** "official" | "third-party": shown nowhere on purpose (the user sees plugins, not origins), carried for the index's own audit trail. */
    val trust: String,
    /** The key document's keySha256, or null when the entry carries no usable key document -- then [dev.droidtop.library.integrations.PluginCatalog.originOffered] is false for it no matter what. */
    val keySha256: String?,
    val plugins: List<PluginCatalogPlugin>,
    /** The key document's SPKI, base64, when [keySha256] is set: what an added catalog's origin is trusted with on first use, after the person saw its fingerprint. */
    val keyBase64: String? = null,
)

/**
 * An added catalog's `catalog` block (docs/SPEC.md 12a "Added catalogs"): who it says it is. Display data
 * like everything else in the index, except [keyBase64], the catalog master's public key, which droidtop
 * trusts on first use when the person accepts the catalog and from then on requires the index to be
 * signed under.
 */
data class PluginCatalogInfo(
    /** "owner/name" of the repository that publishes it; never the official catalog's id. */
    val id: String,
    val name: String,
    val homepage: String?,
    /** "unofficial" for every catalog but droidtop's own. */
    val trust: String,
    /**
     * The organisation's plugin master, P-256 SPKI base64, or null for a catalog without one: trusted on first use
     * as its own origin's key ([origin]), which certifies each of its repositories' keys.
     */
    val keyBase64: String?,
    /** The organisation's own origin id (e.g. "gamegrab"), listed under [keyBase64]; null without a master. */
    val origin: String? = null,
)

/** An added catalog's `disclaimer` block: shown, and accepted by the person, before anything from it is listed. */
data class PluginCatalogDisclaimer(val version: Int, val text: String)

data class PluginCatalogIndex(
    val generatedAt: String?,
    val origins: List<PluginCatalogOrigin>,
    /** Null for droidtop's own catalog, which has neither block. */
    val catalog: PluginCatalogInfo? = null,
    val disclaimer: PluginCatalogDisclaimer? = null,
) {
    fun pluginById(id: String): PluginCatalogPlugin? =
        origins.asSequence().flatMap { it.plugins }.firstOrNull { it.id == id }
}

/**
 * Parses the index into [PluginCatalogIndex]. Returns null -- not a
 * partial object -- when the document is not the shape this build
 * speaks (a different [SCHEMA_VERSION], a structurally broken entry), so
 * a corrupt or moved index can never masquerade as "nothing is
 * published": the caller keeps its last good copy and says the refresh
 * failed. What it does NOT police is trust: every field it accepts is
 * checked for SHAPE only (lowercase namespaced ids, 64-hex digests,
 * https URLs, known stream names), never for TRUTH.
 */
object PluginCatalogIndexParser {
    const val SCHEMA_VERSION = 1
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    private val STREAMS = setOf("stable", "testing", "unstable")
    private val CATALOG_ID = Regex("[A-Za-z0-9._-]{1,100}/[A-Za-z0-9._-]{1,100}")
    private const val MAX_DISCLAIMER_CHARS = 4000
    private const val MAX_NAME_CHARS = 80

    fun parse(text: String): PluginCatalogIndex? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null
        val originsJson = root.optJSONArray("origins") ?: return null
        val origins = mutableListOf<PluginCatalogOrigin>()
        val seenIds = mutableSetOf<String>()
        for (i in 0 until originsJson.length()) {
            val originJson = originsJson.optJSONObject(i) ?: return null
            val origin = originJson.optString("origin")
            if (origin.isBlank() || origin != origin.lowercase()) return null
            val trust = originJson.optString("trust")
            if (trust != "official" && trust != "third-party") return null

            // The key document is cross-checked, not trusted (SPEC 12a
            // "The catalog"): present-but-wrong or absent both end up as
            // "not offered", never as "trusted". A lying key document
            // must not take the rest of the origin's listing down with
            // it -- the listing itself is display data and the install
            // path is where trust is decided.
            val keyDocument = runCatching {
                val key = originJson.optJSONObject("key") ?: return@runCatching null
                if (key.optString("algorithm") != "SHA256withECDSA") return@runCatching null
                if (key.optString("origin") != origin) return@runCatching null
                val spki = key.optString("publicKeySpki")
                val declared = key.optString("keySha256")
                if (!HEX_64.matches(declared)) return@runCatching null
                val der = runCatching { java.util.Base64.getDecoder().decode(spki) }.getOrNull() ?: return@runCatching null
                if (!Sha256.hex(der).equals(declared, ignoreCase = true)) return@runCatching null
                declared to spki
            }.getOrNull()
            val keySha256 = keyDocument?.first

            val pluginsJson = originJson.optJSONArray("plugins") ?: return null
            val plugins = mutableListOf<PluginCatalogPlugin>()
            for (j in 0 until pluginsJson.length()) {
                val pluginJson = pluginsJson.optJSONObject(j) ?: return null
                val id = pluginJson.optString("id")
                // The id IS the namespace (checklist point 2): lowercase,
                // and namespaced under this very origin, so two origins
                // can never list the same plugin.
                if (id != id.lowercase() || !id.startsWith("$origin.")) return null
                if (!seenIds.add(id)) return null
                val label = pluginJson.optString("label")
                if (label.isBlank()) return null
                val description = nullableText(pluginJson, "description")
                val releasesJson = pluginJson.optJSONArray("releases") ?: return null
                val releases = mutableListOf<PluginCatalogRelease>()
                for (k in 0 until releasesJson.length()) {
                    val releaseJson = releasesJson.optJSONObject(k) ?: return null
                    val version = releaseJson.optString("version")
                    if (version.isBlank()) return null
                    val stream = releaseJson.optString("stream")
                    if (stream !in STREAMS) return null
                    val manifestSha256 = releaseJson.optString("manifestSha256")
                    if (!HEX_64.matches(manifestSha256)) return null
                    val bundleJson = releaseJson.optJSONObject("bundle") ?: return null
                    val name = bundleJson.optString("name")
                    if (name.isBlank()) return null
                    val url = bundleJson.optString("url")
                    if (!url.startsWith("https://")) return null
                    val size = bundleJson.optLong("size", -1)
                    if (size < 0) return null
                    val sha256 = bundleJson.optString("sha256")
                    if (!HEX_64.matches(sha256)) return null
                    releases.add(
                        PluginCatalogRelease(
                            version = version,
                            stream = stream,
                            publishedAt = nullableText(releaseJson, "publishedAt"),
                            manifestSha256 = manifestSha256,
                            bundle = PluginCatalogBundle(name, url, size, sha256),
                        ),
                    )
                }
                if (releases.isEmpty()) return null
                plugins.add(PluginCatalogPlugin(id, label, description, releases))
            }
            origins.add(PluginCatalogOrigin(origin, trust, keySha256, plugins, keyDocument?.second))
        }
        // The added-catalog blocks are optional in the format (droidtop's own index has neither), but one
        // that is present must be whole: a half-readable disclaimer is never shown as if it were the text.
        val catalog = if (root.has("catalog")) parseCatalog(root.optJSONObject("catalog")) ?: return null else null
        val disclaimer = if (root.has("disclaimer")) parseDisclaimer(root.optJSONObject("disclaimer")) ?: return null else null
        return PluginCatalogIndex(nullableText(root, "generatedAt"), origins, catalog, disclaimer)
    }

    private fun parseCatalog(json: JSONObject?): PluginCatalogInfo? {
        if (json == null) return null
        val id = json.optString("id")
        if (!CATALOG_ID.matches(id)) return null
        val name = json.optString("name").trim()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) return null
        val trust = json.optString("trust")
        if (trust.isBlank()) return null
        val homepage = nullableText(json, "homepage")?.takeIf { it.startsWith("https://") }
        val keyBase64 = if (!json.has("key")) {
            null
        } else {
            val key = json.optJSONObject("key") ?: return null
            if (key.optString("algorithm") != "SHA256withECDSA") return null
            val spki = key.optString("publicKeySpki")
            val der = runCatching { java.util.Base64.getDecoder().decode(spki) }.getOrNull() ?: return null
            if (!Sha256.hex(der).equals(key.optString("keySha256"), ignoreCase = true)) return null
            spki
        }
        val origin = nullableText(json, "origin")
        if (origin != null && (origin != origin.lowercase() || keyBase64 == null)) return null
        return PluginCatalogInfo(id, name, homepage, trust, keyBase64, origin)
    }

    private fun parseDisclaimer(json: JSONObject?): PluginCatalogDisclaimer? {
        if (json == null) return null
        val version = json.optInt("version", -1)
        val text = json.optString("text").trim()
        if (version < 1 || text.isEmpty() || text.length > MAX_DISCLAIMER_CHARS) return null
        return PluginCatalogDisclaimer(version, text)
    }

    /** The org.json `null`-vs-"null"-string trap (see PluginRecord's own copy of this check): a JSON null must come back as a real null. */
    private fun nullableText(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
}
