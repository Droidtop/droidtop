package dev.droidtop.shell.gamepad

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verify the interactive row's semantics, not merely that the decorative switch can be drawn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MenuRowSemanticsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `switch exposes its role and current state and updates after activation`() {
        rule.setContent {
            var enabled by remember { mutableStateOf(false) }
            MenuRow("Wi-Fi", switchOn = enabled, onClick = { enabled = !enabled })
        }
        val row = rule.onNodeWithText("Wi-Fi")
        assertEquals(Role.Switch, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.Off, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals("Off", row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        row.performClick()
        assertEquals(ToggleableState.On, row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        assertEquals("On", row.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun `a state row that opens another screen does not claim to toggle`() {
        rule.setContent { MenuRow("Battery saver settings", switchOn = true, switchIsToggle = false, onClick = {}) }
        val state = rule.onNodeWithText("Battery saver settings").fetchSemanticsNode().config
        assertEquals("On", state.getOrNull(SemanticsProperties.StateDescription))
        assertNull(state.getOrNull(SemanticsProperties.ToggleableState))
        assertNull(state.getOrNull(SemanticsProperties.Role))
    }

    @Test fun `adjustment arrows name the setting and invoke the same directional changes`() {
        var changes = 0
        rule.setContent {
            CompositionLocalProvider(LocalShellWindow provides ShellWindow(400, 800)) {
                MenuRow("Volume", value = "50", adjustable = true, onAdjust = { changes += it })
            }
        }
        val decrease = rule.onNodeWithContentDescription("Decrease Volume")
        val increase = rule.onNodeWithContentDescription("Increase Volume")
        decrease.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        assertEquals(Role.Button, decrease.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        decrease.performClick()
        assertEquals(-1, changes)
        increase.performClick()
        assertEquals(0, changes)
    }
}
