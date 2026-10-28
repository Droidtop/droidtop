package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The 4-row layouts made from HeliBoard's (Droidtop/tracker#342): each generated res/xml-<locale>/kbd_qwerty.xml
 * holds its source layout's letters, row by row, between the template's Shift and Delete, so a source edited without
 * running layouts-src/heliboard-to-kbd.py again fails here.
 */
class HeliBoardLayoutsTest {
    private val sources = mapOf("mk" to "macedonian", "be" to "belarusian", "mn" to "mongolian")

    private fun sourceRows(name: String): List<List<String>> =
        File("layouts-src/heliboard/$name.txt").readText().split(Regex("\n\\s*\n"))
            .map { row -> row.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split(" ")[0] } }
            .filter { it.isNotEmpty() }

    private fun layoutRows(locale: String): List<List<Element>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml-$locale/kbd_qwerty.xml"))
        val rows = doc.getElementsByTagName("Row")
        return (0 until rows.length).map { i ->
            val keys = (rows.item(i) as Element).getElementsByTagName("Key")
            (0 until keys.length).map { keys.item(it) as Element }
        }
    }

    @Test
    fun `each layout holds its source's letters, with Shift and Delete around the third row`() {
        for ((locale, name) in sources) {
            val source = sourceRows(name)
            val rows = layoutRows(locale)
            assertEquals(locale, 3, source.size)
            assertEquals(locale, source[0], rows[0].map { it.getAttribute("android:keyLabel") })
            assertEquals(locale, source[1], rows[1].map { it.getAttribute("android:keyLabel") })
            val third = rows[2]
            assertEquals(locale, "@integer/key_shift", third.first().getAttribute("android:codes"))
            assertEquals(locale, "@integer/key_delete", third.last().getAttribute("android:codes"))
            assertEquals(locale, source[2], third.drop(1).dropLast(1).map { it.getAttribute("android:keyLabel") })
        }
    }

    @Test
    fun `the top row long-presses to the digits, and the bottom rows are the template's`() {
        val template = layoutRows("bg")
        for (locale in sources.keys) {
            val rows = layoutRows(locale)
            rows[0].take(10).forEachIndexed { i, key ->
                assertTrue(locale, key.getAttribute("android:popupCharacters").startsWith("1234567890"[i].toString()))
            }
            assertEquals(locale, template.size, rows.size)
            for (r in 3 until rows.size) {
                assertEquals(locale, template[r].map { it.getAttribute("android:codes") + it.getAttribute("android:keyLabel") },
                    rows[r].map { it.getAttribute("android:codes") + it.getAttribute("android:keyLabel") })
            }
        }
    }
}
