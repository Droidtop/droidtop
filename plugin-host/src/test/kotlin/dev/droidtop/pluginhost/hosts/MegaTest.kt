package dev.droidtop.pluginhost.hosts

import dev.droidtop.pluginhost.WebSessionDownload
import dev.droidtop.pluginhost.debrid.DebridTransport
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Mega through its own API (Droidtop/tracker#638 part 5): the four link forms, the key's parts, the attributes, the CTR
 * decryption and the meta-MAC against vectors made with OpenSSL (python cryptography) from the SDK's published scheme,
 * and a file and a folder resolved from recorded replies through the replaceable transport.
 */
class MegaTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun forgetAccounts() { HostLinks.accounts = { null } }

    // The AES key of every vector below is the one in FIPS-197's example; the nonce is a0..a7; the file is the bytes (i * 31 + 7) mod 256.
    private val aesHex = "2b7e151628aed2a6abf7158809cf4f3c"
    private val nonceHex = "a0a1a2a3a4a5a6a7"
    private fun plain(size: Int) = ByteArray(size) { ((it * 31 + 7) and 0xff).toByte() }

    /** The key a file of this size has in a link: k0..k5 hide the AES key, k6 k7 are its meta-MAC. */
    private val keys = mapOf(
        0 to "i9-3tYwLdAGr9xWICc9PPKChoqOkpaanAAAAAAAAAAA",
        16 to "i9-3tYwLdAEmlAT-oVs236ChoqOkpaanjWMRdqiUeeM",
        40 to "i9-3tYwLdAHzQDiMl6Vwq6ChoqOkpaanWLctBJ5qP5c",
        131072 to "i9-3tYwLdAFxhPhRBXqhU6ChoqOkpaan2nPt2Qy17m8",
        131073 to "i9-3tYwLdAERWn-TlYeM76ChoqOkpaanuq1qG5xIw9M",
        1000003 to "i9-3tYwLdAGuHyUB8ECL6qChoqOkpaanBegwifmPxNY",
        5767169 to "i9-3tYwLdAFTy6tjadcXeKChoqOkpaan-Dy-62AYWEQ",
    )
    private val fileKey = keys.getValue(1000003)
    private val folderKey = "8PHy8_T19vf4-fr7_P3-_w"
    private val attributes = "kZGV6VJdp23C2hM_7NuQYo22D7e1YMHFlKP_mGY9l3o"

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun key(text: String) = MegaCrypto.fileKey(MegaCrypto.decode(text)!!)!!

    /** What Mega stores of [plain]: AES-CTR from the nonce with a counter of 0. */
    private fun encrypted(plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(aesHex), "AES"), IvParameterSpec(hex(nonceHex) + ByteArray(8)))
            doFinal(plain)
        }

    private fun fileOf(bytes: ByteArray) = tmp.newFile().also { it.writeBytes(bytes) }

    // The links.

    private val handle = "AbCd1234"

    @Test
    fun allFourLinkFormsAreMegaLinksAndNothingElseIs() {
        val mega = Mega(Recorded { _, _ -> 200 to "[]" })
        assertTrue(mega.matches("https://mega.nz/file/$handle#$fileKey"))
        assertTrue(mega.matches("https://mega.nz/folder/ROOTNODE#$folderKey"))
        assertTrue(mega.matches("https://mega.nz/#!$handle!$fileKey"))
        assertTrue(mega.matches("https://mega.nz/#F!ROOTNODE!$folderKey"))
        assertTrue(mega.matches("https://www.mega.co.nz/file/$handle#$fileKey"))
        assertTrue(mega.matches("https://mega.io/file/$handle#$fileKey"))
        assertFalse("http is not taken", mega.matches("http://mega.nz/file/$handle#$fileKey"))
        assertFalse("no key", mega.matches("https://mega.nz/file/$handle"))
        assertFalse("a folder's key is not a file's", mega.matches("https://mega.nz/file/$handle#$folderKey"))
        assertFalse("a file's key is not a folder's", mega.matches("https://mega.nz/folder/ROOTNODE#$fileKey"))
        assertFalse("a file inside a folder is not the folder", mega.matches("https://mega.nz/folder/ROOTNODE#$folderKey/file/FILENOD1"))
        assertFalse("a short handle", mega.matches("https://mega.nz/file/abc#$fileKey"))
        assertFalse("another path", mega.matches("https://mega.nz/help/$handle#$fileKey"))
        assertFalse("another site", mega.matches("https://notmega.nz/file/$handle#$fileKey"))
        assertFalse("another site", mega.matches("https://mega.nz.evil.example/file/$handle#$fileKey"))
    }

    // The key, the attributes and the content.

    @Test
    fun theFileKeyIsTheAesKeyTheNonceAndTheMetaMacInItsWords() {
        val key = key(fileKey)
        assertEquals(aesHex, key.aes.hex())
        assertEquals(nonceHex, key.nonce.hex())
        assertEquals("05e83089f98fc4d6", key.metaMac.hex())
        assertNull("16 bytes is a folder's", MegaCrypto.fileKey(ByteArray(16)))
        assertNull(MegaCrypto.decode("not base64!"))
    }

    @Test
    fun theAttributesOpenWithTheFilesAesKeyAndGiveItsName() {
        assertEquals("Game v1.0.zip", MegaCrypto.fileName(attributes, key(fileKey).aes))
        assertNull("another key opens nothing", MegaCrypto.fileName(attributes, ByteArray(16)))
        assertNull(MegaCrypto.fileName("AAAA", key(fileKey).aes))
    }

    @Test
    fun aNodesKeyOpensWithTheFolderKeyInEcbBlocks() {
        // FILENOD1's `k` in the folder listing, less the owner's handle.
        val sealed = MegaCrypto.decode("uCxOhFx7o9Sv0wt8Lc6SmPsIyP0PY-C4POtG6tqr59M")!!
        assertEquals("AQYLEBUaHyQpLjM4PUJHTFFWW2Blam90eX6DiI2Sl5w", MegaCrypto.encode(MegaCrypto.nodeKey(sealed, MegaCrypto.decode(folderKey)!!)!!))
        assertNull(MegaCrypto.nodeKey(ByteArray(20), MegaCrypto.decode(folderKey)!!))
    }

    @Test
    fun aKnownCtrVectorDecryptsAndItsMacIsTheKeys() {
        // 40 bytes of the pattern, encrypted by OpenSSL: AES-128-CTR, IV a0..a7 then a counter of 0.
        val ciphertext = hex("75d55cdd4f21a102b33892033c38a09c2619c14c6f0aef3a06b867b0f5475903c7607bbffd386c9b")
        val decryptor = MegaCrypto.Decryptor(key(keys.getValue(40)))
        decryptor.update(ciphertext, 0, ciphertext.size)
        assertEquals("0726456483a2c1e0ff1e3d5c7b9ab9d8f71635547392b1d0ef0e2d4c6b8aa9c8e70625446382a1c0", ciphertext.hex())
        assertTrue(decryptor.verify())
    }

    @Test
    fun theMetaMacHoldsAcrossTheChunkBoundariesOfEveryFileSize() {
        // 0 and 16 bytes, a partial block, exactly one 128 KB chunk and one byte more, four chunks, and a file past the
        // eighth chunk (4608 KB) and one byte into the first 1 MB chunk after it (5632 KB).
        for ((size, text) in keys) {
            val file = fileOf(encrypted(plain(size)))
            assertTrue("$size bytes", MegaCrypto.decryptFile(file, key(text)))
            assertArrayEquals("$size bytes", plain(size), file.readBytes())
        }
    }

    @Test
    fun theChunkEndsAre128Then256KbAndSoOnToAMegabyteThenAMegabyteEach() {
        val ends = generateSequence(MegaCrypto.chunkCeil(0)) { MegaCrypto.chunkCeil(it) }.take(11).toList()
        assertEquals(listOf(131072L, 393216L, 786432L, 1310720L, 1966080L, 2752512L, 3670016L, 4718592L, 5767168L, 6815744L, 7864320L), ends)
    }

    @Test
    fun bytesComingInAnyPiecesDecryptTheSame() {
        val data = encrypted(plain(1000003))
        val decryptor = MegaCrypto.Decryptor(key(fileKey))
        var at = 0
        var piece = 1
        while (at < data.size) {
            val take = minOf(piece, data.size - at)
            decryptor.update(data, at, take)
            at += take
            piece = piece * 3 % 40009 + 1
        }
        assertArrayEquals(plain(1000003), data)
        assertTrue(decryptor.verify())
    }

    @Test
    fun aDamagedFileOrAWrongKeyFailsTheMacAndNeverPasses() {
        val data = encrypted(plain(1000003))
        data[500000] = (data[500000].toInt() xor 1).toByte()
        assertFalse("one flipped bit", MegaCrypto.decryptFile(fileOf(data), key(fileKey)))
        assertFalse("a file cut short", MegaCrypto.decryptFile(fileOf(encrypted(plain(1000003)).copyOf(999000)), key(fileKey)))
        assertFalse("a key with another MAC", MegaCrypto.decryptFile(fileOf(encrypted(plain(1000003))), key(keys.getValue(131073))))
        try {
            HostDecryption.decrypt(HostDecryption.megaSpec(MegaCrypto.decode(fileKey)!!), fileOf(data)) {}
            fail("a file that fails its MAC is a failed job")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("checksum"))
        }
    }

    @Test
    fun aCancelledDecryptionStopsBetweenBlocksAndTheCancelIsNotSwallowed() {
        val file = fileOf(encrypted(plain(300000)))
        var blocks = 0
        try {
            MegaCrypto.decryptFile(file, key(keys.getValue(40))) { if (++blocks == 2) throw IllegalStateException("cancelled") }
            fail("the cancel is not swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("cancelled", e.message)
        }
        assertEquals(2, blocks)
    }

    @Test
    fun theKeyTravelsInAHeaderOfDroidtopsOwnAndNeverToAServer() {
        val spec = HostDecryption.megaSpec(MegaCrypto.decode(fileKey)!!)
        assertEquals("mega:$fileKey", spec)
        val sent = mapOf("Cookie" to "a=b", HostDecryption.SECRET to spec, HostDecryption.PENDING to "1")
        assertEquals(mapOf("Cookie" to "a=b") to spec, HostDecryption.split(sent))
        assertEquals(mapOf("Cookie" to "a=b") to null, HostDecryption.split(mapOf("Cookie" to "a=b")))
        // After a restart the job has the marker and not the key: it fails, rather than place the encrypted file.
        try {
            HostDecryption.split(mapOf(HostDecryption.PENDING to "1"))
            fail("the key is gone")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("start the download again"))
        }
    }

    // Mega's API through recorded replies.

    /** Every POST kept; [answer] is given the address and the JSON body and says what Mega replies. */
    private class Recorded(val answer: (url: String, body: String) -> Pair<Int, String>) : DebridTransport {
        val posts = mutableListOf<Pair<String, String>>()

        override fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?): Pair<Int, String> =
            throw AssertionError("Mega's API takes JSON")

        override fun postJson(url: String, json: String): Pair<Int, String> {
            posts += url to json
            return answer(url, json)
        }
    }

    private fun mega(http: DebridTransport) = Mega(http, firstId = 100, retryDelayMs = 0)

    private val cdn = "https://gfs270n123.userstorage.mega.co.nz/dl/AbCdEf"
    private val fileReply = """[{"g":"$cdn","s":1000003,"at":"$attributes"}]"""

    @Test
    fun aFileLinkIsOneDownloadWithItsNameSizeAndKeyAndMegaNeverSeesTheKey() {
        val http = Recorded { _, _ -> 200 to fileReply }
        val files = (mega(http).resolve("https://mega.nz/file/$handle#$fileKey", null) as HostResolution.Files).files
        assertEquals(listOf(HostFile("Game v1.0.zip", 1000003L, cdn, emptyMap(), null, "mega:$fileKey")), files)
        val (url, body) = http.posts.single()
        assertEquals("https://g.api.mega.co.nz/cs?id=101", url)
        val command = JSONArray(body).getJSONObject(0)
        assertEquals("g", command.getString("a"))
        assertEquals(1, command.getInt("g"))
        assertEquals("https only", 2, command.getInt("ssl"))
        assertEquals(handle, command.getString("p"))
        assertTrue("the key stays here", http.posts.none { (u, b) -> fileKey in u || fileKey in b })
    }

    @Test
    fun theLegacyLinkFormsAreResolvedTheSame() {
        val http = Recorded { _, _ -> 200 to fileReply }
        val legacy = (mega(http).resolve("https://mega.nz/#!$handle!$fileKey", null) as HostResolution.Files).files
        assertEquals("Game v1.0.zip", legacy.single().name)
        assertEquals(handle, JSONArray(http.posts.single().second).getJSONObject(0).getString("p"))
    }

    private val flatFolder = """{"f":[{"h":"ROOTNODE","p":"","u":"OWNERHANDLE","t":1,"a":"nhJzRTZpOqRsrS3FVPYs2A","k":"OWNERHANDLE:ShOB0944Y6kOz8WFbiy1tQ","ts":1700000000},{"h":"FILENOD1","p":"ROOTNODE","u":"OWNERHANDLE","t":0,"a":"iy067BT0r2al3oeeOfsM4B0AkuM410nEWUv516hPeNc","k":"OWNERHANDLE:uCxOhFx7o9Sv0wt8Lc6SmPsIyP0PY-C4POtG6tqr59M","ts":1700000000,"s":100},{"h":"FILENOD2","p":"ROOTNODE","u":"OWNERHANDLE","t":0,"a":"GfXHbIy88G9xqqxBvyAdlbfhgjj-0QQdoWZpRZiG0rM","k":"OWNERHANDLE:g1d8hZm3CP_m-G2Cu1LWUX4_JFuWQyg9MvA2L9UPt48","ts":1700000000,"s":50}],"sn":"abcdef"}"""
    private val nestedFolder = """{"f":[{"h":"ROOTNODE","p":"","u":"OWNERHANDLE","t":1,"a":"nhJzRTZpOqRsrS3FVPYs2A","k":"OWNERHANDLE:ShOB0944Y6kOz8WFbiy1tQ","ts":1700000000},{"h":"FILENOD1","p":"ROOTNODE","u":"OWNERHANDLE","t":0,"a":"iy067BT0r2al3oeeOfsM4B0AkuM410nEWUv516hPeNc","k":"OWNERHANDLE:uCxOhFx7o9Sv0wt8Lc6SmPsIyP0PY-C4POtG6tqr59M","ts":1700000000,"s":100},{"h":"SUBNODE1","p":"ROOTNODE","u":"OWNERHANDLE","t":1,"a":"XdKngcL72J4tB0N19JX5mdaeDNvTZ-ft40ABT5E_IkQ","k":"OWNERHANDLE:NwC0mLGp6dZPmLw7vFj-Mw","ts":1700000000},{"h":"FILENOD3","p":"SUBNODE1","u":"OWNERHANDLE","t":0,"a":"tEKDE0ot8kJYv5DoJuKuwyfHZKfd7I73m6PhQiAkbVk","k":"OWNERHANDLE:g1d8hZm3CP_m-G2Cu1LWUX4_JFuWQyg9MvA2L9UPt48","ts":1700000000,"s":5}],"sn":"abcdef"}"""

    /** Mega's folder: the listing for an `f` command, and for the `g` commands one reply each by node. */
    private fun folderTransport(listing: String) = Recorded { url, body ->
        val commands = JSONArray(body)
        when (commands.getJSONObject(0).getString("a")) {
            "f" -> 200 to "[$listing]"
            else -> 200 to (0 until commands.length()).joinToString(",", "[", "]") { i ->
                when (commands.getJSONObject(i).getString("n")) {
                    "FILENOD1" -> """{"g":"https://gfs1.userstorage.mega.co.nz/dl/one","s":100,"at":"iy067BT0r2al3oeeOfsM4B0AkuM410nEWUv516hPeNc"}"""
                    else -> """{"g":"https://gfs1.userstorage.mega.co.nz/dl/two","s":50,"at":"GfXHbIy88G9xqqxBvyAdlbfhgjj-0QQdoWZpRZiG0rM"}"""
                }
            }
        }
    }

    @Test
    fun aFolderIsListedWithTheFolderKeyAndEachFileAskedForByItsNode() {
        val http = folderTransport(flatFolder)
        val files = (mega(http).resolve("https://mega.nz/folder/ROOTNODE#$folderKey", null) as HostResolution.Files).files
        assertEquals(
            listOf(
                HostFile("Game.part1.rar", 100L, "https://gfs1.userstorage.mega.co.nz/dl/one", emptyMap(), null, "mega:AQYLEBUaHyQpLjM4PUJHTFFWW2Blam90eX6DiI2Sl5w"),
                HostFile("Game.part2.rar", 50L, "https://gfs1.userstorage.mega.co.nz/dl/two", emptyMap(), null, "mega:BA0WHygxOkNMVV5ncHmCi5Sdpq-4wcrT3OXu9wAJEhs"),
            ),
            files,
        )
        assertEquals(2, http.posts.size)
        val (listUrl, listBody) = http.posts[0]
        assertEquals("https://g.api.mega.co.nz/cs?id=101&n=ROOTNODE", listUrl)
        val list = JSONArray(listBody).getJSONObject(0)
        assertEquals("f", list.getString("a"))
        assertEquals(1, list.getInt("c"))
        assertEquals(1, list.getInt("r"))
        val (getUrl, getBody) = http.posts[1]
        assertEquals("https://g.api.mega.co.nz/cs?id=102&n=ROOTNODE", getUrl)
        val gets = JSONArray(getBody)
        assertEquals(listOf("FILENOD1", "FILENOD2"), (0 until gets.length()).map { gets.getJSONObject(it).getString("n") })
        assertTrue((0 until gets.length()).all { gets.getJSONObject(it).getString("a") == "g" && !gets.getJSONObject(it).has("p") })
        assertTrue("the key stays here", http.posts.none { (u, b) -> folderKey in u || folderKey in b })
    }

    @Test
    fun aFolderWithFoldersInItOrTooManyFilesOrNoFilesIsRefusedNotFlattened() {
        fun refusal(listing: String, link: String = "https://mega.nz/folder/ROOTNODE#$folderKey") =
            (mega(folderTransport(listing)).resolve(link, null) as HostResolution.Refused).reason
        assertTrue(refusal(nestedFolder).contains("folders inside it"))
        assertEquals("The Mega folder has no files", refusal("""{"f":[{"h":"ROOTNODE","p":"","t":1,"k":"x:y"}]}"""))
        val many = (1..17).joinToString(",") { """{"h":"N000000$it","p":"ROOTNODE","t":0,"k":"x:y"}""" }
        assertTrue(refusal("""{"f":[$many]}""").contains("more files than one download takes"))
        // A node key that is not whole blocks is no key this folder's key can open.
        assertEquals("The key in that Mega link does not open the folder", refusal("""{"f":[{"h":"FILENOD1","p":"ROOTNODE","t":0,"k":"x:AAAA"}]}"""))
    }

    @Test
    fun aKeyThatDoesNotOpenTheAttributesIsARefusal() {
        val http = Recorded { _, _ -> 200 to fileReply }
        val wrong = "j" + fileKey.drop(1)
        assertEquals(
            "The key in that Mega link does not open the file",
            (mega(http).resolve("https://mega.nz/file/$handle#$wrong", null) as HostResolution.Refused).reason,
        )
    }

    private fun refusalFor(status: Int, body: String) =
        (mega(Recorded { _, _ -> status to body }).resolve("https://mega.nz/file/$handle#$fileKey", null) as HostResolution.Refused).reason

    @Test
    fun megasErrorNumbersAreSentencesNotFiles() {
        assertEquals("Mega does not have that file", refusalFor(200, "[-9]"))
        assertEquals("Mega will not give that link without an account", refusalFor(200, "[-11]"))
        assertEquals("Mega's limit on anonymous downloads is reached", refusalFor(200, "[-17]"))
        assertEquals("Mega's limit on anonymous downloads is reached", refusalFor(200, """[{"e":-17,"tl":3600}]"""))
        assertEquals("a bare number is the whole request failing", "Mega does not have that file", refusalFor(200, "-9"))
        assertEquals("Mega answered error -2", refusalFor(200, "[-2]"))
        assertEquals("Mega answered 500", refusalFor(500, "<html>"))
        assertEquals("Mega's reply could not be read", refusalFor(200, "not json"))
        assertEquals("Mega's reply could not be read", refusalFor(200, """[{"g":"http://insecure.example/x","s":1,"at":"$attributes"}]"""))
    }

    @Test
    fun aTryAgainIsAskedAgainAFewTimesThenItIsABusySentence() {
        var asked = 0
        val flaky = Recorded { _, _ -> 200 to if (++asked < 3) "[-3]" else fileReply }
        assertTrue(mega(flaky).resolve("https://mega.nz/file/$handle#$fileKey", null) is HostResolution.Files)
        assertEquals(3, flaky.posts.size)
        assertEquals("the same request again", 1, flaky.posts.distinct().size)
        assertEquals("Mega is busy and asked to try again later", refusalFor(200, "-3"))
        val always = Recorded { _, _ -> 200 to "[-3]" }
        assertTrue(mega(always).resolve("https://mega.nz/file/$handle#$fileKey", null) is HostResolution.Refused)
        assertEquals(3, always.posts.size)
    }

    @Test
    fun anUnreachableMegaIsAnIoFailureAndHasNoAccountToTest() {
        val offline = Recorded { _, _ -> throw IOException("The service could not be reached") }
        try {
            mega(offline).resolve("https://mega.nz/file/$handle#$fileKey", null)
            fail("a lost connection is not a refusal")
        } catch (e: IOException) {
            assertNotNull(e.message)
        }
        assertTrue(mega(offline).check("anything")!!.contains("does not sign in to Mega yet"))
    }

    // Where it sits in the order.

    private val link = "https://mega.nz/file/$handle#$fileKey"

    @Test
    fun anAnonymousMegaDownloadCarriesItsKeyForTheJobAndSaysWhereItCameFrom() {
        val downloads = HostLinks.resolve(link, debrid = { null }, resolvers = listOf(mega(Recorded { _, _ -> 200 to fileReply })))!!
        val download = downloads.single()
        assertEquals("direct from Mega", download.via)
        assertEquals(cdn, download.url)
        assertEquals(mapOf(HostDecryption.SECRET to "mega:$fileKey"), download.headers)
        assertEquals("Game v1.0.zip", dev.droidtop.pluginhost.WebSessions.fileNameFor(download.url, download.contentDisposition))
        assertEquals(1000003L, download.size)
    }

    @Test
    fun overTheAnonymousLimitADebridAccountServesIt() {
        // The order puts a debrid account before the anonymous API, so Mega is not even asked.
        val debrid = listOf(WebSessionDownload.direct("https://cdn.debrid.example/g", "Game.zip", 9))
        val asked = Recorded { _, _ -> 200 to "[-17]" }
        assertEquals(debrid, HostLinks.resolve(link, debrid = { debrid }, resolvers = listOf(mega(asked))))
        assertTrue(asked.posts.isEmpty())
    }

    @Test
    fun overTheLimitWithNoDebridTheWebViewOpensAndTheCallerIsToldWhy() {
        val told = mutableListOf<String>()
        val limited = mega(Recorded { _, _ -> 200 to "[-17]" })
        assertNull(HostLinks.resolve(link, debrid = { null }, resolvers = listOf(limited), refused = { told += it }))
        assertEquals(listOf("Mega's limit on anonymous downloads is reached"), told)
    }

    @Test
    fun aFolderOfMoreFilesThanOneJobTakesIsLeftToTheWebView() {
        val many = (1..17).joinToString(",") { """{"h":"N000000$it","p":"ROOTNODE","t":0,"k":"x:y"}""" }
        val big = mega(folderTransport("""{"f":[$many]}"""))
        assertNull(HostLinks.resolve("https://mega.nz/folder/ROOTNODE#$folderKey", debrid = { null }, resolvers = listOf(big)))
    }
}
