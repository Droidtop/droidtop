package dev.droidtop.pluginhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop-mode facets (Droidtop/tracker#221): the window and file subjects, `desktop.window`, and icons on more kinds. */
class DesktopFacetsTest {
    private val window = FacetSubject.Window(42L, "org.mozilla.firefox")

    private val plugin = FacetGuards.Plugin(
        id = "sendto",
        declaredTargets = setOf("desktop.window", "desktop.tray", "files.action"),
        domains = emptyList(),
        permissions = setOf("windows.read"),
    )

    private fun read(json: String, subject: FacetSubject = FacetSubject.Plugin) = Facets.read(JSONObject(json), subject)

    @Test
    fun `window and file subjects are read, and incomplete ones are not`() {
        assertEquals(window, Facets.subjectOf(JSONObject("""{"type": "window", "windowId": 42, "appId": "org.mozilla.firefox"}""")))
        assertEquals(FacetSubject.File("tok1", "text/plain"), Facets.subjectOf(JSONObject("""{"type": "file", "token": "tok1", "mime": "text/plain"}""")))
        assertNull(Facets.subjectOf(JSONObject("""{"type": "window", "appId": "x"}""")))
        assertNull(Facets.subjectOf(JSONObject("""{"type": "file", "token": "tok1"}""")))
        assertEquals("window", window.type)
    }

    @Test
    fun `a window menu entry about the asked window is taken`() {
        val reading = read("""{"facets": [
            {"id": "send", "kind": "action", "targets": ["desktop.window"], "label": "Send to phone", "icon": "send", "call": {"op": "send"}}
        ]}""", window)
        val taken = FacetGuards.admit(reading.facets, "desktop.window", plugin, asked = window)
        assertEquals(listOf("send"), taken.map { it.id })
        assertEquals("send", (taken.single().body as FacetBody.Action).icon)
    }

    @Test
    fun `a facet about a window droidtop did not ask about is dropped`() {
        val reading = read("""{"facets": [
            {"id": "other", "kind": "action", "targets": ["desktop.window"], "label": "Close it",
             "subject": {"type": "window", "windowId": 7, "appId": "org.gnome.Terminal"}, "call": {"op": "x"}}
        ]}""", window)
        val messages = mutableListOf<String>()
        assertTrue(FacetGuards.admit(reading.facets, "desktop.window", plugin, messages, asked = window).isEmpty())
        assertTrue(messages.single().contains("did not ask about"))
    }

    @Test
    fun `desktop window needs windows read, and takes no kind beyond its own`() {
        val reading = read("""{"facets": [
            {"id": "a", "kind": "action", "targets": ["desktop.window"], "label": "Go", "call": {"op": "x"}},
            {"id": "m", "kind": "meter", "targets": ["desktop.window"], "label": "CPU", "value": 1, "max": 2}
        ]}""", window)
        assertEquals(listOf("a"), FacetGuards.admit(reading.facets, "desktop.window", plugin, asked = window).map { it.id })
        val messages = mutableListOf<String>()
        assertTrue(FacetGuards.admit(reading.facets, "desktop.window", plugin.copy(permissions = emptySet()), messages, asked = window).isEmpty())
        assertTrue(messages.single().contains("windows.read"))
    }

    @Test
    fun `files action is about the picked file, no longer about the plugin`() {
        val file = FacetSubject.File("tok1", "image/png")
        val action = """{"facets": [{"id": "open", "kind": "action", "targets": ["files.action"], "label": "Open in Viewer", "call": {"op": "open"}}]}"""
        assertEquals(listOf("open"), FacetGuards.admit(read(action, file).facets, "files.action", plugin, asked = file).map { it.id })
        assertTrue(FacetGuards.admit(read(action).facets, "files.action", plugin).isEmpty())
    }

    @Test
    fun `status, badge and toggle carry an icon from droidtop's set, and an unknown name is left out`() {
        val reading = read("""{"facets": [
            {"id": "s", "kind": "status", "targets": ["desktop.tray"], "label": "VPN", "state": "on", "icon": "vpn"},
            {"id": "b", "kind": "badge", "targets": ["desktop.tray"], "text": "3", "icon": "notification"},
            {"id": "t", "kind": "toggle", "targets": ["desktop.tray"], "label": "Sync", "value": true, "icon": "sync", "call": {"op": "sync"}},
            {"id": "x", "kind": "status", "targets": ["desktop.tray"], "label": "Odd", "state": "on", "icon": "https://example.com/a.png"}
        ]}""")
        assertEquals("vpn", (reading.facets[0].body as FacetBody.Status).icon)
        assertEquals("notification", (reading.facets[1].body as FacetBody.Badge).icon)
        assertEquals("sync", (reading.facets[2].body as FacetBody.Toggle).icon)
        assertNull((reading.facets[3].body as FacetBody.Status).icon)
        assertTrue(reading.messages.single().contains("icon set"))
    }

    @Test
    fun `desktop window has a cap and a label`() {
        assertEquals(3, PluginViewLimits.facetsPerTarget("desktop.window"))
        assertEquals("Desktop window menus", FacetGuards.TARGET_LABELS["desktop.window"])
    }
}
