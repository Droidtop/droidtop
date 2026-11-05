package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.settings.WordsForTests
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** What TalkBack reads for a themed list's entry under the cursor (Droidtop/tracker#728). */
class EsDeSpokenTest {
    @Before
    fun installWords() = WordsForTests.install()

    private fun item(label: String, favorite: Boolean = false, system: String? = null) =
        EsDeListItem(key = label, label = label, logoPath = null, onSelect = {}, favorite = favorite, sourceSystemName = system)

    @Test
    fun `the name and the position`() {
        assertEquals("Doom, 2 of 3", esDeSpokenEntry(listOf(item("Quake"), item("Doom"), item("Hexen")), 1))
    }

    @Test
    fun `a favourite says so, and a collection names the system`() {
        assertEquals("Doom, favourite, PC, 1 of 1", esDeSpokenEntry(listOf(item("Doom", favorite = true, system = "PC")), 0))
    }

    @Test
    fun `an empty list says nothing`() {
        assertEquals("", esDeSpokenEntry(emptyList(), 0))
    }
}
