package dev.droidtop.runtime.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which processes run none of droidtop's start (docs/SPEC.md 12a "Start order", Droidtop/tracker#775). */
class IsolatedProcessTest {
    @Test
    fun `the full-access plugin slots are plugin processes, droidtop's own are not`() {
        assertTrue(IsolatedProcess.isFullAccessPlugin("dev.droidtop.app:pluginhost"))
        assertTrue(IsolatedProcess.isFullAccessPlugin("dev.droidtop.app:pluginhost7"))
        assertFalse(IsolatedProcess.isFullAccessPlugin("dev.droidtop.app"))
        assertFalse(IsolatedProcess.isFullAccessPlugin("dev.droidtop.app:wallpaper_chooser"))
        assertFalse(IsolatedProcess.isFullAccessPlugin("dev.droidtop.app:pluginhostX"))
        assertFalse(IsolatedProcess.isFullAccessPlugin(null))
    }
}
