package dev.droidtop.net.engine

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local network (docs/SPEC.md 12a): the engine reaches a person's own server only for an HTTP download droidtop sent
 * there, by a pass for exactly that address and port; a torrent never gets one.
 */
class LocalPassTest {
    private val dir: File = Files.createTempDirectory("local-pass").toFile()

    @After
    fun clean() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /** Records the passes and the order of calls; [statuses] answer `status` in turn, the last one again. */
    private class Engine(vararg statuses: Transfer) : TransferEngine {
        private val queue = ArrayDeque(statuses.toList())
        val calls = mutableListOf<String>()

        override fun add(url: String, options: Map<String, String>): String = "gid".also { calls += "add $url" }

        override fun status(gid: String): Transfer = if (queue.size > 1) queue.removeFirst() else queue.first()

        override fun remove(gid: String) {
            calls += "remove"
        }

        override fun allowLocal(address: InetAddress, port: Int) {
            calls += "allow ${address.hostAddress}:$port"
        }

        override fun revokeLocal(address: InetAddress, port: Int) {
            calls += "revoke ${address.hostAddress}:$port"
        }
    }

    private val passThrough: (String, Map<String, String>) -> RedirectGuard.Resolved = { url, headers -> RedirectGuard.Resolved(url, headers) }
    private val nas: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("100.101.5.6")) }
    private val size: SizeFormat = { "$it B" }

    private fun request(url: String) = TransferRequest(url, emptyMap(), File(dir, "acquire_1.bin"))

    @Test
    fun aDownloadFromThePersonsOwnServerOpensAPassForItAndClosesItAfter() = runTest {
        val engine = Engine(Transfer("complete", 10, 10, 0, 0))
        EngineFetch.fetch(engine, request("https://nas.tailnet.ts.net/games/a.zip"), { }, size, passThrough, Dispatchers.Unconfined, nas)
        assertEquals(listOf("allow 100.101.5.6:443", "add https://nas.tailnet.ts.net/games/a.zip", "revoke 100.101.5.6:443"), engine.calls)
    }

    @Test
    fun aPauseClosesThePassToo() = runTest {
        val engine = Engine(Transfer("active", 100, 10, 5, 0))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            EngineFetch.fetch(engine, request("http://192.168.1.20:8080/a.zip"), { }, size, passThrough, Dispatchers.Unconfined) {
                listOf(InetAddress.getByName("192.168.1.20"))
            }
        }
        advanceTimeBy(EngineFetch.POLL_MS * 2)
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(listOf("allow 192.168.1.20:8080", "add http://192.168.1.20:8080/a.zip", "remove", "revoke 192.168.1.20:8080"), engine.calls)
    }

    @Test
    fun aPublicServerGetsNoPass() = runTest {
        val engine = Engine(Transfer("complete", 10, 10, 0, 0))
        EngineFetch.fetch(engine, request("https://example.org/a.zip"), { }, size, passThrough, Dispatchers.Unconfined) {
            listOf(InetAddress.getByName("93.184.216.34"))
        }
        assertEquals(listOf("add https://example.org/a.zip"), engine.calls)
    }

    @Test
    fun localIsWhatTheEnginesBrokerCallsLocal() {
        fun local(text: String) = LocalAddresses.isLocal(InetAddress.getByName(text))
        for (text in listOf("127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.1.1", "169.254.3.3", "100.64.0.1", "100.127.255.254", "224.0.0.251", "0.0.0.0", "::1", "fe80::1", "fd7a:115c:a1e0::1", "ff02::1", "64:ff9b::a00:1", "::ffff:192.168.0.1")) {
            assertTrue("$text is local", local(text))
        }
        for (text in listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "2606:4700::1111", "64:ff9b::808:808")) {
            assertFalse("$text is public", local(text))
        }
        assertEquals(emptyList<Pair<InetAddress, Int>>(), LocalAddresses.passesFor("ftp://192.168.1.1/x", { listOf(InetAddress.getByName("192.168.1.1")) }))
        assertEquals(emptyList<Pair<InetAddress, Int>>(), LocalAddresses.passesFor("https://nowhere.invalid/x", { throw java.net.UnknownHostException(it) }))
    }

    @Test
    fun aRefusalIsSaidInWordsNotAsAnEngineErrorNumber() = runTest {
        // The person's own server, given a pass, still refused: that server could not be reached.
        val engine = Engine(Transfer("error", 0, 0, 0, 1))
        try {
            EngineFetch.fetch(engine, request("https://nas.tailnet.ts.net/a.zip"), { }, size, passThrough, Dispatchers.Unconfined, nas) { it }
            org.junit.Assert.fail("refused")
        } catch (e: TransferException) {
            assertEquals("droidtop could not reach nas.tailnet.ts.net on your local network", e.message)
        }
        assertEquals("revoke 100.101.5.6:443", engine.calls.last())
    }

    @Test
    fun aSiteThatSendsADownloadOntoTheLocalNetworkIsNamed() {
        val lan: (String) -> List<InetAddress> = { host -> listOf(InetAddress.getByName(if (host == "cdn.example.org") "93.184.216.34" else "192.168.1.10")) }
        assertEquals(
            "cdn.example.org sent this download on to 192.168.1.10, an address on your local network, and droidtop does not follow a site onto your own network",
            EngineFetch.refusal("https://cdn.example.org/a.zip", hadPass = false, lookup = lan, follow = { "https://192.168.1.10:8443/a.zip" }),
        )
        // An unknown error on the internet stays the engine's own: nothing local was involved.
        assertEquals(null, EngineFetch.refusal("https://cdn.example.org/a.zip", hadPass = false, lookup = lan, follow = { it }))
        assertEquals(null, EngineFetch.refusal("https://cdn.example.org/a.zip", hadPass = false, lookup = lan, follow = { throw java.io.IOException("offline") }))
    }
}
