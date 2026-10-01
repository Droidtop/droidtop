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
)

data class PluginCatalogIndex(
    val generatedAt: String?,
    val origins: List<PluginCatalogOrigin>,
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
            val keySha256 = runCatching {
                val key = originJson.optJSONObject("key") ?: return@runCatching null
                if (key.optString("algorithm") != "SHA256withECDSA") return@runCatching null
                if (key.optString("origin") != origin) return@runCatching null
                val spki = key.optString("publicKeySpki")
                val declared = key.optString("keySha256")
                if (!HEX_64.matches(declared)) return@runCatching null
                val der = runCatching { java.util.Base64.getDecoder().decode(spki) }.getOrNull() ?: return@runCatching null
                if (!Sha256.hex(der).equals(declared, ignoreCase = true)) return@runCatching null
                declared
            }.getOrNull()

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
            origins.add(PluginCatalogOrigin(origin, trust, keySha256, plugins))
        }
        return PluginCatalogIndex(nullableText(root, "generatedAt"), origins)
    }

    /** The org.json `null`-vs-"null"-string trap (see PluginRecord's own copy of this check): a JSON null must come back as a real null. */
    private fun nullableText(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
}
