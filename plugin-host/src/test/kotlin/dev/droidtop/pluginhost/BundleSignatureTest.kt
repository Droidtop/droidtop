package dev.droidtop.pluginhost

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleSignatureTest {
    private fun generateKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun `verifies a real ECDSA P-256 signature against the pinned key`() {
        val pair = generateKeyPair()
        val data = "hello plugin manifest".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(pair.private)
            update(data)
        }.sign()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)

        PluginOriginKeys.withOrigin("testorigin", publicKeyBase64) {
            assertTrue(BundleSignature.verifyManifest(data, Base64.getEncoder().encodeToString(signature), "testorigin"))
        }
    }

    @Test
    fun `rejects a signature from the wrong key`() {
        val pair = generateKeyPair()
        val impostor = generateKeyPair()
        val data = "hello plugin manifest".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(impostor.private)
            update(data)
        }.sign()
        val publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded)

        PluginOriginKeys.withOrigin("testorigin", publicKeyBase64) {
            assertFalse(BundleSignature.verifyManifest(data, Base64.getEncoder().encodeToString(signature), "testorigin"))
        }
    }

    @Test
    fun `rejects an unpinned origin outright`() {
        assertFalse(BundleSignature.verifyManifest("data".toByteArray(), Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), "no-such-origin"))
    }

    @Test
    fun `sha256 matches a known vector`() {
        // echo -n "" | sha256sum
        assertTrue(BundleSignature.sha256(ByteArray(0)) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }
}
