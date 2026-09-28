package dev.droidtop.pluginhost

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source-key fetch (docs/SPEC.md 12a "The primary path"):
 * URL derivation for the well-known key file, the two-field published
 * format, and that nothing untrustworthy (a non-P-256 key, an origin
 * claiming the official id) ever survives a fetch to reach a prompt.
 * The network leg is injected, so these tests run the real derivation
 * and parsing chain with no network.
 */
class PluginSourceKeysTest {
    private fun freshKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun spkiBase64() = Base64.getEncoder().encodeToString(freshKeyPair().public.encoded)

    private fun keyJson(origin: String, key: String) =
        JSONObject().put("origin", origin).put("key", key).toString()

    @Test
    fun `a github repo URL becomes the raw well-known file URL`() {
        val raw = "https://raw.githubusercontent.com/acme/acme-plugins/HEAD/${PluginSourceKeys.KEY_FILE_NAME}"
        assertEquals(listOf(raw), PluginSourceKeys.keyUrlsFor("https://github.com/acme/acme-plugins"))
        assertEquals(listOf(raw), PluginSourceKeys.keyUrlsFor("https://github.com/acme/acme-plugins/"))
        assertEquals(listOf(raw), PluginSourceKeys.keyUrlsFor("https://github.com/acme/acme-plugins.git"))
        // github.com over http is upgraded: the derived raw URL is always https.
        assertEquals(listOf(raw), PluginSourceKeys.keyUrlsFor("http://github.com/acme/acme-plugins"))
        // A /tree/<branch> URL names the branch to read the key from.
        assertEquals(
            listOf("https://raw.githubusercontent.com/acme/acme-plugins/nightly/${PluginSourceKeys.KEY_FILE_NAME}"),
            PluginSourceKeys.keyUrlsFor("https://github.com/acme/acme-plugins/tree/nightly"),
        )
    }

    @Test
    fun `an https json URL is fetched directly, a plain https root gets the well-known file appended`() {
        val index = "https://example.com/plugins/catalog-index.json"
        assertEquals(listOf(index), PluginSourceKeys.keyUrlsFor(index))
        assertEquals(
            listOf("https://example.com/plugins/${PluginSourceKeys.KEY_FILE_NAME}"),
            PluginSourceKeys.keyUrlsFor("https://example.com/plugins"),
        )
        // raw.githubusercontent.com without a file name still lands on the well-known file.
        assertEquals(
            listOf("https://raw.githubusercontent.com/acme/acme-plugins/HEAD/${PluginSourceKeys.KEY_FILE_NAME}"),
            PluginSourceKeys.keyUrlsFor("https://raw.githubusercontent.com/acme/acme-plugins/HEAD"),
        )
    }

    @Test
    fun `plaintext http and things that are not URLs are refused`() {
        assertNull(PluginSourceKeys.keyUrlsFor("http://example.com/plugins"))
        assertNull(PluginSourceKeys.keyUrlsFor("http://example.com/plugins/index.json"))
        assertNull(PluginSourceKeys.keyUrlsFor("github.com/acme/acme-plugins"))
        assertNull(PluginSourceKeys.keyUrlsFor(""))
        assertNull(PluginSourceKeys.keyUrlsFor("not a url at all"))
        assertNull(PluginSourceKeys.keyUrlsFor("https://github.com/only-owner"))
    }

    @Test
    fun `parsePublishedKey reads the documented two fields and refuses everything else`() {
        val key = spkiBase64()
        val published = PluginSourceKeys.parsePublishedKey(keyJson("acme", key))
        assertEquals("acme", published?.origin)
        assertEquals(key, published?.keyBase64)

        assertNull(PluginSourceKeys.parsePublishedKey(JSONObject().put("key", key).toString()))
        assertNull(PluginSourceKeys.parsePublishedKey(JSONObject().put("origin", "acme").toString()))
        assertNull(PluginSourceKeys.parsePublishedKey("this is not json"))
        assertNull(PluginSourceKeys.parsePublishedKey(""))
    }

    @Test
    fun `fetchKey returns the source's validated key`() {
        val key = spkiBase64()
        val fetched = PluginSourceKeys.fetchKey("https://github.com/acme/acme-plugins") { url ->
            assertEquals("https://raw.githubusercontent.com/acme/acme-plugins/HEAD/${PluginSourceKeys.KEY_FILE_NAME}", url)
            keyJson("acme", key).toByteArray()
        }
        assertTrue(fetched is PluginSourceKeys.FetchResult.Fetched)
        val published = (fetched as PluginSourceKeys.FetchResult.Fetched).key
        assertEquals("acme", published.origin)
        assertEquals(key, published.keyBase64)
    }

    @Test
    fun `fetchKey refuses a source publishing the official origin id`() {
        val key = spkiBase64()
        val fetched = PluginSourceKeys.fetchKey("https://github.com/acme/acme-plugins") {
            keyJson(PluginOriginKeys.OFFICIAL_ORIGIN, key).toByteArray()
        }
        assertTrue(fetched is PluginSourceKeys.FetchResult.Failed)
        assertTrue((fetched as PluginSourceKeys.FetchResult.Failed).reason.contains("official"))
    }

    @Test
    fun `fetchKey refuses a published key that is not a P-256 SPKI`() {
        val fetched = PluginSourceKeys.fetchKey("https://example.com/plugins") {
            keyJson("acme", "not a real spki key").toByteArray()
        }
        assertTrue(fetched is PluginSourceKeys.FetchResult.Failed)
        assertTrue((fetched as PluginSourceKeys.FetchResult.Failed).reason.contains("P-256"))
    }

    @Test
    fun `fetchKey fails with a user-facing reason when nothing could be fetched`() {
        val fetched = PluginSourceKeys.fetchKey("https://example.com/plugins") { null }
        assertTrue(fetched is PluginSourceKeys.FetchResult.Failed)
        val reason = (fetched as PluginSourceKeys.FetchResult.Failed).reason
        assertTrue(reason.contains("Couldn't fetch"))
        assertTrue(reason.contains(PluginSourceKeys.KEY_FILE_NAME))
    }
}
