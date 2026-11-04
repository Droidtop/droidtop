package dev.droidtop.runtime.windows

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseModeDefaultTest {
    @Test
    fun `a pointer genre starts mouse mode on, and a word inside another does not count`() {
        assertTrue(MouseModeDefault.genreNeedsMouse("Adventure, Point-and-click"))
        assertTrue(MouseModeDefault.genreNeedsMouse("Adventure / Point and Click"))
        assertTrue(MouseModeDefault.genreNeedsMouse("Real Time Strategy (RTS)"))
        assertTrue(MouseModeDefault.genreNeedsMouse("Visual Novel"))
        assertTrue(MouseModeDefault.genreNeedsMouse("Card & Board Game"))
        assertFalse(MouseModeDefault.genreNeedsMouse("Sports"))
        assertFalse(MouseModeDefault.genreNeedsMouse("Platform, Action"))
        assertFalse(MouseModeDefault.genreNeedsMouse("Shorts"))
        assertFalse(MouseModeDefault.genreNeedsMouse(null))
    }
}
