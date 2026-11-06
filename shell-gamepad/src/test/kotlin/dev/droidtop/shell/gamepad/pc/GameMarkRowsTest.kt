package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.settings.GameMark
import dev.droidtop.library.settings.PlayStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The game page's own-marks rows (Droidtop/tracker#722). */
class GameMarkRowsTest {
    @Test
    fun `A adds a star and turns the status`() {
        var changed: GameMark? = null
        val rows = gameMarkRows(GameMark(5, PlayStatus.DROPPED)) { changed = it }
        assertEquals("★★★★★", rows[0].value)
        rows[0].onActivate!!()
        assertEquals(GameMark(0, PlayStatus.DROPPED), changed)
        rows[1].onActivate!!()
        assertEquals(GameMark(5, PlayStatus.NONE), changed)
        assertEquals(PageTab.ACTIVITY, rows[1].tab)
    }
}
