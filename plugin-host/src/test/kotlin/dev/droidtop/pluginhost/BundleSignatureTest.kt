package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.Sha256

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BundleSignatureTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun generateKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun sign(privateKey: java.security.PrivateKey, data: ByteArray): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(privateKey)
                update(data)
            }.sign(),
        )

    /** The manifest signature through the one bundle-trust decision, with no certificate. */
    private fun verifies(data: ByteArray, signatureBase64: String, origin: String, userKeys: Map<String, String> = emptyMap()): Boolean =
        BundleSignature.verifyBundle(data, signatureBase64, origin, "$origin.sample", null, userKeys) is BundleVerdict.Verified

    @Test
    fun `verifies a real ECDSA P-256 signature against the pinned key`() {
        val pair = generateKeyPair()
        val data = "hello plugin manifest".toByteArray()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)

        PluginOriginKeys.withOrigin("testorigin", publicKeyBase64) {
            assertTrue(verifies(data, sign(pair.private, data), "testorigin"))
        }
    }

    @Test
    fun `rejects a signature from the wrong key`() {
        val pair = generateKeyPair()
        val impostor = generateKeyPair()
        val data = "hello plugin manifest".toByteArray()

        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            assertFalse(verifies(data, sign(impostor.private, data), "testorigin"))
        }
    }

    @Test
    fun `rejects an unpinned origin outright`() {
        assertFalse(verifies("data".toByteArray(), Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), "no-such-origin"))
    }

    @Test
    fun `sha256 matches a known vector`() {
        // echo -n "" | sha256sum
        assertTrue(Sha256.hex(ByteArray(0)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `the official origin verifies through the pinned-key path`() {
        val pair = generateKeyPair()
        val data = "hello plugin manifest".toByteArray()
        // The test hook stands in for the official private half, which
        // never leaves the coordination machine: what this exercises is
        // that origin "droidtop" resolves and verifies through exactly
        // the path a shipped official bundle takes.
        PluginOriginKeys.withOrigin(PluginOriginKeys.OFFICIAL_ORIGIN, Base64.getEncoder().encodeToString(pair.public.encoded)) {
            assertTrue(verifies(data, sign(pair.private, data), PluginOriginKeys.OFFICIAL_ORIGIN))
        }
    }

    @Test
    fun `a user-trusted key verifies a manifest no official key covers, and is refused without it`() {
        val pair = generateKeyPair()
        val data = "hello plugin manifest".toByteArray()
        val userKeys = mapOf("acme" to Base64.getEncoder().encodeToString(pair.public.encoded))

        assertTrue(verifies(data, sign(pair.private, data), "acme", userKeys))
        // Without the user key the origin is unknown -- refused, not downgraded.
        assertFalse(verifies(data, sign(pair.private, data), "acme"))
    }

    @Test
    fun `the official pinned key wins over a user key claiming the same origin`() {
        val attacker = generateKeyPair()
        val data = "forged manifest".toByteArray()
        val userKeys = mapOf(PluginOriginKeys.OFFICIAL_ORIGIN to Base64.getEncoder().encodeToString(attacker.public.encoded))

        // Official-first resolution: even with a "droidtop" entry in the
        // user keys (only constructible by hand-editing the store, since
        // add refuses it), resolve returns the real pinned key...
        val resolved = PluginOriginKeys.resolve(PluginOriginKeys.OFFICIAL_ORIGIN, userKeys)
        assertArrayEquals(Base64.getDecoder().decode(PluginOriginKeys.officialKeyBase64()), resolved?.encoded)
        // ... so the forged signature does not verify.
        assertFalse(verifies(data, sign(attacker.private, data), PluginOriginKeys.OFFICIAL_ORIGIN, userKeys))
    }

    @Test
    fun `a user key removed from the store no longer verifies`() {
        val pair = generateKeyPair()
        val data = "hello plugin manifest".toByteArray()
        val store = tmp.newFile("plugin-user-keys.json")

        UserOriginKeys.add(store, "acme", Base64.getEncoder().encodeToString(pair.public.encoded), source = "https://example.com/plugins")
        assertTrue(verifies(data, sign(pair.private, data), "acme", UserOriginKeys.loadBase64(store)))

        assertTrue(UserOriginKeys.remove(store, "acme"))
        // The origin is unknown again: refused, exactly like a never-trusted one.
        assertFalse(verifies(data, sign(pair.private, data), "acme", UserOriginKeys.loadBase64(store)))
    }
}
