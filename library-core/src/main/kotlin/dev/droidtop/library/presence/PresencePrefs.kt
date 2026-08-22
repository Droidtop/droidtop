package dev.droidtop.library.presence

import android.content.Context

/**
 * User-supplied Spotify app credentials + stored OAuth tokens for
 * [SpotifyPresenceClient] -- same shared-prefs pattern as
 * [dev.droidtop.library.scraper.ScraperPrefs]. The client ID is the user's
 * own free, self-service Spotify app (developer.spotify.com/dashboard);
 * no client secret is ever stored, since the PKCE flow doesn't use one.
 */
object PresencePrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_SPOTIFY_CLIENT_ID = "droidtop_spotify_client_id"
    private const val KEY_SPOTIFY_REDIRECT_URI = "droidtop_spotify_redirect_uri"
    private const val KEY_SPOTIFY_ACCESS_TOKEN = "droidtop_spotify_access_token"
    private const val KEY_SPOTIFY_REFRESH_TOKEN = "droidtop_spotify_refresh_token"
    private const val KEY_SPOTIFY_TOKEN_EXPIRES_AT = "droidtop_spotify_token_expires_at"

    fun spotifyClientId(context: Context): String =
        prefs(context).getString(KEY_SPOTIFY_CLIENT_ID, "") ?: ""

    fun spotifyRedirectUri(context: Context): String =
        prefs(context).getString(KEY_SPOTIFY_REDIRECT_URI, "droidtop://spotify-callback") ?: "droidtop://spotify-callback"

    fun setSpotifyClientId(context: Context, clientId: String) {
        prefs(context).edit().putString(KEY_SPOTIFY_CLIENT_ID, clientId).apply()
    }

    fun spotifyAccessToken(context: Context): String? = prefs(context).getString(KEY_SPOTIFY_ACCESS_TOKEN, null)

    fun spotifyRefreshToken(context: Context): String? = prefs(context).getString(KEY_SPOTIFY_REFRESH_TOKEN, null)

    /** Epoch millis after which [spotifyAccessToken] should be refreshed via [SpotifyPresenceClient.refreshAccessToken]. */
    fun spotifyTokenExpiresAt(context: Context): Long = prefs(context).getLong(KEY_SPOTIFY_TOKEN_EXPIRES_AT, 0L)

    fun storeSpotifyTokens(context: Context, result: SpotifyPresenceClient.TokenResult) {
        val editor = prefs(context).edit()
            .putString(KEY_SPOTIFY_ACCESS_TOKEN, result.accessToken)
            .putLong(KEY_SPOTIFY_TOKEN_EXPIRES_AT, System.currentTimeMillis() + result.expiresInSeconds * 1000L)
        // Spotify only sends a new refresh_token on some refreshes -- keep the
        // existing one rather than overwriting it with an absent/blank value.
        if (result.refreshToken.isNotBlank()) editor.putString(KEY_SPOTIFY_REFRESH_TOKEN, result.refreshToken)
        editor.apply()
    }

    fun clearSpotifyTokens(context: Context) {
        prefs(context).edit()
            .remove(KEY_SPOTIFY_ACCESS_TOKEN)
            .remove(KEY_SPOTIFY_REFRESH_TOKEN)
            .remove(KEY_SPOTIFY_TOKEN_EXPIRES_AT)
            .apply()
    }

    fun isSpotifyConfigured(context: Context): Boolean = spotifyClientId(context).isNotBlank()

    fun isSpotifyLinked(context: Context): Boolean = !spotifyRefreshToken(context).isNullOrBlank()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
