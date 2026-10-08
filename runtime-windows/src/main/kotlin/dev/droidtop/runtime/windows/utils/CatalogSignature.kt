package dev.droidtop.runtime.windows.utils

import dev.droidtop.runtime.util.EcP256
import dev.droidtop.runtime.util.Sha256
import java.security.PublicKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The component catalog's signature (docs/SPEC.md 9 "What droidtop-components
 * may re-host, and the catalog's signature"). The same model as plugin
 * certificates (12a): droidtop's master key ([dev.droidtop.runtime.util.MasterKey])
 * certifies one catalog key, derived from the master seed, in `catalog.cert`;
 * that key signs the exact bytes of `catalog.json` as `catalog.json.sig`
 * (base64 DER ECDSA/SHA-256). Both files sit beside the catalog on the
 * `catalog` release, written by droidtop-components' workflow from its
 * `CATALOG_SIGNING_KEY` and `CATALOG_SIGNING_CERT` secrets.
 *
 * While this build pins no master the catalog is accepted unsigned
 * ([Verdict.Unpinned]); once one is pinned a catalog without a valid pair is
 * refused, whatever the catalog itself says about a signature, so removing
 * the files cannot downgrade a device to unsigned.
 *
 *     {
 *       "formatVersion": 1,
 *       "certId": "Droidtop/droidtop-components#0",
 *       "catalogs": ["Droidtop/droidtop-components"],
 *       "publicKeySpki": "<P-256 SubjectPublicKeyInfo, base64>",
 *       "keySha256": "<hex SHA-256 of that DER>",
 *       "notBefore": 1790000000,
 *       "notAfter": 1885000000,
 *       "issuer": { "keySha256": "<master key's hex SHA-256>", "signature": "<base64 DER ECDSA over signedBytes>" }
 *     }
 */
object CatalogSignature {
    const val SIGNATURE_FILE = "catalog.json.sig"
    const val CERTIFICATE_FILE = "catalog.cert"

    /** The catalog a certificate must name: the repository that publishes it. */
    const val CATALOG_ID = "Droidtop/droidtop-components"

    private const val PREFIX = "droidtop-catalog-cert-v1"
    private const val MAX_CHARS = 16 * 1024
    private val CERT_ID = Regex("[A-Za-z0-9._/#@-]{1,200}")
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Verdict {
        /** No master is pinned in this build: the catalog is taken as fetched. */
        data object Unpinned : Verdict

        data class Verified(val certId: String, val masterSha256: String) : Verdict

        data class Refused(val reason: String) : Verdict
    }

    data class Certificate(
        val certId: String,
        val catalogs: List<String>,
        val publicKey: PublicKey,
        val keySha256: String,
        val notBefore: Long,
        val notAfter: Long,
    )

    /** What the master signs: one field per line, so the signature never depends on how JSON is written. */
    fun signedBytes(certId: String, catalogs: List<String>, publicKeyBase64: String, notBefore: Long, notAfter: Long): ByteArray =
        buildString {
            append(PREFIX).append('\n')
            append("id:").append(certId).append('\n')
            append("catalogs:").append(catalogs.joinToString(",")).append('\n')
            append("key:").append(publicKeyBase64).append('\n')
            append("notBefore:").append(notBefore).append('\n')
            append("notAfter:").append(notAfter).append('\n')
        }.toByteArray(Charsets.UTF_8)

    /** The certificate in [text] when [master] signed it and every field is well formed, else null. */
    fun parseCertificate(text: String, master: PublicKey): Certificate? = runCatching {
        if (text.length > MAX_CHARS) return null
        val obj: JsonObject = json.parseToJsonElement(text).jsonObject
        if (obj["formatVersion"]?.jsonPrimitive?.long != 1L) return null
        val certId = obj.getValue("certId").jsonPrimitive.content.takeIf { CERT_ID.matches(it) } ?: return null
        val catalogs = obj.getValue("catalogs").jsonArray.map { it.jsonPrimitive.content }
        if (catalogs.isEmpty() || catalogs.any { it.isBlank() || it.contains(',') || it.contains('\n') }) return null
        val keyBase64 = obj.getValue("publicKeySpki").jsonPrimitive.content.trim()
        val key = EcP256.parseSpki(keyBase64) ?: return null
        val keySha256 = Sha256.hex(key.encoded)
        if (!obj.getValue("keySha256").jsonPrimitive.content.equals(keySha256, ignoreCase = true)) return null
        val notBefore = obj.getValue("notBefore").jsonPrimitive.long
        val notAfter = obj.getValue("notAfter").jsonPrimitive.long
        if (notAfter < notBefore) return null
        val issuer = obj.getValue("issuer").jsonObject
        if (!issuer.getValue("keySha256").jsonPrimitive.content.equals(Sha256.hex(master.encoded), ignoreCase = true)) return null
        val signed = signedBytes(certId, catalogs, keyBase64, notBefore, notAfter)
        if (!EcP256.verify(master, signed, issuer.getValue("signature").jsonPrimitive.content)) return null
        Certificate(certId, catalogs, key, keySha256, notBefore, notAfter)
    }.getOrNull()

    /**
     * The one decision on a fetched catalog: [catalogBytes] exactly as
     * downloaded, [signatureBase64] and [certificateText] as fetched beside it
     * (null when missing), [master] the pinned master or null.
     */
    fun verify(
        catalogBytes: ByteArray,
        signatureBase64: String?,
        certificateText: String?,
        master: PublicKey?,
        nowEpochSeconds: Long,
    ): Verdict {
        if (master == null) return Verdict.Unpinned
        if (signatureBase64.isNullOrBlank() || certificateText.isNullOrBlank()) {
            return Verdict.Refused("the catalog has no $SIGNATURE_FILE and $CERTIFICATE_FILE, and this build requires them")
        }
        val certificate = parseCertificate(certificateText, master)
            ?: return Verdict.Refused("$CERTIFICATE_FILE is not a catalog certificate signed by droidtop's master key")
        if (CATALOG_ID !in certificate.catalogs) {
            return Verdict.Refused("the certificate is for ${certificate.catalogs.joinToString()}, not $CATALOG_ID")
        }
        if (nowEpochSeconds !in certificate.notBefore..certificate.notAfter) {
            return Verdict.Refused("the catalog certificate \"${certificate.certId}\" is not valid now (check the device's date)")
        }
        if (!EcP256.verify(certificate.publicKey, catalogBytes, signatureBase64)) {
            return Verdict.Refused("the catalog's signature does not verify against its certified key")
        }
        return Verdict.Verified(certificate.certId, Sha256.hex(master.encoded))
    }
}
