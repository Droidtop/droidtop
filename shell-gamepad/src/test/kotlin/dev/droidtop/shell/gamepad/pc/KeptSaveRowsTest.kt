package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.computers.ComputerSaves
import dev.droidtop.library.settings.WordsForTests
import dev.droidtop.net.peer.Computer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A settled save conflict's kept copies on the game page (Droidtop/tracker#822). */
class KeptSaveRowsTest {
    @Before
    fun installWords() = WordsForTests.install()

    private val day = 24L * 60 * 60 * 1000
    private val pc = Computer(id = "ab", name = "PC", addresses = emptyList(), pairedAtMs = 0)
    private val fromPc = ComputerSaves.KeptCopy(pc, "PC", files = 3, newestMs = 10 * day)
    private val fromHere = ComputerSaves.KeptCopy(pc, "Legion Go", files = 1, newestMs = 9 * day)

    @Test
    fun `each kept copy is a Details row that asks to bring it back`() {
        var asked: ComputerSaves.KeptCopy? = null
        val rows = keptSaveRows(listOf(fromPc, fromHere), restoring = null, now = 12 * day) { asked = it }
        assertEquals(listOf("Bring back saves from PC", "Bring back saves from Legion Go"), rows.map { it.title })
        assertEquals("2 days ago", rows[0].value)
        assertTrue(rows[0].subtitle!!.startsWith("3 files"))
        assertTrue(rows.all { it.tab == PageTab.DETAILS })
        rows[1].onActivate!!.invoke()
        assertEquals(fromHere, asked)
    }

    @Test
    fun `while one is coming back it says so and nothing else can start`() {
        val rows = keptSaveRows(listOf(fromPc, fromHere), restoring = fromPc, now = 12 * day) {}
        assertEquals("Bringing back…", rows[0].value)
        assertNull(rows[0].onActivate)
        assertNull(rows[1].onActivate)
        assertNotNull(rows[1].value)
    }

    @Test
    fun `no kept copy, no rows`() {
        assertEquals(emptyList<PageFact>(), keptSaveRows(emptyList(), null, 0) {})
    }
}
