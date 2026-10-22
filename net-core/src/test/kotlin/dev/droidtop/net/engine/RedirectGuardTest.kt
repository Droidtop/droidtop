package dev.droidtop.net.engine

import java.io.IOException
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Credentials stay on the site they were made for, as the earlier downloader kept them (Droidtop/tracker#417). */
class RedirectGuardTest {
    private class Net(vararg routes: Pair<String, RedirectGuard.Answer>) {
        private val routes = routes.toMap()
        val asked = mutableListOf<Triple<String, Map<String, String>, String>>()

        fun ask(url: String, headers: Map<String, String>, method: String): RedirectGuard.Answer {
            asked += Triple(url, headers, method)
            return routes[url] ?: RedirectGuard.Answer(200, null)
        }
    }

    private val identifying = mapOf("Cookie" to "s=1", "Referer" to "https://a.test/page", "Accept" to "*/*")

    private fun redirect(to: String, status: Int = 302) = RedirectGuard.Answer(status, to)

    @Test
    fun aPluginsDownloadIsWalkedAndEveryHopMustBeOnItsSites() {
        val net = Net("https://a.test/f" to redirect("https://cdn.a.test/f"), "https://cdn.a.test/f" to redirect("https://evil.test/x"))
        val allowed = { url: String -> if (!java.net.URL(url).host.endsWith("a.test")) throw java.io.IOException("not on the plugin's sites: $url") }
        try {
            RedirectGuard.resolve("https://a.test/f", emptyMap(), net::ask, allowed)
            org.junit.Assert.fail("a hop off the plugin's sites must be refused")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("evil.test"))
        }
        assertTrue("the refused address is never asked", net.asked.none { it.first.contains("evil.test") })
        val ok = Net("https://a.test/f" to redirect("https://cdn.a.test/f"))
        assertEquals("https://cdn.a.test/f", RedirectGuard.resolve("https://a.test/f", emptyMap(), ok::ask, allowed).url)
    }

    @Test
    fun aRequestWithNothingToKeepBackIsNotWalked() {
        val net = Net()
        val resolved = RedirectGuard.resolve("https://a.test/f", mapOf("Accept" to "*/*"), net::ask)
        assertEquals("https://a.test/f", resolved.url)
        assertTrue(net.asked.isEmpty())
    }

    @Test
    fun aRedirectOnTheSameSiteKeepsEveryHeader() {
        val net = Net("https://a.test/f" to redirect("/g"))
        val resolved = RedirectGuard.resolve("https://a.test/f", identifying, net::ask)
        assertEquals("https://a.test/g", resolved.url)
        assertEquals(identifying, resolved.headers)
    }

    @Test
    fun aRedirectToAnotherHostDropsTheIdentifyingHeaders() {
        val net = Net("https://a.test/f" to redirect("https://cdn.test/f?sig=9"))
        val resolved = RedirectGuard.resolve("https://a.test/f", identifying + ("Authorization" to "Bearer t"), net::ask)
        assertEquals("https://cdn.test/f?sig=9", resolved.url)
        assertEquals(mapOf("Accept" to "*/*"), resolved.headers)
        // The first host got everything; the second was asked with none of them.
        assertEquals(setOf("Cookie", "Referer", "Accept", "Authorization"), net.asked.first().second.keys)
        assertFalse("Cookie" in net.asked.last().second)
    }

    @Test
    fun aStepDownFromHttpsDropsThemAndNothingComesBack() {
        val net = Net("https://a.test/f" to redirect("http://a.test/f"), "http://a.test/f" to redirect("https://a.test/back"))
        val resolved = RedirectGuard.resolve("https://a.test/f", identifying, net::ask)
        assertEquals("https://a.test/back", resolved.url)
        assertEquals(mapOf("Accept" to "*/*"), resolved.headers)
    }

    @Test
    fun aServerThatRefusesHeadIsAskedWithAOneByteGet() {
        val net = Net()
        val answers = ArrayDeque(listOf(RedirectGuard.Answer(405, null), redirect("https://cdn.test/x")))
        val resolved = RedirectGuard.resolve("https://a.test/f", identifying) { url, headers, method ->
            net.asked += Triple(url, headers, method)
            if (url == "https://a.test/f") answers.removeFirst() else RedirectGuard.Answer(200, null)
        }
        assertEquals(listOf("HEAD", "GET", "HEAD"), net.asked.map { it.third })
        assertEquals("https://cdn.test/x", resolved.url)
    }

    @Test
    fun anErrorAnswerEndsTheWalkAtThatAddress() {
        val net = Net("https://a.test/f" to RedirectGuard.Answer(403, null))
        val resolved = RedirectGuard.resolve("https://a.test/f", identifying, net::ask)
        assertEquals("https://a.test/f", resolved.url)
        assertEquals(identifying, resolved.headers)
    }

    @Test
    fun aRedirectLoopFails() {
        val net = Net("https://a.test/f" to redirect("https://a.test/f"))
        try {
            RedirectGuard.resolve("https://a.test/f", identifying, net::ask)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("more than 5 redirects"))
        }
    }

    @Test
    fun carryComparesHostsWithoutCase() {
        val kept = RedirectGuard.carry(identifying, URL("https://A.test/x"), URL("https://a.TEST/y"))
        assertEquals(identifying, kept)
    }
}
