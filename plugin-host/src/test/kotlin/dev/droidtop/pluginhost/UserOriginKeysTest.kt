package dev.droidtop.pluginhost

import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The user-trusted key store (docs/SPEC.md 12a "Keys you trust"): what
 * may be trusted, what never may (the official origin id), that a
 * changed key is never silently written over a trusted one, and that
 * removal is real.
 */
class UserOriginKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun freshKeyPair(curve: String = "secp256r1") = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair()

    private fun spkiBase64(pair: java.security.KeyPair): String =
        Base64.getEncoder().encodeToString(pair.public.encoded)

    private fun store(): File = tmp.newFile("plugin-user-keys.json")

    @Test
    fun `add persists an origin the user can then load`() {
        val file = store()
        val pair = freshKeyPair()

        val outcome = UserOriginKeys.add(file, "acme", spkiBase64(pair), source = "https://example.com/plugins")

        assertTrue(outcome is AddKeyOutcome.Added)
        val loaded = UserOriginKeys.load(file)
        assertEquals(setOf("acme"), loaded.keys)
        assertEquals(spkiBase64(pair), loaded["acme"]?.keyBase64)
        assertEquals("https://example.com/plugins", loaded["acme"]?.source)
        assertEquals(loaded.mapValues { it.value.keyBase64 }, UserOriginKeys.loadBase64(file))
    }

    @Test
    fun `add refuses the official origin id`() {
        val file = store()
        val pair = freshKeyPair()

        val outcome = UserOriginKeys.add(file, PluginOriginKeys.OFFICIAL_ORIGIN, spkiBase64(pair), source = null)

        assertTrue(outcome is AddKeyOutcome.Refused)
        assertTrue((outcome as AddKeyOutcome.Refused).reason.contains("official"))
        assertTrue(UserOriginKeys.load(file).isEmpty())
    }

    @Test
    fun `add refuses origin ids a plugin id could never carry`() {
        val file = store()
        val key = spkiBase64(freshKeyPair())

        for (bad in listOf("", "   ", "Acme", "my origin", "a.b", "acme_plugins", "acme!")) {
            val outcome = UserOriginKeys.add(file, bad, key, source = null)
            assertTrue("origin \"$bad\" should be refused", outcome is AddKeyOutcome.Refused)
        }
        assertTrue(UserOriginKeys.load(file).isEmpty())
    }

    @Test
    fun `add refuses a key that is neither P-256 nor RSA of 2048 bits or more`() {
        val file = store()

        val garbage = UserOriginKeys.add(file, "acme", "this is not base64 !!!", source = null)
        assertTrue(garbage is AddKeyOutcome.Refused)

        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertTrue(UserOriginKeys.add(file, "acme", Base64.getEncoder().encodeToString(rsa.public.encoded), source = null) is AddKeyOutcome.Refused)

        val p384 = UserOriginKeys.add(file, "acme", spkiBase64(freshKeyPair("secp384r1")), source = null)
        assertTrue(p384 is AddKeyOutcome.Refused)

        assertTrue(UserOriginKeys.load(file).isEmpty())
    }

    @Test
    fun `adding the same key twice is AlreadyTrustedSameKey and writes nothing`() {
        val file = store()
        val pair = freshKeyPair()
        UserOriginKeys.add(file, "acme", spkiBase64(pair), source = null)
        val before = file.readText()

        val outcome = UserOriginKeys.add(file, "acme", spkiBase64(pair), source = null)

        assertEquals(AddKeyOutcome.AlreadyTrustedSameKey, outcome)
        assertEquals(before, file.readText())
    }

    @Test
    fun `a different key for a trusted origin changes nothing`() {
        val file = store()
        val first = freshKeyPair()
        val second = freshKeyPair()
        UserOriginKeys.add(file, "acme", spkiBase64(first), source = "https://example.com/plugins")

        val outcome = UserOriginKeys.add(file, "acme", spkiBase64(second), source = "https://example.com/plugins")

        assertTrue(outcome is AddKeyOutcome.KeyChanged)
        val changed = outcome as AddKeyOutcome.KeyChanged
        assertEquals(spkiBase64(first), changed.stored.keyBase64)
        assertEquals(spkiBase64(second), changed.proposed.keyBase64)
        // The stored key is still the one the user trusted.
        assertEquals(spkiBase64(first), UserOriginKeys.load(file)["acme"]?.keyBase64)
    }

    @Test
    fun `replace writes the new key, and only for an origin already trusted`() {
        val file = store()
        val first = freshKeyPair()
        val second = freshKeyPair()

        assertTrue(UserOriginKeys.replace(file, "acme", spkiBase64(first), source = null) is AddKeyOutcome.Refused)

        UserOriginKeys.add(file, "acme", spkiBase64(first), source = null)
        val outcome = UserOriginKeys.replace(file, "acme", spkiBase64(second), source = null)

        assertTrue(outcome is AddKeyOutcome.Added)
        assertEquals(spkiBase64(second), UserOriginKeys.load(file)["acme"]?.keyBase64)
    }

    @Test
    fun `a confirmed replace makes the new key the old one's successor, and only that`() {
        val file = store()
        val first = freshKeyPair()
        val second = freshKeyPair()
        val third = freshKeyPair()
        val firstSha = UserOriginKeys.keySha256(spkiBase64(first))!!
        val secondSha = UserOriginKeys.keySha256(spkiBase64(second))!!
        UserOriginKeys.add(file, "acme", spkiBase64(first), source = null)
        assertFalse(UserOriginKeys.succeeds(file, "acme", firstSha, secondSha))

        UserOriginKeys.replace(file, "acme", spkiBase64(second), source = null)

        assertEquals(listOf(firstSha), UserOriginKeys.load(file)["acme"]?.previous)
        assertTrue(UserOriginKeys.succeeds(file, "acme", firstSha, secondSha))
        assertFalse("not for another origin", UserOriginKeys.succeeds(file, "other", firstSha, secondSha))
        assertFalse("not for a key that is not the trusted one", UserOriginKeys.succeeds(file, "acme", firstSha, UserOriginKeys.keySha256(spkiBase64(third))!!))
        // A second rotation keeps the whole line.
        UserOriginKeys.replace(file, "acme", spkiBase64(third), source = null)
        assertTrue(UserOriginKeys.succeeds(file, "acme", firstSha, UserOriginKeys.keySha256(spkiBase64(third))!!))
    }

    @Test
    fun `removing a key and adding another records no succession`() {
        val file = store()
        val first = freshKeyPair()
        val second = freshKeyPair()
        UserOriginKeys.add(file, "acme", spkiBase64(first), source = null)
        UserOriginKeys.remove(file, "acme")
        UserOriginKeys.add(file, "acme", spkiBase64(second), source = null)
        assertFalse(UserOriginKeys.succeeds(file, "acme", UserOriginKeys.keySha256(spkiBase64(first))!!, UserOriginKeys.keySha256(spkiBase64(second))!!))
    }

    @Test
    fun `remove stops trusting an origin, and only one that was trusted`() {
        val file = store()
        val pair = freshKeyPair()

        assertFalse(UserOriginKeys.remove(file, "acme"))

        UserOriginKeys.add(file, "acme", spkiBase64(pair), source = null)
        assertTrue(UserOriginKeys.remove(file, "acme"))
        assertTrue(UserOriginKeys.load(file).isEmpty())
        assertFalse(UserOriginKeys.remove(file, "acme"))
    }

    @Test
    fun `fingerprint is sixteen hex characters in four groups, stable per key`() {
        val pair = freshKeyPair()
        val other = freshKeyPair()

        val fingerprint = UserOriginKeys.fingerprint(spkiBase64(pair))

        assertNotNull(fingerprint)
        assertTrue(Regex("[0-9a-f]{4}( [0-9a-f]{4}){3}").matches(fingerprint!!))
        assertEquals(fingerprint, UserOriginKeys.fingerprint(spkiBase64(pair)))
        assertFalse(fingerprint == UserOriginKeys.fingerprint(spkiBase64(other)))
        assertNull(UserOriginKeys.fingerprint("not a key"))
    }

    @Test
    fun `a key pasted with line breaks is stored in one canonical line and still resolves`() {
        val pair = freshKeyPair()
        val file = store()
        // A copy/paste from a PEM-ish dump often carries line breaks.
        val wrapped = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.public.encoded)

        val outcome = UserOriginKeys.add(file, "acme", wrapped, source = null)

        assertTrue(outcome is AddKeyOutcome.Added)
        assertEquals(spkiBase64(pair), UserOriginKeys.load(file)["acme"]?.keyBase64)
        assertNotNull(PluginOriginKeys.anchorFor("acme", UserOriginKeys.loadBase64(file)))
    }

    @Test
    fun `a missing or unreadable store reads as empty, never throws`() {
        assertTrue(UserOriginKeys.load(File(tmp.root, "no-such-store.json")).isEmpty())

        val corrupt = tmp.newFile("corrupt.json")
        corrupt.writeText("{ this is not json")
        assertTrue(UserOriginKeys.load(corrupt).isEmpty())
    }

    @Test
    fun `a key trusted through an added catalog remembers the catalog`() {
        val file = store()
        val key = spkiBase64(freshKeyPair())

        UserOriginKeys.add(file, "acme", key, source = "https://example.com/index.json", catalog = "someone/catalog")

        assertEquals("someone/catalog", UserOriginKeys.load(file)["acme"]?.catalog)
        // Trusted earlier some other way, then accepted through a catalog: the catalog is recorded, the key is untouched.
        UserOriginKeys.add(file, "other", spkiBase64(freshKeyPair()), source = null)
        assertTrue(UserOriginKeys.attachCatalog(file, "other", "someone/catalog"))
        assertEquals("someone/catalog", UserOriginKeys.load(file)["other"]?.catalog)
        assertFalse(UserOriginKeys.attachCatalog(file, "missing", "someone/catalog"))
    }

    // ----- Adding an origin as a certificate (docs/SPEC.md 12a "Signatures: certificate chains to droidtop's signing root") -----

    @Test
    fun `a publisher's certificate stores its own key, and must name the origin if it names any`() {
        val pair = freshKeyPair()
        val named = dev.droidtop.testpki.TestPki.cert("acme", pair.public).eku(dev.droidtop.testpki.TestPki.PLUGIN)
            .uri(dev.droidtop.runtime.util.ChainSignature.ORIGIN_URN + "acme").selfSigned(pair.private).build()
        assertEquals(spkiBase64(pair), UserOriginKeys.anchorKey("acme", dev.droidtop.testpki.TestPki.pem(named)))
        assertNull(UserOriginKeys.anchorKey("other", dev.droidtop.testpki.TestPki.pem(named)))
        val unnamed = dev.droidtop.testpki.TestPki.cert("plain", pair.public).selfSigned(pair.private).build()
        assertEquals(spkiBase64(pair), UserOriginKeys.anchorKey("acme", Base64.getEncoder().encodeToString(unnamed.encoded)))
        val file = store()
        assertTrue(UserOriginKeys.add(file, "acme", dev.droidtop.testpki.TestPki.pem(named), source = null) is AddKeyOutcome.Added)
        assertEquals(spkiBase64(pair), UserOriginKeys.load(file)["acme"]?.keyBase64)
    }

    @Test
    fun `a certificate anchors at its own key whoever issued it`() {
        // No automatic anchoring at droidtop's root: gamegrab is trusted only at the root it publishes.
        val rootKey = dev.droidtop.testpki.TestPki.rsa()
        val root = dev.droidtop.testpki.TestPki.core(rootKey)
        val leaf = dev.droidtop.testpki.TestPki.pluginLeaf(freshKeyPair(), "gamegrab", root, rootKey.private)
        dev.droidtop.runtime.util.DroidtopSigningRoot.withPinned(root.publicKey) {
            assertEquals(Base64.getEncoder().encodeToString(leaf.publicKey.encoded), UserOriginKeys.anchorKey("gamegrab", dev.droidtop.testpki.TestPki.pem(leaf)))
            assertNull(UserOriginKeys.anchorKey("romgi", dev.droidtop.testpki.TestPki.pem(leaf)))
        }
    }
}
