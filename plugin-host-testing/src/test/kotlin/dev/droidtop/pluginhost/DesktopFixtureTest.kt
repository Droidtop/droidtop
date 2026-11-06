package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Desktop-mode fixture (src/test/fixtures/desktop, docs/plugin-api.md 1.12, Droidtop/tracker#221) against
 * droidtop's side of each hook: its manifest declares what each needs, its facets pass the guards of both Desktop
 * targets, and the host ops it calls answer it through the broker. desktop_fixture_check.py drives its plugin.py.
 */
class DesktopFixtureTest {
    private val dir = File("src/test/fixtures/desktop")
    private val manifest = PluginManifest.fromJson(JSONObject(File(dir, "manifest.json").readText()))!!
    private val record = TestPlugins.record(manifest)
    private val replies = JSONObject(File(dir, "replies.json").readText())

    private val window = DesktopWindow(7L, "foot", "~", "HEADLESS-1", focused = false)

    private class Session(val list: List<DesktopWindow>) : DesktopWindowControl {
        val focused = mutableListOf<Long>()
        override fun windows() = list
        override fun focus(id: Long) = list.any { it.id == id }.also { if (it) focused += id }
        override fun close(id: Long) = false
    }

    private class Clipboard : DesktopClipboard {
        var held: String? = "copied"
        override fun text() = held
        override fun setText(text: String) = true.also { held = text }
    }

    private fun guards() = FacetGuards.Plugin(
        id = manifest.id,
        declaredTargets = FacetGuards.declaredTargets(manifest.v2.provides),
        domains = emptyList(),
        permissions = manifest.v2.permissions.map { it.id }.toSet(),
    )

    @After
    fun noDesktop() {
        DesktopPluginBridge.windows = null
        DesktopPluginBridge.clipboard = null
    }

    @Test
    fun `the manifest declares every Desktop hook`() {
        assertEquals(setOf("desktop.tray", "desktop.window"), FacetGuards.declaredTargets(manifest.v2.provides))
        val handler = manifest.v2.provides.single { it.point == FileHandlers.POINT }
        assertEquals("Inspect with Desktop fixture", FileHandlers.match(handler, "Krita.AppImage", null)?.label)
        listOf(PluginEvent.WINDOW_OPENED, PluginEvent.WINDOW_CLOSED, PluginEvent.WINDOW_FOCUSED, PluginEvent.CLIPBOARD_CHANGED)
            .forEach { assertTrue(it.id, manifest.subscribesTo(it)) }
        val declared = manifest.v2.permissions.map { it.id }.toSet()
        assertTrue(declared.containsAll(setOf("windows.read", "windows.control", "clipboard.read", "clipboard.write", "notify.post", "files.picker")))
    }

    @Test
    fun `its tray and window facets all pass the guards, with icons from droidtop's set`() {
        val messages = mutableListOf<String>()
        val tray = Facets.read(replies.getJSONObject("desktop.tray"), FacetSubject.Device)
        assertEquals(5, FacetGuards.admit(tray.facets, "desktop.tray", guards(), messages, asked = FacetSubject.Device).size)
        val subject = FacetSubject.Window(window.id, window.appId)
        val menu = Facets.read(replies.getJSONObject("desktop.window"), subject)
        assertEquals(listOf("window.focus", "window.badge"), FacetGuards.admit(menu.facets, "desktop.window", guards(), messages, asked = subject).map { it.id })
        assertTrue(messages.joinToString(), (tray.messages + menu.messages + messages).isEmpty())
    }

    @Test
    fun `the host ops it calls answer it once allowed`() {
        val session = Session(listOf(window))
        val clipboard = Clipboard()
        DesktopPluginBridge.windows = session
        DesktopPluginBridge.clipboard = clipboard
        val env = FakeEnv(record).also { e ->
            e.user = true
            listOf("windows.read", "windows.control", "clipboard.read", "clipboard.write").forEach { e.setGrant(manifest.id, it, GrantState.GRANTED) }
        }
        val broker = BrokerCore(manifest.id, env)
        fun call(api: String, op: String, args: JSONObject = JSONObject()) =
            PluginReply.parse(broker.call(JSONObject().put("api", api).put("version", 1).put("op", op).put("args", args).toString()))

        val listed = call("windows", "list")
        assertEquals(7L, listed.data.getJSONArray("windows").getJSONObject(0).getLong("id"))
        assertTrue(call("windows", "focus", JSONObject().put("id", 7L)).ok)
        assertEquals(listOf(7L), session.focused)
        assertEquals("copied", call("clipboard", "get_text").data.getString("text"))
        assertTrue(call("clipboard", "set_text", JSONObject().put("text", "droidtop desktop fixture")).ok)
        assertEquals("droidtop desktop fixture", clipboard.held)
        assertNotNull(env.audits.firstOrNull { (_, e) -> e.api == "clipboard" && e.op == "set_text" })
    }
}
