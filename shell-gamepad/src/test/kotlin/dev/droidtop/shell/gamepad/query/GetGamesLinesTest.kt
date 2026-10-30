package dev.droidtop.shell.gamepad.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Get games names every source a merged game came from (Droidtop/tracker#825). */
class GetGamesLinesTest {

    @Test
    fun `a game from two sources names both`() {
        assertEquals("from F95zone · LewdCorner", sourcesLine(listOf("F95zone", "LewdCorner")))
    }

    @Test
    fun `one source leaves the row its own detail`() {
        assertNull(sourcesLine(listOf("F95zone")))
        assertNull(sourcesLine(listOf("F95zone", "F95zone")))
        assertNull(sourcesLine(emptyList()))
    }
}
