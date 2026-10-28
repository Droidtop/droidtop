package dev.droidtop.pluginhost

import dev.droidtop.shared.trust.ChainSignature
import dev.droidtop.runtime.util.DroidtopSigningRoot
import dev.droidtop.runtime.util.RevocationList
import dev.droidtop.shared.trust.Sha256
import dev.droidtop.testpki.TestPki
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one bundle-trust decision over `manifest.jws` (docs/SPEC.md 12a "Signatures: certificate chains to
 * Droidtop Core"). Core is a stand-in generated here; so is every other key.
 */
class BundleSignatureTest {
    @get:Rule val tmp = TemporaryFolder()

    private val coreKey = TestPki.rsa()
    private val core = TestPki.core(coreKey)
    private val manifest = "hello plugin manifest".toByteArray()
    private val typ = ChainSignature.Purpose.PLUGIN.typ

    private fun b64(pair: KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun jws(chain: List<X509Certificate>, pair: KeyPair, data: ByteArray = manifest) = TestPki.detached(typ, chain, pair.private, data)

    private fun verdict(
        jws: String,
        origin: String,
        pluginId: String = "$origin.sample",
        userKeys: Map<String, String> = emptyMap(),
        revocations: PluginRevocationList = PluginRevocationList.NONE,
    ) = DroidtopSigningRoot.withPinned(core.publicKey) { BundleSignature.verifyBundle(manifest, jws, origin, pluginId, userKeys, revocations) }

    @Test
    fun `an official bundle verifies through a leaf Core issued for the official origin`() {
        val leafKey = TestPki.ec()
        val leaf = TestPki.pluginLeaf(leafKey, "droidtop", core, coreKey.private)
        val result = verdict(jws(listOf(leaf), leafKey), "droidtop")
        assertEquals(Sha256.hex(core.publicKey.encoded), (result as BundleVerdict.Verified).anchorSha256)
    }

    @Test
    fun `a Core leaf for another origin cannot sign official bundles`() {
        val leafKey = TestPki.ec()
        val gamegrab = TestPki.pluginLeaf(leafKey, "gamegrab", core, coreKey.private)
        assertTrue(verdict(jws(listOf(gamegrab), leafKey), "droidtop") is BundleVerdict.Refused)
    }

    @Test
    fun `a leaf naming plugins signs only those`() {
        val leafKey = TestPki.ec()
        val leaf = TestPki.cert("repo", leafKey.public).eku(TestPki.PLUGIN)
            .uri(ChainSignature.ORIGIN_URN + "droidtop", ChainSignature.PLUGIN_URN + "droidtop.one").issuedBy(core, coreKey.private).build()
        assertTrue(verdict(jws(listOf(leaf), leafKey), "droidtop", "droidtop.one") is BundleVerdict.Verified)
        assertTrue(verdict(jws(listOf(leaf), leafKey), "droidtop", "droidtop.two") is BundleVerdict.Refused)
    }

    @Test
    fun `with no signing root pinned an official bundle is refused, saying so`() {
        val leafKey = TestPki.ec()
        val leaf = TestPki.pluginLeaf(leafKey, "droidtop", core, coreKey.private)
        val result = DroidtopSigningRoot.withPinned(null) { BundleSignature.verifyBundle(manifest, jws(listOf(leaf), leafKey), "droidtop", "droidtop.sample") }
        assertTrue((result as BundleVerdict.Refused).reason.contains("signing root"))
    }

    @Test
    fun `a user-trusted key anchors its origin, and an unknown origin is refused`() {
        val pair = TestPki.ec()
        val cert = TestPki.cert("acme", pair.public).eku(TestPki.PLUGIN).uri(ChainSignature.ORIGIN_URN + "acme").selfSigned(pair.private).build()
        val keys = mapOf("acme" to b64(pair))
        assertTrue(verdict(jws(listOf(cert), pair), "acme", userKeys = keys) is BundleVerdict.Verified)
        assertTrue(verdict(jws(listOf(cert), pair), "acme") is BundleVerdict.Refused)
        // The same certificate cannot speak for another origin the person trusts with the same key.
        assertTrue(verdict(jws(listOf(cert), pair), "other", userKeys = mapOf("other" to b64(pair))) is BundleVerdict.Refused)
    }

    @Test
    fun `a third-party root the person trusts anchors its own leaves, never droidtop's`() {
        // gamegrab has its own root (owner, 2026-10-15: "The gamegrab repo and plugins obviously get their own keys").
        val rootKey = TestPki.ec()
        val root = TestPki.cert("gamegrab root", rootKey.public).ca(0).selfSigned(rootKey.private).build()
        val leafKey = TestPki.ec()
        val leaf = TestPki.pluginLeaf(leafKey, "gamegrab", root, rootKey.private)
        val keys = mapOf("gamegrab" to b64(rootKey))
        val result = verdict(jws(listOf(leaf), leafKey), "gamegrab", userKeys = keys)
        assertEquals(Sha256.hex(rootKey.public.encoded), (result as BundleVerdict.Verified).anchorSha256)
        // A gamegrab leaf droidtop's own root issued does not verify under gamegrab's root.
        val byDroidtop = TestPki.pluginLeaf(leafKey, "gamegrab", core, coreKey.private)
        assertTrue(verdict(jws(listOf(byDroidtop), leafKey), "gamegrab", userKeys = keys) is BundleVerdict.Refused)
    }

    @Test
    fun `the official origin resolves to Core even when a user key claims it`() {
        val forger = TestPki.ec()
        val cert = TestPki.cert("forger", forger.public).eku(TestPki.PLUGIN).uri(ChainSignature.ORIGIN_URN + "droidtop").selfSigned(forger.private).build()
        assertTrue(verdict(jws(listOf(cert), forger), "droidtop", userKeys = mapOf("droidtop" to b64(forger))) is BundleVerdict.Refused)
    }

    @Test
    fun `Core's list revokes Core-anchored chains, an origin's own list only that origin`() {
        val leafKey = TestPki.ec()
        val leaf = TestPki.pluginLeaf(leafKey, "droidtop", core, coreKey.private)
        val revokesLeaf = RevocationList(1, setOf(Sha256.hex(leaf.encoded)), emptySet())
        assertTrue(verdict(jws(listOf(leaf), leafKey), "droidtop", revocations = PluginRevocationList(revokesLeaf)) is BundleVerdict.Refused)

        val pair = TestPki.ec()
        val acme = TestPki.cert("acme", pair.public).eku(TestPki.PLUGIN).uri(ChainSignature.ORIGIN_URN + "acme").selfSigned(pair.private).build()
        val keys = mapOf("acme" to b64(pair))
        val byKey = RevocationList(1, emptySet(), setOf(Sha256.hex(pair.public.encoded)))
        // droidtop's list does not reach a user-anchored origin; that origin's own list does.
        assertTrue(verdict(jws(listOf(acme), pair), "acme", userKeys = keys, revocations = PluginRevocationList(byKey)) is BundleVerdict.Verified)
        val own = PluginRevocationList(RevocationList.NONE, mapOf("acme" to byKey))
        assertTrue(verdict(jws(listOf(acme), pair), "acme", userKeys = keys, revocations = own) is BundleVerdict.Refused)
    }

    @Test
    fun `approval carries across official anchors, not across other keys`() {
        DroidtopSigningRoot.withPinned(core.publicKey) {
            val coreSha = Sha256.hex(core.publicKey.encoded)
            val legacy = LegacyBundleSignature.officialFingerprints()
            assertTrue(legacy.isNotEmpty())
            legacy.forEach { assertTrue(PluginOriginKeys.sameTrustAnchor(it, coreSha)) }
            assertFalse(PluginOriginKeys.sameTrustAnchor("a".repeat(64), coreSha))
            assertFalse(PluginOriginKeys.sameTrustAnchor("", coreSha))
        }
    }

    @Test
    fun `a user key removed from the store no longer verifies`() {
        val pair = TestPki.ec()
        val cert = TestPki.cert("acme", pair.public).eku(TestPki.PLUGIN).uri(ChainSignature.ORIGIN_URN + "acme").selfSigned(pair.private).build()
        val store = tmp.newFile("plugin-user-keys.json")
        UserOriginKeys.add(store, "acme", TestPki.pem(cert), source = "https://example.com/plugins")
        assertTrue(verdict(jws(listOf(cert), pair), "acme", userKeys = UserOriginKeys.loadBase64(store)) is BundleVerdict.Verified)
        assertTrue(UserOriginKeys.remove(store, "acme"))
        assertTrue(verdict(jws(listOf(cert), pair), "acme", userKeys = UserOriginKeys.loadBase64(store)) is BundleVerdict.Refused)
    }

    @Test
    fun `sha256 matches a known vector`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
    }
}
