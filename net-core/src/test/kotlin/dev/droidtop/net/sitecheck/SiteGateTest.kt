package dev.droidtop.net.sitecheck

import dev.droidtop.net.Http
import dev.droidtop.shared.sitecheck.CheckOutcome
import dev.droidtop.shared.sitecheck.Clearance
import dev.droidtop.shared.sitecheck.SiteAsker
import dev.droidtop.shared.sitecheck.SiteSolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One check per site, the browser's try before the person, and a request that waits finds the pass (Droidtop/tracker#751). */
class SiteGateTest {
    private class MemoryStorage(var text: String? = null) : SiteClearanceStore.Storage {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private val ua = "Mozilla/5.0 (Linux; Android 14; wv) Chrome/130"
    private val challenge = Http.Response(403, "<title>Just a moment...</title>".toByteArray(), mapOf("cf-mitigated" to "challenge"))
    private fun ok(text: String = "page") = Http.Response(200, text.toByteArray(), emptyMap())
    private fun clearanceFor(site: String) = Clearance(ua, mapOf(site to "cf_clearance=ok"))

    /** A site that answers a check until a request carries its clearance. */
    private fun site(sent: MutableList<Map<String, String>> = mutableListOf()): (Map<String, String>) -> Http.Response = { headers ->
        synchronized(sent) { sent += headers }
        if (headers["Cookie"].orEmpty().contains("cf_clearance=ok")) ok() else challenge
    }

    private fun store(now: () -> Long = { 1_000L }) = SiteClearanceStore(MemoryStorage(), now)

    @Test
    fun `an ordinary answer, a plain refusal included, is returned untouched and nobody is asked`() {
        var asked = 0
        val gate = SiteGate(store(), solver = { _, _ -> asked++; CheckOutcome.NeedsPerson }, asker = { _, _ -> asked++; CheckOutcome.NeedsPerson })
        assertEquals(200, gate.run("https://example.org/a", emptyMap()) { ok() }.status)
        val refusal = Http.Response(403, "Forbidden".toByteArray(), emptyMap())
        assertEquals(403, gate.run("https://example.org/a", emptyMap()) { refusal }.status)
        assertEquals(0, asked)
    }

    @Test
    fun `the browser's own try passes a check and the request is made once more with the clearance and the view's user agent`() {
        val sent = mutableListOf<Map<String, String>>()
        var triedFor = ""
        var givenMs = 0L
        val gate = SiteGate(store(), solver = { url, ms -> triedFor = url; givenMs = ms; CheckOutcome.Passed(clearanceFor("example.org")) }, asker = null)
        val answer = gate.run("https://example.org/page", mapOf("Cookie" to "xf_user=42", "User-Agent" to "droidtop"), site(sent))
        assertEquals(200, answer.status)
        assertEquals("https://example.org/page", triedFor)
        assertEquals("the browser is given 15 seconds", SiteGate.SOLVE_MS, givenMs)
        assertEquals(2, sent.size)
        assertEquals("the first try carries only what the caller sent", "xf_user=42", sent[0]["Cookie"])
        assertEquals("xf_user=42; cf_clearance=ok", sent[1]["Cookie"])
        assertEquals(ua, sent[1]["User-Agent"])
    }

    @Test
    fun `the person is asked only when the browser could not pass it, in place when the request is theirs and droidtop is in front`() {
        val inPlace = mutableListOf<Boolean>()
        val asker = SiteAsker { _, here -> inPlace += here; CheckOutcome.Passed(clearanceFor("example.org")) }
        val solver = SiteSolver { _, _ -> CheckOutcome.NeedsPerson }
        SiteGate(store(), solver, asker, isForeground = { true }).run("https://example.org/", emptyMap(), site())
        SiteGate(store(), solver, asker, isForeground = { true }).let { gate -> SiteGate.asUser { gate.run("https://example.org/", emptyMap(), site()) } }
        SiteGate(store(), solver, asker, isForeground = { false }).let { gate -> SiteGate.asUser { gate.run("https://example.org/", emptyMap(), site()) } }
        assertEquals("a background request is never asked in place; a user's is only when droidtop is in front", listOf(false, true, false), inPlace)
        var asked = 0
        SiteGate(store(), { _, _ -> CheckOutcome.Passed(clearanceFor("example.org")) }, { _, _ -> asked++; CheckOutcome.NeedsPerson }).run("https://example.org/", emptyMap(), site())
        assertEquals("a check the browser passed does not ask anyone", 0, asked)
    }

    @Test
    fun `a check nobody passes fails the request with the site named, and a burst after it does not ask again until the time is up`() {
        var clock = 0L
        var asked = 0
        val gate = SiteGate(store { clock }, solver = null, asker = { _, _ -> asked++; CheckOutcome.Failed("closed") }, now = { clock }, pendingMs = 60_000)
        for (i in 1..5) {
            try {
                gate.run("https://example.org/$i", emptyMap(), site())
                fail("expected the check to be required")
            } catch (e: SiteCheckRequired) {
                assertEquals("example.org", e.host)
                assertEquals("example.org checks the browser first; open it in droidtop to continue", e.message)
            }
        }
        assertEquals("one ask for the whole burst", 1, asked)
        clock = 61_000
        try { gate.run("https://example.org/6", emptyMap(), site()) } catch (_: SiteCheckRequired) { }
        assertEquals("asked again once the time is up", 2, asked)
        try { SiteGate.asUser { gate.run("https://example.org/7", emptyMap(), site()) } } catch (_: SiteCheckRequired) { }
        assertEquals("a request the person started asks again at once", 3, asked)
    }

