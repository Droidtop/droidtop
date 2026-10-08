package dev.droidtop.runtime.util

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * droidtop's master public key (docs/SPEC.md 12a "Per-plugin keys and the
 * plugin master", and 9 "What droidtop-components may re-host, and the
 * catalog's signature"): pinned once, here, in the binary. It signs nothing
 * but certificates and revocation lists: plugin repository certificates
 * (`PluginCertificates`, `:plugin-host`) and the component catalog's
 * certificate (`CatalogSignature`, `:runtime-windows`), each over its own
 * domain-separated bytes so one can never stand in for the other.
 *
 * The key is derived from the owner's offline master seed, so only the owner
 * can produce this value (`plugin-key-provision master-public`); until it is
 * pinned, [key] is null: certified plugin bundles are refused, and the
 * component catalog is accepted unsigned.
 */
object MasterKey {
    /** The master's SubjectPublicKeyInfo (X.509), base64, P-256; null until the owner pins it. */
    private val PINNED: String? = null

    @Volatile private var override: String? = null

    /** The pinned master's SPKI, base64, or null while none is pinned. */
    fun base64(): String? = override ?: PINNED

    /** The pinned master key, or null while none is pinned. */
    fun key(): PublicKey? = base64()?.let(EcP256::parseSpki)

    /** Test hook: pins a throwaway master for the duration of [block]. */
    fun <T> withPinned(publicKeyBase64: String?, block: () -> T): T {
        val previous = override
        override = publicKeyBase64
        try {
            return block()
        } finally {
            override = previous
        }
    }
}

/** The one P-256 key and ECDSA/SHA-256 signature check droidtop's signed formats share. */
object EcP256 {
    /**
     * Parses an SPKI base64 blob into a key, refusing anything that is not an
     * elliptic-curve P-256 public key.
     */
    fun parseSpki(keyBase64: String): PublicKey? {
        val bytes = runCatching { Base64.getMimeDecoder().decode(keyBase64) }.getOrNull() ?: return null
        val key = runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrNull() as? ECPublicKey ?: return null
        // P-256 exactly: a P-384/P-521 SPKI parses fine as "EC", so the
        // curve's own field size is the check that pins the algorithm.
        if (key.params?.curve?.field?.fieldSize != 256) return null
        return key
    }

    /** ECDSA P-256/SHA-256 of [data] by [key]; [signatureBase64] is the DER signature, base64. */
    fun verify(key: PublicKey, data: ByteArray, signatureBase64: String): Boolean {
        val signatureBytes = runCatching { Base64.getMimeDecoder().decode(signatureBase64.trim()) }.getOrNull() ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(data)
            }.verify(signatureBytes)
        }.getOrDefault(false)
    }
}
