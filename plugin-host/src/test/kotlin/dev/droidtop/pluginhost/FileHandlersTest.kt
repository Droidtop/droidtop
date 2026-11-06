package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Open with, for plugins (docs/plugin-api.md 1.12, catalogue E4, Droidtop/tracker#221): which files a handler takes. */
class FileHandlersTest {
    private fun entry(vararg associations: JSONObject, point: String = FileHandlers.POINT) =
        ProvidedPoint(point = point, extra = JSONObject().put("associations", JSONArray(associations.toList())).toString())

    private fun assoc(ext: String? = null, mime: String? = null, label: String = "Inspect") =
        JSONObject().apply { ext?.let { put("ext", it) }; mime?.let { put("mime", it) }; put("label", label) }

    @Test
    fun `an extension matches ignoring case and a leading dot`() {
        val handler = entry(assoc(ext = ".AppImage", label = "Inspect with Acme"))
        assertEquals("Inspect with Acme", FileHandlers.match(handler, "Krita-5.2.appimage", null)?.label)
        assertNull(FileHandlers.match(handler, "Krita.deb", null))
        assertNull("a name with no extension", FileHandlers.match(handler, "AppImage", null))
    }

    @Test
    fun `a mime type matches exactly or by its top-level type, and a bare star takes nothing`() {
        assertEquals("Inspect", FileHandlers.match(entry(assoc(mime = "application/x-rpm")), "a.bin", "application/x-rpm")?.label)
        assertEquals("Inspect", FileHandlers.match(entry(assoc(mime = "image/*")), "a.bin", "image/png")?.label)
        assertNull(FileHandlers.match(entry(assoc(mime = "image/*")), "a.bin", "video/mp4"))
        assertNull(FileHandlers.match(entry(assoc(mime = "*/*")), "a.bin", "image/png"))
        assertNull("an association with neither is skipped", FileHandlers.match(entry(assoc()), "a.png", "image/png"))
    }

    @Test
    fun `only files handler entries count, and labels are capped`() {
        assertNull(FileHandlers.match(entry(assoc(ext = "deb"), point = "ui.facets"), "a.deb", null))
        val long = "x".repeat(FileHandlers.MAX_LABEL + 10)
        assertEquals(FileHandlers.MAX_LABEL, FileHandlers.associations(entry(assoc(ext = "deb", label = long))).single().label.length)
    }

    @Test
    fun `open hands the token, never a path`() {
        val args = FileHandlers.openArgs("f-1", "Krita.AppImage", null)
        assertEquals(setOf("token", "name", "mime"), args.keys().asSequence().toSet())
        assertEquals("f-1", args.getString("token"))
    }
}
