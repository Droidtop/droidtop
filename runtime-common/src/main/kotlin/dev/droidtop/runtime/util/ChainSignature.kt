package dev.droidtop.runtime.util

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * droidtop's one signature format (docs/SPEC.md 12a "Signatures: certificate
 * chains to droidtop's signing root", Droidtop/tracker#550): a JWS (RFC 7515) in compact
 * serialization whose protected header carries the signing certificate chain
 * as `x5c`, verified up to one trust [Anchor] and no further. For droidtop's
 * own signatures the anchor is droidtop's pinned signing root
 * ([DroidtopSigningRoot]); for an origin or catalog the person trusts it is the key
 * they confirmed. Plugin bundles, catalog indexes and revocation lists all use
 * it, told apart by [Purpose] (the header's `typ` and the leaf's extended key
 * usage), so a signature made for one can never be used for another.
 *
 * JWS rather than CMS because it verifies with nothing but the platform's
 * X.509 parser and `java.security.Signature`: Android has no public CMS
 * parser. Enginehost implements the same format.
 */
object ChainSignature {
    /** droidtop's OID arc: a UUID OID (ITU-T X.667), which needs no registration. */
    const val OID_ARC = "2.25.230248497294207031301727532441854392946"

    const val ORIGIN_URN = "urn:droidtop:origin:"
    const val PLUGIN_URN = "urn:droidtop:plugin:"
    const val CATALOG_URN = "urn:droidtop:catalog:"

    enum class Purpose(val oid: String, val typ: String) {
        PLUGIN("$OID_ARC.1", "droidtop-plugin+jws"),
        CATALOG("$OID_ARC.2", "droidtop-catalog+jws"),
        REVOCATIONS("$OID_ARC.3", "droidtop-revocations+jws"),
    }

    /**
     * What a chain must end at. [pinned] is set for droidtop's signing root, whose
     * key is pinned in the binary: the chain's last certificate must verify under
     * it, and no certificate in the chain may hold it (the chain stops below it).
     * For a key the person trusts, the chain's last certificate may instead hold
     * that key itself (a publisher's self-signed certificate).
     */
    class Anchor(val key: PublicKey, val pinned: Boolean = false) {
        val sha256: String = Sha256.hex(key.encoded)
    }

    sealed interface Result {
        /** [chain] is leaf first; [payload] is the attached payload, null for detached content. */
        data class Verified(val chain: List<X509Certificate>, val anchor: Anchor, val payload: ByteArray?) : Result {
            val leaf: X509Certificate get() = chain.first()
        }

        data class Refused(val reason: String) : Result
    }

    private const val MAX_CHARS = 64 * 1024
    private const val MAX_CHAIN = 3
    private const val MIN_RSA_BITS = 2048
    private val json = Json { ignoreUnknownKeys = true }

    /** The certificate signature algorithms a chain may use: SHA-2 RSA PKCS#1 v1.5 and ECDSA. */
    private val CERT_SIG_ALGS = setOf(
        "1.2.840.113549.1.1.11", "1.2.840.113549.1.1.12", "1.2.840.113549.1.1.13",
        "1.2.840.10045.4.3.2", "1.2.840.10045.4.3.3",
    )

