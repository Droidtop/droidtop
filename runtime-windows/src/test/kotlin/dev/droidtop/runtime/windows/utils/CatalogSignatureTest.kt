package dev.droidtop.runtime.windows.utils

import dev.droidtop.runtime.util.CatalogSignature
import dev.droidtop.runtime.util.Sha256
import dev.droidtop.runtime.windows.RuntimeDownloads
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The component catalog's signature (docs/SPEC.md 9). Every key here is a
 * throwaway pair generated inside the test, which plays the master's part.
 */
class CatalogSignatureTest {
    private val master = freshKeyPair()
    private val catalogKey = freshKeyPair()
    private val now = 1_800_000_000L
    private val catalog = """{"format":1,"version":1,"items":{}}""".toByteArray()

    private fun freshKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun sign(key: PrivateKey, data: ByteArray): String =
        b64(Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign())

    private fun certificate(
        catalogs: List<String> = listOf(CatalogSignature.CATALOG_ID),
        subject: KeyPair = catalogKey,
        issuer: KeyPair = master,
        signedBytes: ByteArray? = null,
        notBefore: Long = now - 86_400,
        notAfter: Long = now + 86_400 * 365,
    ): String {
        val spki = b64(subject.public.encoded)
        val signed = signedBytes
            ?: CatalogSignature.signedBytes("Droidtop/droidtop-components#0", catalogs, spki, notBefore, notAfter)
        val list = catalogs.joinToString(",") { "\"$it\"" }
        return """
            {"formatVersion":1,"certId":"Droidtop/droidtop-components#0","catalogs":[$list],
             "publicKeySpki":"$spki","keySha256":"${Sha256.hex(subject.public.encoded)}",
             "notBefore":$notBefore,"notAfter":$notAfter,
             "issuer":{"keySha256":"${Sha256.hex(issuer.public.encoded)}","signature":"${sign(issuer.private, signed)}"}}
        """.trimIndent()
    }

    private fun verify(signature: String?, cert: String?, bytes: ByteArray = catalog) =
        CatalogSignature.verify(bytes, signature, cert, master.public, now)

    @Test
    fun unsignedCatalogIsAcceptedWhileNoMasterIsPinned() {
        assertEquals(CatalogSignature.Verdict.Unpinned, CatalogSignature.verify(catalog, null, null, null, now))
    }

    @Test
    fun signedCatalogVerifiesThroughItsCertificate() {
        val verdict = verify(sign(catalogKey.private, catalog), certificate())
        assertTrue(verdict is CatalogSignature.Verdict.Verified)
        assertEquals(Sha256.hex(master.public.encoded), (verdict as CatalogSignature.Verdict.Verified).masterSha256)
    }

    @Test
    fun aPinnedMasterRequiresTheSignature() {
        assertTrue(verify(null, null) is CatalogSignature.Verdict.Refused)
        assertTrue(verify(sign(catalogKey.private, catalog), null) is CatalogSignature.Verdict.Refused)
    }

    @Test
    fun aChangedCatalogIsRefused() {
        val signature = sign(catalogKey.private, catalog)
        val changed = catalog.copyOf().also { it[it.size - 2] = 'x'.code.toByte() }
        assertTrue(verify(signature, certificate(), changed) is CatalogSignature.Verdict.Refused)
    }

    @Test
    fun aCertificateNotIssuedByTheMasterIsRefused() {
        val other = freshKeyPair()
        assertTrue(verify(sign(catalogKey.private, catalog), certificate(issuer = other)) is CatalogSignature.Verdict.Refused)
    }

    @Test
    fun aSignatureByAnotherKeyIsRefused() {
        val other = freshKeyPair()
        assertTrue(verify(sign(other.private, catalog), certificate()) is CatalogSignature.Verdict.Refused)
    }

    @Test
    fun aCertificateForAnotherCatalogIsRefused() {
        val cert = certificate(catalogs = listOf("Someone/else"))
        assertTrue(verify(sign(catalogKey.private, catalog), cert) is CatalogSignature.Verdict.Refused)
    }

    @Test
    fun anExpiredCertificateIsRefused() {
        val cert = certificate(notBefore = now - 1000, notAfter = now - 1)
        assertTrue(verify(sign(catalogKey.private, catalog), cert) is CatalogSignature.Verdict.Refused)
    }

    /** A plugin certificate's signed bytes (another domain) cannot stand in for a catalog certificate. */
    @Test
    fun aMasterSignatureOverOtherBytesIsRefused() {
        val spki = b64(catalogKey.public.encoded)
        val pluginBytes = "droidtop-plugin-cert-v1\nid:Droidtop/droidtop-components#0\nplugins:${CatalogSignature.CATALOG_ID}\n" +
            "key:$spki\nnotBefore:${now - 86_400}\nnotAfter:${now + 86_400 * 365}\n"
        val cert = certificate(signedBytes = pluginBytes.toByteArray())
        assertTrue(verify(sign(catalogKey.private, catalog), cert) is CatalogSignature.Verdict.Refused)
    }

    @Test
    fun downloadsFallBackAcrossLocationsInOrder() {
        val tried = mutableListOf<String>()
        val got = RuntimeDownloads.firstThatWorks(listOf("ours", "maker", "other")) { url ->
            tried += url
            if (url == "ours") throw IOException("did not match its published checksum")
            url
        }
        assertEquals("maker", got)
        assertEquals(listOf("ours", "maker"), tried)
    }

    @Test(expected = IOException::class)
    fun downloadsFailWhenNoLocationWorks() {
        RuntimeDownloads.firstThatWorks(listOf("ours", "maker")) { throw IOException("HTTP 404") }
    }

    @Test
    fun anEntryWithoutAListUsesItsUrl() {
        assertEquals(listOf("u"), ManifestEntry(id = "i", name = "n", url = "u").downloadUrls())
        assertEquals(listOf("a", "b"), CatalogFile(url = "a", urls = listOf("a", "b")).downloadUrls())
    }
}
