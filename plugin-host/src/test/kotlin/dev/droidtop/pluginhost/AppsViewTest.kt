package dev.droidtop.pluginhost

import dev.droidtop.pluginhost.TestPlugins.arr
import dev.droidtop.pluginhost.TestPlugins.obj
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `apps.view` (docs/plugin-api.md 3 F2, Droidtop/tracker#418): which links, and only during a call the person started. */
class AppsViewTest {
    private val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Some+Game+%5BRPG%5D"

    private fun request(args: JSONObject) = obj("api" to "apps", "version" to 1, "op" to "view", "args" to args).toString()

    private fun reply(text: String) = PluginReply.parse(text)

    private fun plugin(declared: Boolean = true) = TestPlugins.record(
        TestPlugins.manifest(id = "acme.links", label = "Links") {
            if (declared) it.put("permissions", arr(obj("id" to "apps.view")))
        },
    )

    private fun envWith(record: PluginRecord, user: Boolean = true) = FakeEnv(record).also {
        it.user = user
        it.setGrant(record.manifest.id, "apps.view", GrantState.GRANTED)
    }

    @Test
    fun `only web pages and magnet links are allowed`() {
        assertNull(AppLinks.refusal("https://nyaa.si/view/123"))
        assertNull(AppLinks.refusal("http://example.com/page?x=1"))
        assertNull(AppLinks.refusal(magnet))
        assertNotNull(AppLinks.refusal("file:///sdcard/Download/a.torrent"))
        assertNotNull(AppLinks.refusal("content://com.example/x"))
        assertNotNull(AppLinks.refusal("intent://scan/#Intent;scheme=zxing;end"))
        assertNotNull(AppLinks.refusal("javascript:alert(1)"))
        assertNotNull(AppLinks.refusal("droidtop://plugin/x"))
        assertNotNull(AppLinks.refusal("no scheme here"))
        assertNotNull(AppLinks.refusal("https://"))
        assertNotNull(AppLinks.refusal("magnet:?dn=just+a+name"))
        assertNotNull(AppLinks.refusal("https://a.example/ b"))
        assertNotNull(AppLinks.refusal("https://a.example/" + "x".repeat(AppLinks.MAX_LENGTH)))
        assertNotNull(AppLinks.refusal("   "))
    }

    @Test
    fun `the log names the kind of link and a page's host and never a magnet's contents`() {
        assertEquals("magnet", AppLinks.target(magnet))
        assertEquals("https://nyaa.si", AppLinks.target("https://nyaa.si/view/123?q=secret"))
        assertEquals("", AppLinks.target("nonsense"))
    }

    @Test
    fun `a granted plugin in a call the person started opens a magnet link through the chooser`() {
        val env = envWith(plugin())
        val r = reply(BrokerCore("acme.links", env).call(request(obj("uri" to magnet, "chooser" to true, "title" to "Open in a torrent app"))))
        assertTrue(r.ok)
        assertTrue(r.data.getBoolean("opened"))
        assertEquals(listOf(magnet to "Open in a torrent app"), env.links.toList())
    }

    @Test
    fun `no app taking the link is an answer with a reason and not an error`() {
        val env = envWith(plugin()).also { it.linkRefusal = "no app opens magnet links" }
        val r = reply(BrokerCore("acme.links", env).call(request(obj("uri" to magnet))))
        assertTrue(r.ok)
        assertFalse(r.data.getBoolean("opened"))
        assertEquals("no app opens magnet links", r.data.getString("reason"))
    }

    @Test
    fun `other schemes are refused before any app is asked`() {
        val env = envWith(plugin())
        val r = reply(BrokerCore("acme.links", env).call(request(obj("uri" to "file:///sdcard/x.torrent"))))
        assertEquals(PluginErrorCode.INVALID_ARGS, r.code)
        assertTrue(env.links.isEmpty())
    }

    @Test
    fun `outside a call the person started nothing opens`() {
        val env = envWith(plugin(), user = false)
        val r = reply(BrokerCore("acme.links", env).call(request(obj("uri" to magnet))))
        assertFalse(r.ok)
        assertTrue(env.links.isEmpty())
    }

    @Test
    fun `a plugin that never declared the permission cannot open links`() {
        val env = FakeEnv(plugin(declared = false)).also { it.user = true }
        val r = reply(BrokerCore("acme.links", env).call(request(obj("uri" to magnet))))
        assertEquals(PluginErrorCode.PERMISSION_DENIED, r.code)
        assertTrue(env.links.isEmpty())
    }

    @Test
    fun `every use is written to the activity log whatever the tier`() {
        val env = envWith(plugin())
        BrokerCore("acme.links", env).call(request(obj("uri" to "https://nyaa.si/view/123?q=secret")))
        val entry = env.audits.single().second
        assertTrue(entry.toString().contains("https://nyaa.si"))
        assertFalse(entry.toString().contains("secret"))
    }
}
