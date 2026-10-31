package dev.droidtop.pluginhost.debrid

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debrid as a step of torrent jobs (Droidtop/tracker#638): the hash, the two services, and which one serves. */
class DebridTest {
    private val hash = "3888fbc0f39f807569ae7aa8d21d16a8b4fec56f"
    private val ref = TorrentRef(hash, "magnet:?xt=urn:btih:$hash")

    @Test
    fun aMagnetsHashIsReadHexOrBase32AndATorrentsIsComputed() {
        assertEquals(hash, TorrentRefs.ofMagnet("magnet:?xt=urn:btih:${hash.uppercase()}&dn=Game")?.infoHash)
        assertEquals(hash, TorrentRefs.ofMagnet("magnet:?dn=Game&xt=urn:btih:HCEPXQHTT6AHK2NOPKUNEHIWVC2P5RLP")?.infoHash)
        assertNull(TorrentRefs.ofMagnet("magnet:?dn=no-hash"))
        assertNull(TorrentRefs.ofMagnet("https://example.com/a.torrent"))
        val info = "d6:lengthi5e4:name5:a.bin12:piece lengthi16384e6:pieces20:" + "A".repeat(20) + "e"
        val torrent = ("d8:announce3:x:y4:info" + info + "e").toByteArray(Charsets.ISO_8859_1)
        assertEquals("7d6740768a8b6045e9d4fd95093a2211b8d86c42", TorrentRefs.ofTorrent(torrent)?.infoHash)
        assertNull(TorrentRefs.ofTorrent("not a torrent".toByteArray()))
    }

    @Test
    fun aFileOutsideTheDownloadIsRefused() {
        val folder = File("/tmp/job")
        assertEquals(File("/tmp/job/Game/data/a.bin"), DebridStep.childOf(folder, "Game/data/a.bin"))
        assertEquals(File("/tmp/job/a.bin"), DebridStep.childOf(folder, "/a.bin"))
        assertNull(DebridStep.childOf(folder, "../escape"))
        assertNull(DebridStep.childOf(folder, ""))
    }

    /** Canned answers by "METHOD url-prefix", first match wins; every call is kept. */
    private class Canned(vararg answers: Pair<String, Pair<Int, String>>) : DebridTransport {
        val calls = mutableListOf<String>()
        private val table = answers.toList()
        override fun call(method: String, url: String, headers: Map<String, String>, form: Map<String, String>?): Pair<Int, String> {
            calls += "$method $url"
            return table.firstOrNull { (key, _) -> "$method $url".startsWith(key) }?.second ?: (404 to "")
        }
    }

    @Test
    fun torBoxServesAFinishedTorrentFileByFile() {
        val http = Canned(
            "POST https://api.torbox.app/v1/api/torrents/createtorrent" to (200 to """{"success":true,"data":{"torrent_id":7}}"""),
            "GET https://api.torbox.app/v1/api/torrents/mylist?id=7" to (200 to """{"success":true,"data":{"id":7,"download_present":true,"download_finished":true,"files":[{"id":0,"name":"Game/a.bin","size":5},{"id":1,"name":"Game/b.bin","size":6}]}}"""),
            "GET https://api.torbox.app/v1/api/torrents/requestdl" to (200 to """{"success":true,"data":"https://cdn.torbox.example/x"}"""),
        )
        val sent = mutableListOf<Map<String, String>>()
        val state = TorBox({ method, url, headers, form -> form?.let { sent += it }; http.call(method, url, headers, form) }).request("key", ref) as DebridState.Ready
        assertEquals("a torrent droidtop adds is never seeded", "3", sent.first()["seed"])
        assertEquals(listOf("Game/a.bin", "Game/b.bin"), state.files.map { it.path })
        assertEquals(2, http.calls.count { it.contains("requestdl") })
    }

    @Test
    fun torBoxsRefusalsSayWhatTheyAre() {
        val box = TorBox(Canned())
        assertTrue(box.refusal(401, "")!!.auth)
        val monthly = box.refusal(200, """{"success":false,"error":"MONTHLY_LIMIT","detail":"Monthly limit reached"}""")!!
        assertTrue("a limit rests the account, it is not the end of it", monthly.limit && !monthly.permanent && monthly.restUntilMs != null)
        assertTrue(box.refusal(200, """{"success":false,"error":"INVALID_MAGNET","detail":"Bad magnet"}""")!!.permanent)
        assertNull(box.refusal(200, """{"success":true}"""))
    }

    @Test
    fun realDebridSelectsEveryFileThenUnrestrictsEachLink() {
        val waiting = Canned(
            "GET https://api.real-debrid.com/rest/1.0/torrents?" to (200 to """[{"id":"R1","hash":"$hash"}]"""),
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/R1" to (200 to """{"status":"waiting_files_selection","files":[]}"""),
            "POST https://api.real-debrid.com/rest/1.0/torrents/selectFiles/R1" to (204 to ""),
        )
        assertTrue(RealDebrid(waiting).request("key", ref) is DebridState.Caching)
        assertTrue(waiting.calls.none { it.contains("addMagnet") })

        val done = Canned(
            "GET https://api.real-debrid.com/rest/1.0/torrents?" to (200 to """[{"id":"R1","hash":"$hash"}]"""),
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/R1" to (200 to """{"status":"downloaded","files":[{"id":2,"path":"/b.bin","bytes":6,"selected":1},{"id":1,"path":"/a.bin","bytes":5,"selected":1}],"links":["https://real-debrid.com/d/1","https://real-debrid.com/d/2"]}"""),
            "POST https://api.real-debrid.com/rest/1.0/unrestrict/link" to (200 to """{"download":"https://dl.rd.example/f"}"""),
        )
        val ready = RealDebrid(done).request("key", ref) as DebridState.Ready
        assertEquals(listOf("a.bin", "b.bin"), ready.files.map { it.path })
    }

    /** A service whose answers are set by the test. */
    private class Fake(override val name: String, var cached: Boolean?, vararg states: DebridState) : DebridProvider {
        override val id = name.lowercase()
        private val queue = states.toMutableList()
        var asked = 0
        var released = 0
        override fun release(key: String, torrent: TorrentRef) { released++ }
        override fun check(key: String): String? = null
        override fun cached(key: String, torrent: TorrentRef) = cached
        override fun request(key: String, torrent: TorrentRef): DebridState {
            asked++
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    private val folder: File = Files.createTempDirectory("debrid").toFile()
    private val ready = DebridState.Ready(listOf(DebridFile("Game/a.bin", 5, "https://cdn.example/a")))

    private fun serve(accounts: List<DebridProvider>, waits: Boolean, fetched: MutableList<String> = mutableListOf()) = runBlocking {
        DebridStep.serve(accounts.map { it to "key" }, waits, ref, folder, { _, _ -> }, { file, into, _ -> fetched += "${file.url} ${into.relativeTo(folder).path}" }, pollMs = 1, maxWaitMs = 10)
    }

    @Test
    fun theFirstAccountThatHasItServesItIntoTheJobsFolder() {
        val fetched = mutableListOf<String>()
        val notHere = Fake("TorBox", cached = false, DebridState.NotCached)
        val here = Fake("Other", cached = true, ready)
        assertEquals("Other", serve(listOf(notHere, here), waits = false, fetched))
        assertEquals(listOf("https://cdn.example/a Game/a.bin"), fetched)
        assertEquals(0, notHere.asked)
    }

    @Test
    fun noAccountWithItFallsBackUnlessThePersonChoseToWait() {
        assertNull(serve(emptyList(), waits = true))
        assertNull(serve(listOf(Fake("TorBox", cached = false, DebridState.Caching(0.1), ready)), waits = false))
        // Waiting: the service is asked until it has it.
        assertEquals("TorBox", serve(listOf(Fake("TorBox", cached = false, DebridState.Caching(0.1), DebridState.Caching(0.5), ready)), waits = true))
        // A service that cannot say is asked by adding it, and serves it when it already has it.
        assertEquals("Real-Debrid", serve(listOf(Fake("Real-Debrid", cached = null, ready)), waits = false))
        // A wait that runs out falls back.
        assertNull(serve(listOf(Fake("TorBox", cached = false, DebridState.Caching(0.1))), waits = true))
    }

    @Test
    fun aServiceAskedByAddingThatDidNotServeTakesItBackOffAndTheOneThatServedKeepsIt() {
        val couldNotSay = Fake("Real-Debrid", cached = null, DebridState.Caching(0.0))
        val served = Fake("TorBox", cached = null, ready)
        assertEquals("TorBox", serve(listOf(couldNotSay, served), waits = false))
        assertEquals(1, couldNotSay.released)
        assertEquals(0, served.released)
        // Nobody served it: everything asked by adding is taken back off.
        val alone = Fake("Real-Debrid", cached = null, DebridState.Caching(0.0))
        assertNull(serve(listOf(alone), waits = false))
        assertEquals(1, alone.released)
    }

    @Test
    fun realDebridTakesOffOnlyATorrentDroidtopAdded() {
        val already = Canned(
            "GET https://api.real-debrid.com/rest/1.0/torrents?" to (200 to """[{"id":"R1","hash":"$hash"}]"""),
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/R1" to (200 to """{"status":"downloading","progress":10}"""),
        )
        RealDebrid(already).apply { request("key", ref); release("key", ref) }
        assertTrue("one that was on the list stays", already.calls.none { it.startsWith("DELETE") })

        val fresh = Canned(
            "GET https://api.real-debrid.com/rest/1.0/torrents?" to (200 to "[]"),
            "POST https://api.real-debrid.com/rest/1.0/torrents/addMagnet" to (201 to """{"id":"R9"}"""),
            "GET https://api.real-debrid.com/rest/1.0/torrents/info/R9" to (200 to """{"status":"queued"}"""),
        )
        RealDebrid(fresh).apply { request("key", ref); release("key", ref) }
        assertTrue(fresh.calls.contains("DELETE https://api.real-debrid.com/rest/1.0/torrents/delete/R9"))
    }

    @Test
    fun aHosterLinkIsServedOnlyForAHostTheAccountLists() {
        val domains = setOf("pixeldrain.com", "mega.nz")
        assertTrue(DebridLinks.covers(domains, "pixeldrain.com"))
        assertTrue(DebridLinks.covers(domains, "www.pixeldrain.com"))
        assertTrue(DebridLinks.covers(domains, "eu.mega.nz"))
        assertTrue(!DebridLinks.covers(domains, "notpixeldrain.com"))
        assertTrue(!DebridLinks.covers(domains, "f95zone.to"))
    }

    @Test
    fun torBoxListsItsWorkingHostersAndServesAFetchedLinkFileByFile() {
        val http = Canned(
            "GET https://api.torbox.app/v1/api/webdl/hosters" to (200 to """{"success":true,"data":[{"name":"Pixeldrain","domains":["pixeldrain.com"],"status":true},{"name":"Down","domains":["down.example"],"status":false}]}"""),
            "POST https://api.torbox.app/v1/api/webdl/createwebdownload" to (200 to """{"success":true,"data":{"webdownload_id":"w1","hash":"h"}}"""),
            "GET https://api.torbox.app/v1/api/webdl/mylist?id=w1" to (200 to """{"success":true,"data":{"id":"w1","download_present":true,"download_finished":true,"files":[{"id":3,"name":"x/Game-1.0.zip","short_name":"Game-1.0.zip","size":9}]}}"""),
            "GET https://api.torbox.app/v1/api/webdl/requestdl" to (200 to """{"success":true,"data":"https://cdn.torbox.example/g"}"""),
        )
        val box = TorBox(http)
        assertEquals(setOf("pixeldrain.com"), box.hosts("key"))
        val ready = box.unrestrict("key", "https://pixeldrain.com/u/abc") as DebridState.Ready
        assertEquals(listOf("Game-1.0.zip"), ready.files.map { it.path })
        assertEquals("https://cdn.torbox.example/g", ready.files.single().url)
        assertTrue(http.calls.any { it.contains("web_id=w1") && it.contains("file_id=3") })
    }

    @Test
    fun realDebridListsItsSupportedHostersAndServesALinkAsOneDownload() {
        val http = Canned(
            "GET https://api.real-debrid.com/rest/1.0/hosts/status" to (200 to """{"pixeldrain.com":{"supported":1,"status":"up"},"mega.nz":{"supported":1,"status":"down"},"gofile.io":{"supported":0,"status":"up"}}"""),
            "POST https://api.real-debrid.com/rest/1.0/unrestrict/link" to (200 to """{"filename":"Game.zip","filesize":123,"download":"https://dl.real-debrid.example/Game.zip"}"""),
        )
        val rd = RealDebrid(http)
        assertEquals(setOf("pixeldrain.com"), rd.hosts("t"))
        val ready = rd.unrestrict("t", "https://pixeldrain.com/u/abc") as DebridState.Ready
        assertEquals(listOf(DebridFile("Game.zip", 123, "https://dl.real-debrid.example/Game.zip")), ready.files)
        val fallback = Canned("GET https://api.real-debrid.com/rest/1.0/hosts/domains" to (200 to """["1fichier.com","Pixeldrain.com"]"""))
        assertEquals(setOf("1fichier.com", "pixeldrain.com"), RealDebrid(fallback).hosts("t"))
    }

    @Test
    fun realDebridsErrorCodesSayWhatTheyAre() {
        val rd = RealDebrid(Canned())
        assertTrue(rd.errorState(401, org.json.JSONObject("""{"error":"bad_token","error_code":8}""")).auth)
        assertTrue(rd.errorState(503, org.json.JSONObject("""{"error":"hoster_unsupported","error_code":16}""")).permanent)
        val traffic = rd.errorState(503, org.json.JSONObject("""{"error":"traffic_exhausted","error_code":23}"""))
        assertEquals("This Real-Debrid account's traffic is used up", traffic.message)
        assertTrue(!traffic.permanent)
        assertEquals("Real-Debrid did not fetch the link (unknown thing)", rd.errorState(503, org.json.JSONObject("""{"error":"unknown_thing","error_code":99}""")).message)
    }

    @Test
    fun aDeviceSignInWaitsForApprovalThenTurnsTheCodeIntoAToken() {
        var approved = false
        val form = mutableListOf<Map<String, String>>()
        val http = DebridTransport { method, url, _, sent ->
            sent?.let { form += it }
            when {
                url.startsWith("https://api.real-debrid.com/oauth/v2/device/code?client_id=X245A4XAIBGVM&new_credentials=yes") ->
                    200 to """{"device_code":"DEV","user_code":"ABCD1234","interval":5,"expires_in":1800,"verification_url":"https://real-debrid.com/device"}"""
                url.startsWith("https://api.real-debrid.com/oauth/v2/device/credentials?client_id=X245A4XAIBGVM&code=DEV") ->
                    if (approved) 200 to """{"client_id":"MINE","client_secret":"SECRET"}""" else { approved = true; 403 to """{"error":"authorization_pending"}""" }
                method == "POST" && url == "https://api.real-debrid.com/oauth/v2/token" ->
                    200 to """{"access_token":"ACCESS","expires_in":3600,"token_type":"Bearer","refresh_token":"REFRESH"}"""
                else -> 404 to ""
            }
        }
        val flow = RealDebridSignIn(http)
        val code = flow.code().first!!
        assertEquals("ABCD1234", code.userCode)
        var clock = 1_000L
        val slept = mutableListOf<Long>()
        val outcome = flow.await(code, isCancelled = { false }, now = { clock }, sleep = { slept += it; clock += it })
        val credential = (outcome as RealDebridSignIn.Outcome.SignedIn).credential
        assertEquals(RealDebridSignIn.Credential("MINE", "SECRET", "ACCESS", "REFRESH", clock + 3_600_000L), credential)
        assertEquals(5_000L, slept.sum())
        assertEquals(mapOf("client_id" to "MINE", "client_secret" to "SECRET", "code" to "DEV", "grant_type" to RealDebridSignIn.DEVICE_GRANT), form.single())
        assertEquals(credential, RealDebridSignIn.Credential.fromJson(credential.toJson()))
        assertTrue("the secret half is never printed", !credential.toString().contains("SECRET") && !code.toString().contains("DEV"))

        flow.refresh(credential)
        assertEquals("REFRESH", form.last()["code"])
    }

    @Test
    fun aCodeNobodyApprovesRunsOutAndCancelStopsTheWait() {
        val pending = DebridTransport { _, _, _, _ -> 403 to """{"error":"authorization_pending"}""" }
        val code = RealDebridSignIn.Code("DEV", "ABCD", "https://real-debrid.com/device", 5, 12)
        var clock = 0L
        assertEquals(RealDebridSignIn.Outcome.Expired, RealDebridSignIn(pending).await(code, { false }, now = { clock }, sleep = { clock += it }))
        assertEquals(RealDebridSignIn.Outcome.Cancelled, RealDebridSignIn(pending).await(code, { true }, now = { 0L }, sleep = {}))
    }

    /** A provider with a set cost, for the choice. */
    private class Priced(override val id: String, override val slotLimited: Boolean = true, var price: DebridCost = DebridCost.Free) : DebridProvider {
        override val name = id
        var asked = 0
        override fun cost(key: String, host: String?): DebridCost { asked++; return price }
        override fun check(key: String): String? = null
        override fun cached(key: String, torrent: TorrentRef): Boolean? = null
        override fun request(key: String, torrent: TorrentRef): DebridState = DebridState.NotCached
    }

    private fun names(ranked: List<DebridChoice.Account>) = ranked.map { it.provider.id }

    @Test
    fun theAccountItCostsLeastServesAndTheOrderSettlesATie() {
        val a = Priced("a"); val b = Priced("b"); val c = Priced("c"); val d = Priced("d")
        val ranked = DebridChoice.rank(
            listOf(
                DebridChoice.Account(a, "k", DebridCost.Spends(0.2)),
                DebridChoice.Account(b, "k", DebridCost.Free),
                DebridChoice.Account(c, "k", DebridCost.NotNow("out")),
                DebridChoice.Account(d, "k", DebridCost.Spends(0.9)),
            ),
            spread = false,
        )
        assertEquals("free first, then the most left; one that cannot serve is left out", listOf("b", "d", "a"), names(ranked))
        val tie = DebridChoice.rank(listOf(DebridChoice.Account(a, "k", DebridCost.Free), DebridChoice.Account(b, "k", DebridCost.Free)), spread = false)
        assertEquals(listOf("a", "b"), names(tie))
        val unknown = DebridChoice.rank(listOf(DebridChoice.Account(a, "k", DebridCost.Spends(null)), DebridChoice.Account(b, "k", DebridCost.Spends(0.1))), spread = false)
        assertEquals("a known amount left goes before an unknown one", listOf("b", "a"), names(unknown))
    }

    @Test
    fun spreadModeSharesATieByTheJobsRunningOnSlotLimitedAccountsOnly() {
        val busy = Priced("busy"); val idle = Priced("idle"); val open = Priced("open", slotLimited = false)
        val accounts = listOf(DebridChoice.Account(busy, "k", DebridCost.Free, running = 2), DebridChoice.Account(idle, "k", DebridCost.Free, running = 0))
        assertEquals(listOf("busy", "idle"), names(DebridChoice.rank(accounts, spread = false)))
        assertEquals(listOf("idle", "busy"), names(DebridChoice.rank(accounts, spread = true)))
        val cheaper = listOf(DebridChoice.Account(busy, "k", DebridCost.Free, running = 3), DebridChoice.Account(idle, "k", DebridCost.Spends(1.0)))
        assertEquals("spread never puts a spending account before a free one", listOf("busy", "idle"), names(DebridChoice.rank(cheaper, spread = true)))
        val unlimited = listOf(DebridChoice.Account(open, "k", DebridCost.Free, running = 5), DebridChoice.Account(idle, "k", DebridCost.Free))
        assertEquals(listOf("open", "idle"), names(DebridChoice.rank(unlimited, spread = true)))
    }

    @Test
    fun aLimitRestsTheAccountUntilItsResetAndCostsAreKeptAWhile() {
        DebridChoice.reset()
        val first = Priced("first"); val second = Priced("second")
        var clock = 1_000_000L
        val accounts = listOf<Pair<DebridProvider, String>>(first to "k", second to "k")
        assertEquals(listOf("first", "second"), DebridChoice.order(accounts, spread = false, now = { clock }).map { it.first.id })
        DebridChoice.order(accounts, spread = false, now = { clock })
        assertEquals("asked once within a few minutes", 1, first.asked)
        DebridChoice.refused(first, "k", DebridState.Failed("traffic used up", limit = true, restUntilMs = clock + 60_000), now = { clock })
        DebridChoice.refused(second, "k", DebridState.Failed("bad magnet", permanent = true), now = { clock })
        assertEquals(listOf("second"), DebridChoice.order(accounts, spread = false, now = { clock }).map { it.first.id })
        clock += 61_000
        assertEquals(listOf("first", "second"), DebridChoice.order(accounts, spread = false, now = { clock }).map { it.first.id })
        DebridChoice.reset()
    }

    @Test
    fun theServicesSayWhatAJobCostsFromTheAccount() {
        val now = isoMs("2026-10-24T00:00:00Z")!!
        fun torBox(me: String) = TorBox(Canned("GET https://api.torbox.app/v1/api/user/me" to (200 to me)), now = { now })
        assertEquals(DebridCost.Free, torBox("""{"success":true,"data":{"plan":2,"premium_expires_at":"2026-12-01T00:00:00Z","cooldown_until":"2025-02-01T00:00:00Z"}}""").cost("k", null))
        assertTrue("a cooldown ahead holds any plan", torBox("""{"success":true,"data":{"plan":1,"cooldown_until":"2026-10-25T00:00:00Z"}}""").cost("k", null) is DebridCost.NotNow)
        assertTrue(torBox("""{"success":true,"data":{"plan":1,"premium_expires_at":"2026-10-01T00:00:00Z"}}""").cost("k", null) is DebridCost.NotNow)
        assertEquals(DebridCost.Spends(null), torBox("""{"success":true,"data":{"plan":0,"cooldown_until":null}}""").cost("k", null))
        assertTrue(torBox("""{"success":true,"data":{"plan":0,"cooldown_until":"2026-10-24T05:00:00+00:00"}}""").cost("k", null) is DebridCost.NotNow)

        val rd = RealDebrid(
            Canned(
                "GET https://api.real-debrid.com/rest/1.0/user" to (200 to """{"type":"premium","premium":86400}"""),
                "GET https://api.real-debrid.com/rest/1.0/traffic" to (200 to """{"1fichier.com":{"left":0,"limit":100,"type":"gigabytes"},"katfile.com":{"left":25,"limit":100,"type":"gigabytes"}}"""),
            ),
            now = { now },
        )
        assertEquals("a torrent spends no hoster's traffic", DebridCost.Free, rd.cost("t", null))
        assertEquals(DebridCost.Free, rd.cost("t", "https://pixeldrain.com/u/abc"))
        assertEquals(DebridCost.Spends(0.25), rd.cost("t", "https://www.katfile.com/abc/Game.zip"))
        assertTrue(rd.cost("t", "https://1fichier.com/?abc") is DebridCost.NotNow)
        val lapsed = RealDebrid(Canned("GET https://api.real-debrid.com/rest/1.0/user" to (200 to """{"type":"free","premium":0}""")))
        assertTrue(lapsed.cost("t", null) is DebridCost.NotNow)
        assertTrue(rd.errorState(503, org.json.JSONObject("""{"error":"traffic_exhausted","error_code":23}""")).limit)
    }

    @Test
    fun aTorBoxHosterCappedADaySpendsWhatIsLeftAndARefusedKeySaysSignInAgain() {
        val now = isoMs("2026-10-24T00:00:00Z")!!
        val box = TorBox(
            Canned(
                "GET https://api.torbox.app/v1/api/user/me" to (200 to """{"success":true,"data":{"plan":2,"premium_expires_at":"2026-12-01T00:00:00Z"}}"""),
                "GET https://api.torbox.app/v1/api/webdl/hosters" to (200 to """{"success":true,"data":[
                    {"name":"Nitroflare","domains":["nitroflare.com"],"status":"up","daily_link_limit":5,"daily_link_used":4,"daily_bandwidth_limit":10737418240,"daily_bandwidth_used":0},
                    {"name":"Turbobit","domains":["turbobit.net"],"status":"up","daily_link_limit":5,"daily_link_used":5},
                    {"name":"Rapidgator","domains":["rapidgator.net"],"status":"down","daily_link_limit":5,"daily_link_used":0},
                    {"name":"Send","domains":[],"regex":"^https://send\\.now/","status":"up","daily_link_limit":5,"daily_link_used":1},
                    {"name":"PixelDrain","domains":["pixeldrain.com"],"status":"up","daily_link_limit":100,"daily_link_used":0,"daily_bandwidth_limit":0,"daily_bandwidth_used":0}]}"""),
            ),
            now = { now },
        )
        assertEquals("the least left of its caps", DebridCost.Spends(0.2), box.cost("k", "https://nitroflare.com/view/abc"))
        assertTrue(box.cost("k", "https://turbobit.net/abc.html") is DebridCost.NotNow)
        assertTrue("a hoster TorBox marks down cannot serve", box.cost("k", "https://rapidgator.net/file/abc") is DebridCost.NotNow)
        assertEquals("matched by its regex", DebridCost.Spends(0.8), box.cost("k", "https://send.now/abc"))
        assertEquals("a bandwidth limit of 0 is none", DebridCost.Spends(1.0), box.cost("k", "https://pixeldrain.com/u/abc"))
        assertEquals("a hoster TorBox does not list is free", DebridCost.Free, box.cost("k", "https://example.org/a.zip"))
        assertEquals(DebridCost.Free, box.cost("k", null))

        val rotated = TorBox(Canned("GET https://api.torbox.app/v1/api/user/me" to (401 to """{"success":false,"error":"BAD_TOKEN"}""")))
        val cost = rotated.cost("old", null) as DebridCost.NotNow
        assertTrue(cost.auth && cost.reason.contains("enter the new one"))
        DebridChoice.reset()
        val said = mutableListOf<String>()
        DebridChoice.onKeyRefused = { id, message -> said += "$id: $message" }
        try {
            assertEquals(emptyList<Pair<DebridProvider, String>>(), DebridChoice.order(listOf<Pair<DebridProvider, String>>(rotated to "old"), spread = false))
            assertEquals("never a silent skip", listOf("torbox: ${TorBox.KEY_REFUSED}"), said)
        } finally {
            DebridChoice.onKeyRefused = { _, _ -> }
            DebridChoice.reset()
        }
    }

    @Test
    fun aPrivateTorrentIsNeverHandedToADebridService() {
        val info = "d6:lengthi5e4:name5:a.bin12:piece lengthi16384e6:pieces20:" + "A".repeat(20) + "7:privatei1ee"
        val torrent = ("d8:announce3:x:y4:info" + info + "e").toByteArray(Charsets.ISO_8859_1)
        val ref = TorrentRefs.ofTorrent(torrent)!!
        assertTrue(ref.private)
        val open = "d6:lengthi5e4:name5:a.bin12:piece lengthi16384e6:pieces20:" + "A".repeat(20) + "e"
        assertTrue(!TorrentRefs.ofTorrent(("d4:info" + open + "e").toByteArray(Charsets.ISO_8859_1))!!.private)
        val fake = Priced("never")
        var asked = false
        val served = runBlocking {
            DebridStep.serve(listOf<Pair<DebridProvider, String>>(fake to "k"), waits = true, torrent = ref, folder = File("/tmp/job"), status = { _, _ -> asked = true }, fetch = { _, _, _ -> asked = true })
        }
        assertNull(served)
        assertTrue("nothing was asked", !asked)
    }

    /** A service that serves one hoster's links at once. */
    private class Hoster(override val name: String) : DebridProvider {
        override val id = name.lowercase()
        override fun check(key: String): String? = null
        override fun cached(key: String, torrent: TorrentRef): Boolean? = null
        override fun request(key: String, torrent: TorrentRef): DebridState = DebridState.NotCached
        override fun hosts(key: String) = setOf("pixeldrain.com")
        override fun linkCached(key: String, link: String) = true
        override fun unrestrict(key: String, link: String) = DebridState.Ready(listOf(DebridFile("Game.zip", 7, "https://cdn.example/g")))
    }

    @Test
    fun aHosterLinkADebridAccountServedSaysWhichAccountOnTheJobsLine() {
        DebridChoice.reset()
        val before = DebridLinks.accounts
        DebridLinks.accounts = { listOf(Hoster("TorBox") to "k") }
        try {
            val served = DebridLinks.resolve("https://pixeldrain.com/u/abc")!!
            assertEquals(listOf("https://cdn.example/g"), served.map { it.url })
            assertEquals("via your TorBox", served.single().via)
        } finally {
            DebridLinks.accounts = before
            DebridChoice.reset()
        }
    }

    /** A hoster TorBox has not fetched yet, served once it has (Droidtop/tracker#638: Bare Witness's GoFile mirror). */
    private class Fetching(private val calls: MutableList<String>) : DebridProvider {
        override val name = "TorBox"
        override val id = "torbox"
        private var asked = 0
        override fun check(key: String): String? = null
        override fun cached(key: String, torrent: TorrentRef): Boolean? = null
        override fun request(key: String, torrent: TorrentRef): DebridState = DebridState.NotCached
        override fun hosts(key: String) = setOf("gofile.io", "mixdrop.ag")
        override fun linkCached(key: String, link: String) = false
        override fun unrestrict(key: String, link: String): DebridState {
            calls += link
            return if (asked++ == 0) DebridState.Ready(listOf(DebridFile("BareWitness.zip", 9, "https://cdn.torbox.example/bw"))) else DebridState.NotCached
        }
    }

    @Test
    fun aHosterLinkTorBoxHasNotFetchedYetIsFetchedNotSkippedAndASkipSaysWhy() {
        DebridChoice.reset()
        val before = DebridLinks.accounts
        val calls = mutableListOf<String>()
        DebridLinks.accounts = { listOf(Fetching(calls) to "bare-witness-key") }
        try {
            // Not cached: before, "Wait for the service" (a torrent setting, off by default) skipped it in silence.
            val served = DebridLinks.resolve("https://gofile.io/d/bw")!!
            assertEquals("via your TorBox", served.single().via)
            assertEquals(listOf("https://gofile.io/d/bw"), calls)
            // A host the account does not list is passed over, and says so.
            val told = mutableListOf<String>()
            assertEquals(null, DebridLinks.resolve("https://uploadhaven.com/download/x", skipped = { told += it }))
            assertEquals(listOf("TorBox doesn't serve uploadhaven.com"), told)
            // Its host list, fetched once, makes a masked mirror's host name count before the link is opened.
            DebridLinks.prime()
            assertTrue(DebridLinks.servesName("GOFILE"))
            assertTrue(DebridLinks.servesName("MixDrop"))
            assertTrue(!DebridLinks.servesName("UPLOADHAVEN"))
            assertTrue(DebridLinks.serves("https://gofile.io/d/other"))
            assertTrue(dev.droidtop.pluginhost.hosts.HostLinks.served("https://gofile.io/d/other"))
        } finally {
            DebridLinks.accounts = before
            DebridChoice.reset()
        }
    }
}
