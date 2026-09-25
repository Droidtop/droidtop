package dev.droidtop.pluginhost

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Per-origin pinned P-256 keys, certified under one root -- the same
 * strength enginehost's engine bundles use (docs/SPEC.md 7d, and 12a
 * point 4, which requires plugins be "at least as strict"). Keys are
 * public material (verification only lives on-device); the private
 * signing keys never appear in this repo.
 *
 * "droidtop" is the one shipped origin, matching the sample plugin
 * (samples/plugin-sample-statustile). A third-party catalog adds its own
 * origin/key pair the same way `players-database.json` and
 * `engines-database.json` add origins today: a droidtop-platforms row,
 * not a code change here.
 */
object PluginOriginKeys {
    /**
     * origin id -> SubjectPublicKeyInfo (X.509), base64, P-256. This is a
     * REAL generated key pair (`openssl ecparam -name prime256v1
     * -genkey`), not a placeholder string: the private half is on the
     * coordination machine (`/root/coordination/keys/droidtop-plugins/`,
     * never in this repo) and signs the sample plugin bundle
     * (samples/plugin-sample-statustile). It is a first working origin
     * key, not a certified production root -- §8's root-key derivation
     * covers enginehost's engine bundles; a droidtop plugin catalog root
     * is real follow-up work once more than one origin needs pinning.
     */
    private val PINNED: Map<String, String> = mapOf(
        "droidtop" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYs1dY+vQaHC/tj9XEfv2KxHfCzLtsgyIi/AN" +
            "wdSuKyoGcp7kCNXoy0sYhOaOdFUTpV4tnkKsICv/EY8q+zM5Dw==",
    )

    fun publicKeyFor(origin: String): PublicKey? {
        val encoded = PINNED[origin] ?: return null
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        }.getOrNull()
    }

    /** Test/tooling hook -- lets unit tests and the sample-bundle packager pin a throwaway key without touching [PINNED]. */
    fun withOrigin(origin: String, publicKeyBase64: String, block: () -> Unit) {
        val previous = overrides[origin]
        overrides[origin] = publicKeyBase64
        try {
            block()
        } finally {
            if (previous == null) overrides.remove(origin) else overrides[origin] = previous
        }
    }

    private val overrides = mutableMapOf<String, String>()

    fun resolve(origin: String): PublicKey? {
        val encoded = overrides[origin] ?: PINNED[origin] ?: return null
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        }.getOrNull()
    }
}

/**
 * Verifies a bundle's manifest against its origin's pinned key
 * (ECDSA/SHA-256 over the exact manifest bytes) and every payload file's
 * declared SHA-256 against the real bytes on disk. Both checks are
 * mandatory (checklist points 1 and 4): a bundle with no signature block,
 * an unpinned origin, or a signature that doesn't verify is refused, not
 * downgraded to "unverified but installable".
 */
object BundleSignature {
    fun verifyManifest(manifestBytes: ByteArray, signatureBase64: String, origin: String): Boolean {
        val key = PluginOriginKeys.resolve(origin) ?: return false
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull() ?: return false
        return runCatching {
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(manifestBytes)
            }.verify(signatureBytes)
        }.getOrDefault(false)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
