package dev.droidtop.shell.gamepad.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaytimeLabelTest {
    @Test fun `a launched game with no tracked duration is Played, not Never played`() {
        assertEquals("Played", playtimeLabel(0, 1_700_000_000_000, 1))
        assertEquals("Played", playtimeLabel(0, null, 2))
    }

    @Test fun `a game never launched reads Never played`() {
        assertEquals("Never played", playtimeLabel(0, null, 0))
    }

    @Test fun `a known duration is formatted as hours and minutes`() {
        assertEquals("1 h 1 min", playtimeLabel(3660, 1L, 1))
    }
}
