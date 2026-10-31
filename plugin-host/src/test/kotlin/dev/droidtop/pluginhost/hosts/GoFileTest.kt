package dev.droidtop.pluginhost.hosts

import dev.droidtop.pluginhost.DownloadJobs
import dev.droidtop.pluginhost.debrid.DebridTransport
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GoFile through its own API (Droidtop/tracker#638 part 5): its links, the guest token, a folder with nested folders,
 * the MD5 of each file, and every refusal. The replies are recorded from gofile.io/api (2026-10-24), the failing ones
 * from the live API: a guest token's listing answers 401 `error-notPremium`.
 */
class GoFileTest {
    private val md5 = "0f8adc1149b1a2c3d4e5f60718293a4b"
    private val guestToken = "y0o0OhI8IwxYFxzlFc2p7Uedhx0mxKiO"
    private val ownToken = "premiumTokenPremiumTokenPremium01"
    private val dir: File = Files.createTempDirectory("gofile").toFile()

    @After fun cleanUp() {
        dir.deleteRecursively()
        HostLinks.accounts = { null }
    }

    /** Recorded replies by "METHOD url", every call kept with its headers. */
    private class Canned(vararg answers: Pair<String, Pair<Int, String>>) : DebridTransport {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        private val table = answers.toMap()
        override fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?): Pair<Int, String> {
            calls += "$method $url" to headers
            return table["$method $url"] ?: (404 to """{"status":"error-notFound","data":{}}""")
        }
    }

    private val account = """{"status":"ok","data":{"id":"6308dae0-13f2-4635-a603-dd0fdbc4d29e","rootFolder":"15a60360-c165-4deb-9ef1-26fb6546cec0","tier":"guest","token":"$guestToken"}}"""
    private val notPremium = """{"status":"error-notPremium","data":{}}"""

    /** A folder with one file, one folder of two files and, inside that, one folder with one more file. */
    private val tree = """{"status":"ok","data":{"id":"root-id","type":"folder","name":"Game","code":"x7k2p9","public":true,"childrenCount":2,"children":{
        "f1":{"id":"f1","type":"file","name":"Game.part1.rar","size":100,"md5":"$md5","mimetype":"application/x-rar","link":"https://store-1.gofile.io/download/web/f1/Game.part1.rar"},
        "d1":{"id":"d1","type":"folder","name":"Extras","childrenCount":2,"children":{
            "f2":{"id":"f2","type":"file","name":"Game.part2.rar","size":50,"md5":"${md5.uppercase()}","link":"https://store-2.gofile.io/download/web/f2/Game.part2.rar"},
            "d2":{"id":"d2","type":"folder","name":"Deep","childrenCount":1,"children":{
                "f3":{"id":"f3","type":"file","name":"readme.txt","size":3,"link":"https://store-1.gofile.io/download/web/f3/readme.txt"}}}}}}},
        "metadata":{"page":1,"pageSize":100,"totalCount":2,"totalPages":1,"hasNextPage":false}}"""

    private val own = mapOf("Authorization" to "Bearer $ownToken")

    private fun files(host: GoFile, url: String, key: String?) = (host.resolve(url, key) as HostResolution.Files).files

    private fun refusal(host: GoFile, url: String, key: String?) = (host.resolve(url, key) as HostResolution.Refused).reason

    @Test
    fun gofileTakesItsContentLinksOnTheOneDomainAndNoOthers() {
        val gf = GoFile(Canned())
        assertTrue(gf.matches("https://gofile.io/d/x7k2p9"))
        assertTrue(gf.matches("https://www.gofile.io/d/9f8e7d6c-5b4a-3928-1716-151413121110"))
        assertFalse(gf.matches("http://gofile.io/d/x7k2p9"))
        assertFalse(gf.matches("https://gofile.io/d/"))
        assertFalse(gf.matches("https://gofile.io/d/x7k2p9/extra"))
        assertFalse(gf.matches("https://gofile.io/contents/x7k2p9"))
        assertFalse(gf.matches("https://api.gofile.io/d/x7k2p9"))
        assertFalse(gf.matches("https://notgofile.io/d/x7k2p9"))
        assertFalse(gf.matches("https://gofile.io.evil.example/d/x7k2p9"))
    }

    @Test
    fun theOwnTokenListsNestedFoldersAndEveryFileKeepsItsMd5SizeAndTheAccountCookie() {
        val http = Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (200 to tree))
        val found = files(GoFile(http), "https://gofile.io/d/x7k2p9", " $ownToken ")
        assertEquals(listOf("Game.part1.rar", "Game.part2.rar", "readme.txt"), found.map { it.name })
        assertEquals(listOf(100L, 50L, 3L), found.map { it.size })
        assertEquals(
            listOf(
                "https://store-1.gofile.io/download/web/f1/Game.part1.rar",
                "https://store-2.gofile.io/download/web/f2/Game.part2.rar",
                "https://store-1.gofile.io/download/web/f3/readme.txt",
            ),
            found.map { it.url },
        )
        assertEquals("the digest is lower case, and absent when GoFile gave none", listOf(md5, md5, null), found.map { it.md5 })
        assertTrue(found.all { it.sha256 == null })
        assertTrue(found.all { it.headers == mapOf("Cookie" to "accountToken=$ownToken") })
        assertEquals("a person's token needs no guest account", own, http.calls.single().second)
    }

    @Test
    fun aFolderThatCameWithoutItsChildrenIsAskedForAndTheChildrenMayBeAList() {
        val shallow = """{"status":"ok","data":{"id":"root-id","type":"folder","childrenCount":1,"children":{
            "d1":{"id":"d1","type":"folder","name":"Extras","childrenCount":1}}}}"""
        val inner = """{"status":"ok","data":{"id":"d1","type":"folder","childrenCount":1,"children":[
            {"id":"f2","type":"file","name":"Game.part2.rar","size":50,"link":"https://store-2.gofile.io/download/web/f2/Game.part2.rar"}]}}"""
        val http = Canned(
            "GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (200 to shallow),
            "GET https://api.gofile.io/contents/d1?maxdepth=16" to (200 to inner),
        )
        assertEquals(listOf("Game.part2.rar"), files(GoFile(http), "https://gofile.io/d/x7k2p9", ownToken).map { it.name })
        assertEquals(2, http.calls.size)
        // A link to one file is that file's own payload, without children.
        val single = """{"status":"ok","data":{"id":"f9","type":"file","name":"Solo.zip","size":9,"md5":"$md5","link":"https://store-1.gofile.io/download/web/f9/Solo.zip"}}"""
        val one = Canned("GET https://api.gofile.io/contents/f9?maxdepth=16" to (200 to single))
        assertEquals(listOf("Solo.zip"), files(GoFile(one), "https://gofile.io/d/f9", ownToken).map { it.name })
    }

    @Test
    fun aGuestGetsOneTokenFromTheAccountsCallAndKeepsItForTheRun() {
        val http = Canned(
            "POST https://api.gofile.io/accounts" to (200 to account),
            "GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (200 to tree),
        )
        val gf = GoFile(http) { token -> if (token == guestToken) "site-token" else null }
        val first = files(gf, "https://gofile.io/d/x7k2p9", null)
        files(gf, "https://gofile.io/d/x7k2p9", " ")
        assertEquals(1, http.calls.count { it.first.startsWith("POST ") })
        val listing = http.calls.first { it.first.startsWith("GET ") }.second
        assertEquals(mapOf("Authorization" to "Bearer $guestToken", "X-Website-Token" to "site-token"), listing)
        assertTrue(first.all { it.headers == mapOf("Cookie" to "accountToken=$guestToken") })
    }

    @Test
    fun withoutAWebsiteTokenAGuestIsNotAskedForAListingAtAll() {
        val http = Canned("POST https://api.gofile.io/accounts" to (200 to account))
        assertEquals("GoFile lists a folder for a guest only to its own page", refusal(GoFile(http), "https://gofile.io/d/x7k2p9", null))
        assertTrue("no content call is made without a token the site would accept", http.calls.none { it.first.contains("/contents/") })
    }

    @Test
    fun aMissingGuestAccountAndARefusedPremiumOnlyListingAreRefusalsNotFiles() {
        val link = "https://gofile.io/d/x7k2p9"
        assertEquals("GoFile would not make a guest account", refusal(GoFile(Canned("POST https://api.gofile.io/accounts" to (429 to """{"status":"error-rateLimit","data":{}}"""))) { "t" }, link, null))
        val premiumOnly = Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (401 to notPremium))
        assertEquals("GoFile lists folders through its API for Premium accounts only", refusal(GoFile(premiumOnly), link, ownToken))
        val wrong = Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (401 to """{"status":"error-wrongToken","data":{}}"""))
        assertEquals("GoFile did not take that API token", refusal(GoFile(wrong), link, ownToken))
        assertEquals("GoFile did not take that API token", refusal(GoFile(Canned()), link, "has spaces in it"))
        assertEquals("GoFile does not have that content", refusal(GoFile(Canned()), link, ownToken))
        val limited = Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (200 to """{"status":"error-rateLimit","data":{}}"""))
        assertEquals("GoFile will not serve it now (error-rateLimit)", refusal(GoFile(limited), link, ownToken))
        assertEquals("GoFile answered 502", refusal(GoFile(Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (502 to "<html>"))), link, ownToken))
        val password = Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (200 to """{"status":"ok","data":{"id":"x","type":"folder","canAccess":false,"password":true}}"""))
        assertTrue(refusal(GoFile(password), link, ownToken).contains("password"))
    }

    @Test
    fun aFileWithoutAnAddressOnGoFileOrWithAClashingNameKeepsTheWholeFolderFromThisWay() {
        fun listing(vararg children: String) = Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (200 to """{"status":"ok","data":{"id":"r","type":"folder","childrenCount":${children.size},"children":{${children.joinToString(",")}}}}"""))
        fun file(id: String, name: String, link: String?) = "\"$id\":{\"id\":\"$id\",\"type\":\"file\",\"name\":\"$name\",\"size\":1" + (link?.let { ",\"link\":\"$it\"" } ?: "") + "}"
        val ok = file("a", "a.bin", "https://store-1.gofile.io/download/web/a/a.bin")
        // The account's cookie never goes to another host, and part of a game is no use.
        assertEquals("GoFile gave no download address for b.bin", refusal(GoFile(listing(ok, file("b", "b.bin", null))), "https://gofile.io/d/x7k2p9", ownToken))
        assertEquals("GoFile gave no download address for b.bin", refusal(GoFile(listing(ok, file("b", "b.bin", "https://evil.example/download/b.bin"))), "https://gofile.io/d/x7k2p9", ownToken))
        assertEquals("GoFile gave no download address for b.bin", refusal(GoFile(listing(ok, file("b", "b.bin", "http://store-1.gofile.io/download/web/b/b.bin"))), "https://gofile.io/d/x7k2p9", ownToken))
        assertEquals("Two files in the GoFile folder share a name", refusal(GoFile(listing(ok, file("b", "A.BIN", "https://store-1.gofile.io/download/web/b/A.BIN"))), "https://gofile.io/d/x7k2p9", ownToken))
        assertEquals("The GoFile link has no files", refusal(GoFile(listing()), "https://gofile.io/d/x7k2p9", ownToken))
        val many = (1..17).map { n -> file("f$n", "p$n.bin", "https://store-1.gofile.io/download/web/f$n/p$n.bin") }.toTypedArray()
        assertTrue(refusal(GoFile(listing(*many)), "https://gofile.io/d/x7k2p9", ownToken).contains("more files than one job takes"))
    }

    @Test
    fun aFolderThatNeverShowsItsChildrenIsGivenUpOnRatherThanAskedForForever() {
        val loop = """{"status":"ok","data":{"id":"d1","type":"folder","childrenCount":1}}"""
        val http = Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (200 to loop), "GET https://api.gofile.io/contents/d1?maxdepth=16" to (200 to loop))
        assertTrue(refusal(GoFile(http), "https://gofile.io/d/x7k2p9", ownToken).contains("nests deeper"))
        assertTrue(http.calls.size <= 18)
    }

    @Test
    fun theTestNamesTheAccountAndSaysWhyATokenFailsOrNeverServes() {
        fun check(reply: Pair<Int, String>, token: String = ownToken) = GoFile(Canned("GET https://api.gofile.io/accounts/getid" to reply)).check(token)
        val http = Canned("GET https://api.gofile.io/accounts/getid" to (200 to """{"status":"ok","data":{"id":"u","email":"me@example.com","tier":"premium"}}"""))
        assertNull(GoFile(http).check(" $ownToken "))
        assertEquals(own, http.calls.single().second)
        assertEquals("GoFile did not take that API token", check(401 to """{"status":"error-wrongToken","data":{}}"""))
        assertEquals("GoFile did not take that API token", check(200 to "{}", token = "x"))
        assertEquals("GoFile answered 500", check(500 to "<html>"))
        assertEquals(
            "That GoFile account is not Premium, so GoFile will not list folders for it",
            check(200 to """{"status":"ok","data":{"id":"u","email":"guest1@gofile.io","tier":"guest"}}"""),
        )
    }

    @Test
    fun theMd5GoFileGivesIsCheckedOnTheFileThatComesDown() = runBlocking {
        HostLinks.accounts = { if (it == "gofile") ownToken else null }
        val one = """{"status":"ok","data":{"id":"f9","type":"file","name":"Solo.zip","size":0,"md5":"d41d8cd98f00b204e9800998ecf8427e","link":"https://store-1.gofile.io/download/web/f9/Solo.zip"}}"""
        val gf = GoFile(Canned("GET https://api.gofile.io/contents/f9?maxdepth=16" to (200 to one)))
        val download = HostLinks.resolve("https://gofile.io/d/f9", debrid = { null }, resolvers = listOf(gf))!!.single()
        assertEquals("via your GoFile", download.via)
        assertEquals(mapOf("Cookie" to "accountToken=$ownToken"), download.headers)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", download.md5)
        assertNull(download.sha256)
        val args = mapOf("md5" to download.md5!!)
        val whole = File(dir, "whole.bin").also { it.writeBytes(ByteArray(0)) }
        DownloadJobs.verifyDigest(whole, args)
        assertTrue("a file with the right md5 is kept", whole.exists())
        val broken = File(dir, "broken.bin").also { it.writeBytes(byteArrayOf(1)) }
        try {
            DownloadJobs.verifyDigest(broken, args)
            fail("a file that does not match the md5 must fail the job")
        } catch (e: IllegalStateException) {
            assertEquals(DownloadJobs.DIGEST_MISMATCH, e.message)
        }
        assertFalse(broken.exists())
    }

    @Test
    fun aRefusalHandsTheLinkOnToDebridThenTheGuestAndThenTheWebView() {
        val link = "https://gofile.io/d/x7k2p9"
        val premiumOnly = GoFile(Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (401 to notPremium)))
        val debridFile = listOf(dev.droidtop.pluginhost.WebSessionDownload.direct("https://cdn.debrid.example/g", "Game.zip", 9))
        HostLinks.accounts = { ownToken }
        assertEquals("a refused account goes on to debrid", debridFile, HostLinks.resolve(link, debrid = { debridFile }, resolvers = listOf(premiumOnly)))
        // With no debrid the guest is asked, and with no website token that is the web view's.
        val guestOnly = GoFile(Canned("GET https://api.gofile.io/contents/x7k2p9?maxdepth=16" to (401 to notPremium), "POST https://api.gofile.io/accounts" to (200 to account)))
        assertNull(HostLinks.resolve(link, debrid = { null }, resolvers = listOf(guestOnly)))
        val offline = GoFile(DebridTransport { _, _, _, _ -> throw IOException("The service could not be reached") })
        assertNull(HostLinks.resolve(link, debrid = { null }, resolvers = listOf(offline)))
        assertNull("another host's link is the web view's", HostLinks.resolve("https://mega.nz/file/x", debrid = { null }, resolvers = listOf(guestOnly)))
    }
}
