package dev.droidtop.library.romdetect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two id spaces line up where they should and the one core name maps to nothing. */
class SystemIdMappingTest {

    @Test
    fun `ids that differ between the two spaces are translated`() {
        assertEquals("genesis", SystemID.GENESIS.toConsoleSystemId())
        assertEquals("segacd", SystemID.SEGACD.toConsoleSystemId())
        assertEquals("tg16", SystemID.PC_ENGINE.toConsoleSystemId())
        assertEquals("ngpc", SystemID.NGC.toConsoleSystemId())
    }

    @Test
    fun `the arcade core name has no ES-DE system`() {
        assertNull(SystemID.MAME2003PLUS.toConsoleSystemId())
    }

    @Test
    fun `every other system maps to something`() {
        SystemID.values().filter { it != SystemID.MAME2003PLUS }.forEach {
            assertNotNull(it.name, it.toConsoleSystemId())
        }
    }

    @Test
    fun `database names are unique`() {
        val names = SystemID.values().map { it.dbname }
        assertTrue(names.size == names.toSet().size)
    }
}
