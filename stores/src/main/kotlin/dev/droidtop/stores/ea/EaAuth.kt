package dev.droidtop.stores.ea

// Ported from DroidDeck app/src/main/java/com/droiddeck/launcher/stores/ea/EaAuth.kt
// at cc63b403872b (GPL-3.0; see NOTICE.md); adapted to droidtop's store and HTTP interfaces.

import android.content.Context
import android.util.Log
import dev.droidtop.net.AtomicWrite
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EaAuth {
    private const val TAG = "EaAuth"
    const val CLIENT_ID = "JUNO_PC_CLIENT"
    private const val CLIENT_SECRET = "4mRLtYMb6vq9qglomWEaT4ChxsXWcyqbQpuBNfMPOYOiDmYYQmjuaBsF2Zp0RyVeWkfqhE9TuGgAw7te"
    const val AUTH_URL = "https://accounts.ea.com/connect/auth"
    internal var tokenUrl = "https://accounts.ea.com/connect/token"
    const val REDIRECT = "qrc:///html/login_successful.html"
    private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    private val refreshLock = Any()
    private var generation = 0L
    private val random = SecureRandom()

    private val PC_SIGN_KEYS = mapOf(
        "v1" to "ISa3dpGOc8wW7Adn4auACSQmaccrOyR2".toByteArray(),
        "v2" to "nt5FfJbdPzNcl2pkC3zgjO43Knvscxft".toByteArray(),
    )

    val OPAQUE_SCOPES = listOf(
        "basic.commerce.cartv2", "service.atom", "dp.client.default", "signin", "social_recommendation_user",
        "basic.optin.write", "basic.commerce.cartv2.write", "basic.billing", "external.social_information_ups_admin",
    )

    class Account(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val userId: String,
        val personaId: String,
        val displayName: String,
        val eaId: String,
        val rejected: Boolean = false,
    )

    private class Rejected(message: String) : IllegalStateException(message)

    class Pending(val url: String, val verifier: String, val state: String)

    internal fun authFile(context: Context) = File(context.filesDir, "ea/session.json")

    private fun read(context: Context): JSONObject? = authFile(context).takeIf { it.isFile }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun write(context: Context, record: JSONObject) = AtomicWrite.text(authFile(context), record.toString())

    internal fun fromJson(o: JSONObject): Account? {
        val access = o.optString("access_token").ifEmpty { return null }
        return Account(
            accessToken = access,
            refreshToken = o.optString("refresh_token"),
            expiresAt = o.optLong("expires_at"),
            userId = o.optString("user_id"),
            personaId = o.optString("persona_id"),
            displayName = o.optString("display_name"),
            eaId = o.optString("ea_id"),
            rejected = o.optBoolean("refresh_rejected"),
        )
    }

    fun load(context: Context): Account? = read(context.applicationContext)?.let(::fromJson)

    private fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("ea-auth", Context.MODE_PRIVATE)
        prefs.getString("device", null)?.let { return it }
        val made = EaCrypto.hex(ByteArray(16).also(random::nextBytes))
        prefs.edit().putString("device", made).apply()
        return made
    }

    internal fun pcSign(device: String, now: Date = Date(), version: String = if (random.nextBoolean()) "v1" else "v2"): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val mid = EaCrypto.fnv1a(("DroidDeck" + device.uppercase() + "DroidDeck").toByteArray()).toString()
        val json = "{\"av\":\"v1\",\"bsn\":\"None\",\"gid\":0,\"hsn\":\"None\",\"mid\":\"$mid\",\"msn\":\"${device.uppercase()}\",\"sv\":\"$version\",\"ts\":\"$stamp\"}"
        val payload = EaCrypto.base64Url(json.toByteArray())
        val signature = EaCrypto.base64Url(EaCrypto.hmacSha256(PC_SIGN_KEYS.getValue(version), payload.toByteArray()))
        return "$payload.$signature"
    }

    internal fun authUrl(
        device: String,
        responseType: String,
        challenge: String? = null,
        accessToken: String? = null,
        clientId: String = CLIENT_ID,
        scopes: List<String> = emptyList(),
        tokenFormat: String? = null,
        expiresIn: Int? = null,
    ): String {
        val b = AUTH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("sbiod_enabled", "false")
            .addQueryParameter("response_type", responseType)
            .addQueryParameter("locale", "en_US")
            .addQueryParameter("pc_sign", pcSign(device))
            .addQueryParameter("nonce", random.nextInt().toString())
        if (scopes.isNotEmpty()) b.addQueryParameter("scope", scopes.joinToString(" "))
        if (challenge != null) {
            b.addQueryParameter("code_challenge_method", "S256")
            b.addQueryParameter("code_challenge", challenge)
        }
        if (accessToken != null) b.addQueryParameter("access_token", accessToken)
        if (tokenFormat != null) b.addQueryParameter("token_format", tokenFormat)
        if (expiresIn != null) b.addQueryParameter("expires_in", expiresIn.toString())
        return b.build().toString()
    }

    fun begin(context: Context): Pending {
        val verifier = EaCrypto.base64Url(ByteArray(32).also(random::nextBytes))
        val challenge = EaCrypto.base64Url(EaCrypto.sha256(verifier.toByteArray()))
        val state = EaCrypto.base64Url(ByteArray(32).also(random::nextBytes))
        return Pending(authUrl(deviceId(context), "code", challenge = challenge).toHttpUrl().newBuilder()
            .addQueryParameter("state", state).build().toString(), verifier, state)
    }

    /** Pulls one parameter out of the redirect EA sends to its desktop client's local page. */
    internal fun redirectValue(location: String, name: String): String? {
        val normal = location.replace("qrc:///html", "https://ea-callback.invalid").replace("qrc:/html", "https://ea-callback.invalid")
        val uri = runCatching { normal.toHttpUrl() }.getOrNull() ?: return null
        uri.queryParameter(name)?.let { return it }
        val fragment = uri.encodedFragment ?: return null
        return runCatching { "https://ea-callback.invalid/?$fragment".toHttpUrl().queryParameter(name) }.getOrNull()
    }

    private fun tokenCall(values: List<Pair<String, String>>): JSONObject {
        val reply = EaHttp.request(
            tokenUrl, "POST",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            EaHttp.form(values + listOf("client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET)).toByteArray(),
            follow = false,
        )
        val json = runCatching { JSONObject(reply.text) }.getOrNull()
        if (!reply.ok || json == null || json.optString("access_token").isEmpty()) {
            val message = "EA token request failed (${reply.status}): ${json?.optString("error_description")?.ifEmpty { json.optString("error") } ?: ""}"
            throw if (json?.optString("error") == "invalid_grant") Rejected(message) else IllegalStateException(message)
        }
        return json
    }

    fun exchange(context: Context, code: String, verifier: String) {
        val started = synchronized(refreshLock) { generation }
        val token = tokenCall(
            listOf(
                "grant_type" to "authorization_code", "code" to code, "code_verifier" to verifier,
                "redirect_uri" to REDIRECT, "token_format" to "JWS",
            ),
        )
        val access = token.getString("access_token")
        val player = EaApi.player(access)
        check(player.userId.isNotBlank()) { "EA did not identify the signed-in account" }
        val record = JSONObject()
            .put("access_token", access)
            .put("refresh_token", token.optString("refresh_token"))
            .put("expires_at", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000)
            .put("user_id", player.userId)
            .put("persona_id", player.personaId)
            .put("display_name", player.displayName)
            .put("ea_id", player.eaId)
        synchronized(refreshLock) {
            check(generation == started) { "The EA sign-in was cancelled. Start it again." }
            write(context.applicationContext, record)
        }
    }

    private fun fresh(a: Account) = a.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS

    private fun refreshLocked(context: Context, a: Account): String? {
        if (a.refreshToken.isEmpty()) return null
        val token = try {
            tokenCall(listOf("grant_type" to "refresh_token", "refresh_token" to a.refreshToken))
        } catch (e: Exception) {
            Log.w(TAG, "refresh: ${e.message}")
            if (e is Rejected) markRejected(context, a.refreshToken)
            return null
        }
        val access = token.getString("access_token")
        val player = if (load(context)?.userId.isNullOrEmpty()) runCatching { EaApi.player(access) }.getOrNull() else null
        return access.takeIf { storeRefreshed(context, access, token.optLong("expires_in", 3600), token.optString("refresh_token"), player) }
    }

    /** Writes a refreshed token into the saved sign-in, unless sign-out removed it meanwhile. */
    internal fun storeRefreshed(context: Context, access: String, expiresIn: Long, refresh: String, player: EaApi.Player? = null): Boolean =
        synchronized(refreshLock) {
            val app = context.applicationContext
            val record = read(app) ?: return false
            record.put("access_token", access).put("expires_at", System.currentTimeMillis() + expiresIn * 1000)
            refresh.takeIf { it.isNotEmpty() }?.let { record.put("refresh_token", it) }
            record.remove("refresh_rejected")
            player?.let { record.put("user_id", it.userId).put("persona_id", it.personaId).put("display_name", it.displayName).put("ea_id", it.eaId) }
            write(app, record)
            true
        }

    private fun markRejected(context: Context, refresh: String) = synchronized(refreshLock) {
        val app = context.applicationContext
        val record = read(app) ?: return@synchronized
        if (record.optString("refresh_token") != refresh) return@synchronized
        write(app, record.put("refresh_rejected", true))
    }

    fun signedIn(context: Context): Boolean {
        val a = load(context) ?: return false
        return a.userId.isNotEmpty() && !((a.rejected || a.refreshToken.isEmpty()) && a.expiresAt <= System.currentTimeMillis())
    }

    fun accessToken(context: Context): String? {
        val current = load(context) ?: return null
        if (fresh(current)) return current.accessToken
        synchronized(refreshLock) {
            val again = load(context) ?: return null
            if (fresh(again)) return again.accessToken
            return refreshLocked(context, again) ?: again.accessToken.takeIf { again.expiresAt > System.currentTimeMillis() }
        }
    }

    fun refreshNow(context: Context, stale: String?): String? = synchronized(refreshLock) {
        val current = load(context) ?: return null
        if (stale != null && current.accessToken != stale) return current.accessToken
        refreshLocked(context, current)
    }

    /** Asks EA to mint a code or token for another client from the signed-in session, as the EA app does for games. */
    fun mint(
        context: Context,
        clientId: String,
        responseType: String,
        scopes: List<String> = emptyList(),
        tokenFormat: String? = null,
        expiresIn: Int? = null,
    ): String? {
        val access = accessToken(context) ?: return null
        val url = authUrl(deviceId(context), responseType, accessToken = access, clientId = clientId, scopes = scopes, tokenFormat = tokenFormat, expiresIn = expiresIn)
        val reply = runCatching { EaHttp.request(url, follow = false) }.getOrElse {
            Log.w(TAG, "mint $clientId: ${it.javaClass.simpleName}")
            return null
        }
        val location = reply.header("Location") ?: run {
            Log.w(TAG, "mint $clientId: no redirect (${reply.status})")
            return null
        }
        if (location.startsWith("https://signin.ea.com")) {
            Log.w(TAG, "mint $clientId: the session needs a new sign-in")
            return null
        }
        return redirectValue(location, if (responseType == "token") "access_token" else responseType)
    }

    /** Waits for any refresh in progress, so it cannot write the sign-in back afterwards. */
    fun signOut(context: Context) {
        synchronized(refreshLock) { generation++; authFile(context.applicationContext).delete() }
    }
}

