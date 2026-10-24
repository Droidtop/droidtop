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
        val state = TorBox(http).request("key", ref) as DebridState.Ready
        assertEquals(listOf("Game/a.bin", "Game/b.bin"), state.files.map { it.path })
        assertEquals(2, http.calls.count { it.contains("requestdl") })
    }

    @Test
    fun torBoxsRefusalsSayWhatTheyAre() {
        val box = TorBox(Canned())
        assertTrue(box.refusal(401, "")!!.auth)
        assertTrue(box.refusal(200, """{"success":false,"error":"MONTHLY_LIMIT","detail":"Monthly limit reached"}""")!!.permanent)
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
}
