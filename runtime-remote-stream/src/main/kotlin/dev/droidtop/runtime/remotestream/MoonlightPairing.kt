package dev.droidtop.runtime.remotestream

import java.io.ByteArrayInputStream
import java.security.Key
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Full port of moonlight-android's real PairingManager: the salted-PIN AES
 * challenge/response handshake GameStream hosts use to establish mutual
 * trust (client authenticates via its self-signed cert + a proof it knows
 * the PIN shown on the host's screen; the client in turn verifies the
 * host's response is signed by the cert it just received, to rule out a
 * MITM answering in the host's place).
 */
class MoonlightPairing(
    private val http: GameStreamHttpClient,
    identity: ClientIdentity,
) {
    private val clientCert: X509Certificate = identity.clientCertificate
    private val clientKey: PrivateKey = identity.clientPrivateKey
    private val pemCertBytes: ByteArray = identity.pemEncodedClientCertificate

    data class Result(val state: PairState, val serverCert: X509Certificate?)

    fun pair(pin: String): Result {
        val serverInfo = http.getServerInfo()
        val serverMajorVersion = http.getServerMajorVersion(serverInfo)
        // Gen 7+ (GFE 3.x / current Sunshine) hashes with SHA-256; older hosts use SHA-1.
        val hashAlgo: PairingHashAlgorithm = if (serverMajorVersion >= 7) Sha256Hash else Sha1Hash

        val salt = randomBytes(16)
        val aesKey = generateAesKey(hashAlgo, salt + pin.toByteArray(Charsets.UTF_8))

        // No read timeout: the user must physically enter the PIN on the host before it answers.
        val getCert = http.executePairingCommand(
            "phrase=getservercert&salt=${bytesToHex(salt)}&clientcert=${bytesToHex(pemCertBytes)}",
            enableReadTimeout = false,
        )
        if (GameStreamXml.getString(getCert, "paired", true) != "1") return Result(PairState.FAILED, null)

        val serverCert = extractPlainCert(getCert)
            ?: run {
                // Empty plaincert means another client is already mid-pairing.
                http.unpair()
                return Result(PairState.ALREADY_IN_PROGRESS, null)
            }
        http.pinnedServerCert = serverCert

        val randomChallenge = randomBytes(16)
        val encryptedChallenge = aesEcbEncrypt(randomChallenge, aesKey)
        val challengeResp = http.executePairingCommand("clientchallenge=${bytesToHex(encryptedChallenge)}", enableReadTimeout = true)
        if (GameStreamXml.getString(challengeResp, "paired", true) != "1") {
            http.unpair()
            return Result(PairState.FAILED, null)
        }

        val encServerChallengeResponse = hexToBytes(GameStreamXml.getString(challengeResp, "challengeresponse", true)!!)
        val decServerChallengeResponse = aesEcbDecrypt(encServerChallengeResponse, aesKey)
        val hashLength = hashAlgo.hashLength
        val serverResponse = decServerChallengeResponse.copyOfRange(0, hashLength)
        val serverChallenge = decServerChallengeResponse.copyOfRange(hashLength, hashLength + 16)

        val clientSecret = randomBytes(16)
        val challengeRespHash = hashAlgo.hash(serverChallenge + clientCert.signature + clientSecret)
        val challengeRespEncrypted = aesEcbEncrypt(challengeRespHash, aesKey)
        val secretResp = http.executePairingCommand("serverchallengeresp=${bytesToHex(challengeRespEncrypted)}", enableReadTimeout = true)
        if (GameStreamXml.getString(secretResp, "paired", true) != "1") {
            http.unpair()
            return Result(PairState.FAILED, null)
        }

        val serverSecretResp = hexToBytes(GameStreamXml.getString(secretResp, "pairingsecret", true)!!)
        val serverSecret = serverSecretResp.copyOfRange(0, 16)
        val serverSignature = serverSecretResp.copyOfRange(16, serverSecretResp.size)

        if (!verifySignature(serverSecret, serverSignature, serverCert)) {
            // A real signature mismatch here means someone answered in the host's place.
            http.unpair()
            return Result(PairState.FAILED, null)
        }

        val serverChallengeRespHash = hashAlgo.hash(randomChallenge + serverCert.signature + serverSecret)
        if (!serverChallengeRespHash.contentEquals(serverResponse)) {
            // The host's response doesn't match what our PIN-derived key predicts -- wrong PIN.
            http.unpair()
            return Result(PairState.PIN_WRONG, null)
        }

        val clientPairingSecret = clientSecret + signData(clientSecret, clientKey)
        val clientSecretResp = http.executePairingCommand("clientpairingsecret=${bytesToHex(clientPairingSecret)}", enableReadTimeout = true)
        if (GameStreamXml.getString(clientSecretResp, "paired", true) != "1") {
            http.unpair()
            return Result(PairState.FAILED, null)
        }

        // GFE/Sunshine require this extra round-trip to actually flip to "paired" state.
        val pairChallenge = http.executePairingChallenge()
        if (GameStreamXml.getString(pairChallenge, "paired", true) != "1") {
            http.unpair()
            return Result(PairState.FAILED, null)
        }

        return Result(PairState.PAIRED, serverCert)
    }

    private fun extractPlainCert(xml: String): X509Certificate? {
        val certText = GameStreamXml.getString(xml, "plaincert", false) ?: return null
        val certBytes = hexToBytes(certText)
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }

    private fun generateAesKey(hashAlgo: PairingHashAlgorithm, keyData: ByteArray): ByteArray =
        hashAlgo.hash(keyData).copyOf(16)

    private fun aesEcbTransform(mode: Int, key: ByteArray, input: ByteArray): ByteArray {
        val blockSize = 16
        val roundedSize = (input.size + (blockSize - 1)) / blockSize * blockSize
        val padded = input.copyOf(roundedSize) // zero-padded, matching the raw block-cipher reference
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(padded)
    }

    private fun aesEcbEncrypt(data: ByteArray, key: ByteArray) = aesEcbTransform(Cipher.ENCRYPT_MODE, key, data)
    private fun aesEcbDecrypt(data: ByteArray, key: ByteArray) = aesEcbTransform(Cipher.DECRYPT_MODE, key, data)

    private fun sha256SignatureFor(key: Key): Signature = when (key.algorithm) {
        "RSA" -> Signature.getInstance("SHA256withRSA")
        "EC" -> Signature.getInstance("SHA256withECDSA")
        else -> throw IllegalArgumentException("Unhandled key algorithm: ${key.algorithm}")
    }

    private fun verifySignature(data: ByteArray, signature: ByteArray, cert: X509Certificate): Boolean {
        val sig = sha256SignatureFor(cert.publicKey)
        sig.initVerify(cert.publicKey)
        sig.update(data)
        return sig.verify(signature)
    }

    private fun signData(data: ByteArray, key: PrivateKey): ByteArray {
        val sig = sha256SignatureFor(key)
        sig.initSign(key)
        sig.update(data)
        return sig.sign()
    }

    private fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private interface PairingHashAlgorithm {
        val hashLength: Int
        fun hash(data: ByteArray): ByteArray
    }

    private object Sha1Hash : PairingHashAlgorithm {
        override val hashLength = 20
        override fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)
    }

    private object Sha256Hash : PairingHashAlgorithm {
        override val hashLength = 32
        override fun hash(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    }

    companion object {
        private val hexChars = "0123456789ABCDEF".toCharArray()

        fun bytesToHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = hexChars[v ushr 4]
                out[i * 2 + 1] = hexChars[v and 0x0F]
            }
            return String(out)
        }

        fun hexToBytes(s: String): ByteArray {
            require(s.length % 2 == 0) { "Illegal string length: ${s.length}" }
            val data = ByteArray(s.length / 2)
            for (i in s.indices step 2) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            }
            return data
        }

        /** Random 4-digit PIN, matching Moonlight's real client-side PIN generator. */
        fun generatePinString(): String {
            val r = SecureRandom()
            return "%d%d%d%d".format(r.nextInt(10), r.nextInt(10), r.nextInt(10), r.nextInt(10))
        }
    }
}
