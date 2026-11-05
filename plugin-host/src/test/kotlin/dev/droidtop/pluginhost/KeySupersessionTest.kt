package dev.droidtop.pluginhost

import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Superseded keys, the owner's design (Droidtop/tracker#887): a source marks an old key "superseded by" its current
 * key, droidtop follows it for trust that came from that source, and what the old key signed stays valid.
 */
class KeySupersessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun spki(): String = Base64.getEncoder().encodeToString(
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded,
    )

    private val old = "a".repeat(64)
    private val current = "b".repeat(64)

    @Test
    fun `markers are read from a key block, and anything unreadable is none`() {
        val block = JSONObject().put(
            "superseded",
            JSONArray()
                .put(JSONObject().put("keySha256", old).put("by", current))
                .put(JSONObject().put("keySha256", "not hex").put("by", current))
                .put(JSONObject().put("keySha256", current).put("by", current)),
        )
        assertEquals(mapOf(old to current), KeySupersession.markers(block))
        assertTrue(KeySupersession.markers(JSONObject()).isEmpty())
        assertTrue(KeySupersession.markers(null).isEmpty())
    }

    @Test
    fun `a marker is followed only to exactly the key the source names now`() {
        val markers = mapOf(old to current)
        assertTrue(KeySupersession.follows(markers, old, current))
        assertTrue(KeySupersession.follows(markers, old.uppercase(), current))
        assertFalse(KeySupersession.follows(markers, old, "c".repeat(64)))
        assertFalse(KeySupersession.follows(markers, "c".repeat(64), current))
    }

    @Test
    fun `following a marker moves the trusted key and keeps the old one as a predecessor`() {
        val file: File = tmp.newFile("plugin-user-keys.json")
        val before = spki()
        val after = spki()
        UserOriginKeys.add(file, "gamegrab", before, source = null, catalog = "gamegrab-sources/catalog")
        val beforeSha = UserOriginKeys.keySha256(before)!!

        val moved = UserOriginKeys.supersede(file, "gamegrab", after)
        assertNotNull(moved)
        val stored = UserOriginKeys.load(file)["gamegrab"]!!
        assertEquals(UserOriginKeys.keySha256(after), UserOriginKeys.keySha256(stored.keyBase64))
        assertEquals(listOf(beforeSha), stored.previous)
        assertEquals("gamegrab-sources/catalog", stored.catalog)
        // What the old key approved stays valid; an update signed by the new key is the same publisher.
        assertTrue(UserOriginKeys.predecessorInForce(stored, beforeSha))
        assertTrue(UserOriginKeys.succeeds(file, "gamegrab", beforeSha, UserOriginKeys.keySha256(after)!!))
        assertFalse(UserOriginKeys.predecessorInForce(stored, "c".repeat(64)))
        assertNull(UserOriginKeys.supersede(file, "nobody", after))
    }
}
