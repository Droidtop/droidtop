package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Get games entry's rules (docs/SPEC.md 13, "There is no Get games sheet"). Pure, no Android. */
class GetGamesEntryTest {

    @Test
    fun `a console system is searched for, PC and no system are not`() {
        assertEquals("snes", GetGamesEntry.searchSystem(" snes "))
        assertNull(GetGamesEntry.searchSystem(GetGamesEntry.PC_SYSTEM_ID))
        assertNull(GetGamesEntry.searchSystem(null))
        assertNull(GetGamesEntry.searchSystem("  "))
    }

    @Test
    fun `the no source line shows only when nothing could be asked`() {
        assertEquals("No download source is installed", GetGamesEntry.noSourceLine(asked = 0, notRunnable = 0, scoped = false))
        assertEquals("No download source gets games for this system", GetGamesEntry.noSourceLine(asked = 0, notRunnable = 0, scoped = true))
        assertNull(GetGamesEntry.noSourceLine(asked = 1, notRunnable = 0, scoped = false))
        // A source waiting for approval has its own row; this line would hide it.
        assertNull(GetGamesEntry.noSourceLine(asked = 0, notRunnable = 1, scoped = true))
    }
}
