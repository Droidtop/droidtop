package dev.droidtop.runtime.util

import dev.droidtop.testpki.TestPki
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x509.KeyUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The certificate signature format (docs/SPEC.md 12a "Signatures: certificate chains to Droidtop Core",
 * Droidtop/tracker#550). Every key and certificate here is generated in the test; Core is a stand-in.
 */
class ChainSignatureTest {
    private val ucmRootKey = TestPki.rsa()
    private val ucmRoot = TestPki.cert("UCM root (test)", ucmRootKey.public).ca(-1).selfSigned(ucmRootKey.private).build()
    private val coreKey = TestPki.rsa()

    /** Core as the owner's CA issues it: under the UCM root, which droidtop must never need. */
    private val core = TestPki.cert("Droidtop Core (test)", coreKey.public).ca(0).issuedBy(ucmRoot, ucmRootKey.private).build()
    private val anchor = ChainSignature.Anchor(core.publicKey, pinned = true)
    private val leafKey = TestPki.ec()
    private val leaf = TestPki.pluginLeaf(leafKey, "droidtop", core, coreKey.private)
    private val manifest = """{"id":"droidtop.example","origin":"droidtop"}""".toByteArray()
    private val now = System.currentTimeMillis() / 1000
    private val pluginTyp = ChainSignature.Purpose.PLUGIN.typ

    private fun verify(jws: String, content: ByteArray? = manifest, on: ChainSignature.Anchor = anchor, at: Long? = now) =
        ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, on, content, at)

    private fun refusedFor(result: ChainSignature.Result): String = (result as ChainSignature.Result.Refused).reason

    @Test
    fun `a leaf issued by Core verifies, and stops at Core`() {
        val result = verify(TestPki.detached(pluginTyp, listOf(leaf), leafKey.private, manifest))
        assertTrue(result is ChainSignature.Result.Verified)
        assertEquals(anchor.sha256, (result as ChainSignature.Result.Verified).anchor.sha256)
        assertTrue(ChainSignature.names(result.leaf, ChainSignature.ORIGIN_URN, "droidtop"))
    }

    @Test
    fun `an RSA leaf signs with RS256`() {
        val rsa = TestPki.rsa()
        val rsaLeaf = TestPki.pluginLeaf(rsa, "droidtop", core, coreKey.private)
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(rsaLeaf), rsa.private, manifest)) is ChainSignature.Result.Verified)
    }

    @Test
    fun `changed content or another signer is refused`() {
        val jws = TestPki.detached(pluginTyp, listOf(leaf), leafKey.private, manifest)
        assertTrue(verify(jws, content = manifest + ' '.code.toByte()) is ChainSignature.Result.Refused)
        val other = TestPki.ec()
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(leaf), other.private, manifest)) is ChainSignature.Result.Refused)
    }

    @Test
    fun `a chain that includes Core or reaches past it is refused`() {
        assertTrue(refusedFor(verify(TestPki.detached(pluginTyp, listOf(leaf, core), leafKey.private, manifest))).contains("Droidtop Core"))
        // Signed by the owner's wider CA, not by Core: untrusted, even with the UCM root in the chain.
        val underRoot = TestPki.pluginLeaf(leafKey, "droidtop", ucmRoot, ucmRootKey.private)
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(underRoot), leafKey.private, manifest)) is ChainSignature.Result.Refused)
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(underRoot, ucmRoot), leafKey.private, manifest)) is ChainSignature.Result.Refused)
    }

    @Test
    fun `a leaf issued under the Core name by another key is refused`() {
        val impostorKey = TestPki.rsa()
        val impostor = TestPki.cert("x", leafKey.public).eku(TestPki.PLUGIN).uri("urn:droidtop:origin:droidtop")
            .issuedByKey("Droidtop Core (test)", impostorKey.private).build()
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(impostor), leafKey.private, manifest)) is ChainSignature.Result.Refused)
    }

    @Test
    fun `the leaf must be a signing certificate for this purpose`() {
        fun leafWith(build: (TestPki.Cert) -> TestPki.Cert): X509Certificate =
            build(TestPki.cert("l", leafKey.public).uri("urn:droidtop:origin:droidtop")).issuedBy(core, coreKey.private).build()
        val cases = mapOf(
            "no EKU" to leafWith { it },
            "catalog EKU" to leafWith { it.eku(TestPki.CATALOG) },
            "CA leaf" to leafWith { it.eku(TestPki.PLUGIN).ca(0) },
            "no digitalSignature" to leafWith { it.eku(TestPki.PLUGIN).keyUsage(KeyUsage.nonRepudiation) },
            "keyCertSign" to leafWith { it.eku(TestPki.PLUGIN).keyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign) },
            "no key usage" to leafWith { it.eku(TestPki.PLUGIN).noKeyUsage() },
        )
        cases.forEach { (name, cert) ->
            assertTrue(name, verify(TestPki.detached(pluginTyp, listOf(cert), leafKey.private, manifest)) is ChainSignature.Result.Refused)
        }
    }

    @Test
    fun `the typ binds the purpose`() {
        val jws = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(leaf), leafKey.private, manifest)
        assertTrue(verify(jws) is ChainSignature.Result.Refused)
    }

    @Test
    fun `validity is checked only when a time is given`() {
        val expired = TestPki.cert("old", leafKey.public).eku(TestPki.PLUGIN).uri("urn:droidtop:origin:droidtop")
            .valid(Date((now - 86_400 * 30) * 1000), Date((now - 86_400) * 1000)).issuedBy(core, coreKey.private).build()
        val jws = TestPki.detached(pluginTyp, listOf(expired), leafKey.private, manifest)
        assertTrue(verify(jws) is ChainSignature.Result.Refused)
        assertTrue(verify(jws, at = null) is ChainSignature.Result.Verified)
    }

    @Test
    fun `revocation by certificate or key refuses the chain`() {
        val jws = TestPki.detached(pluginTyp, listOf(leaf), leafKey.private, manifest)
        val byCert = RevocationList(1, setOf(Sha256.hex(leaf.encoded)), emptySet())
        val byKey = RevocationList(1, emptySet(), setOf(Sha256.hex(leaf.publicKey.encoded)))
        assertTrue(ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, anchor, manifest, now, byCert::revokes) is ChainSignature.Result.Refused)
        assertTrue(ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, anchor, manifest, now, byKey::revokes) is ChainSignature.Result.Refused)
    }

    @Test
    fun `a trusted key anchors its own chain, self-signed or through an intermediate`() {
        val publisher = TestPki.ec()
        val selfSigned = TestPki.cert("acme", publisher.public).eku(TestPki.PLUGIN).uri("urn:droidtop:origin:acme").selfSigned(publisher.private).build()
        val trusted = ChainSignature.Anchor(publisher.public)
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(selfSigned), publisher.private, manifest), on = trusted) is ChainSignature.Result.Verified)

        val orgKey = TestPki.rsa()
        val org = TestPki.cert("acme org", orgKey.public).ca(1).selfSigned(orgKey.private).build()
        val repoKey = TestPki.ec()
        val repo = TestPki.pluginLeaf(repoKey, "acme", org, orgKey.private)
        val orgAnchor = ChainSignature.Anchor(orgKey.public)
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(repo), repoKey.private, manifest), on = orgAnchor) is ChainSignature.Result.Verified)
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(repo, org), repoKey.private, manifest), on = orgAnchor) is ChainSignature.Result.Verified)
        // The same chain under a key the person does not trust.
        assertTrue(verify(TestPki.detached(pluginTyp, listOf(repo), repoKey.private, manifest), on = trusted) is ChainSignature.Result.Refused)
    }

    @Test
    fun `an intermediate must be a CA within its path length`() {
        val orgKey = TestPki.rsa()
        val org = TestPki.cert("org", orgKey.public).ca(-1).selfSigned(orgKey.private).build()
        val subKey = TestPki.rsa()
        val notCa = TestPki.cert("sub", subKey.public).issuedBy(org, orgKey.private).build()
        val repoKey = TestPki.ec()
        val repo = TestPki.pluginLeaf(repoKey, "acme", notCa, subKey.private)
        val result = verify(TestPki.detached(pluginTyp, listOf(repo, notCa), repoKey.private, manifest), on = ChainSignature.Anchor(orgKey.public))
        assertTrue(result is ChainSignature.Result.Refused)
    }

    @Test
    fun `plugin names narrow what a leaf may sign`() {
        val narrow = TestPki.cert("n", leafKey.public).eku(TestPki.PLUGIN)
            .uri("urn:droidtop:origin:droidtop", "urn:droidtop:plugin:droidtop.example", "urn:droidtop:plugin:droidtop.tools.*")
            .issuedBy(core, coreKey.private).build()
        assertTrue(ChainSignature.coversPlugin(narrow, "droidtop.example"))
        assertTrue(ChainSignature.coversPlugin(narrow, "droidtop.tools.one"))
        assertFalse(ChainSignature.coversPlugin(narrow, "droidtop.tools."))
        assertFalse(ChainSignature.coversPlugin(narrow, "droidtop.other"))
        assertTrue(ChainSignature.coversPlugin(leaf, "droidtop.anything"))
    }

    @Test
    fun `malformed signatures are refused, never thrown`() {
        listOf("", "a.b", "a.b.c", "..", TestPki.header(pluginTyp, emptyList(), leafKey.private) + "..AAAA").forEach {
            assertTrue(it, verify(it) is ChainSignature.Result.Refused)
        }
    }

    @Test
    fun `keys are P-256 or RSA of at least 2048 bits`() {
        assertTrue(ChainSignature.parseSpki(java.util.Base64.getEncoder().encodeToString(leafKey.public.encoded)) != null)
        assertTrue(ChainSignature.parseSpki(java.util.Base64.getEncoder().encodeToString(coreKey.public.encoded)) != null)
        val small = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertNull(ChainSignature.parseSpki(java.util.Base64.getEncoder().encodeToString(small.public.encoded)))
        assertNull(ChainSignature.parseSpki("not a key"))
    }

    // ----- Catalogs -----

    private fun catalogLeaf(pair: KeyPair, id: String) =
        TestPki.cert("catalog", pair.public).eku(TestPki.CATALOG).uri("urn:droidtop:catalog:$id").issuedBy(core, coreKey.private).build()

    @Test
    fun `a catalog signature names its catalog`() {
        val key = TestPki.ec()
        val bytes = """{"format":1}""".toByteArray()
        val good = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(catalogLeaf(key, CatalogSignature.CATALOG_ID)), key.private, bytes)
        assertTrue(CatalogSignature.verify(bytes, good, anchor, now) is CatalogSignature.Verdict.Verified)
        val other = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(catalogLeaf(key, "Someone/else")), key.private, bytes)
        assertTrue(CatalogSignature.verify(bytes, other, anchor, now) is CatalogSignature.Verdict.Refused)
        assertTrue(CatalogSignature.verify(bytes, null, anchor, now) is CatalogSignature.Verdict.Refused)
        assertEquals(CatalogSignature.Verdict.Unpinned, CatalogSignature.verify(bytes, null, null, now))
        // A plugin signature over the same bytes is not a catalog signature.
        val plugin = TestPki.detached(pluginTyp, listOf(leaf), leafKey.private, bytes)
        assertTrue(CatalogSignature.verify(bytes, plugin, anchor, now) is CatalogSignature.Verdict.Refused)
    }

    // ----- Revocation lists -----

    private fun revocationSigner(pair: KeyPair, catalog: String) =
        TestPki.cert("revocations", pair.public).eku(TestPki.REVOCATIONS).uri("urn:droidtop:catalog:$catalog").issuedBy(core, coreKey.private).build()

    private fun list(catalog: String = "droidtop", sequence: Long = 2, signerCatalog: String = catalog): String {
        val key = TestPki.ec()
        val body = """{"type":"droidtop-revocations","version":1,"catalog":"$catalog","sequence":$sequence,"issuedAt":$now,
            "certSha256":["${Sha256.hex(leaf.encoded)}"],"keySha256":["${"A".repeat(64)}"]}"""
        return TestPki.attached(ChainSignature.Purpose.REVOCATIONS.typ, listOf(revocationSigner(key, signerCatalog)), key.private, body.toByteArray())
    }

    @Test
    fun `a revocation list verifies under its anchor and names its catalog`() {
        val parsed = RevocationList.parse(list(), anchor, "droidtop", now)!!
        assertEquals(2L, parsed.sequence)
        assertTrue(parsed.revokes(leaf))
        assertTrue(parsed.revokesKey("a".repeat(64)))
        assertNull(RevocationList.parse(list(signerCatalog = "someone/else"), anchor, "droidtop", now))
        assertNull(RevocationList.parse(list(catalog = "someone/else", signerCatalog = "droidtop"), anchor, "droidtop", now))
        assertNull(RevocationList.parse(list(sequence = 0), anchor, "droidtop", now))
        assertNull(RevocationList.parse(list(), ChainSignature.Anchor(TestPki.rsa().public), "droidtop", now))
    }

    /**
     * Android's provider is Conscrypt (BoringSSL), whose getExtendedKeyUsage() throws on droidtop's UUID arc;
     * found when :library-core's tests ran after a Robolectric test had installed it. Every check must hold under it.
     */
    @Test
    fun `verification holds under Conscrypt, the provider a device uses`() {
        val provider = org.conscrypt.Conscrypt.newProvider()
        java.security.Security.insertProviderAt(provider, 1)
        try {
            val parsed = ChainSignature.parseCertificate(java.util.Base64.getEncoder().encodeToString(leaf.encoded))!!
            assertTrue(parsed.javaClass.name, parsed.javaClass.name.startsWith("org.conscrypt"))
            assertTrue(verify(TestPki.detached(pluginTyp, listOf(leaf), leafKey.private, manifest)) is ChainSignature.Result.Verified)
            val catalogTyp = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(leaf), leafKey.private, manifest)
            assertTrue(ChainSignature.verify(catalogTyp, ChainSignature.Purpose.CATALOG, anchor, manifest, now) is ChainSignature.Result.Refused)
            val rsa = TestPki.rsa()
            val rsaLeaf = TestPki.pluginLeaf(rsa, "droidtop", core, coreKey.private)
            assertTrue(verify(TestPki.detached(pluginTyp, listOf(rsaLeaf), rsa.private, manifest)) is ChainSignature.Result.Verified)
        } finally {
            java.security.Security.removeProvider(provider.name)
        }
    }

    @Test
    fun `the pinned Core is a public key resource, and a placeholder pins none`() {
        // The repository ships a placeholder until the coordinator commits Core's public key.
        val shipped = DroidtopCore::class.java.classLoader!!.getResourceAsStream(DroidtopCore.RESOURCE)!!.use { it.readBytes().toString(Charsets.US_ASCII) }
        if (!shipped.contains("BEGIN PUBLIC KEY")) assertNull(DroidtopCore.key())
        assertNull(DroidtopCore.parse(shipped.lines().filter { it.startsWith("#") }.joinToString("\n")))
        val pem = "-----BEGIN PUBLIC KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(core.publicKey.encoded) + "\n-----END PUBLIC KEY-----\n"
        assertEquals(Sha256.hex(core.publicKey.encoded), Sha256.hex(DroidtopCore.parse(pem)!!.encoded))
        DroidtopCore.withPinned(core.publicKey) {
            assertEquals(Sha256.hex(core.publicKey.encoded), DroidtopCore.keySha256())
            assertTrue(DroidtopCore.issued(leaf))
            assertFalse(DroidtopCore.issued(core))
        }
    }
}
