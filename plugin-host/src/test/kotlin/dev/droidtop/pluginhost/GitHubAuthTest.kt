package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAuthTest {
    @Test
    fun tokenIsAttachedOnlyForGitHubHostsOverHttps() {
        assertEquals("Bearer t0k", GitHubAuth.authorizationFor("https://api.github.com/repos/a/b/releases", "t0k"))
        assertEquals("Bearer t0k", GitHubAuth.authorizationFor("https://raw.githubusercontent.com/a/b/HEAD/x.json", " t0k "))
        assertEquals("Bearer t0k", GitHubAuth.authorizationFor("https://github.com/a/b/releases/download/v1/x", "t0k"))
        assertNull(GitHubAuth.authorizationFor("https://example.com/x.json", "t0k"))
        assertNull(GitHubAuth.authorizationFor("https://github.com.evil.example/x", "t0k"))
        assertNull(GitHubAuth.authorizationFor("https://evilgithub.com/x", "t0k"))
        // The signed redirect target of a private asset must not get the token.
        assertNull(GitHubAuth.authorizationFor("https://objects.githubusercontent.com/x?sig=1", "t0k"))
        assertNull(GitHubAuth.authorizationFor("http://api.github.com/user", "t0k"))
    }

    @Test
    fun noTokenMeansNoHeader() {
        assertNull(GitHubAuth.authorizationFor("https://api.github.com/user", null))
        assertNull(GitHubAuth.authorizationFor("https://api.github.com/user", ""))
        assertNull(GitHubAuth.authorizationFor("https://api.github.com/user", "   "))
    }

    @Test
    fun apiAssetUrlsAreRecognised() {
        assertTrue(GitHubAuth.isApiAssetUrl("https://api.github.com/repos/a/b/releases/assets/123"))
        assertFalse(GitHubAuth.isApiAssetUrl("https://api.github.com/repos/a/b/releases"))
        assertFalse(GitHubAuth.isApiAssetUrl("https://example.com/repos/a/b/releases/assets/123"))
    }

    @Test
    fun maskShowsOnlyTheEnd() {
        assertEquals("****wxyz", GitHubTokenStore.masked("ghp_abcdwxyz"))
        assertEquals("****", GitHubTokenStore.masked("abc"))
    }
}