    @Test
    fun `eight requests to one site cause one check, and all eight get their page`() {
        val solves = AtomicInteger()
        val solver = SiteSolver { _, _ -> solves.incrementAndGet(); Thread.sleep(150); CheckOutcome.Passed(clearanceFor("example.org")) }
        val gate = SiteGate(store(), solver, asker = null)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map { i -> pool.submit<Int> { start.await(); gate.run("https://example.org/$i", emptyMap(), site()).status } }
        start.countDown()
        assertEquals(List(8) { 200 }, results.map { it.get(10, TimeUnit.SECONDS) })
        pool.shutdown()
        assertEquals(1, solves.get())
    }

    @Test
    fun `two sites are checked independently, each once`() {
        val solved = java.util.Collections.synchronizedList(mutableListOf<String>())
        val solver = SiteSolver { url, _ ->
            val host = SiteGate.hostOf(url)!!
            solved += host
            Thread.sleep(100)
            CheckOutcome.Passed(clearanceFor(host))
        }
        val gate = SiteGate(store(), solver, null)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val work = listOf("a.example", "b.example", "a.example", "b.example").mapIndexed { i, host ->
            pool.submit<Int> { start.await(); gate.run("https://$host/$i", emptyMap(), site()).status }
        }
        start.countDown()
        assertEquals(List(4) { 200 }, work.map { it.get(10, TimeUnit.SECONDS) })
        pool.shutdown()
        assertEquals(listOf("a.example", "b.example"), solved.sorted())
    }

    @Test
    fun `a pass the site refuses again is forgotten and the request is not made a third time`() {
        val s = store()
        val calls = AtomicInteger()
        val gate = SiteGate(s, solver = { _, _ -> CheckOutcome.Passed(clearanceFor("example.org")) }, asker = null)
        try {
            gate.run("https://example.org/", emptyMap()) { calls.incrementAndGet(); challenge }
            fail("expected the check to be required")
        } catch (_: SiteCheckRequired) { }
        assertEquals("the request, then once after the pass", 2, calls.get())
        assertNull("a clearance the site does not take is not kept", s.headersFor("example.org"))
    }

    @Test
    fun `a stored clearance goes on later requests to its site and subdomains, not to another host, and a refused one is dropped`() {
        val s = store()
        s.keep(clearanceFor("example.org"))
        val gate = SiteGate(s, solver = null, asker = null)
        val sent = mutableListOf<Map<String, String>>()
        gate.run("https://files.example.org/x", emptyMap(), site(sent))
        assertEquals("cf_clearance=ok", sent.single()["Cookie"])
        assertEquals(ua, sent.single()["User-Agent"])
        val other = mutableListOf<Map<String, String>>()
        gate.run("https://notexample.org/x", emptyMap(), { h -> synchronized(other) { other += h }; ok() })
        assertFalse(other.single().containsKey("Cookie"))
        // the site stops taking it: with nobody to ask, the request fails and the stale clearance is forgotten
        try { gate.run("https://example.org/y", emptyMap()) { challenge } } catch (_: SiteCheckRequired) { }
        assertNull(s.headersFor("example.org"))
    }

    @Test
    fun `the store keeps sites with their dates, forgets one, and survives a restart`() {
        var clock = 100L
        val storage = MemoryStorage()
        val s = SiteClearanceStore(storage) { clock }
        s.keep(Clearance(ua, mapOf("a.org" to "cf_clearance=1")))
        clock = 200L
        s.keep(Clearance(ua, mapOf("b.org" to "cf_clearance=2; __cf_bm=x")))
        assertEquals(listOf("b.org", "a.org"), s.sites().map { it.site })
        assertEquals(listOf(200L, 100L), s.sites().map { it.passedAtMs })
        val restarted = SiteClearanceStore(storage) { clock }
        assertEquals("cf_clearance=2; __cf_bm=x", restarted.headersFor("www.b.org")?.cookie)
        assertEquals(ua, restarted.headersFor("a.org")?.userAgent)
        assertEquals(listOf(200L, 100L), restarted.sites().map { it.passedAtMs })
        restarted.forget("a.org")
        assertNull(restarted.headersFor("a.org"))
        assertNotNull(SiteClearanceStore(storage).headersFor("b.org"))
        assertNull("a forgotten site stays forgotten after a restart", SiteClearanceStore(storage).headersFor("a.org"))
        restarted.forget("b.org")
        assertTrue(SiteClearanceStore(storage).sites().isEmpty())
    }

    @Test
    fun `unreadable stored text is an empty store, not a crash`() {
        assertTrue(SiteClearanceStore(MemoryStorage("not our file\nsite\t1")).sites().isEmpty())
        assertTrue(SiteClearanceStore(MemoryStorage("")).sites().isEmpty())
    }
}
