package dev.droidtop.runtime.remotestream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate

/** A discovered or manually-added GameStream-protocol host (Sunshine, Apollo, or legacy NVIDIA GFE). */
data class RemoteHost(
    val address: String,
    val name: String,
    val paired: Boolean,
    val serverCertHash: String? = null,
    /** PEM-encoded pinned server cert from the pairing handshake — the actual trust anchor for this host. */
    val serverCertPem: String? = null,
)

/** One app Sunshine (or Apollo) has configured as streamable on a given [RemoteHost]. */
data class RemoteApp(
    val hostAddress: String,
    val appId: Int,
    val name: String,
    val artworkUri: String? = null,
)

enum class PairState { NOT_PAIRED, PIN_ENTRY_REQUIRED, PAIRED, PIN_WRONG, ALREADY_IN_PROGRESS, FAILED }

/**
 * LAN host discovery. NOT part of vendor/moonlight-common-c itself — that
 * library is transport/protocol only. Every platform Moonlight client
 * (moonlight-android in particular) layers its own discovery on top; this
 * needs to be ported/reimplemented here, not pulled in "for free."
 *
 * moonlight-android's discovery approach is the direct reference: broadcast
 * probes on the LAN + optionally mDNS, matching hosts that answer the
 * GameStream/Sunshine `serverinfo` HTTPS endpoint.
 */
interface RemoteHostDiscovery {
    suspend fun discover(): List<RemoteHost>
}

/**
 * Pairing and app-list retrieval are a plain HTTPS+XML REST layer — ported
 * directly from moonlight-android's real, public NvHTTP/PairingManager/
 * AndroidCryptoProvider (confirmed via source inspection that this is NOT
 * part of vendor/moonlight-common-c, which is streaming-protocol only).
 * Only actual frame streaming needs the native moonlight-common-c bridge,
 * so the native lib is loaded lazily by [startStream] alone — pairing and
 * the app list work even if the native streaming path isn't built yet.
 */
class MoonlightClient(context: Context) {
    private val identity = ClientIdentity(context.applicationContext)

    /**
     * Runs the real salted-PIN AES challenge/response handshake against
     * [host]. On success, returns a [RemoteHost] with `paired = true` and
     * the newly-pinned server cert recorded — callers must persist this
     * returned host (not the one passed in) so future connections trust
     * the right cert.
     */
    suspend fun pair(host: RemoteHost, pin: String): Pair<PairState, RemoteHost> = withContext(Dispatchers.IO) {
        val httpClient = GameStreamHttpClient(identity, host.address)
        val result = MoonlightPairing(httpClient, identity).pair(pin)
        val pairedHost = if (result.state == PairState.PAIRED && result.serverCert != null) {
            host.copy(
                paired = true,
                serverCertHash = sha256Fingerprint(result.serverCert),
                serverCertPem = encodeCertPem(result.serverCert),
            )
        } else {
            host
        }
        result.state to pairedHost
    }

    suspend fun fetchAppList(host: RemoteHost): List<RemoteApp> = withContext(Dispatchers.IO) {
        val pinnedCert = host.serverCertPem?.let(::decodeCertPem)
        val httpClient = GameStreamHttpClient(identity, host.address, pinnedServerCert = pinnedCert)
        AppListParser.parse(httpClient.getAppListXml(), host.address)
    }

    fun startStream(host: RemoteHost, app: RemoteApp) {
        System.loadLibrary("remotestream")
        check(nativeStartStream(host.address, app.appId)) { "Failed to start remote stream for ${app.name}@${host.address}" }
    }

    private external fun nativeStartStream(hostAddress: String, appId: Int): Boolean

    companion object {
        private fun sha256Fingerprint(cert: X509Certificate): String = try {
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        } catch (e: CertificateEncodingException) {
            ""
        }

        private fun encodeCertPem(cert: X509Certificate): String {
            val base64 = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
            val lines = base64.chunked(64).joinToString("\n")
            return "-----BEGIN CERTIFICATE-----\n$lines\n-----END CERTIFICATE-----\n"
        }

        private fun decodeCertPem(pem: String): X509Certificate {
            val base64 = pem.lineSequence()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            return java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(bytes.inputStream()) as X509Certificate
        }
    }
}
