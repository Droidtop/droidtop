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
        assertEquals(StorageShares.NOT_FOUND, StorageShares.outcome(JSONObject().put("error", "x").put("code", "not_found")).first)
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
    fun anSftpShareCarriesItsPinnedKeyAndAnSmbShareItsShareName() {
        val sftp = StorageShare(
            id = "f1", name = "Deck", kind = StorageShares.SFTP, url = "steamdeck.local", user = "deck",
            allowPlainHttp = false, trusted = false, folder = "droidtop", port = 0,
            hostKey = "SHA256:abc",
        )
        val a = StorageShares.nativeArgsFor(sftp, "pw")
        assertEquals("sftp", a.getString("kind"))
        assertEquals("steamdeck.local", a.getString("host"))
        assertEquals(22, a.getInt("port"))
        assertEquals("deck", a.getString("user"))
        assertEquals("pw", a.getString("password"))
        assertEquals("SHA256:abc", a.getString("host_key"))
        assertEquals(2222, StorageShares.nativeArgsFor(sftp.copy(port = 2222), "pw").getInt("port"))

        val smb = StorageShare(
            id = "m1", name = "NAS", kind = StorageShares.SMB, url = "nas.local", user = "me", allowPlainHttp = false,
            trusted = true, folder = "games/saves", shareName = "home", domain = "WORKGROUP", requireEncryption = true,
        )
        val b = StorageShares.nativeArgsFor(smb, "pw")
        assertEquals("smb", b.getString("kind"))
        assertEquals("nas.local", b.getString("server"))
        assertEquals("home", b.getString("share"))
        assertEquals("WORKGROUP", b.getString("domain"))
        assertEquals("games/saves", b.getString("folder"))
        assertTrue(b.getBoolean("require_encryption"))
        assertEquals("nas.local:4450", StorageShares.nativeArgsFor(smb.copy(port = 4450), "pw").getString("server"))
        assertEquals("nas.local", StorageShares.nativeArgsFor(smb.copy(port = 445), "pw").getString("server"))

        // Both read back from the stored list, without the password.
        val text = JSONArray().put(sftp.toJson()).put(smb.toJson()).toString()
        assertFalse(text.contains("\"pw\""))
        assertEquals(listOf(sftp, smb), StorageShares.parse(text))
    }

    @Test
    fun serverNamesAreNamesOnly() {
        assertNull(StorageShares.hostProblem("nas.local"))
        assertNull(StorageShares.hostProblem("192.168.1.20"))
        assertNotNull(StorageShares.hostProblem(""))
        assertNotNull(StorageShares.hostProblem("sftp://nas.local"))
        assertNotNull(StorageShares.hostProblem("nas.local/share"))
        assertNotNull(StorageShares.hostProblem("me@nas.local"))
    }

    @Test
    fun aServerKeyNotYetAcceptedIsOfferedForThePersonToCheck() {
        val unknown = JSONObject().put("error", "droidtop doesn't know this server's key yet").put("code", "host_key_unknown")
            .put("fingerprint", "SHA256:UN92sNP/UxDHd7Vh05wpQGj7SjlCmZequqTapOwsr8A")
        assertEquals(StorageShares.HOST_KEY_UNKNOWN, StorageShares.outcome(unknown).first)
        assertEquals("SHA256:UN92sNP/UxDHd7Vh05wpQGj7SjlCmZequqTapOwsr8A", StorageShares.offeredKey(unknown))
        val changed = JSONObject().put("error", "changed").put("code", "host_key_changed").put("fingerprint", "SHA256:new")
        assertEquals(StorageShares.HOST_KEY_CHANGED, StorageShares.outcome(changed).first)
        assertEquals("SHA256:new", StorageShares.offeredKey(changed))
        // Nothing is offered for any other failure, or a fingerprint in the wrong form.
        assertNull(StorageShares.offeredKey(JSONObject().put("error", "x").put("code", "refused").put("fingerprint", "SHA256:x")))
        assertNull(StorageShares.offeredKey(JSONObject().put("error", "x").put("code", "host_key_unknown").put("fingerprint", "md5:x")))
    }

    @Test
    fun aMissingFolderAndNoNetworkAreTheirOwnStates() {
        assertEquals(StorageShares.NOT_FOUND, StorageShares.outcome(JSONObject().put("error", "/x is not on the share").put("code", "not_found")).first)
        assertEquals("Folder not there", StorageShares.stateLabel(StorageShares.NOT_FOUND))
        assertEquals("No network", StorageShares.stateLabel(StorageShares.NO_NETWORK))
        assertTrue(StorageShares.unreachable(StorageShares.UNREACHABLE))
        assertTrue(StorageShares.unreachable(StorageShares.NO_NETWORK))
        assertFalse(StorageShares.unreachable(StorageShares.SIGN_IN_REFUSED))
        assertFalse(StorageShares.unreachable(StorageShares.CONNECTED))
    }

    @Test
    fun whenAShareLastConnectedIsKept() {
        val share = StorageShare(
            id = "w1", name = "Cloud", kind = StorageShares.WEBDAV, url = "https://cloud.example.com/dav/", user = "me",
            allowPlainHttp = false, trusted = false, state = StorageShares.UNREACHABLE, lastConnectedMs = 1_793_860_215_000,
        )
        val back = StorageShares.parse(JSONArray().put(share.toJson()).toString()).single()
        assertEquals(1_793_860_215_000, back.lastConnectedMs)
        assertEquals(0L, StorageShares.parse("""[{"id":"x","url":"https://a.example/"}]""").single().lastConnectedMs)
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
