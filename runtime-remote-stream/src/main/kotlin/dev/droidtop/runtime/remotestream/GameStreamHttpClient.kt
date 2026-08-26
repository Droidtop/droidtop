package dev.droidtop.runtime.remotestream

import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Mutual-TLS HTTP(S) client for one GameStream host, ported from
 * moonlight-android's real NvHTTP. GameStream identifies the client by its
 * self-signed cert ([ClientIdentity]) and the client identifies the host by
 * trust-on-first-use: the exact cert the host presented during pairing
 * ([pinnedServerCert]) is the only cert accepted afterwards -- there is no
 * CA to validate against, so pinning IS the security model here.
 */
class GameStreamHttpClient(
    identity: ClientIdentity,
    private val address: String,
    private val httpPort: Int = DEFAULT_HTTP_PORT,
    @Volatile var pinnedServerCert: X509Certificate? = null,
) {
    private val httpBaseUrl = HttpUrl.Builder().scheme("http").host(address).port(httpPort).build()
    private var httpsPort = 0

    private val defaultTrustManager: X509TrustManager = run {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val trustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            error("Should never be called")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                // Allow hosts with a real CA-issued cert to validate normally.
                defaultTrustManager.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                // Expected for GFE/Sunshine's self-signed certs -- fall back
                // to comparing against whatever we pinned during pairing.
                val pinned = pinnedServerCert
                if (chain.size == 1 && pinned != null) {
                    if (chain[0] != pinned) throw CertificateException("Certificate mismatch")
                } else {
                    throw e
                }
            }
        }
    }

    private val keyManager = object : X509KeyManager {
        override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = "droidtop-remotestream"
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.clientCertificate)
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getPrivateKey(alias: String?): PrivateKey = identity.clientPrivateKey
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    private val hostnameVerifier = HostnameVerifier { hostname, session: SSLSession ->
        try {
            val peerCerts = session.peerCertificates
            val pinned = pinnedServerCert
            if (peerCerts.size == 1 && pinned != null && peerCerts[0] == pinned) {
                return@HostnameVerifier true
            }
        } catch (e: SSLPeerUnverifiedException) {
            // Fall through to the default verifier below.
        }
        HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
    }

    private val sslSocketFactory = run {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        sslContext.socketFactory
    }

    private fun clientBuilder() = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .sslSocketFactory(sslSocketFactory, trustManager)
        .hostnameVerifier(hostnameVerifier)
        .proxy(Proxy.NO_PROXY)

    /** Standard timeout, read timeout applies -- used for everything but the PIN-entry step. */
    private val longConnectClient = clientBuilder()
        .connectTimeout(LONG_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** No read timeout -- the user must physically enter the PIN before the host responds. */
    private val longConnectNoReadTimeoutClient = longConnectClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun get(client: OkHttpClient, baseUrl: HttpUrl, path: String, query: String? = null): String {
        val urlBuilder = baseUrl.newBuilder().addPathSegment(path)
        if (query != null) urlBuilder.query(query)
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        client.newCall(request).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    fun getServerInfo(): String = get(longConnectClient, httpBaseUrl, "serverinfo")

    fun getServerMajorVersion(serverInfo: String): Int {
        val version = GameStreamXml.getString(serverInfo, "appversion", true)
            ?: throw XmlNotFoundException("Missing server version field")
        return version.split(".").firstOrNull()?.toIntOrNull()
            ?: throw XmlNotFoundException("Malformed server version field: $version")
    }

    private fun httpsBaseUrl(): HttpUrl {
        if (httpsPort == 0) {
            httpsPort = try {
                GameStreamXml.getString(getServerInfo(), "HttpsPort", true)?.toInt() ?: DEFAULT_HTTPS_PORT
            } catch (e: Exception) {
                DEFAULT_HTTPS_PORT
            }
        }
        return HttpUrl.Builder().scheme("https").host(address).port(httpsPort).build()
    }

    /** `pair` phrase requests during the PIN-entry step -- plain HTTP, no read timeout. */
    fun executePairingCommand(additionalArguments: String, enableReadTimeout: Boolean): String {
        val client = if (enableReadTimeout) longConnectClient else longConnectNoReadTimeoutClient
        return get(client, httpBaseUrl, "pair", "devicename=roth&updateState=1&$additionalArguments")
    }

    /** Final "are we really paired now" round-trip, over HTTPS using the freshly-pinned cert. */
    fun executePairingChallenge(): String =
        get(longConnectClient, httpsBaseUrl(), "pair", "devicename=roth&updateState=1&phrase=pairchallenge")

    fun unpair() {
        get(longConnectClient, httpBaseUrl, "unpair")
    }

    fun getAppListXml(): String = get(longConnectClient, httpsBaseUrl(), "applist")

    companion object {
        const val DEFAULT_HTTP_PORT = 47989
        private const val DEFAULT_HTTPS_PORT = 47984
        private const val LONG_CONNECTION_TIMEOUT_MS = 5000L
        private const val READ_TIMEOUT_MS = 7000L
    }
}
