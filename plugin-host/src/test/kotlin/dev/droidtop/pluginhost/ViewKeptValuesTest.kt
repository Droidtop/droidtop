package dev.droidtop.pluginhost

import org.junit.Assert.assertEquals
import org.junit.Test

/** The person's own choices kept when a page is read again after a failed action (Droidtop/tracker#846). */
class ViewKeptValuesTest {
    private val mirrors = listOf("www" to "Win/Linux · WWW", "pixeldrain" to "Win/Linux · PIXELDRAIN", "mega" to "Mac · MEGA")

    private fun page(mirror: List<Pair<String, String>> = mirrors) = PluginView(
        title = "Braveheart Academy",
        subtitle = null,
        sections = listOf(
            ViewSection(
                "download",
                null,
                listOf(
                    ViewNode.Choice("mirror", "Download from", null, mirror, mirror.first().first, null),
                    ViewNode.Toggle("unpack", "Unpack", null, true, null),
                    ViewNode.Slider("parts", "Parts at once", null, 1, 4, 2, null),
                    ViewNode.Text("note", "Note", null, "", null),
                ),
            ),
        ),
    )

    @Test
    fun `a mirror the person picked is kept when the page is read again`() {
        val before = mapOf("mirror" to "pixeldrain", "unpack" to "false", "parts" to "3", "note" to "for the console")
        val kept = page().valuesKeeping(before, setOf("mirror", "unpack", "parts", "note"))
        assertEquals(before, kept)
    }

    @Test
    fun `only the inputs the person set are kept, the rest are the plugin's`() {
        val before = mapOf("mirror" to "pixeldrain", "unpack" to "false", "parts" to "3", "note" to "x")
        val kept = page().valuesKeeping(before, setOf("mirror"))
        assertEquals(mapOf("mirror" to "pixeldrain", "unpack" to "true", "parts" to "2", "note" to ""), kept)
    }

    @Test
    fun `a value the new page no longer offers falls back to the plugin's`() {
        val before = mapOf("mirror" to "mega", "unpack" to "maybe", "parts" to "9")
        val kept = page(mirrors.take(2)).valuesKeeping(before, setOf("mirror", "unpack", "parts"))
        assertEquals("www", kept["mirror"])
        assertEquals("true", kept["unpack"])
        assertEquals("2", kept["parts"])
    }

    @Test
    fun `nothing chosen is the plugin's own values`() {
        assertEquals(page().initialValues(), page().valuesKeeping(mapOf("mirror" to "pixeldrain"), emptySet()))
    }
}
