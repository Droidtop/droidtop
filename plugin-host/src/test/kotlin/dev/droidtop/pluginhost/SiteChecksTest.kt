package dev.droidtop.pluginhost

import dev.droidtop.net.sitecheck.SiteClearanceStore
import dev.droidtop.shared.sitecheck.Clearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A plugin's side of a site that checks the browser first (docs/SPEC.md 12a, Droidtop/tracker#600, #751). Telling a check
 * from a page, the clearance cookies and the pass test are droidtop-shared's `:sitecheck`, with their own tests.
 */
class SiteChecksTest {
    @Test
    fun `a web view's session becomes a clearance of its site, with the view's user agent, and back through the vault's JSON`() {
        val session = WebSessions.Stored("Mozilla/5.0 (Linux; Android 14; wv) Chrome/130", mapOf("f95zone.to" to "xf_user=42; cf_clearance=abc", "example.org" to "sid=1"))
        val clearance = SiteChecks.clearanceFrom(session)!!
        assertEquals(mapOf("f95zone.to" to "cf_clearance=abc"), clearance.cookies)
        assertEquals(session.userAgent, clearance.userAgent)
        assertEquals("cf_clearance=abc", clearance.forHost("attachments.f95zone.to"))
        assertNull("a view that cleared nothing earns nothing", SiteChecks.clearanceFrom(WebSessions.Stored(null, mapOf("example.org" to "sid=1"))))
        assertNull(SiteChecks.clearanceFrom(null))
        assertEquals(clearance, SiteChecks.parse(SiteChecks.toJson(clearance)))
        assertEquals(Clearance("Chrome/131", mapOf("a.org" to "cf_clearance=1")), SiteChecks.parse(SiteChecks.toJson(Clearance("Chrome/131", mapOf("a.org" to "cf_clearance=1")))))
    }

    @Test
    fun `a check met is remembered until a request to that site gets through`() {
        SiteChecks.met("test.plugin", "https://f95zone.to/threads/1/")
        assertEquals("https://f95zone.to/threads/1/", SiteChecks.pendingFor("test.plugin"))
        SiteChecks.passed("test.plugin", "api.f95checker.dev")
        assertEquals("another site getting through says nothing", "https://f95zone.to/threads/1/", SiteChecks.pendingFor("test.plugin"))
        SiteChecks.passed("test.plugin", "f95zone.to")
        assertNull(SiteChecks.pendingFor("test.plugin"))
    }

    @Test
    fun `a clearance an earlier build kept in a plugin's vault moves into the shared store, and a second plugin then sends it too`() {
        val store = SiteClearanceStore(object : SiteClearanceStore.Storage {
            override fun read(): String? = null
            override fun write(text: String) {}
        })
        val legacy = SiteChecks.toJson(Clearance("Chrome/131", mapOf("f95zone.to" to "cf_clearance=abc")))
        assertEquals(true, SiteChecks.adopt(legacy, store))
        assertEquals("cf_clearance=abc", store.headersFor("attachments.f95zone.to")?.cookie)
        assertEquals("Chrome/131", store.headersFor("f95zone.to")?.userAgent)
        assertEquals("nothing kept is nothing to move", false, SiteChecks.adopt(null, store))
        assertEquals("unreadable text is nothing to move", false, SiteChecks.adopt("not json", store))
    }

    @Test
    fun `the plugin gets one plain sentence, never the page`() {
        assertEquals("f95zone.to checks the browser first; open it in droidtop to continue", SiteChecks.refusal("f95zone.to"))
    }
}
