package dev.droidtop.runtime.util

import java.security.cert.X509Certificate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A signed revocation list (docs/SPEC.md 12a "Revocation"): what a catalog
 * withdraws, by certificate (SHA-256 of its DER) or by key (SHA-256 of its
 * SubjectPublicKeyInfo). Carried beside the catalog as `revocations.jws`, a
 * [ChainSignature] with an attached payload, signed by a revocation-signing
 * leaf that names the catalog. The device may be offline, so there is no CRL or
 * OCSP: the list rides along with the catalog fetch it already makes.
 *
 *     {"type":"droidtop-revocations","version":1,"catalog":"droidtop","sequence":7,"issuedAt":1792000000,
 *      "certSha256":["<hex>"],"keySha256":["<hex>"]}
 */
data class RevocationList(
    /** Only a list with a higher sequence replaces the stored one, so an old list cannot be replayed to un-revoke anything. */
    val sequence: Long,
    val certSha256: Set<String>,
    val keySha256: Set<String>,
) {
    fun revokesKey(sha256: String): Boolean = sha256.lowercase() in keySha256

    /** Whether [cert] is withdrawn, by itself or by its key. */
    fun revokes(cert: X509Certificate): Boolean =
        Sha256.hex(cert.encoded) in certSha256 || revokesKey(Sha256.hex(cert.publicKey.encoded))

    companion object {
        const val FILE_NAME = "revocations.jws"

        val NONE = RevocationList(0, emptySet(), emptySet())

        private const val TYPE = "droidtop-revocations"
        private val HEX = Regex("[0-9a-f]{64}")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The list in [jws] when it verifies under [anchor], its signer names [catalogId] and its payload says the
         * same; otherwise null.
         */
        fun parse(jws: String, anchor: ChainSignature.Anchor, catalogId: String): RevocationList? {
            val verified = ChainSignature.verify(jws, ChainSignature.Purpose.REVOCATIONS, anchor, null)
                as? ChainSignature.Result.Verified ?: return null
            if (!ChainSignature.names(verified.leaf, ChainSignature.CATALOG_URN, catalogId)) return null
            return runCatching {
                val body = json.parseToJsonElement(verified.payload!!.toString(Charsets.UTF_8)).jsonObject
                if (body.getValue("type").jsonPrimitive.content != TYPE) return null
                if (body.getValue("version").jsonPrimitive.long != 1L) return null
                if (body.getValue("catalog").jsonPrimitive.content != catalogId) return null
                val sequence = body.getValue("sequence").jsonPrimitive.long
                if (sequence < 1) return null
                fun hexes(name: String): Set<String> {
                    val values = (body[name] as? JsonArray)?.map { (it as JsonPrimitive).content.lowercase() }.orEmpty()
                    require(values.all { HEX.matches(it) })
                    return values.toSet()
                }
                RevocationList(sequence, hexes("certSha256"), hexes("keySha256"))
            }.getOrNull()
        }
    }
}
