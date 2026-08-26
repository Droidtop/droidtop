package dev.droidtop.runtime.remotestream

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Locale

/**
 * A stable, self-signed RSA-2048 client identity for GameStream pairing,
 * generated once and persisted to app-private storage -- the exact scheme
 * moonlight-android's AndroidCryptoProvider uses. GameStream hosts identify
 * a paired client by this certificate (mutual TLS), so it must survive
 * across app runs or every launch would look like a brand-new, unpaired
 * client to the host.
 */
class ClientIdentity(context: Context) {
    private val certFile = File(context.filesDir, "remotestream_client.crt")
    private val keyFile = File(context.filesDir, "remotestream_client.key")

    @Volatile private var cert: X509Certificate? = null
    @Volatile private var key: PrivateKey? = null
    @Volatile private var pemCertBytes: ByteArray? = null

    val clientCertificate: X509Certificate
        get() = synchronized(lock) { cert ?: loadOrGenerate().first }

    val clientPrivateKey: PrivateKey
        get() = synchronized(lock) { key ?: loadOrGenerate().second }

    val pemEncodedClientCertificate: ByteArray
        get() = synchronized(lock) {
            if (pemCertBytes == null) loadOrGenerate()
            pemCertBytes!!
        }

    private fun loadOrGenerate(): Pair<X509Certificate, PrivateKey> {
        if (load()) return cert!! to key!!
        generate()
        check(load()) { "Failed to load the client identity we just generated" }
        return cert!! to key!!
    }

    private fun load(): Boolean {
        if (!certFile.exists() || !keyFile.exists()) return false
        return try {
            val certBytes = certFile.readBytes()
            val keyBytes = keyFile.readBytes()
            val certFactory = CertificateFactory.getInstance("X.509", bcProvider)
            cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            pemCertBytes = certBytes
            val keyFactory = KeyFactory.getInstance("RSA", bcProvider)
            key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            true
        } catch (e: Exception) {
            // Corrupt cert or key on disk -- fall through to regenerating.
            false
        }
    }

    private fun generate() {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", bcProvider)
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        val now = java.util.Date()
        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.add(Calendar.YEAR, 20)
        val expirationDate = calendar.time

        val serialBytes = ByteArray(8)
        SecureRandom().nextBytes(serialBytes)
        val serial = BigInteger(serialBytes).abs()

        val name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "NVIDIA GameStream Client")
            .build()

        val certBuilder = X509v3CertificateBuilder(
            name, serial, now, expirationDate, Locale.ENGLISH, name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.private)
        val generatedCert = JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(signer))

        val pemWriter = StringWriter()
        JcaPEMWriter(pemWriter).use { it.writeObject(generatedCert) }
        // Line endings MUST be UNIX for the host to accept the cert.
        val pem = pemWriter.buffer.toString().filter { it != '\r' }

        certFile.writeBytes(pem.toByteArray(Charsets.UTF_8))
        keyFile.writeBytes(keyPair.private.encoded)
    }

    fun encodeBase64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    companion object {
        private val lock = Any()
        private val bcProvider = BouncyCastleProvider().also { Security.addProvider(it) }
    }
}
