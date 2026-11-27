package dev.droidtop.shell.gamepad

import android.view.KeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import dev.droidtop.library.controller.FaceLayout
import dev.droidtop.shell.gamepad.input.ControllerLayouts
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.PadCadence
import dev.droidtop.shell.gamepad.input.PadEdges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Android Compose key wrappers exercise the platform packing, without a device or a live window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedComponentKeysTest {
    @Test
    fun overlappingFaceButtonsKeepBothReleases() {
        SharedComponentPlatform.install()
        ControllerLayouts.useLayoutForTest(FaceLayout.DEFAULT)
        val edges = PadEdges(PadCadence.CHROME)
        val seen = mutableListOf<GamepadAction>()
        fun send(code: Int, action: Int, time: Long): Boolean = edges.dispatch(
            ComposeKeyEvent(KeyEvent(0L, time, action, code, 0)), preview = false,
        ) { seen.add(it.action); true }

        assertTrue(send(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN, 0))
        assertTrue(send(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN, 1))
        assertTrue(send(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP, 2))
        assertTrue(send(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_UP, 3))
        assertEquals(listOf(GamepadAction.A, GamepadAction.B), seen)
    }
}
