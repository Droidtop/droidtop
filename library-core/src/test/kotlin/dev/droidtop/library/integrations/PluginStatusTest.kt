package dev.droidtop.library.integrations

import dev.droidtop.library.settings.StatusMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A plugin's status glyph from its state (Droidtop/tracker#644: quiet when good, attention over work under way). */
class PluginStatusTest {
    @Test
    fun `a plugin that runs well has no glyph, and work under way turns a ring`() {
        assertNull(PluginStatus.markOf("Running", working = false))
        assertEquals(StatusMark.WORKING, PluginStatus.markOf("Running", working = true))
        assertEquals(StatusMark.WORKING, PluginStatus.markOf("Waiting", working = true))
    }

    @Test
    fun `what needs the person outranks work, and what cannot run is an error`() {
        listOf("Needs approval", "Needs an update", "Needs setup", "Wants new access", "Update available").forEach {
            assertEquals(it, StatusMark.ATTENTION, PluginStatus.markOf(it, working = true))
        }
        assertEquals(StatusMark.ERROR, PluginStatus.markOf("Stopped", working = false))
        assertEquals(StatusMark.ERROR, PluginStatus.markOf("Denied", working = false))
        assertNull(PluginStatus.markOf("Off", working = false))
    }
}
