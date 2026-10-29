package dev.droidtop.pluginhost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop's sheet for a plugin's secret (docs/plugin-api.md 1.10, `secret`) keeps the value only while it is open. */
class PluginSecretsTest {
    @After
    fun noSurface() = PluginSecrets.setSurface(null)

    @Test
    fun `with nothing to show it on, nothing is kept`() {
        PluginSecrets.setSurface(null)
        assertFalse(PluginSecrets.show("Syncthing", "Password", "hunter2"))
        assertNull(PluginSecrets.shown.value)
        PluginSecrets.setSurface { false }
        assertFalse(PluginSecrets.show("Syncthing", "Password", "hunter2"))
        assertNull(PluginSecrets.shown.value)
    }

    @Test
    fun `the value is held while the sheet is open and let go when it closes`() {
        PluginSecrets.setSurface { true }
        assertTrue(PluginSecrets.show("Syncthing", "Password", "hunter2"))
        val shown = PluginSecrets.shown.value!!
        assertEquals("hunter2", shown.value)
        // Not a data class: the value never ends up in a log line by way of toString.
        assertFalse(shown.toString().contains("hunter2"))
        PluginSecrets.close(shown)
        assertNull(PluginSecrets.shown.value)
    }
}
