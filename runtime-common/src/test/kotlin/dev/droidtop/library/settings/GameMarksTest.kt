package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The person's own rating and status per game (Droidtop/tracker#722). */
class GameMarksTest {
    @Test
    fun `marks read back, and empty or broken ones are dropped`() {
        val marks = mapOf("g1" to GameMark(4, PlayStatus.PLAYING), "g2" to GameMark(0, PlayStatus.DROPPED))
        assertEquals(marks, GameMarks.parse(GameMarks.format(marks + ("g3" to GameMark()))))
        assertEquals(emptyMap<String, GameMark>(), GameMarks.parse("not json"))
        assertEquals(mapOf("g" to GameMark(5)), GameMarks.parse("""{"g":{"stars":9,"status":"elsewhere"}}"""))
    }

    @Test
    fun `stars read as stars, and the status turns round the list`() {
        assertEquals("Not rated", GameMarks.starsLine(0))
        assertEquals("★★★☆☆", GameMarks.starsLine(3))
        assertEquals(PlayStatus.PLAYING, PlayStatus.NONE.next())
        assertEquals(PlayStatus.NONE, PlayStatus.DROPPED.next())
    }
}
