package dev.droidtop.library.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPresenceClientTest {
    @Test
    fun `codeChallengeFor matches the RFC 7636 appendix B worked example`() {
        // Real, published test vector from RFC 7636 Appendix B -- verifies the
        // SHA-256 + base64url(no padding) derivation is byte-for-byte correct,
        // not just "doesn't crash".
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, SpotifyPresenceClient.codeChallengeFor(verifier))
    }

    @Test
    fun `generateCodeVerifier produces a verifier within RFC 7636's 43-128 char bound`() {
        val verifier = SpotifyPresenceClient.generateCodeVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `buildAuthorizationUrl includes the real required PKCE query params`() {
        val url = SpotifyPresenceClient.buildAuthorizationUrl(
            clientId = "test-client-id",
            redirectUri = "droidtop://spotify-callback",
            codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
        )
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(url.contains("client_id=test-client-id"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
        assertTrue(url.contains("response_type=code"))
    }
}
