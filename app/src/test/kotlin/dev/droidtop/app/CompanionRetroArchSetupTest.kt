package dev.droidtop.app

import dev.droidtop.app.RetroArchSetupLine.Plugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Save and load from here: Set up" on the Game tab (CompanionRetroArchSetup.kt, Droidtop/tracker#414 slice C11). */
class CompanionRetroArchSetupTest {
    @Test fun `the line shows only for RetroArch, with the plugin to be had and a step missing`() {
        // Not RetroArch: never.
        assertNull(RetroArchSetupLine.steps(retroArch = false, plugin = Plugin.OFFERED, networkOn = false))
        // Nothing missing: the rows are already there.
        assertNull(RetroArchSetupLine.steps(retroArch = true, plugin = Plugin.RUNNING, networkOn = true))
        // No way to get the plugin: finishing would not make the rows appear.
        assertNull(RetroArchSetupLine.steps(retroArch = true, plugin = Plugin.ABSENT, networkOn = false))
        val both = RetroArchSetupLine.steps(retroArch = true, plugin = Plugin.OFFERED, networkOn = false)!!
        assertTrue(both.getPlugin && both.turnOn)
        val onlyTurnOn = RetroArchSetupLine.steps(retroArch = true, plugin = Plugin.RUNNING, networkOn = false)!!
        assertFalse(onlyTurnOn.getPlugin)
        assertTrue(onlyTurnOn.turnOn)
        val onlyPlugin = RetroArchSetupLine.steps(retroArch = true, plugin = Plugin.OFFERED, networkOn = true)!!
        assertTrue(onlyPlugin.getPlugin)
        assertFalse(onlyPlugin.turnOn)
        // Installed but not approved: the page says to approve it.
        assertTrue(RetroArchSetupLine.steps(retroArch = true, plugin = Plugin.INSTALLED, networkOn = true)!!.approve)
        // A setting droidtop cannot read counts as off: Turn on says why not if it cannot write either.
        assertTrue(RetroArchSetupLine.steps(retroArch = true, plugin = Plugin.RUNNING, networkOn = null)!!.turnOn)
    }

    @Test fun `Turn on writes network_cmd_enable and keeps the rest of RetroArch's config`() {
        val config = "video_scale_integer = \"true\"\nnetwork_cmd_enable = \"false\"\n"
        val written = RetroArchSetupLine.withNetworkCommands(config)
        assertEquals("video_scale_integer = \"true\"\nnetwork_cmd_enable = \"true\"\n", written)
        assertEquals(true, RetroArchSetupLine.networkOn(written))
        assertEquals(false, RetroArchSetupLine.networkOn(config))
        assertEquals(false, RetroArchSetupLine.networkOn(""))
        assertNull(RetroArchSetupLine.networkOn(null))
        assertEquals(true, RetroArchSetupLine.networkOn(RetroArchSetupLine.withNetworkCommands("")))
    }
}
