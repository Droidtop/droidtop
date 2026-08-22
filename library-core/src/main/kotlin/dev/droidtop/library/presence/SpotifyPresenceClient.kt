package dev.droidtop.library.presence

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Real Spotify Web API client for a second-screen "now playing" widget --
 * see docs/SPEC.md §7e for the full design and why Discord's equivalent
 * isn't implemented yet.
 *
 * Uses Authorization Code **with PKCE**, not the client-credentials flow
 * [dev.droidtop.library.scraper.IgdbScraperClient] uses -- that flow
 * authenticates as the app, not as a specific user, and `/me/player/
 * currently-playing` needs a real user's own session. PKCE is Spotify's
 * own documented recommendation for a native app that can't safely embed
 * a client secret (developer.spotify.com/documentation/web-api/tutorials/
 * code-pkce-flow) -- no secret is used or stored anywhere in this client.
 *
 * Real, documented API shape:
 *  - Authorize: `GET https://accounts.spotify.com/authorize` with
 *    `client_id`, `response_type=code`, `redirect_uri`, `code_challenge`,
 *    `code_challenge_method=S256`, `scope=user-read-currently-playing` --
 *    opened in a browser/Custom Tab, redirects back to [redirectUri] with
 *    `?code=...`.
 *  - Token exchange/refresh: `POST https://accounts.spotify.com/api/token`.
 *  - Now playing: `GET https://api.spotify.com/v1/me/player/currently-playing`
 *    with `Authorization: Bearer <access_token>` -- 204 No Content (not an
 *    error) when nothing is playing, handled explicitly below rather than
 *    treated as a failure.
 *
 * Not yet wired to any UI or tested against a real account this session --
 * the authorization-redirect screen and the background poll that would
 * call [fetchNowPlaying] on a timer are real remaining work, not done here.
 */
object SpotifyPresenceClient {
    class PresenceException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Int,
    )

    data class NowPlaying(
        val trackName: String,
        val artistNames: String,
        val albumArtUrl: String?,
        val isPlaying: Boolean,
        val progressMs: Long,
        val durationMs: Long,
    )

    private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val NOW_PLAYING_URL = "https://api.spotify.com/v1/me/player/currently-playing"

    /** Real PKCE code verifier: 43-128 chars from Spotify's documented unreserved-character set. */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(128)
    }

    /** Real PKCE S256 challenge: base64url(sha256(verifier)), no padding, per RFC 7636. */
    fun codeChallengeFor(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun buildAuthorizationUrl(clientId: String, redirectUri: String, codeVerifier: String): String {
        val challenge = codeChallengeFor(codeVerifier)
        val params = listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "scope" to "user-read-currently-playing user-read-playback-state",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return "$AUTHORIZE_URL?$params"
    }

    fun exchangeCodeForToken(
        clientId: String,
        redirectUri: String,
        code: String,
        codeVerifier: String,
    ): TokenResult {
        val body = listOf(
            "client_id" to clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
        )
        return postForToken(body)
    }

    fun refreshAccessToken(clientId: String, refreshToken: String): TokenResult {
        val body = listOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        return postForToken(body)
    }

    private fun postForToken(params: List<Pair<String, String>>): TokenResult {
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        connection.outputStream.use { it.write(body.toByteArray()) }
        if (connection.responseCode != 200) {
            throw PresenceException("Spotify token request failed: HTTP ${connection.responseCode}")
        }
        val json = JSONObject(connection.inputStream.bufferedReader().readText())
        return TokenResult(
            accessToken = json.getString("access_token"),
            // Spotify only returns a new refresh_token on some refreshes; callers must
            // keep the previous one if this field is absent, not overwrite with blank.
            refreshToken = json.optString("refresh_token", ""),
            expiresInSeconds = json.optInt("expires_in", 3600),
        )
    }

    /** Returns null when nothing is currently playing (Spotify's real 204 response), not an error. */
    fun fetchNowPlaying(accessToken: String): NowPlaying? {
        val connection = (URL(NOW_PLAYING_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        return when (connection.responseCode) {
            204 -> null
            200 -> parseNowPlaying(connection.inputStream.bufferedReader().readText())
            else -> throw PresenceException("Spotify now-playing request failed: HTTP ${connection.responseCode}")
        }
    }

    private fun parseNowPlaying(response: String): NowPlaying? {
        val json = JSONObject(response)
        val item = json.optJSONObject("item") ?: return null
        val artists = item.optJSONArray("artists")
        val artistNames = buildList {
            if (artists != null) for (i in 0 until artists.length()) add(artists.getJSONObject(i).getString("name"))
        }.joinToString(", ")
        val images = item.optJSONObject("album")?.optJSONArray("images")
        val albumArtUrl = images?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("url")
        return NowPlaying(
            trackName = item.optString("name", ""),
            artistNames = artistNames,
            albumArtUrl = albumArtUrl,
            isPlaying = json.optBoolean("is_playing", false),
            progressMs = json.optLong("progress_ms", 0L),
            durationMs = item.optLong("duration_ms", 0L),
        )
    }
}
