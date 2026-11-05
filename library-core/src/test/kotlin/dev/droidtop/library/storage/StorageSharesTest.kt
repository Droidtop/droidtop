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
    fun anS3ShareTellsTheNativeLibraryItsBucketAndKey() {
        val share = StorageShare(
            id = "s3id", name = "Backups", kind = StorageShares.S3, url = "https://s3.eu-central-003.backblazeb2.com",
            user = "AKIAEXAMPLE", allowPlainHttp = false, trusted = false,
            bucket = "my-droidtop", region = "eu-central-003", folder = "handheld", bucketInHost = false,
        )
        val args = StorageShares.nativeArgsFor(share, "the secret")
        assertEquals("s3", args.getString("kind"))
        assertEquals("https://s3.eu-central-003.backblazeb2.com", args.getString("endpoint"))
        assertEquals("my-droidtop", args.getString("bucket"))
        assertEquals("eu-central-003", args.getString("region"))
        assertEquals("AKIAEXAMPLE", args.getString("access_key"))
        assertEquals("the secret", args.getString("secret_key"))
        assertEquals("handheld", args.getString("folder"))
        assertTrue(args.getBoolean("path_style"))
        // The stored list keeps the bucket and folder, never the secret.
        val text = JSONArray().put(share.toJson()).toString()
        assertFalse(text.contains("the secret"))
        assertEquals(listOf(share), StorageShares.parse(text))
        // A region left blank is S3's default.
        assertEquals("us-east-1", StorageShares.nativeArgsFor(share.copy(region = ""), "x").getString("region"))
        assertEquals("webdav", StorageShares.nativeArgsFor(share.copy(kind = StorageShares.WEBDAV), "x").getString("kind"))
    }

    @Test
    fun bucketNamesFollowS3sRules() {
        assertNull(StorageShares.bucketProblem("my-droidtop.saves"))
        assertNotNull(StorageShares.bucketProblem(""))
        assertNotNull(StorageShares.bucketProblem("ab"))
        assertNotNull(StorageShares.bucketProblem("My_Bucket"))
        assertNotNull(StorageShares.bucketProblem("a".repeat(64)))
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
