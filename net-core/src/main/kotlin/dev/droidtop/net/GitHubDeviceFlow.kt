package dev.droidtop.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * GitHub's OAuth device flow (RFC 8628) as droidtop uses it to sign the
 * person in without typing a token (docs/SPEC.md 12a "Signing in with
 * GitHub"). The client id below is a PUBLIC identifier of droidtop's
 * registered OAuth app; the device flow has no client secret and droidtop
 * holds none.
 */
object GitHubOAuth {
    const val CLIENT_ID = "Ov23liF3eYQLTmtf1b29"
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

    /** No scope at all: a token that reads only public data, and lifts the request limit. */
    const val SCOPE_PUBLIC = ""

    /**
     * The smallest scope GitHub offers for reading a private repository. GitHub's OAuth apps have no
     * read-only variant of it: the permission also allows changes, which droidtop never makes. The
     * sign-in screen says so, and offers a pasted fine-grained token as the read-only alternative.
     */
    const val SCOPE_PRIVATE_REPOS = "repo"
}

/** What GitHub hands out to start a sign-in: show [userCode] and [verificationUri]; poll with [deviceCode]. */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
) {
    /** The device code is the secret half of the pair: never printed. */
    override fun toString(): String = "DeviceCode(userCode=$userCode, verificationUri=$verificationUri, expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

sealed interface DeviceCodeResult {
    data class Issued(val code: DeviceCode) : DeviceCodeResult
    data class Offline(val reason: String) : DeviceCodeResult
    data class Failed(val reason: String) : DeviceCodeResult
}

sealed interface DeviceFlowOutcome {
    data class Granted(val token: String, val scope: String) : DeviceFlowOutcome {
        /** Never carries the token into a log line or an exception message. */
        override fun toString(): String = "Granted(scope=$scope)"
    }

    /** The person pressed Deny on github.com. */
    object Denied : DeviceFlowOutcome

    /** The code ran out before anyone approved it. */
    object Expired : DeviceFlowOutcome

    object Cancelled : DeviceFlowOutcome

    /** The network went away and stayed away. */
    data class Offline(val reason: String) : DeviceFlowOutcome

    data class Failed(val reason: String) : DeviceFlowOutcome
}

/** One HTTP answer, status and body, for [DeviceFlowTransport]. */
data class DeviceFlowReply(val status: Int, val body: String)

/** The one network seam of the flow; the unit tests supply a scripted one. Throws [IOException] when GitHub cannot be reached. */
fun interface DeviceFlowTransport {
    fun post(url: String, form: Map<String, String>): DeviceFlowReply
}

/**
 * The state machine of the flow, with its transport, clock and sleeping
 * injected so the unit tests drive it with no network and no real waiting.
 * It honours the `interval` GitHub states and every `slow_down` (RFC 8628
 * 3.5: the interval grows by five seconds, or to the value GitHub sends),
 * stops at the code's expiry, and polls only while `isCancelled` is false.
 * Blocking: call it off the main thread.
 */
class GitHubDeviceFlow(
    private val transport: DeviceFlowTransport = HttpDeviceFlowTransport,
    private val clientId: String = GitHubOAuth.CLIENT_ID,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun requestCode(scope: String): DeviceCodeResult {
        val form = buildMap {
            put("client_id", clientId)
            if (scope.isNotEmpty()) put("scope", scope)
        }
        val reply = try {
            transport.post(GitHubOAuth.DEVICE_CODE_URL, form)
        } catch (failure: IOException) {
            return DeviceCodeResult.Offline(failure.message ?: "no network")
        }
        val json = parse(reply.body)
        if (json != null) {
            val error = json.string("error")
            if (error != null) return DeviceCodeResult.Failed(describeError(error, json.string("error_description")))
        }
        if (reply.status != 200 || json == null) return DeviceCodeResult.Failed("GitHub answered HTTP ${reply.status}")
        val deviceCode = json.string("device_code")
        val userCode = json.string("user_code")
        val uri = json.string("verification_uri")
        if (deviceCode == null || userCode == null || uri == null) return DeviceCodeResult.Failed("GitHub's answer was not a device code")
        // The address is shown to a person who will type it into a browser: it must be GitHub's own.
        if (!isGitHubHttps(uri)) return DeviceCodeResult.Failed("GitHub named a sign-in address that is not on github.com")
        val expires = json.int("expires_in") ?: DEFAULT_EXPIRES_SECONDS
        val interval = (json.int("interval") ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS)
        return DeviceCodeResult.Issued(DeviceCode(deviceCode, userCode, uri, expires, interval))
    }

    /**
     * Polls until the person approves, denies, the code expires, [isCancelled] turns true, or the
     * network stays away. [onWaiting] hears the seconds left once a second, for a countdown.
     */
    fun poll(code: DeviceCode, isCancelled: () -> Boolean, onWaiting: (secondsLeft: Int) -> Unit = {}): DeviceFlowOutcome {
        val deadline = nowMs() + code.expiresInSeconds * 1000L
        var intervalMs = code.intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS) * 1000L
        var offlineInARow = 0
        while (true) {
            // Wait one interval, a second at a time, so cancel and the countdown stay prompt.
            var waited = 0L
            while (waited < intervalMs) {
                if (isCancelled()) return DeviceFlowOutcome.Cancelled
                val left = deadline - nowMs()
                if (left <= 0) return DeviceFlowOutcome.Expired
                onWaiting(((left + 999) / 1000).toInt())
                val slice = minOf(1000L, intervalMs - waited, left)
                sleepMs(slice)
                waited += slice
            }
            if (isCancelled()) return DeviceFlowOutcome.Cancelled
            if (nowMs() >= deadline) return DeviceFlowOutcome.Expired
            val reply = try {
                transport.post(
                    GitHubOAuth.ACCESS_TOKEN_URL,
                    mapOf("client_id" to clientId, "device_code" to code.deviceCode, "grant_type" to GRANT_TYPE),
                )
            } catch (failure: IOException) {
                if (++offlineInARow >= MAX_OFFLINE_POLLS) return DeviceFlowOutcome.Offline(failure.message ?: "no network")
                continue
            }
            if (reply.status in 500..599) {
                if (++offlineInARow >= MAX_OFFLINE_POLLS) return DeviceFlowOutcome.Offline("GitHub answered HTTP ${reply.status}")
                continue
            }
            offlineInARow = 0
            val json = parse(reply.body)
                ?: return DeviceFlowOutcome.Failed("GitHub answered HTTP ${reply.status} with something that was not a sign-in answer")
            val token = json.string("access_token")
            if (token != null) return DeviceFlowOutcome.Granted(token, json.string("scope").orEmpty())
            when (val error = json.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    val stated = json.int("interval")?.times(1000L)
                    intervalMs = maxOf(stated ?: (intervalMs + SLOW_DOWN_STEP_MS), intervalMs + 1000L)
                }
                "expired_token" -> return DeviceFlowOutcome.Expired
                "access_denied" -> return DeviceFlowOutcome.Denied
                else -> return DeviceFlowOutcome.Failed(describeError(error ?: "unknown_error", json.string("error_description")))
            }
        }
    }

    private companion object {
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val DEFAULT_EXPIRES_SECONDS = 900
        const val DEFAULT_INTERVAL_SECONDS = 5
        const val MIN_INTERVAL_SECONDS = 1
        const val SLOW_DOWN_STEP_MS = 5_000L
        const val MAX_OFFLINE_POLLS = 3

        fun parse(body: String): JsonObject? =
            runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()

        fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        fun JsonObject.int(name: String): Int? =
            (this[name] as? JsonPrimitive)?.intOrNull

        fun isGitHubHttps(address: String): Boolean {
            val uri = runCatching { URI(address) }.getOrNull() ?: return false
            return "https".equals(uri.scheme, ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true)
        }

        /** A sentence for GitHub's machine-readable error codes. */
        fun describeError(error: String, description: String?): String = when (error) {
            "device_flow_disabled" -> "GitHub sign-in is not enabled for this app"
            "incorrect_client_credentials" -> "GitHub does not recognise this app's sign-in id"
            "incorrect_device_code" -> "GitHub no longer recognises this sign-in code"
            "unsupported_grant_type" -> "GitHub refused the sign-in request"
            else -> description?.let { "GitHub said: $it" } ?: "GitHub said: $error"
        }
    }
}

/** The real transport: a form POST to github.com, JSON back, no credentials of any kind in the request. */
object HttpDeviceFlowTransport : DeviceFlowTransport {
    override fun post(url: String, form: Map<String, String>): DeviceFlowReply {
        require(url.startsWith("https://github.com/")) { "device flow talks only to github.com" }
        val body = form.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", Http.USER_AGENT)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return DeviceFlowReply(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
