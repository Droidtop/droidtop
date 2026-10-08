package dev.droidtop.runtime.systemstatus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 13+ restricted settings (docs/SPEC.md 4c, Droidtop/tracker#314, #329). */
class RestrictedSettingsRulesTest {
    private val errored = RestrictedSettingsRules.MODE_ERRORED
    private val allowed = RestrictedSettingsRules.MODE_ALLOWED
    private val default = 3

    @Test
    fun `the step shows when Android marked the app restricted and the grant is missing`() {
        assertTrue(RestrictedSettingsRules.stepNeeded(33, granted = false, opMode = errored, attempted = false))
        assertFalse(RestrictedSettingsRules.stepNeeded(33, granted = true, opMode = errored, attempted = true))
        assertFalse(RestrictedSettingsRules.stepNeeded(33, granted = false, opMode = allowed, attempted = true))
    }

    @Test
    fun `an unknown mode needs a grant screen that did not end in the grant`() {
        assertFalse(RestrictedSettingsRules.stepNeeded(35, granted = false, opMode = default, attempted = false))
        assertTrue(RestrictedSettingsRules.stepNeeded(35, granted = false, opMode = default, attempted = true))
        assertTrue(RestrictedSettingsRules.stepNeeded(35, granted = false, opMode = null, attempted = true))
    }

    @Test
    fun `before Android 13 nothing is restricted`() {
        assertFalse(RestrictedSettingsRules.stepNeeded(32, granted = false, opMode = errored, attempted = true))
    }
}
