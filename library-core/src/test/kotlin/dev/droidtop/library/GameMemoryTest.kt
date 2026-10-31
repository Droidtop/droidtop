package dev.droidtop.library

import dev.droidtop.library.GameMemory.Remembered
import dev.droidtop.library.GameMemory.Values
import dev.droidtop.runtime.systemstatus.OverlayLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMemoryTest {
    private val start = Values(OverlayLevel.OFF, 1f)

    @Test
    fun `a session that changed nothing keeps nothing`() {
        val kept = GameMemory.afterSession(start, start, Remembered())
        assertTrue(kept.isEmpty)
    }

    @Test
    fun `what changed during the session is kept for the game, the rest is left as it was`() {
        val old = Remembered(companion = 0.4f)
        val end = Values(OverlayLevel.FULL, 1f)
        val kept = GameMemory.afterSession(start, end, old)
        assertEquals(OverlayLevel.FULL, kept.overlay)
        assertEquals(0.4f, kept.companion)
    }

    @Test
    fun `turning a setting back during the session keeps the value it ended on`() {
        val applied = Values(OverlayLevel.FULL, 0.4f)
        val end = Values(OverlayLevel.OFF, 0.4f)
        val kept = GameMemory.afterSession(applied, end, Remembered(overlay = OverlayLevel.FULL, companion = 0.4f))
        assertEquals(OverlayLevel.OFF, kept.overlay)
        assertEquals(0.4f, kept.companion)
    }
}
