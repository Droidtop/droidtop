package dev.droidtop.pluginhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When droidtop may use an app plugin's binding again, and when it must bind the app afresh (docs/plugin-api.md 1.3). */
class AppInterfaceConnectionsTest {
    @Test
    fun `a live binding and one still connecting are used again`() {
        assertTrue(appBindingReusable(runtimeAlive = true, firstAnswerSpent = true))
        assertTrue(appBindingReusable(runtimeAlive = null, firstAnswerSpent = false))
    }

    @Test
    fun `a binding whose app went away after connecting is bound afresh`() {
        // The app's process restarted (an update, a crash): Android disconnected it and its runtime is null.
        assertFalse(appBindingReusable(runtimeAlive = null, firstAnswerSpent = true))
        assertFalse(appBindingReusable(runtimeAlive = false, firstAnswerSpent = true))
    }
}
