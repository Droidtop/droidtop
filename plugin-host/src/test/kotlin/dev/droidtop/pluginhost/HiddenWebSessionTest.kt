package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droidtop/tracker#783: a download link's expired page is told by the words the plugin declared, and nothing else. */
class HiddenWebSessionTest {
    private fun webPlugin(entry: JSONObject) = TestPlugins.record(
        TestPlugins.manifest(id = "acme.web") { it.put("permissions", JSONArray().put(entry)) },
    )

    private fun session(vararg expired: String) =
        JSONObject().put("id", "web.session").put("domains", JSONArray().put("forum.example")).put("expiredText", JSONArray(expired.toList()))

    @Test
    fun `a page carrying a declared sentence has expired, whatever its case and spacing`() {
        val declared = listOf("This link has expired")
        assertTrue(HiddenWebSession.expired("Masked link\n\nThis  link has\nEXPIRED. Go back to the thread.", declared))
        assertFalse(HiddenWebSession.expired("Continue to pixeldrain", declared))
    }

    @Test
    fun `with nothing declared no page has expired`() {
        assertFalse(HiddenWebSession.expired("This link has expired", emptyList()))
        assertFalse(HiddenWebSession.expired("anything", listOf("   ")))
    }

    @Test
    fun `the plugin's expired sentences are read from its web session entry, one line each and at most eight`() {
        assertEquals(listOf("This link has expired"), PluginWebAccount.expiredText(webPlugin(session("This link has expired"))))
        assertEquals(emptyList<String>(), PluginWebAccount.expiredText(webPlugin(session("two\nlines", " "))))
        val many = (1..12).map { "sentence $it" }.toTypedArray()
        assertEquals(PluginWebAccount.MAX_EXPIRED_TEXTS, PluginWebAccount.expiredText(webPlugin(session(*many))).size)
        assertEquals(emptyList<String>(), PluginWebAccount.expiredText(webPlugin(JSONObject().put("id", "web.session").put("domains", JSONArray().put("forum.example")))))
    }
}
