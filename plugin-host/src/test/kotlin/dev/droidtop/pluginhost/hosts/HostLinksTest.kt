package dev.droidtop.pluginhost.hosts

import dev.droidtop.pluginhost.WebSessionDownload
import dev.droidtop.pluginhost.debrid.DebridTransport
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File hosts through their own API (Droidtop/tracker#638 part 5): PixelDrain's links, the person's key, and the order. */
class HostLinksTest {
    private val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** Recorded replies by "METHOD url", every call kept with its headers. */
    private class Canned(vararg answers: Pair<String, Pair<Int, String>>) : DebridTransport {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        private val table = answers.toMap()
        override fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?): Pair<Int, String> {
            calls += "$method $url" to headers
            return table["$method $url"] ?: (404 to """{"success":false,"value":"not_found"}""")
        }
    }

    private val info = """{"success":true,"id":"abc123","name":"Game v1.0.zip","size":5694837,"mime_type":"application/zip","hash_sha256":"$sha","availability":"","availability_message":"","can_download":true}"""
    private val limited = """{"success":false,"value":"file_rate_limited_captcha_required","message":"We have detected the use of hotlinking for this file."}"""
    private val list = """{"success":true,"id":"L8bhwx","title":"Game","file_count":2,"files":[
        {"detail_href":"/file/f1/info","success":true,"id":"f1","name":"Game.part1.rar","size":100,"mime_type":"application/x-rar"},
        {"detail_href":"/file/f2/info","success":true,"id":"f2","name":"Game.part2.rar","size":50,"mime_type":"application/x-rar"}]}"""
    private val basic = "Basic " + java.util.Base64.getEncoder().encodeToString(":secret-key".toByteArray())

    @After fun forgetAccounts() { HostLinks.accounts = { null } }

    @Test
    fun pixelDrainTakesItsFileAndListLinksOnBothDomainsAndNoOthers() {
        val pd = PixelDrain(Canned())
        assertTrue(pd.matches("https://pixeldrain.com/u/abc123"))
        assertTrue(pd.matches("https://www.pixeldrain.dev/l/L8bhwx"))
        assertFalse(pd.matches("http://pixeldrain.com/u/abc123"))
        assertFalse(pd.matches("https://pixeldrain.com/api/file/abc123"))
        assertFalse(pd.matches("https://pixeldrain.com/u/"))
        assertFalse(pd.matches("https://notpixeldrain.com/u/abc123"))
        assertFalse(pd.matches("https://pixeldrain.com.evil.example/u/abc123"))
    }

    @Test
    fun aFileLinkIsOneDirectDownloadWithItsNameSizeAndDigestAndNoAccount() {
        val http = Canned("GET https://pixeldrain.com/api/file/abc123/info" to (200 to info))
        val files = (PixelDrain(http).resolve("https://pixeldrain.com/u/abc123", null) as HostResolution.Files).files
        assertEquals(
            listOf(HostFile("Game v1.0.zip", 5694837L, "https://pixeldrain.com/api/file/abc123?download", emptyMap(), sha)),
            files,
        )
        assertTrue("without a key nothing is sent", http.calls.single().second.isEmpty())
    }

    @Test
    fun aListLinkIsOneDownloadPerFileInTheListsOrder() {
        val http = Canned("GET https://pixeldrain.dev/api/list/L8bhwx" to (200 to list))
        val files = (PixelDrain(http).resolve("https://pixeldrain.dev/l/L8bhwx", null) as HostResolution.Files).files
        assertEquals(listOf("Game.part1.rar", "Game.part2.rar"), files.map { it.name })
        assertEquals(listOf(100L, 50L), files.map { it.size })
        assertEquals(
            listOf("https://pixeldrain.dev/api/file/f1?download", "https://pixeldrain.dev/api/file/f2?download"),
            files.map { it.url },
        )
    }

    @Test
    fun theKeyIsBasicAuthWithAnEmptyUserNameOnTheInfoCallAndOnEveryFile() {
        val http = Canned("GET https://pixeldrain.com/api/list/L8bhwx" to (200 to list))
        val files = (PixelDrain(http).resolve("https://pixeldrain.com/l/L8bhwx", " secret-key ") as HostResolution.Files).files
        assertEquals(mapOf("Authorization" to basic), http.calls.single().second)
        assertTrue(files.all { it.headers == mapOf("Authorization" to basic) })
        assertEquals("an empty user name, then the key", "Basic OnNlY3JldC1rZXk=", basic)
    }

    @Test
    fun aRateLimitACaptchaAMissingFileAndARefusedKeyAreRefusalsNotFiles() {
        fun refusal(status: Int, body: String, key: String? = null) =
            (PixelDrain(Canned("GET https://pixeldrain.com/api/file/abc123/info" to (status to body))).resolve("https://pixeldrain.com/u/abc123", key) as HostResolution.Refused).reason
        assertTrue(refusal(403, limited).contains("file_rate_limited_captcha_required"))
        // The info call itself can say the download is restricted, on a 200.
        assertTrue(refusal(200, info.replace("\"availability\":\"\"", "\"availability\":\"ip_download_limited_captcha_required\"")).contains("ip_download_limited_captcha_required"))
        assertEquals("PixelDrain does not have that file", refusal(404, """{"success":false,"value":"not_found"}"""))
        assertEquals("PixelDrain did not take that API key", refusal(401, """{"success":false,"value":"authentication_failed"}""", "bad"))
        assertEquals("PixelDrain answered 500", refusal(500, "<html>"))
        // One restricted file keeps the whole list from this way: part of a game is no use.
        val oneLimited = list.replace("\"id\":\"f2\"", "\"availability\":\"virus_detected_captcha_required\",\"id\":\"f2\"")
        val reply = PixelDrain(Canned("GET https://pixeldrain.com/api/list/L8bhwx" to (200 to oneLimited))).resolve("https://pixeldrain.com/l/L8bhwx", null)
        assertTrue(reply is HostResolution.Refused)
    }

    @Test
    fun theTestCallsTheAccountAndSaysWhyAKeyFails() {
        val http = Canned("GET https://pixeldrain.com/api/user" to (200 to """{"id":"u","username":"someone"}"""))
        assertNull(PixelDrain(http).check("secret-key"))
        assertEquals(mapOf("Authorization" to basic), http.calls.single().second)
        assertEquals("PixelDrain did not take that API key", PixelDrain(Canned("GET https://pixeldrain.com/api/user" to (401 to "{}"))).check("bad"))
    }

    private val debridFile = listOf(WebSessionDownload.direct("https://cdn.debrid.example/g", "Game.zip", 9))

    @Test
    fun theOwnAccountComesFirstThenDebridThenTheAnonymousApiThenTheWebView() {
        val pd = PixelDrain(Canned("GET https://pixeldrain.com/api/file/abc123/info" to (200 to info)))
        val link = "https://pixeldrain.com/u/abc123"
        HostLinks.accounts = { if (it == "pixeldrain") "secret-key" else null }
        val own = HostLinks.resolve(link, debrid = { error("debrid is not asked when the person's own account serves") }, resolvers = listOf(pd))!!
        assertEquals("via your PixelDrain", own.single().via)
        assertEquals(mapOf("Authorization" to basic), own.single().headers)
        assertEquals(sha, own.single().sha256)

        HostLinks.accounts = { null }
        assertEquals("a debrid account before the anonymous API", debridFile, HostLinks.resolve(link, debrid = { debridFile }, resolvers = listOf(pd)))

        val anonymous = HostLinks.resolve(link, debrid = { null }, resolvers = listOf(pd))!!
        assertEquals("direct from PixelDrain", anonymous.single().via)
        assertTrue(anonymous.single().headers.isEmpty())
        assertEquals("Game v1.0.zip", dev.droidtop.pluginhost.WebSessions.fileNameFor(anonymous.single().url, anonymous.single().contentDisposition))

        assertNull("another host's link is the web view's", HostLinks.resolve("https://mega.nz/file/x", debrid = { null }, resolvers = listOf(pd)))
    }

    @Test
    fun aRefusalHandsTheLinkOnToDebridAndThenToTheWebViewWithoutSolvingAnything() {
        val limitedHost = PixelDrain(Canned("GET https://pixeldrain.com/api/file/abc123/info" to (403 to limited)))
        val link = "https://pixeldrain.com/u/abc123"
        assertEquals(debridFile, HostLinks.resolve(link, debrid = { debridFile }, resolvers = listOf(limitedHost)))
        assertNull(HostLinks.resolve(link, debrid = { null }, resolvers = listOf(limitedHost)))
    }

    @Test
    fun aRefusedKeyStillLetsTheAnonymousApiTryAndAnUnreachableHostIsNotAFailure() {
        val link = "https://pixeldrain.com/u/abc123"
        val http = object : DebridTransport {
            override fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?): Pair<Int, String> =
                if (headers.isNotEmpty()) 401 to """{"success":false,"value":"authentication_failed"}""" else 200 to info
        }
        HostLinks.accounts = { "bad" }
        assertEquals("direct from PixelDrain", HostLinks.resolve(link, debrid = { null }, resolvers = listOf(PixelDrain(http)))!!.single().via)
        val offline = PixelDrain { _, _, _, _ -> throw IOException("The service could not be reached") }
        assertNull(HostLinks.resolve(link, debrid = { null }, resolvers = listOf(offline)))
    }

    @Test
    fun aListLargerThanOneJobTakesIsLeftToTheWebView() {
        val entries = (1..17).joinToString(",") { n -> "{\"id\":\"f$n\",\"name\":\"p$n.bin\",\"size\":1}" }
        val many = "{\"success\":true,\"files\":[$entries]}"
        val pd = PixelDrain(Canned("GET https://pixeldrain.com/api/list/big" to (200 to many)))
        assertNull(HostLinks.resolve("https://pixeldrain.com/l/big", debrid = { null }, resolvers = listOf(pd)))
    }
}
