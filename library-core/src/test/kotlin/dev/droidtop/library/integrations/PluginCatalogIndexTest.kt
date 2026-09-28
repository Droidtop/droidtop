package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.BundleSignature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape contract for the plugin catalog index (docs/SPEC.md 12a "The
 * catalog"): what [PluginCatalogIndexParser] accepts, and what it treats
 * as "not a usable catalog" (null) instead of a partial one. Trust is
 * deliberately out of scope here -- the parser checks SHAPE (hex digests,
 * https URLs, namespaced ids), never TRUTH; the truth checks live in
 * PluginCatalog.originOffered and PluginBundleInstaller.
 */
class PluginCatalogIndexTest {
    private val keyDer = ByteArray(48) { it.toByte() }
    private val keySha = BundleSignature.sha256(keyDer)
    private val keySpki = java.util.Base64.getEncoder().encodeToString(keyDer)

    private fun releaseJson(
        version: String = "1.0",
        stream: String = "stable",
        publishedAt: Any = "2026-09-28T12:00:00Z",
        manifestSha256: String = "a".repeat(64),
    ) = JSONObject().apply {
        put("version", version)
        put("stream", stream)
        put("publishedAt", publishedAt)
        put("manifestSha256", manifestSha256)
        put(
            "bundle",
            JSONObject().apply {
                put("name", "droidtop.sample-1.0.droidplugin.tar.xz")
                put("url", "https://github.com/droidtop/droidtop-plugin-sample/releases/download/v1.0/droidtop.sample-1.0.droidplugin.tar.xz")
                put("size", 1234L)
                put("sha256", "b".repeat(64))
            },
        )
    }

    /** A trailing lambda edits the plugin entry; [originOverride] edits its origin. */
    private fun indexWithPlugin(pluginOverride: (JSONObject) -> Unit) = indexJson(pluginOverride = pluginOverride)

    /** A trailing lambda edits the origin entry. */
    private fun indexJson(
        schemaVersion: Int = 1,
        pluginOverride: (JSONObject) -> Unit = {},
        dropOrigins: Boolean = false,
        originOverride: (JSONObject) -> Unit = {},
    ): String {
        val plugin = JSONObject().apply {
            put("id", "droidtop.sample")
            put("label", "Sample")
            put("description", JSONObject.NULL)
            put("releases", JSONArray(listOf(releaseJson())))
        }
        pluginOverride(plugin)
        val origin = JSONObject().apply {
            put("origin", "droidtop")
            put("trust", "official")
            put(
                "key",
                JSONObject().apply {
                    put("formatVersion", 1)
                    put("algorithm", "SHA256withECDSA")
                    put("origin", "droidtop")
                    put("publicKeySpki", keySpki)
                    put("keySha256", keySha)
                },
            )
            put("plugins", JSONArray(listOf(plugin)))
        }
        originOverride(origin)
        return JSONObject().apply {
            put("schemaVersion", schemaVersion)
            put("generatedAt", "2026-09-28T12:00:00Z")
            if (!dropOrigins) put("origins", JSONArray(listOf(origin)))
        }.toString()
    }

    @Test
    fun `parses a well-formed index with its fields intact`() {
        val index = PluginCatalogIndexParser.parse(indexJson())!!
        assertEquals("2026-09-28T12:00:00Z", index.generatedAt)
        assertEquals(1, index.origins.size)
        val origin = index.origins[0]
        assertEquals("droidtop", origin.origin)
        assertEquals("official", origin.trust)
        assertEquals(keySha, origin.keySha256)
        assertEquals(1, origin.plugins.size)
        val plugin = origin.plugins[0]
        assertEquals("droidtop.sample", plugin.id)
        assertEquals("Sample", plugin.label)
        assertNull(plugin.description)
        val release = plugin.releases[0]
        assertEquals("1.0", release.version)
        assertEquals("stable", release.stream)
        assertEquals("a".repeat(64), release.manifestSha256)
        assertEquals(1234L, release.bundle.size)
        assertTrue(release.bundle.url.startsWith("https://"))
    }

    @Test
    fun `a JSON null description comes back as a real null, not the string null`() {
        val index = PluginCatalogIndexParser.parse(indexJson())!!
        assertNull(index.origins[0].plugins[0].description)
    }

    @Test
    fun `an unknown schema version is refused whole, not parsed partially`() {
        assertNull(PluginCatalogIndexParser.parse(indexJson(schemaVersion = 2)))
        assertNull(PluginCatalogIndexParser.parse(indexJson(schemaVersion = 0)))
    }

    @Test
    fun `non-JSON or non-object input is refused`() {
        assertNull(PluginCatalogIndexParser.parse("not json at all"))
        assertNull(PluginCatalogIndexParser.parse("[1, 2, 3]"))
        assertNull(PluginCatalogIndexParser.parse(""))
    }

    @Test
    fun `an index with no origins array is refused, an empty one is a real empty catalog`() {
        assertNull(PluginCatalogIndexParser.parse(indexJson(dropOrigins = true)))
        val empty = JSONObject().apply {
            put("schemaVersion", 1)
            put("origins", JSONArray())
        }
        val parsed = PluginCatalogIndexParser.parse(empty.toString())!!
        assertTrue(parsed.origins.isEmpty())
    }

    @Test
    fun `an origin that is not lowercase or blank is refused`() {
        assertNull(PluginCatalogIndexParser.parse(indexJson { origin -> origin.put("origin", "Droidtop") }))
        assertNull(PluginCatalogIndexParser.parse(indexJson { origin -> origin.put("origin", "") }))
    }

    @Test
    fun `an unrecognized trust level is refused`() {
        assertNull(PluginCatalogIndexParser.parse(indexJson { origin -> origin.put("trust", "trusted") }))
    }

    @Test
    fun `a key document whose declared sha does not match its own key is dropped, not trusted, and does not take the origin down`() {
        val lying = indexJson { origin ->
            origin.getJSONObject("key").put("keySha256", "f".repeat(64))
        }
        val index = PluginCatalogIndexParser.parse(lying)!!
        assertNull(index.origins[0].keySha256)
        assertEquals(1, index.origins[0].plugins.size)
    }

    @Test
    fun `a key document naming another origin is dropped`() {
        val lying = indexJson { origin ->
            origin.getJSONObject("key").put("origin", "someone-else")
        }
        val index = PluginCatalogIndexParser.parse(lying)!!
        assertNull(index.origins[0].keySha256)
    }

    @Test
    fun `an origin with no key document at all still parses, simply not installable`() {
        val noKey = indexJson { origin -> origin.remove("key") }
        val index = PluginCatalogIndexParser.parse(noKey)!!
        assertNull(index.origins[0].keySha256)
    }

    @Test
    fun `a plugin id not namespaced under its own origin is refused`() {
        assertNull(
            PluginCatalogIndexParser.parse(indexWithPlugin { plugin -> plugin.put("id", "otherorigin.sample") }),
        )
        assertNull(
            PluginCatalogIndexParser.parse(indexWithPlugin { plugin -> plugin.put("id", "droidtop.Sample") }),
        )
    }

    @Test
    fun `a duplicate plugin id anywhere in the index is refused`() {
        val two = indexJson { origin ->
            val plugins = origin.getJSONArray("plugins")
            val copy = JSONObject(plugins.getJSONObject(0).toString())
            plugins.put(copy)
        }
        assertNull(PluginCatalogIndexParser.parse(two))
    }

    @Test
    fun `a plugin with no releases is refused, not listed as installable-nothing`() {
        assertNull(
            PluginCatalogIndexParser.parse(indexWithPlugin { plugin -> plugin.put("releases", JSONArray()) }),
        )
    }

    @Test
    fun `a release with a malformed digest or http URL or negative size is refused`() {
        // manifestSha256 not 64 hex
        assertNull(
            PluginCatalogIndexParser.parse(
                indexWithPlugin { plugin ->
                    plugin.getJSONArray("releases").getJSONObject(0).put("manifestSha256", "zzz")
                },
            ),
        )
        // bundle url not https
        assertNull(
            PluginCatalogIndexParser.parse(
                indexWithPlugin { plugin ->
                    plugin.getJSONArray("releases").getJSONObject(0).getJSONObject("bundle").put("url", "http://insecure.example/bundle.tar.xz")
                },
            ),
        )
        // bundle size negative
        assertNull(
            PluginCatalogIndexParser.parse(
                indexWithPlugin { plugin ->
                    plugin.getJSONArray("releases").getJSONObject(0).getJSONObject("bundle").put("size", -1)
                },
            ),
        )
        // bundle sha256 not hex
        assertNull(
            PluginCatalogIndexParser.parse(
                indexWithPlugin { plugin ->
                    plugin.getJSONArray("releases").getJSONObject(0).getJSONObject("bundle").put("sha256", "nope")
                },
            ),
        )
    }

    @Test
    fun `an unknown stream name is refused, the known ones pass`() {
        assertNull(
            PluginCatalogIndexParser.parse(
                indexWithPlugin { plugin ->
                    plugin.getJSONArray("releases").getJSONObject(0).put("stream", "nightly")
                },
            ),
        )
        for (stream in listOf("stable", "testing", "unstable")) {
            val ok = PluginCatalogIndexParser.parse(
                indexWithPlugin { plugin ->
                    plugin.getJSONArray("releases").getJSONObject(0).put("stream", stream)
                },
            )
            assertEquals(stream, ok?.origins?.get(0)?.plugins?.get(0)?.releases?.get(0)?.stream)
        }
    }

    @Test
    fun `a null publishedAt parses as null and sorts as oldest`() {
        val index = PluginCatalogIndexParser.parse(
            indexWithPlugin { plugin ->
                val releases = JSONArray()
                releases.put(releaseJson(publishedAt = JSONObject.NULL, version = "1.0"))
                releases.put(releaseJson(publishedAt = "2026-09-28T12:00:00Z", version = "0.9"))
                plugin.put("releases", releases)
            },
        )!!
        val plugin = index.origins[0].plugins[0]
        assertNull(plugin.releases[0].publishedAt)
        assertEquals(0L, plugin.releases[0].publishedAtMillis())
        // A missing date says nothing, so the version decides.
        assertEquals("1.0", PluginCatalog.latestStable(plugin)?.version)
    }

    @Test
    fun `version and date decide together, and a disagreement offers nothing`() {
        fun plugin(vararg releases: Pair<String, String>) = PluginCatalogPlugin(
            id = "droidtop.sample",
            label = "Sample",
            description = null,
            releases = releases.map { (version, date) ->
                PluginCatalogRelease(version, "stable", date, "a".repeat(64), PluginCatalogBundle("n", "https://x", 1, "b".repeat(64)))
            },
        )
        // Same date: the version decides.
        assertEquals("1.1", PluginCatalog.latestStable(plugin("1.0" to "2026-09-28T12:00:00Z", "1.1" to "2026-09-28T12:00:00Z"))?.version)
        // Same version string: the date decides.
        assertEquals("2026-09-29T12:00:00Z", PluginCatalog.latestStable(plugin("1.0" to "2026-09-28T12:00:00Z", "1.0" to "2026-09-29T12:00:00Z"))?.publishedAt)
        // Both agree.
        assertEquals("0.10", PluginCatalog.latestStable(plugin("0.9" to "2026-09-01T00:00:00Z", "0.10" to "2026-09-02T00:00:00Z"))?.version)
        // Newer version published earlier: the index is wrong, nothing is offered.
        val wrong = plugin("1.1" to "2026-09-01T00:00:00Z", "1.0" to "2026-09-02T00:00:00Z")
        assertTrue(PluginCatalog.hasOrderConflict(wrong))
        assertNull(PluginCatalog.latestStable(wrong))
    }

    @Test
    fun `latestStable ignores testing and unstable releases and returns null when there is no stable`() {
        fun only(vararg streams: Pair<String, String>): PluginCatalogPlugin = PluginCatalogPlugin(
            id = "droidtop.sample",
            label = "Sample",
            description = null,
            releases = streams.map { (stream, version) ->
                PluginCatalogRelease(version, stream, "2026-09-28T12:00:00Z", "a".repeat(64), PluginCatalogBundle("n", "https://x", 1, "b".repeat(64)))
            },
        )
        assertNull(PluginCatalog.latestStable(only("testing" to "2.0")))
        assertNull(PluginCatalog.latestStable(only("testing" to "2.0", "unstable" to "1.9")))
        val mixed = only("testing" to "2.0", "stable" to "1.5", "stable" to "1.4")
        assertEquals("1.5", PluginCatalog.latestStable(mixed)?.version)
    }

    @Test
    fun `updateFor answers the newest stable release only when its digest differs from the installed one`() {
        fun record(digest: String) = dev.droidtop.pluginhost.PluginRecord(
            manifest = dev.droidtop.pluginhost.PluginManifest(
                id = "droidtop.sample",
                origin = "droidtop",
                label = "Sample",
                description = null,
                version = "1.0",
                kind = dev.droidtop.pluginhost.PluginKind.NATIVE_BUNDLE,
                capabilities = setOf(dev.droidtop.pluginhost.PluginCapability.STATUS_TILE),
                contractVersion = 1,
                requestsRoot = false,
                abis = emptySet(),
                entryClass = "x",
                payload = emptyList(),
            ),
            archiveDigest = digest,
            trust = dev.droidtop.pluginhost.PluginTrustState.APPROVED,
            enabled = true,
            rootApproved = false,
            disabledReason = null,
        )
        val oldDigest = "a".repeat(64)
        val newDigest = "c".repeat(64)
        val index = PluginCatalogIndexParser.parse(
            indexWithPlugin { plugin ->
                val releases = JSONArray()
                releases.put(releaseJson(version = "1.0", manifestSha256 = oldDigest))
                releases.put(releaseJson(version = "1.1", publishedAt = "2026-09-28T13:00:00Z", manifestSha256 = newDigest))
                plugin.put("releases", releases)
            },
        )!!
        assertEquals(newDigest, PluginCatalog.updateFor(index, record(oldDigest))?.manifestSha256)
        assertNull(PluginCatalog.updateFor(index, record(newDigest)))
        // An installed build the catalog does not list (same id, other digest) is offered the newest stable (SPEC 12a).
        assertEquals(newDigest, PluginCatalog.updateFor(index, record("f".repeat(64)))?.manifestSha256)
    }
}
