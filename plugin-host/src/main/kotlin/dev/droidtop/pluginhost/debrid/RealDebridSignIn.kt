package dev.droidtop.pluginhost.debrid

import org.json.JSONObject

/**
 * Real-Debrid's device sign-in for open-source apps (api.real-debrid.com, "Workflow for opensource apps";
 * Droidtop/tracker#638): droidtop asks for a short code under Real-Debrid's open-source client id, the person enters
 * it at real-debrid.com/device, and the approval hands this device its own client id and secret, which turn the code
 * into a token and later refresh it. Nothing of droidtop's own is a secret here. Blocking network calls.
 */
class RealDebridSignIn(private val http: DebridTransport = DebridTransport.HTTP) {
    /** What to show: [userCode] to enter at [verificationUrl]. [deviceCode] is the secret half, never shown. */
    class Code(val deviceCode: String, val userCode: String, val verificationUrl: String, val intervalSeconds: Int, val expiresInSeconds: Int) {
        override fun toString() = "Code(userCode=$userCode, verificationUrl=$verificationUrl)"
    }

    /** What a sign-in keeps: this device's credentials, and a token that ends at [expiresAtMs]. */
    data class Credential(val clientId: String, val clientSecret: String, val accessToken: String, val refreshToken: String, val expiresAtMs: Long) {
        fun toJson(): String = JSONObject()
            .put("client_id", clientId).put("client_secret", clientSecret)
            .put("access_token", accessToken).put("refresh_token", refreshToken)
            .put("expires_at", expiresAtMs).toString()

        override fun toString() = "Credential(expiresAtMs=$expiresAtMs)"

        companion object {
            fun fromJson(text: String): Credential? {
                val json = jsonOrNull(text) as? JSONObject ?: return null
                val values = listOf("client_id", "client_secret", "access_token", "refresh_token").map { json.optString(it) }
                if (values.any { it.isBlank() }) return null
                return Credential(values[0], values[1], values[2], values[3], json.optLong("expires_at"))
            }
        }
    }

    /** How a sign-in ended. */
    sealed interface Outcome {
        data class SignedIn(val credential: Credential) : Outcome
        data object Expired : Outcome
        data object Cancelled : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** A new code, or one sentence saying why there is none. */
    fun code(): Pair<Code?, String?> {
        val (status, body) = http.call("GET", "$OAUTH/device/code?client_id=$OPEN_SOURCE_CLIENT&new_credentials=yes", emptyMap(), null)
        val json = jsonOrNull(body) as? JSONObject
        if (status != 200 || json == null) return null to "Real-Debrid did not give a code (${errorOf(status, json)})"
        val device = json.optString("device_code")
        val user = json.optString("user_code")
        if (device.isBlank() || user.isBlank()) return null to "Real-Debrid gave no code"
        val url = json.optString("verification_url").takeIf { it.startsWith("https://") } ?: "https://real-debrid.com/device"
        return Code(device, user, url, json.optInt("interval", 5).coerceIn(1, 60), json.optInt("expires_in", 600)) to null
    }

    /**
     * Waits for the person to approve [code]: asks every interval until Real-Debrid hands over this device's
     * credentials, then turns the code into a token. [sleep] and [now] are replaceable for the tests.
     */
    fun await(
        code: Code,
        isCancelled: () -> Boolean,
        onWaiting: (secondsLeft: Int) -> Unit = {},
        now: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Outcome {
        val ends = now() + code.expiresInSeconds * 1000L
        while (true) {
            if (isCancelled()) return Outcome.Cancelled
            val left = ((ends - now()) / 1000).toInt()
            if (left <= 0) return Outcome.Expired
            onWaiting(left)
            val (status, body) = http.call("GET", "$OAUTH/device/credentials?client_id=$OPEN_SOURCE_CLIENT&code=${enc(code.deviceCode)}", emptyMap(), null)
            val json = jsonOrNull(body) as? JSONObject
            val id = json?.optString("client_id").orEmpty()
            val secret = json?.optString("client_secret").orEmpty()
            if (status == 200 && id.isNotBlank() && secret.isNotBlank()) {
                return token(id, secret, code.deviceCode, now)?.let { Outcome.SignedIn(it) }
                    ?: Outcome.Failed("Real-Debrid approved the code but gave no token")
            }
            // Until approved the answer is an error: keep asking until the code runs out.
            val pause = code.intervalSeconds * 1000L
            var slept = 0L
            while (slept < pause && !isCancelled()) {
                sleep(minOf(500L, pause - slept))
                slept += 500L
            }
        }
    }

    /** A fresh token for [credential], with the same device credentials; null when Real-Debrid refused it. */
    fun refresh(credential: Credential, now: () -> Long = System::currentTimeMillis): Credential? =
        token(credential.clientId, credential.clientSecret, credential.refreshToken, now)

    private fun token(clientId: String, clientSecret: String, code: String, now: () -> Long): Credential? {
        val form = mapOf("client_id" to clientId, "client_secret" to clientSecret, "code" to code, "grant_type" to DEVICE_GRANT)
        val (status, body) = http.call("POST", "$OAUTH/token", emptyMap(), form)
        val json = jsonOrNull(body) as? JSONObject ?: return null
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token")
        if (status != 200 || access.isBlank() || refresh.isBlank()) return null
        return Credential(clientId, clientSecret, access, refresh, now() + json.optLong("expires_in", 3600) * 1000L)
    }

    private fun errorOf(status: Int, json: JSONObject?): String =
        json?.optString("error")?.takeIf { it.isNotBlank() }?.replace('_', ' ') ?: "answered $status"

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Real-Debrid's published client id for open-source apps: public by design. */
        const val OPEN_SOURCE_CLIENT = "X245A4XAIBGVM"
        const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
        private const val OAUTH = "https://api.real-debrid.com/oauth/v2"

        /** A token this close to its end is refreshed before it is used. */
        const val REFRESH_BEFORE_MS = 10 * 60 * 1000L
    }
}
