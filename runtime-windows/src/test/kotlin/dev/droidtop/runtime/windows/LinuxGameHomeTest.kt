package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home a Linux game may keep its saves in, and the scripts that act in the container (docs/SPEC.md 7c). */
class LinuxGameHomeTest {

    @Test
    fun `an entry id is a safe folder name and two ids never share one`() {
        val a = LinuxGameHome.safeName("folder:CUSTOM_GAME_42")
        assertTrue(a.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(a, LinuxGameHome.safeName("folder_CUSTOM_GAME_42"))
        assertEquals(a, LinuxGameHome.safeName("folder:CUSTOM_GAME_42"))
        assertTrue(LinuxGameHome.safeName("../../etc").matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `the game is pointed at its home through HOME and the XDG folders`() {
        val env = LinuxGameHome.environment("/app-storage/linux-games/x/home")
        assertEquals("/app-storage/linux-games/x/home", env["HOME"])
        assertEquals("/app-storage/linux-games/x/home/.config", env["XDG_CONFIG_HOME"])
        assertEquals("/app-storage/linux-games/x/home/.local/share", env["XDG_DATA_HOME"])
        assertEquals("/app-storage/linux-games/x/home/.cache", env["XDG_CACHE_HOME"])
    }

    @Test
    fun `the scripts take their paths from the environment, never the command line, and cannot run unset`() {
        assertTrue("\$DT_STOP_PATTERN" in LinuxGameHome.STOP_SCRIPT)
        assertTrue("self=\$\$" in LinuxGameHome.STOP_SCRIPT)
        assertTrue("\${DT_HOME:?}" in LinuxGameHome.RESET_SCRIPT)
        assertTrue("-mindepth 1" in LinuxGameHome.RESET_SCRIPT)
    }
}
