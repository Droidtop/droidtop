package dev.droidtop.library.integrations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A declared setting's `when` is decided on states droidtop already knows, with no plugin call (Droidtop/tracker#880). */
class PluginSettingsRowsTest {
    private val signedIn = PluginSettingsRows.States(signedIn = true, computerPaired = false, granted = setOf("web.session"))
    private val signedOut = signedIn.copy(signedIn = false)

    @Test
    fun `when shows a row only while its state holds, and not inverts it`() {
        assertTrue(PluginSettingsRows.holds(null, signedOut))
        assertTrue(PluginSettingsRows.holds("web.session.signed_in", signedIn))
        assertFalse(PluginSettingsRows.holds("web.session.signed_in", signedOut))
        assertTrue(PluginSettingsRows.holds("not web.session.signed_in", signedOut))
        assertFalse(PluginSettingsRows.holds("computer.paired", signedIn))
        assertTrue(PluginSettingsRows.holds("permission:web.session", signedIn))
        assertFalse(PluginSettingsRows.holds("permission:net.any", signedIn))
    }
}
