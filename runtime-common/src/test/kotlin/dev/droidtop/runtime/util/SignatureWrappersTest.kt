package dev.droidtop.runtime.util

import dev.droidtop.shared.trust.ChainSignature
import dev.droidtop.shared.trust.Sha256
import dev.droidtop.testpki.TestPki
import java.security.KeyPair
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What droidtop adds around the shared certificate-chain verifier (vendor/droidtop-shared, `:trust`, whose own tests cover the
 * format): its revocation list, the catalog signature and the pinned signing root (docs/SPEC.md 12a). Every key and
 * certificate here is generated in the test; Core is a stand-in.
 */
class SignatureWrappersTest {
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

    private fun verify(jws: String, content: ByteArray? = manifest, on: ChainSignature.Anchor = anchor) =
        ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, on, content)

    @Test
    fun `expiry never invalidates a signature, only revocation does`() {
        // Owner, 2026-10-15: "Can we ignore its expiration date? We don't want older plugins to be invalidated".
        val expired = TestPki.cert("old", leafKey.public).eku(TestPki.PLUGIN).uri("urn:droidtop:origin:droidtop")
            .valid(Date((now - 86_400 * 30) * 1000), Date((now - 86_400) * 1000)).issuedBy(core, coreKey.private).build()
        val notYet = TestPki.cert("future", leafKey.public).eku(TestPki.PLUGIN).uri("urn:droidtop:origin:droidtop")
            .valid(Date((now + 86_400) * 1000), Date((now + 86_400 * 30) * 1000)).issuedBy(core, coreKey.private).build()
        for (cert in listOf(expired, notYet)) {
            val jws = TestPki.detached(pluginTyp, listOf(cert), leafKey.private, manifest)
            assertTrue(verify(jws) is ChainSignature.Result.Verified)
            val revoked = RevocationList(1, setOf(Sha256.hex(cert.encoded)), emptySet())
            assertTrue(ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, anchor, manifest, revoked::revokes) is ChainSignature.Result.Refused)
        }
    }

    @Test
    fun `revocation by certificate or key refuses the chain`() {
        val jws = TestPki.detached(pluginTyp, listOf(leaf), leafKey.private, manifest)
        val byCert = RevocationList(1, setOf(Sha256.hex(leaf.encoded)), emptySet())
        val byKey = RevocationList(1, emptySet(), setOf(Sha256.hex(leaf.publicKey.encoded)))
        assertTrue(ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, anchor, manifest, byCert::revokes) is ChainSignature.Result.Refused)
        assertTrue(ChainSignature.verify(jws, ChainSignature.Purpose.PLUGIN, anchor, manifest, byKey::revokes) is ChainSignature.Result.Refused)
    }

    // ----- Catalogs -----

    private fun catalogLeaf(pair: KeyPair, id: String) =
        TestPki.cert("catalog", pair.public).eku(TestPki.CATALOG).uri("urn:droidtop:catalog:$id").issuedBy(core, coreKey.private).build()

    @Test
    fun `a catalog signature names its catalog`() {
        val key = TestPki.ec()
        val bytes = """{"format":1}""".toByteArray()
        val good = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(catalogLeaf(key, CatalogSignature.CATALOG_ID)), key.private, bytes)
        assertTrue(CatalogSignature.verify(bytes, good, anchor) is CatalogSignature.Verdict.Verified)
        val other = TestPki.detached(ChainSignature.Purpose.CATALOG.typ, listOf(catalogLeaf(key, "Someone/else")), key.private, bytes)
        assertTrue(CatalogSignature.verify(bytes, other, anchor) is CatalogSignature.Verdict.Refused)
        assertTrue(CatalogSignature.verify(bytes, null, anchor) is CatalogSignature.Verdict.Refused)
        assertEquals(CatalogSignature.Verdict.Unpinned, CatalogSignature.verify(bytes, null, null))
        // A plugin signature over the same bytes is not a catalog signature.
        val plugin = TestPki.detached(pluginTyp, listOf(leaf), leafKey.private, bytes)
        assertTrue(CatalogSignature.verify(bytes, plugin, anchor) is CatalogSignature.Verdict.Refused)
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
        val parsed = RevocationList.parse(list(), anchor, "droidtop")!!
        assertEquals(2L, parsed.sequence)
        assertTrue(parsed.revokes(leaf))
        assertTrue(parsed.revokesKey("a".repeat(64)))
        assertNull(RevocationList.parse(list(signerCatalog = "someone/else"), anchor, "droidtop"))
        assertNull(RevocationList.parse(list(catalog = "someone/else", signerCatalog = "droidtop"), anchor, "droidtop"))
        assertNull(RevocationList.parse(list(sequence = 0), anchor, "droidtop"))
        assertNull(RevocationList.parse(list(), ChainSignature.Anchor(TestPki.rsa().public), "droidtop"))
    }

    @Test
    fun `the pinned signing root is a public key resource, and a placeholder pins none`() {
        // The repository ships a placeholder until the coordinator commits the root's public key.
        val shipped = DroidtopSigningRoot::class.java.classLoader!!.getResourceAsStream(DroidtopSigningRoot.RESOURCE)!!.use { it.readBytes().toString(Charsets.US_ASCII) }
        if (!shipped.contains("BEGIN PUBLIC KEY")) assertNull(DroidtopSigningRoot.key())
        assertNull(DroidtopSigningRoot.parse(shipped.lines().filter { it.startsWith("#") }.joinToString("\n")))
        val pem = "-----BEGIN PUBLIC KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(core.publicKey.encoded) + "\n-----END PUBLIC KEY-----\n"
        assertEquals(Sha256.hex(core.publicKey.encoded), Sha256.hex(DroidtopSigningRoot.parse(pem)!!.encoded))
        DroidtopSigningRoot.withPinned(core.publicKey) {
            assertEquals(Sha256.hex(core.publicKey.encoded), DroidtopSigningRoot.keySha256())
        }
    }
}
