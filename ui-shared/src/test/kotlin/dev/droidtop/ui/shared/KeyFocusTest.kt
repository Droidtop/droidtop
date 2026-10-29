package dev.droidtop.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pad panel whose only focusable child is a text field keeps hearing the pad after the field gives its focus up, as
 * it does when Back hides the on-screen keyboard (Droidtop/tracker#747: D-pad Up and A did nothing in the Search sheet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class KeyFocusTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the panel hears Up after its field clears focus`() {
        var ups by mutableIntStateOf(0)
        lateinit var focusManager: FocusManager
        val field = FocusRequester()
        rule.setContent {
            focusManager = LocalFocusManager.current
            var text by remember { mutableStateOf("bare witness") }
            Column(
                Modifier
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            ups++
                            true
                        } else {
                            false
                        }
                    }
                    .holdKeyFocus(remember { FocusRequester() }),
            ) {
                BasicTextField(text, { text = it }, Modifier.focusRequester(field))
            }
        }
        rule.runOnIdle { field.requestFocus() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.runOnIdle { assertEquals(1, ups) }

        // What Back does to the field when it hides the keyboard: its focus goes, and nothing in the panel has it.
        rule.runOnIdle { focusManager.clearFocus(force = true) }
        rule.waitForIdle()
        repeat(3) { rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) } }
        rule.runOnIdle { assertEquals(4, ups) }
    }
}
