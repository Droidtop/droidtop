package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A site that checks the browser first, and the clearance passing it earns (docs/SPEC.md 12a, Droidtop/tracker#600). */
class SiteChecksTest {
    private fun page(text: String) = text.toByteArray()

    @Test
    fun `a challenge is told from the page by what F95Checker looks for, and by Cloudflare's own header`() {
        val challenge = page("<!DOCTYPE html><html><head><title>Just a moment...</title></head><script>(function(){window._cf_chl_opt = {")
        assertTrue(SiteChecks.isChallenge(403, emptyMap(), challenge))
        assertTrue(SiteChecks.isChallenge(503, emptyMap(), page("<script src=\"/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1\">")))
        assertTrue(SiteChecks.isChallenge(403, mapOf("CF-Mitigated" to "challenge"), page("")))
        assertTrue(SiteChecks.isChallenge(403, emptyMap(), page("<title>DDoS-Guard</title>")))
    }

    @Test
    fun `a page that only mentions a check, or an ordinary refusal, is not one`() {
        assertFalse("a thread that quotes the words is a page", SiteChecks.isChallenge(200, emptyMap(), page("<title>Just a moment...</title>")))
        assertFalse(SiteChecks.isChallenge(403, emptyMap(), page("<title>Forbidden</title>")))
        assertFalse(SiteChecks.isChallenge(404, mapOf("server" to "cloudflare"), page("Not found")))
    }

    @Test
    fun `only a check's clearance cookies are kept, never an account's`() {
        assertEquals("cf_clearance=abc; __cf_bm=x", SiteChecks.clearanceOf("xf_user=42; cf_clearance=abc; xf_session=s; __cf_bm=x"))
        assertNull(SiteChecks.clearanceOf("xf_user=42; xf_session=s"))
        assertTrue(SiteChecks.isClearanceCookie("__ddg1_"))
        assertFalse(SiteChecks.isClearanceCookie("xf_user"))
    }

    @Test
    fun `a request's own cookie keeps its value, and the clearance's are added`() {
        assertEquals("xf_user=42; cf_clearance=new", SiteChecks.mergeCookies("xf_user=42", "cf_clearance=new"))
        assertEquals("cf_clearance=mine", SiteChecks.mergeCookies("cf_clearance=mine", "cf_clearance=new"))
        assertEquals("cf_clearance=new", SiteChecks.mergeCookies(null, "cf_clearance=new"))
        assertNull(SiteChecks.mergeCookies(null, null))
    }

    @Test
    fun `a web view's clearance covers its site and the site's subdomains, with the view's user agent`() {
        val session = WebSessions.Stored("Mozilla/5.0 (Linux; Android 14; wv) Chrome/130", mapOf("f95zone.to" to "xf_user=42; cf_clearance=abc", "example.org" to "sid=1"))
        val clearance = SiteChecks.Clearance.from(session)!!
        assertEquals(mapOf("f95zone.to" to "cf_clearance=abc"), clearance.cookies)
        assertEquals("cf_clearance=abc", clearance.forHost("attachments.f95zone.to"))
        assertNull(clearance.forHost("notf95zone.to"))
        assertEquals(session.userAgent, clearance.userAgent)
        assertNull("a view that cleared nothing earns nothing", SiteChecks.Clearance.from(WebSessions.Stored(null, mapOf("example.org" to "sid=1"))))
        val newer = clearance.plus(SiteChecks.Clearance("Chrome/131", mapOf("f95zone.to" to "cf_clearance=def")))
        assertEquals("cf_clearance=def", newer.forHost("f95zone.to"))
        assertEquals("Chrome/131", newer.userAgent)
        assertEquals(clearance, SiteChecks.Clearance.parse(clearance.toJson()))
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
    fun `the plugin gets one plain sentence, never the page`() {
        assertEquals("f95zone.to checks the browser first; open it in droidtop to continue", SiteChecks.refusal("f95zone.to"))
    }
}