    /**
     * Verifies [jws] for [purpose] under [anchor]. [detached] is the content for
     * a detached signature (a manifest or an index, exact bytes), null for one
     * with an attached payload (a revocation list). No certificate's validity
     * dates are read: expiry never invalidates a signature, only revocation does
     * (owner, 2026-10-15: "Can we ignore its expiration date? We don't want older
     * plugins to be invalidated"; docs/SPEC.md 12a). [revoked] refuses the chain
     * when it names any certificate in it. Who the leaf speaks for (its origin
     * or catalog) is the caller's check: [uris], [names], [coversPlugin].
     */
    fun verify(
        jws: String,
        purpose: Purpose,
        anchor: Anchor,
        detached: ByteArray?,
        revoked: (X509Certificate) -> Boolean = { false },
    ): Result {
        val text = jws.trim()
        if (text.length > MAX_CHARS) return Result.Refused("the signature is too large")
        val parts = text.split('.')
        if (parts.size != 3) return Result.Refused("the signature is not a JWS")
        val header = runCatching { json.parseToJsonElement(String(b64url(parts[0]), Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: return Result.Refused("the signature's header is unreadable")
        if (header.containsKey("crit")) return Result.Refused("the signature names critical header parameters droidtop does not know")
        if (header.string("typ") != purpose.typ) return Result.Refused("the signature is not a ${purpose.typ} signature")
        val alg = header.string("alg") ?: return Result.Refused("the signature names no algorithm")
        val x5c = (header["x5c"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: return Result.Refused("the signature carries no certificate chain")
        if (x5c.isEmpty() || x5c.size > MAX_CHAIN || x5c.any { it == null }) return Result.Refused("the signature's certificate chain must hold 1 to $MAX_CHAIN certificates")
        val chain = x5c.map { parseCertificate(it!!) ?: return Result.Refused("a certificate in the signature's chain is unreadable") }

        val payload: ByteArray?
        val signingInput: ByteArray
        if (detached != null) {
            if (parts[1].isNotEmpty()) return Result.Refused("the signature should be detached and carries a payload")
            payload = null
            signingInput = (parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(detached)).toByteArray(Charsets.US_ASCII)
        } else {
            payload = runCatching { b64url(parts[1]) }.getOrNull() ?: return Result.Refused("the signed payload is unreadable")
            signingInput = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)
        }
        val signature = runCatching { b64url(parts[2]) }.getOrNull() ?: return Result.Refused("the signature value is unreadable")

        chainProblem(chain, purpose, anchor)?.let { return Result.Refused(it) }
        if (chain.any(revoked)) return Result.Refused("a certificate in the signature's chain has been revoked")
        if (!verifySignature(alg, chain.first().publicKey, signingInput, signature)) {
            return Result.Refused("the signature does not verify against its signing certificate")
        }
        return Result.Verified(chain, anchor, payload)
    }

    /** Why [chain] does not lead from a [purpose] leaf to [anchor], or null when it does. */
    private fun chainProblem(chain: List<X509Certificate>, purpose: Purpose, anchor: Anchor): String? {
        chain.forEach { cert ->
            if (cert.version != 3) return "a certificate in the chain is not X.509 v3"
            if (cert.sigAlgOID !in CERT_SIG_ALGS) return "a certificate in the chain uses a signature algorithm droidtop does not accept"
            if (!acceptableKey(cert.publicKey)) return "a certificate in the chain holds a key that is not P-256 or RSA of at least $MIN_RSA_BITS bits"
        }
        val leaf = chain.first()
        if (leaf.basicConstraints != -1) return "the signing certificate is a CA certificate"
        val usage = leaf.keyUsage ?: return "the signing certificate has no key usage"
        if (!usage.getOrElse(0) { false }) return "the signing certificate is not for digital signatures"
        if (usage.getOrElse(5) { false }) return "the signing certificate may sign certificates"
        if (!hasExtendedKeyUsage(leaf, purpose.oid)) return "the signing certificate is not for ${purpose.typ.removeSuffix("+jws")} signing"
        chain.drop(1).forEachIndexed { index, ca ->
            val pathLen = ca.basicConstraints
            if (pathLen < 0) return "an intermediate certificate is not a CA certificate"
            if (!(ca.keyUsage?.getOrElse(5) { false } ?: false)) return "an intermediate certificate may not sign certificates"
            if (index > pathLen) return "the chain is longer than an intermediate certificate allows"
        }
        for (i in 0 until chain.size - 1) {
            if (chain[i].issuerX500Principal != chain[i + 1].subjectX500Principal || !signedBy(chain[i], chain[i + 1].publicKey)) {
                return "the certificate chain is broken"
            }
        }
        val top = chain.last()
        if (anchor.pinned) {
            if (chain.any { it.publicKey.encoded.contentEquals(anchor.key.encoded) }) {
                return "the chain carries droidtop's signing root itself; it must stop below it"
            }
            if (!signedBy(top, anchor.key)) return "the chain does not lead to droidtop's signing root"
        } else if (!top.publicKey.encoded.contentEquals(anchor.key.encoded) && !signedBy(top, anchor.key)) {
            return "the chain does not lead to the key you trust"
        }
        return null
    }

    /**
     * Whether [cert]'s extendedKeyUsage names [oid], read from the extension's DER rather than
     * [X509Certificate.getExtendedKeyUsage]: Android's provider (Conscrypt, BoringSSL) cannot turn
     * droidtop's UUID arc, wider than 64 bits, into text and throws ("Unknown BoringSSL error"), which
     * would refuse every droidtop certificate on a device.
     */
    private fun hasExtendedKeyUsage(cert: X509Certificate, oid: String): Boolean {
        val wanted = oidContent(oid)
        val outer = cert.getExtensionValue("2.5.29.37") ?: return false
        return runCatching {
            val (sequence, _) = derElement(outer, 0, 0x04)
            val (body, _) = derElement(sequence, 0, 0x30)
            var at = 0
            while (at < body.size) {
                val (content, next) = derElement(body, at, 0x06)
                if (content.contentEquals(wanted)) return true
                at = next
            }
            false
        }.getOrDefault(false)
    }

    /** The content of the DER element of [tag] at [at] in [bytes], and the offset after it. */
    private fun derElement(bytes: ByteArray, at: Int, tag: Int): Pair<ByteArray, Int> {
        require(bytes[at].toInt() and 0xff == tag)
        var length = bytes[at + 1].toInt() and 0xff
        var start = at + 2
        if (length >= 0x80) {
            val count = length and 0x7f
            require(count in 1..3)
            length = 0
            repeat(count) { length = (length shl 8) or (bytes[start++].toInt() and 0xff) }
        }
        require(start + length <= bytes.size)
        return bytes.copyOfRange(start, start + length) to start + length
    }

    /** The DER content octets of a dotted OID, arcs of any size. */
    private fun oidContent(oid: String): ByteArray {
        val arcs = oid.split('.').map { BigInteger(it) }
        val out = java.io.ByteArrayOutputStream()
        (listOf(arcs[0] * BigInteger.valueOf(40) + arcs[1]) + arcs.drop(2)).forEach { arc ->
            val groups = mutableListOf<Int>()
            var rest = arc
            do {
                groups.add(rest.and(BigInteger.valueOf(0x7f)).toInt())
                rest = rest.shiftRight(7)
            } while (rest.signum() > 0)
            groups.reversed().forEachIndexed { i, group -> out.write(if (i < groups.size - 1) group or 0x80 else group) }
        }
        return out.toByteArray()
    }

    private fun signedBy(cert: X509Certificate, key: PublicKey): Boolean = runCatching { cert.verify(key) }.isSuccess

    private fun verifySignature(alg: String, key: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        val (jca, der) = when (alg) {
            "ES256" -> if (key is ECPublicKey && signature.size == 64) "SHA256withECDSA" to rawToDer(signature) else return false
            "RS256" -> if (key is RSAPublicKey) "SHA256withRSA" to signature else return false
            else -> return false
        }
        return runCatching {
            Signature.getInstance(jca).apply {
                initVerify(key)
                update(data)
            }.verify(der)
        }.getOrDefault(false)
    }

    /** A JWS ES256 signature (R || S, 32 bytes each) as the DER the JCA reads. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun b64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    // ----- Shared certificate and key helpers -----

    /** A certificate from base64 DER or PEM, or null. */
    fun parseCertificate(text: String): X509Certificate? = runCatching {
        val body = text.trim()
        val der = if (body.contains("-----BEGIN CERTIFICATE-----")) {
            Base64.getMimeDecoder().decode(body.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----"))
        } else {
            Base64.getMimeDecoder().decode(body)
        }
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }.getOrNull()

    /** An EC P-256 or RSA (at least 2048 bits) public key, the only kinds droidtop's signatures use. */
    fun acceptableKey(key: PublicKey): Boolean = when (key) {
        is ECPublicKey -> key.params?.curve?.field?.fieldSize == 256
        is RSAPublicKey -> key.modulus.bitLength() >= MIN_RSA_BITS
        else -> false
    }

    /** A SubjectPublicKeyInfo (base64 DER) as a key, when it is an [acceptableKey]; otherwise null. */
    fun parseSpki(keyBase64: String): PublicKey? {
        val bytes = runCatching { Base64.getMimeDecoder().decode(keyBase64.trim()) }.getOrNull() ?: return null
        for (algorithm in listOf("EC", "RSA")) {
            val key = runCatching { KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(bytes)) }.getOrNull()
            if (key != null) return key.takeIf(::acceptableKey)
        }
        return null
    }

    /** The certificate's subjectAltName URIs. */
    fun uris(cert: X509Certificate): List<String> =
        runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
            .filter { (it.getOrNull(0) as? Int) == 6 }
            .mapNotNull { it.getOrNull(1) as? String }

    /** Whether [cert] carries the URI [prefix] + [id] exactly. */
    fun names(cert: X509Certificate, prefix: String, id: String): Boolean = (prefix + id) in uris(cert)

    /**
     * Whether a bundle-signing leaf may sign [pluginId]: with no `urn:droidtop:plugin:` names it may sign any plugin
     * of its origin; with some, the id must be one of them or fall under a `<prefix>.*` one.
     */
    fun coversPlugin(leaf: X509Certificate, pluginId: String): Boolean {
        val entries = uris(leaf).filter { it.startsWith(PLUGIN_URN) }.map { it.removePrefix(PLUGIN_URN) }
        if (entries.isEmpty()) return true
        return entries.any { entry ->
            if (entry.endsWith(".*")) {
                val prefix = entry.dropLast(1)
                pluginId.startsWith(prefix) && pluginId.length > prefix.length
            } else {
                entry == pluginId
            }
        }
    }
}
