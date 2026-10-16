package dev.droidtop.runtime

import dev.droidtop.runtime.LauncherLabels.Home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Standard's label setting on the home screen and in the drawer (docs/SPEC.md "Standard"). */
class LauncherLabelsTest {
    @Test
    fun `home only shows labels on the home screen even where auto would drop them`() {
        assertEquals(Home.SHOWN, LauncherLabels.home("NEVER_DRAWER"))
        assertFalse(LauncherLabels.drawer("NEVER_DRAWER"))
    }

    @Test
    fun `each setting on the home screen and in the drawer`() {
        assertEquals(Home.HIDDEN, LauncherLabels.home("NEVER_FULL"))
        assertFalse(LauncherLabels.drawer("NEVER_FULL"))
        assertEquals(Home.HIDDEN, LauncherLabels.home("NEVER"))
        assertTrue(LauncherLabels.drawer("NEVER"))
        assertEquals(Home.IF_ROOM, LauncherLabels.home("AUTO"))
        assertTrue(LauncherLabels.drawer("AUTO"))
        assertEquals(Home.SHOWN, LauncherLabels.home("ALWAYS"))
        assertTrue(LauncherLabels.drawer("ALWAYS"))
    }
}
