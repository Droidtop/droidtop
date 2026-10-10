package dev.droidtop.pluginhost

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The links a plugin may register and how they match (docs/plugin-api.md 3 F3, Droidtop/tracker#459). */
class PluginLinksTest {
    private fun entry(vararg links: JSONObject) =
        ProvidedPoint(point = "apps.catalog", extra = JSONObject().put("links", JSONArray(links.toList())).toString())

    @Test
    fun `an F-Droid plugin's patterns match the repository links F-Droid's own client answers`() {
        val patterns = PluginLinks.declared(
            entry(
                JSONObject().put("scheme", "fdroidrepos"),
                JSONObject().put("scheme", "https").put("pathSuffix", "/fdroid/repo"),
                JSONObject().put("scheme", "https").put("host", "fdroid.link"),
            ),
        )
        assertEquals(3, patterns.size)
        fun matched(link: String) = patterns.any { it.matches(URI(link)) }
        assertTrue(matched("fdroidrepos://apt.izzysoft.de/fdroid/repo?fingerprint=3BF0D6ABFEAE2F401707B6D966BE743BF0EEE49C2561B9BA39073711F628937A"))
        assertTrue(matched("FDROIDREPOS://example.org/fdroid/repo"))
        assertTrue(matched("https://apt.izzysoft.de/fdroid/repo?fingerprint=3bf0"))
        assertTrue(matched("https://example.org/fdroid/repo/"))
        assertTrue(matched("https://fdroid.link/#https://example.org/fdroid/repo"))
        assertFalse(matched("https://example.org/fdroid/repository"))
        assertFalse(matched("https://example.org/"))
    }

    @Test
    fun `a plugin may claim named sites and other schemes, never all of the web or droidtop's own scheme`() {
        assertFalse(PluginLinks.supported(LinkPattern("droidtop", "plugin", pathPrefix = "/droidtop.fdroid"), "droidtop.fdroid"))
        assertFalse(PluginLinks.supported(LinkPattern("droidtop", "add-catalog"), "droidtop.fdroid"))
        assertFalse(PluginLinks.supported(LinkPattern("https"), "droidtop.fdroid"))
        assertFalse(PluginLinks.supported(LinkPattern("https", "*"), "droidtop.fdroid"))
        assertFalse(PluginLinks.supported(LinkPattern("https", "droidtop.github.io"), "droidtop.fdroid"))
        assertTrue(PluginLinks.supported(LinkPattern("https", "f95zone.to"), "x.f95"))
        assertTrue(PluginLinks.supported(LinkPattern("https", pathSuffix = "/fdroid/repo"), "droidtop.fdroid"))
        assertTrue(PluginLinks.supported(LinkPattern("fdroidrepos"), "droidtop.fdroid"))
    }

    @Test
    fun `a pattern's host covers its subdomains only when it says so, and a prefix is a whole path segment`() {
        val sub = LinkPattern("https", "*.example.org")
        assertTrue(sub.matches(URI("https://a.example.org/x")))
        assertTrue(sub.matches(URI("https://example.org/x")))
        assertFalse(sub.matches(URI("https://badexample.org/x")))
        val prefix = LinkPattern("https", "example.org", pathPrefix = "/apps")
        assertTrue(prefix.matches(URI("https://example.org/apps/x")))
        assertFalse(prefix.matches(URI("https://example.org/appsx")))
    }

    @Test
    fun `query parameters are read by name ignoring case`() {
        val uri = URI("https://example.org/fdroid/repo?FINGERPRINT=AB%20CD&x=1")
        assertEquals("AB CD", PluginLinks.queryParameter(uri, "fingerprint"))
        assertEquals(null, PluginLinks.queryParameter(uri, "missing"))
    }

    @Test
    fun `only manifest schemes are switched, and unreadable patterns are dropped`() {
        val patterns = PluginLinks.declared(entry(JSONObject().put("scheme", "Bad Scheme!"), JSONObject().put("scheme", "magnet")))
        assertEquals(listOf(LinkPattern("magnet")), patterns)
        assertTrue("fdroidrepos" in PluginLinks.MANIFEST_SCHEMES)
        assertFalse("https" in PluginLinks.MANIFEST_SCHEMES)
    }
}
