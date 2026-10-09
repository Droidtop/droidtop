package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.runtime.util.Sha256
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Added catalogs (docs/SPEC.md 12a "Added catalogs"): the address rules, the store, the
 * `catalog` and `disclaimer` blocks, the offer rule that matches an index against the
 * person's own trust decisions and never makes one, and the revocation list's shape.
 */
class PluginCatalogSourcesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val spki = Base64.getEncoder().encodeToString(
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded,
    )
    private val otherSpki = Base64.getEncoder().encodeToString(
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded,
    )
    private fun sha(keyBase64: String) = Sha256.hex(Base64.getDecoder().decode(keyBase64))

    private val added = PluginCatalogSource(id = "someone/catalog", name = "Someone", indexUrl = "https://example.com/index.json", acceptedDisclaimer = 1)

    @Test
    fun `addresses become index addresses, https only`() {
        assertEquals(
            "https://raw.githubusercontent.com/someone/catalog/HEAD/index.json",
            PluginCatalogSources.indexUrlFor("https://github.com/someone/catalog/"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/someone/catalog/main/index.json",
            PluginCatalogSources.indexUrlFor("https://github.com/someone/catalog/tree/main"),
        )
        assertEquals("https://example.com/x/index.json", PluginCatalogSources.indexUrlFor("https://example.com/x/index.json"))
        assertEquals("https://example.com/x/index.json", PluginCatalogSources.indexUrlFor("https://example.com/x"))
        assertNull(PluginCatalogSources.indexUrlFor("http://example.com/x/index.json"))
        assertNull(PluginCatalogSources.indexUrlFor("someone/catalog"))
        assertNull(PluginCatalogSources.indexUrlFor("https://github.com/someone"))
        assertEquals("https://example.com/x/revocations.json", PluginCatalogSources.siblingUrl("https://example.com/x/index.json", "revocations.json"))
    }

    @Test
    fun `the store keeps added catalogs and never the official one`() {
        val file = tmp.newFile("plugin-catalogs.json")
        PluginCatalogSources.put(file, added)
        PluginCatalogSources.put(file, added.copy(masterKeyBase64 = spki, indexSigned = true))
        val loaded = PluginCatalogSources.added(file)
        assertEquals(1, loaded.size)
        assertEquals(spki, loaded[0].masterKeyBase64)
        assertTrue(loaded[0].indexSigned)
        assertEquals(1, loaded[0].acceptedDisclaimer)
        assertTrue(runCatching { PluginCatalogSources.put(file, PluginCatalogSources.OFFICIAL) }.isFailure)
        assertTrue(PluginCatalogSources.remove(file, added.id))
        assertFalse(PluginCatalogSources.remove(file, added.id))
        assertTrue(PluginCatalogSources.added(file).isEmpty())
    }

    private fun index(catalog: JSONObject?, disclaimer: JSONObject?, originKey: String = spki): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("generatedAt", "2026-10-08T00:00:00Z")
        catalog?.let { put("catalog", it) }
        disclaimer?.let { put("disclaimer", it) }
        put(
            "origins",
            org.json.JSONArray().put(
                JSONObject()
                    .put("origin", "acme")
                    .put("trust", "third-party")
                    .put(
                        "key",
                        JSONObject().put("formatVersion", 1).put("algorithm", "SHA256withECDSA").put("origin", "acme")
                            .put("publicKeySpki", originKey).put("keySha256", sha(originKey)),
                    )
                    .put(
                        "plugins",
                        org.json.JSONArray().put(
                            JSONObject().put("id", "acme.tool").put("label", "Tool").put(
                                "releases",
                                org.json.JSONArray().put(
                                    JSONObject().put("version", "1.0").put("stream", "stable").put("publishedAt", "2026-10-08T00:00:00Z")
                                        .put("manifestSha256", "a".repeat(64))
                                        .put("bundle", JSONObject().put("name", "b").put("url", "https://x/b").put("size", 1).put("sha256", "b".repeat(64))),
                                ),
                            ),
                        ),
                    ),
            ),
        )
    }.toString()

    private val catalogBlock get() = JSONObject().put("id", "someone/catalog").put("name", "Someone").put("trust", "unofficial")
        .put("homepage", "https://github.com/someone/catalog")
    private val disclaimerBlock get() = JSONObject().put("version", 2).put("text", "Unofficial. Not part of droidtop.")

    @Test
    fun `the catalog and disclaimer blocks are read whole or not at all`() {
        val parsed = PluginCatalogIndexParser.parse(index(catalogBlock, disclaimerBlock))!!
        assertEquals("someone/catalog", parsed.catalog?.id)
        assertEquals("Someone", parsed.catalog?.name)
        assertNull(parsed.catalog?.keyBase64)
        assertEquals(2, parsed.disclaimer?.version)
        assertEquals(spki, parsed.origins[0].keyBase64)
        // droidtop's own index has neither block.
        val official = PluginCatalogIndexParser.parse(index(null, null))!!
        assertNull(official.catalog)
        assertNull(official.disclaimer)
        // A present but broken block refuses the index rather than showing half of it.
        assertNull(PluginCatalogIndexParser.parse(index(catalogBlock, JSONObject().put("version", 0).put("text", "x"))))
        assertNull(PluginCatalogIndexParser.parse(index(catalogBlock.put("id", "no-slash"), disclaimerBlock)))
        val signed = catalogBlock.put(
            "key",
            JSONObject().put("formatVersion", 1).put("algorithm", "SHA256withECDSA").put("publicKeySpki", spki).put("keySha256", sha(spki)),
        )
        assertEquals(spki, PluginCatalogIndexParser.parse(index(signed, disclaimerBlock))?.catalog?.keyBase64)
        // The organisation's origin comes with its master, never without one.
        assertEquals("acme", PluginCatalogIndexParser.parse(index(JSONObject(signed.toString()).put("origin", "acme"), disclaimerBlock))?.catalog?.origin)
        assertNull(PluginCatalogIndexParser.parse(index(catalogBlock.put("origin", "acme"), disclaimerBlock)))
        val lying = catalogBlock.put(
            "key",
            JSONObject().put("formatVersion", 1).put("algorithm", "SHA256withECDSA").put("publicKeySpki", spki).put("keySha256", "c".repeat(64)),
        )
        assertNull(PluginCatalogIndexParser.parse(index(lying, disclaimerBlock)))
    }

    @Test
    fun `an added catalog lists nothing until its current disclaimer is accepted`() {
        val parsed = PluginCatalogIndexParser.parse(index(catalogBlock, disclaimerBlock))!!
        assertFalse(PluginCatalog.listable(added, parsed))
        assertTrue(PluginCatalog.listable(added.copy(acceptedDisclaimer = 2), parsed))
        assertTrue(PluginCatalog.listable(PluginCatalogSources.OFFICIAL, PluginCatalogIndexParser.parse(index(null, null))!!))
    }

    @Test
    fun `an added catalog offers an origin only under the key the person trusts`() {
        val origin = PluginCatalogIndexParser.parse(index(catalogBlock, disclaimerBlock))!!.origins[0]
        fun state(keys: Map<String, UserOriginKey>) = PluginCatalog.originState(added, origin, keys)

        assertEquals(PluginCatalog.OriginState.NotTrusted, state(emptyMap()))
        assertEquals(PluginCatalog.OriginState.Offered, state(mapOf("acme" to UserOriginKey("acme", spki, null, catalog = added.id))))
        // Trusted some other way with the same key: offered too (the index matches the person's decision).
        assertEquals(PluginCatalog.OriginState.Offered, state(mapOf("acme" to UserOriginKey("acme", spki, null))))
        assertTrue(state(mapOf("acme" to UserOriginKey("acme", otherSpki, null))) is PluginCatalog.OriginState.KeyChanged)

        // The official catalog never offers a third-party origin, whatever the person trusts.
        assertFalse(PluginCatalog.originOffered(PluginCatalogSources.OFFICIAL, origin, mapOf("acme" to UserOriginKey("acme", spki, null))))
    }
}
