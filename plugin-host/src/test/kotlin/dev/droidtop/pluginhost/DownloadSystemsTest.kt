package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The systems a download is for (docs/plugin-api.md 1.10, Droidtop/tracker#617). */
class DownloadSystemsTest {
    @Test
    fun `a forum heading names its systems`() {
        assertEquals(listOf("windows", "linux"), DownloadSystems.fromHeading("Win/Linux"))
        assertEquals(listOf("windows"), DownloadSystems.fromHeading("PC"))
        assertEquals(listOf("windows", "linux", "mac"), DownloadSystems.fromHeading("Win/Linux/Mac"))
        assertEquals(listOf("mac"), DownloadSystems.fromHeading("MAC"))
        assertEquals(listOf("android"), DownloadSystems.fromHeading("Android (APK)"))
        assertEquals(listOf("linux"), DownloadSystems.fromHeading("Linux x64"))
        assertEquals(emptyList<String>(), DownloadSystems.fromHeading("Extras"))
        assertEquals(emptyList<String>(), DownloadSystems.fromHeading("Walkthrough"))
    }

    @Test
    fun `a plugin's systems win over the heading, and only droidtop's names are read`() {
        assertEquals(listOf("linux"), DownloadSystems.of(DownloadSystems.read(JSONArray(listOf("Linux", "beos"))), "Win/Linux"))
        assertEquals(listOf("windows", "linux"), DownloadSystems.of(emptyList(), "Win/Linux"))
    }

    @Test
    fun `only Android has droidtop's notice`() {
        assertEquals("This will not be trackable by us. Windows or Linux is recommended.", DownloadSystems.notice("android"))
        assertNull(DownloadSystems.notice("windows"))
        assertEquals("linux", DownloadSystems.first(listOf("linux", "android")))
    }

    @Test
    fun `a download bundle's entries carry their systems`() {
        val reading = Facets.read(
            JSONObject(
                """{"facets": [{"id": "dl", "kind": "download", "v": 1, "entries": [
                  {"section": "Win/Linux", "host": "MEGA", "label": "MEGA", "link": "0:MEGA"},
                  {"section": "Downloads", "host": "GOFILE", "label": "GOFILE", "link": "1:GOFILE", "systems": ["android"]}
                ]}]}""",
            ),
        )
        val entries = (reading.facets.single().body as FacetBody.Download).entries
        assertEquals(listOf(listOf("windows", "linux"), listOf("android")), entries.map { it.systems })
    }
}
