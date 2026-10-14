package dev.droidtop.pluginhost

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin `notice` node and the error shape (docs/plugin-api.md 1.3 and 1.6, docs/SPEC.md 7k "Notices";
 * Droidtop/tracker#485, final design tests 3 and 5): read into typed notices with every limit from the limits file,
 * moved, dropped, cut, demoted and refused as the rules say, each change said once as a plugin message.
 */
class PluginViewNoticeTest {
    private fun section(id: String, vararg items: JSONObject) = JSONObject().put("id", id).put("items", JSONArray(items.toList()))

    private fun view(vararg sections: JSONObject): JSONObject = JSONObject().put("view", 1).put("sections", JSONArray(sections.toList()))

    private fun notice(id: String, tone: String = "caution", title: String = "Sign in to download", vararg kv: Pair<String, Any>) =
        JSONObject().put("type", "notice").put("id", id).put("tone", tone).put("title", title).also { o -> kv.forEach { (k, v) -> o.put(k, v) } }

    private fun info(id: String) = JSONObject().put("type", "info").put("id", id).put("title", "Info $id")

    @Test
    fun `a notice reads into a typed notice with its action`() {
        val messages = mutableListOf<String>()
        val parsed = PluginView.parse(
            view(section("s", notice("n", "caution", "Sign in to download", "subtitle" to "Downloads are for members.", "details" to "Members only.", "action" to JSONObject().put("kind", "view").put("op", "signin").put("title", "Sign in")))),
            messages,
        )!!
        val n = parsed.sections.single().nodes.single() as ViewNode.Notice
        assertEquals("caution", n.tone)
        assertEquals("Downloads are for members.", n.subtitle)
        assertEquals("Members only.", n.details)
        assertEquals("Sign in", n.action?.title)
        assertFalse(n.banner)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `an unknown tone is drawn as info and said once`() {
        val messages = mutableListOf<String>()
        val n = PluginView.parse(view(section("s", notice("n", "danger"))), messages)!!.sections.single().nodes.single() as ViewNode.Notice
        assertEquals("info", n.tone)
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("danger"))
    }

    @Test
    fun `notices go first, two a section of different tones, three a view`() {
        val messages = mutableListOf<String>()
        val parsed = PluginView.parse(
            view(
                section("a", info("i1"), notice("c1", "caution"), notice("c2", "caution"), notice("i2", "info")),
                section("b", notice("c3", "caution"), notice("i3", "info")),
            ),
            messages,
        )!!
        val a = parsed.sections[0].nodes
        assertEquals(listOf("c1", "i2", "i1"), a.map { it.id })
        val b = parsed.sections[1].nodes
        // The view's third notice is kept, its fourth dropped.
        assertEquals(listOf("c3"), b.map { it.id })
        assertTrue(messages.any { it.contains("moved") })
        assertTrue(messages.any { it.contains("\"c2\"") && it.contains("differ in tone") })
        assertTrue(messages.any { it.contains("\"i3\"") && it.contains("in a view") })
    }

    @Test
    fun `one banner a page, the next drawn in its section`() {
        val messages = mutableListOf<String>()
        val parsed = PluginView.parse(
            view(section("a", notice("b1", "caution", "Sign in to download", "placement" to "banner")), section("b", notice("b2", "info", "Downloads open in your browser", "placement" to "banner"))),
            messages,
        )!!
        assertTrue((parsed.sections[0].nodes.single() as ViewNode.Notice).banner)
        assertFalse((parsed.sections[1].nodes.single() as ViewNode.Notice).banner)
        assertTrue(messages.any { it.contains("one banner a page") })
    }

    @Test
    fun `lengths are cut at the limits file's numbers and said`() {
        val messages = mutableListOf<String>()
        val n = PluginView.parse(
            view(section("s", notice("n", "info", "T".repeat(100), "subtitle" to "S".repeat(300), "details" to "D".repeat(900)))),
            messages,
        )!!.sections.single().nodes.single() as ViewNode.Notice
        assertEquals(60, n.title.length)
        assertEquals(140, n.subtitle!!.length)
        assertEquals(500, n.details!!.length)
        assertEquals(3, messages.size)
    }

    @Test
    fun `a reserved title is drawn as a plain row`() {
        val messages = mutableListOf<String>()
        val node = PluginView.parse(view(section("s", notice("n", "caution", "has FULL access"))), messages)!!.sections.single().nodes.single()
        assertTrue(node is ViewNode.Info)
        assertTrue(messages.single().contains("droidtop's own title"))
    }

    @Test
    fun `a reply notice replaces the message`() {
        val data = JSONObject().put("message", "Signed in").put("notice", JSONObject().put("tone", "info").put("title", "Signed in"))
        val n = PluginViewCall.replyNotice(data)
        assertEquals("Signed in", n?.title)
        assertNull(PluginViewCall.replyNotice(JSONObject().put("message", "Done")))
    }

    @Test
    fun `PERMISSION_DENIED opens Permissions only for a declared permission`() {
        val reply = PluginReply.parse(
            JSONObject().put("ok", false).put(
                "error",
                JSONObject().put("code", "PERMISSION_DENIED").put("message", "net.any refused").put("title", "Not allowed").put("permission", "net.any"),
            ).toString(),
        )
        assertEquals("net.any", reply.permission)
        assertTrue(PluginViewCall.errorNotice(reply, setOf("net.any"), "Couldn't finish").opensPermissions)
        val messages = mutableListOf<String>()
        val undeclared = PluginViewCall.errorNotice(reply, emptySet(), "Couldn't finish", messages)
        assertFalse(undeclared.opensPermissions)
        assertEquals("caution", undeclared.tone)
        assertEquals(1, messages.size)
    }

    @Test
    fun `SIGN_IN_REQUIRED offers its action as Sign in, any other code is a caution notice`() {
        val reply = PluginReply.error(
            PluginErrorCode.SIGN_IN_REQUIRED, "Sign in to search", action = JSONObject().put("kind", "view").put("op", "signin"),
        )
        val back = PluginReply.parse(reply.toJson().toString())
        val n = PluginViewCall.errorNotice(back, emptySet(), "Couldn't finish")
        assertEquals("Sign in", n.action?.title)
        assertEquals("Sign in to search", n.title)
        val other = PluginViewCall.errorNotice(PluginReply.error(PluginErrorCode.FAILED, "no network"), emptySet(), "Couldn't finish")
        assertEquals("Couldn't finish", other.title)
        assertEquals("no network", other.subtitle)
    }

    @Test
    fun `every call carries the view features`() {
        val call = PluginCall("c", 1000L, "library.sources", 1, "search")
        val features = call.toJson().getJSONObject("host").getJSONArray("viewFeatures")
        assertTrue((0 until features.length()).any { features.getString(it) == "notice" })
    }

    @Test
    fun `the schema has the notice node with no danger tone, and the limits file is the one source`() {
        val schemaFile = listOf(File("../docs/plugin-view.schema.json"), File("docs/plugin-view.schema.json")).first { it.exists() }
        val schema = JSONObject(schemaFile.readText())
        val arms = schema.getJSONObject("\$defs").getJSONObject("item").getJSONArray("oneOf")
        val noticeArm = (0 until arms.length()).map { arms.getJSONObject(it) }
            .single { it.getJSONObject("properties").getJSONObject("type").optString("const") == "notice" }
        val tones = noticeArm.getJSONObject("properties").getJSONObject("tone").getJSONArray("enum")
        assertEquals(listOf("info", "caution"), (0 until tones.length()).map { tones.getString(it) })
        // The packaged limits are the file in docs, not a copy.
        val limitsFile = listOf(File("../docs/view-limits/plugin-view-limits.json"), File("docs/view-limits/plugin-view-limits.json")).first { it.exists() }
        val limits = JSONObject(limitsFile.readText())
        assertEquals(limits.getJSONObject("lengths").getInt("noticeTitle"), PluginViewLimits.length("noticeTitle"))
        assertEquals(PluginViewLimits.tones, setOf("info", "caution"))
        assertTrue("has full access" in PluginViewLimits.reservedTitles)
    }
}
