package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.UserOriginKey
import dev.droidtop.runtime.util.CatalogSignature
import dev.droidtop.runtime.util.Sha256
import java.security.KeyPair
import java.security.Signature
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
    fun `catalog links name an address or a plugin and nothing else`() {
        val repo = "https%3A%2F%2Fgithub.com%2Fgamegrab-sources%2Fcatalog"
        assertEquals("https://github.com/gamegrab-sources/catalog", PluginCatalogSources.addressFromLink("droidtop://add-catalog?address=$repo"))
        assertEquals(
            "https://github.com/gamegrab-sources/catalog",
            PluginCatalogSources.addressFromLink("https://droidtop.github.io/add-catalog/?address=$repo"),
        )
        assertNull(PluginCatalogSources.addressFromLink("droidtop://add-catalog?address=http%3A%2F%2Fx"))
        assertNull(PluginCatalogSources.addressFromLink("https://example.com/add-catalog?address=$repo"))
        assertNull(PluginCatalogSources.addressFromLink("droidtop://install-plugin?catalog=$repo&id=a.b"))
        assertNull(PluginCatalogSources.addressFromLink("droidtop://add-catalog?address=%zz"))

        val install = PluginCatalogSources.installFromLink("droidtop://install-plugin?catalog=$repo&id=bi0shacker001.romgi-3ds-decrypt")
        assertEquals(PluginCatalogSources.InstallLink("https://github.com/gamegrab-sources/catalog", "bi0shacker001.romgi-3ds-decrypt"), install)
        // droidtop's own catalog needs no address.
        assertEquals(
            PluginCatalogSources.InstallLink(null, "droidtop.retroarch"),
            PluginCatalogSources.installFromLink("https://droidtop.github.io/install-plugin?id=droidtop.retroarch"),
        )
        assertNull(PluginCatalogSources.installFromLink("droidtop://install-plugin?catalog=$repo"))
        assertNull(PluginCatalogSources.installFromLink("droidtop://install-plugin?catalog=http%3A%2F%2Fx&id=a.b"))
        assertNull(PluginCatalogSources.installFromLink("droidtop://install-plugin?id=..%2F..%2Fx"))
        assertNull(PluginCatalogSources.installFromLink("droidtop://add-catalog?address=$repo&id=a.b"))
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

    // ----- A signed catalog: accepted signed, and from then on an unsigned or foreign copy is refused -----

    private val now = 1_800_000_000L
    private val url = "https://example.com/index.json"

    private fun pair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun sign(pair: KeyPair, data: ByteArray) =
        b64(Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(data) }.sign())

    /** index.cert as the catalog workflow publishes it: the catalog key, certified by the organisation's master. */
    private fun catalogCert(master: KeyPair, catalogKey: KeyPair, idField: String = "certId"): String {
        val spki = b64(catalogKey.public.encoded)
        val signed = CatalogSignature.signedBytes("someone/catalog#catalog0", listOf("someone/catalog"), spki, now - 86_400, now + 86_400 * 365)
        return JSONObject()
            .put("formatVersion", 1)
            .put(idField, "someone/catalog#catalog0")
            .put("catalogs", org.json.JSONArray(listOf("someone/catalog")))
            .put("publicKeySpki", spki)
            .put("keySha256", Sha256.hex(catalogKey.public.encoded))
            .put("notBefore", now - 86_400)
            .put("notAfter", now + 86_400 * 365)
            .put("issuer", JSONObject().put("keySha256", Sha256.hex(master.public.encoded)).put("signature", sign(master, signed)))
            .toString()
    }

    private fun signedIndex(master: KeyPair): String {
        val masterSpki = b64(master.public.encoded)
        val key = JSONObject().put("formatVersion", 1).put("algorithm", "SHA256withECDSA").put("publicKeySpki", masterSpki).put("keySha256", sha(masterSpki))
        return index(catalogBlock.put("origin", "gamegrab").put("key", key), disclaimerBlock)
    }

    private fun check(text: String, expected: PluginCatalogSource?, files: Map<String, String>) =
        PluginCatalog.checkAddedIndex(url, text, expected, now) { files[it] }

    private fun refused(text: String, expected: PluginCatalogSource?, files: Map<String, String>): Boolean =
        runCatching { check(text, expected, files) }.exceptionOrNull() is IllegalStateException

    @Test
    fun `a signed catalog is accepted, and an unsigned copy is refused afterwards`() {
        val master = pair()
        val catalogKey = pair()
        val text = signedIndex(master)
        val files = mapOf(
            PluginCatalogSources.INDEX_SIGNATURE_FILE to sign(catalogKey, text.toByteArray()),
            PluginCatalogSources.INDEX_CERTIFICATE_FILE to catalogCert(master, catalogKey),
        )

        // Adding: the signature verifies under the master the index names, which the person then accepts.
        val first = check(text, expected = null, files = files)
        assertTrue(first.signed)
        assertEquals("gamegrab", first.index.catalog?.origin)
        val accepted = added.copy(masterKeyBase64 = b64(master.public.encoded), indexSigned = true)
        assertTrue(check(text, accepted, files).signed)

        // Afterwards: no signature, a changed index, or a signature from a key the master did not certify is refused.
        assertTrue(refused(text, accepted, emptyMap()))
        assertTrue(refused(text.replace("Someone", "Somebody"), accepted, files))
        assertTrue(refused(text, accepted, files + (PluginCatalogSources.INDEX_SIGNATURE_FILE to sign(pair(), text.toByteArray()))))
        // Before any signature was seen an unsigned copy is fine (the review says it is not signed).
        assertFalse(check(text, added.copy(masterKeyBase64 = b64(master.public.encoded)), emptyMap()).signed)
    }

    @Test
    fun `another master is refused once one was accepted, even with a valid signature under it`() {
        val master = pair()
        val other = pair()
        val catalogKey = pair()
        val text = signedIndex(other)
        val files = mapOf(
            PluginCatalogSources.INDEX_SIGNATURE_FILE to sign(catalogKey, text.toByteArray()),
            PluginCatalogSources.INDEX_CERTIFICATE_FILE to catalogCert(other, catalogKey),
        )
        assertTrue(check(text, expected = null, files = files).signed)
        assertTrue(refused(text, added.copy(masterKeyBase64 = b64(master.public.encoded), indexSigned = true), files))
    }

    /** The certificate format reads `certId`; a certificate whose id field is spelled otherwise is not one. */
    @Test
    fun `a certificate whose id field is not certId is refused`() {
        val master = pair()
        val catalogKey = pair()
        val text = signedIndex(master)
        val files = mapOf(
            PluginCatalogSources.INDEX_SIGNATURE_FILE to sign(catalogKey, text.toByteArray()),
            PluginCatalogSources.INDEX_CERTIFICATE_FILE to catalogCert(master, catalogKey, idField = "id"),
        )
        assertTrue(refused(text, expected = null, files = files))
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

    @Test
    fun `install all covers what the catalog offers under a key the person trusts, and nothing before`() {
        val parsed = PluginCatalogIndexParser.parse(index(catalogBlock, disclaimerBlock))!!
        val accepted = added.copy(acceptedDisclaimer = 2)
        val trusted = mapOf("acme" to UserOriginKey("acme", spki, null, catalog = added.id))
        assertEquals(listOf("acme.tool"), PluginCatalog.installable(accepted, parsed, emptyList(), trusted).map { it.first.id })
        // Not trusted yet, a different key, or a notice not yet accepted: nothing is installed in bulk.
        assertTrue(PluginCatalog.installable(accepted, parsed, emptyList(), emptyMap()).isEmpty())
        assertTrue(PluginCatalog.installable(accepted, parsed, emptyList(), mapOf("acme" to UserOriginKey("acme", otherSpki, null))).isEmpty())
        assertTrue(PluginCatalog.installable(added, parsed, emptyList(), trusted).isEmpty())
    }

    @Test
    fun `an add-catalog link carries only an https catalog address`() {
        val address = "https://github.com/gamegrab-sources/catalog"
        val encoded = java.net.URLEncoder.encode(address, "UTF-8")
        assertEquals(address, PluginCatalogSources.addressFromLink("droidtop://add-catalog?address=$encoded"))
        assertEquals(address, PluginCatalogSources.addressFromLink("droidtop://add-catalog?url=$encoded"))
        assertEquals(address, PluginCatalogSources.addressFromLink("https://droidtop.github.io/add-catalog?address=$encoded"))
        assertEquals(address, PluginCatalogSources.addressFromLink("DROIDTOP://ADD-CATALOG?address=$address"))
        // Not a catalog link, no address, an address that is not https, or a link on another host.
        assertNull(PluginCatalogSources.addressFromLink("droidtop://open?address=$encoded"))
        assertNull(PluginCatalogSources.addressFromLink("droidtop://add-catalog"))
        assertNull(PluginCatalogSources.addressFromLink("droidtop://add-catalog?address=http%3A%2F%2Fexample.com%2Fx"))
        assertNull(PluginCatalogSources.addressFromLink("https://example.com/add-catalog?address=$encoded"))
        assertNull(PluginCatalogSources.addressFromLink("not a link"))
    }

    @Test
    fun `a known catalog is offered until it is added`() {
        assertEquals(listOf("gamegrab-sources/catalog"), PluginCatalogSources.knownNotAdded(emptyList()).map { it.id })
        val gamegrab = added.copy(id = "gamegrab-sources/catalog")
        assertTrue(PluginCatalogSources.knownNotAdded(listOf(gamegrab)).isEmpty())
        // Every known catalog is reachable by the same address rule a typed one is.
        PluginCatalogSources.KNOWN.forEach { assertNotNull(PluginCatalogSources.indexUrlFor(it.address)) }
    }
}
