package dev.droidtop.library.storage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSharesTest {
    @Test
    fun addressesThatWouldLeakASignInAreRefused() {
        assertNull(StorageShares.addressProblem("https://cloud.example.com/remote.php/dav/files/me/", false))
        assertNull(StorageShares.addressProblem("  https://nas.local:5006/  ", false))
        assertNotNull(StorageShares.addressProblem("http://nas.local/dav", false))
        assertNull(StorageShares.addressProblem("http://nas.local/dav", true))
        assertNotNull(StorageShares.addressProblem("https://me:secret@cloud.example.com/dav", false))
        assertNotNull(StorageShares.addressProblem("https://cloud.example.com/dav?token=x", false))
        assertNotNull(StorageShares.addressProblem("https://cloud.example.com/dav#x", false))
        assertNotNull(StorageShares.addressProblem("ftp://cloud.example.com/", false))
        assertNotNull(StorageShares.addressProblem("cloud.example.com", false))
        assertNotNull(StorageShares.addressProblem("https:///dav", false))
    }

    @Test
    fun repliesBecomeStates() {
        assertEquals(StorageShares.CONNECTED to null, StorageShares.outcome(JSONObject().put("entries", 3)))
        assertEquals(StorageShares.UNREACHABLE, StorageShares.outcome(JSONObject().put("unreachable", "timed out")).first)
        val refused = StorageShares.outcome(JSONObject().put("error", "the share refused the sign-in").put("code", "sign_in_refused"))
        assertEquals(StorageShares.SIGN_IN_REFUSED to "the share refused the sign-in", refused)
        assertEquals(StorageShares.NOT_ENCRYPTED, StorageShares.outcome(JSONObject().put("error", "x").put("code", "not_encrypted")).first)
        assertEquals(StorageShares.REFUSED, StorageShares.outcome(JSONObject().put("error", "x").put("code", "not_found")).first)
        assertEquals(StorageShares.REFUSED, StorageShares.outcome(JSONObject().put("error", "x")).first)
    }

    @Test
    fun theStoredListHoldsNoPasswordAndReadsBack() {
        val share = StorageShare(
            id = "abc123", name = "Nextcloud", kind = StorageShares.WEBDAV, url = "https://cloud.example.com/dav/",
            user = "me", allowPlainHttp = false, trusted = true, state = StorageShares.CONNECTED, testedAtMs = 42,
        )
        val text = JSONArray().put(share.toJson()).toString()
        assertFalse(text.contains("password"))
        assertEquals(listOf(share), StorageShares.parse(text))
        assertEquals("droidtop_share_abc123_password", share.passwordKey)
    }

    @Test
    fun aShareWithNoTrustAnswerIsNotTrusted() {
        val parsed = StorageShares.parse("""[{"id":"x","url":"https://a.example/"},{"name":"no id"}]""")
        assertEquals(1, parsed.size)
        assertFalse(parsed[0].trusted)
        assertEquals(StorageShares.NOT_TESTED, parsed[0].state)
        assertEquals(StorageShares.WEBDAV, parsed[0].kind)
        assertTrue(parsed[0].name.isNotBlank())
    }
}
