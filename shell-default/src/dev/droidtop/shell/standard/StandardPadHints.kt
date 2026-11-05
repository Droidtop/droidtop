package dev.droidtop.shell.standard

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.ui.shared.HintViews

/**
 * Mobile's pad hint row (Droidtop/tracker#699, option 1): the [StandardPad] buttons named above the dock while a pad
 * is what the person last used, as Gaming and Desktop teach the pad from their own rows. Each pill is bound only while
 * its press does something: the page buttons with more than one home page, the item menu while an item has the focus.
 */
object StandardPadHints {
    /** Bumped on every pad press, after the launcher has acted on it: the row reads the page and the focus again. */
    private val revision = androidx.compose.runtime.mutableIntStateOf(0)

    @JvmStatic
    fun onHomeShown(launcher: Launcher) {
        val home = { launcher.isInState(LauncherState.NORMAL) }
        val pages = { home() && launcher.workspace.pageCount > 1 }
        HintViews.padRow(launcher, revision) {
            listOf(
                HintBinding(GamepadAction.L, "Previous page") { pages() && launcher.workspace.nextPage > 0 },
                HintBinding(GamepadAction.R, "Next page") { pages() && launcher.workspace.nextPage < launcher.workspace.pageCount - 1 },
                HintBinding(GamepadAction.X, "Menu") { launcher.currentFocus != null },
                HintBinding(GamepadAction.Y, "Search"),
                // Start reaches the mode switcher (rig, RP5 3057: ActivityTaskManager started ModeSwitcherActivity).
                HintBinding(GamepadAction.START, "Modes"),
            )
        }
    }

    /** A key on the launcher: a pad's turns the row on; a keyboard's leaves it as it was. */
    @JvmStatic
    fun noteKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val pad = KeyEvent.isGamepadButton(event.keyCode) ||
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!pad) return
        PadModality.padDriving()
        // After the launcher handles this press (a page turned, the focus moved), not before it.
        android.os.Handler(android.os.Looper.getMainLooper()).post { revision.intValue++ }
    }

    /** A finger on the screen: the row goes until the pad is used again. */
    @JvmStatic
    fun noteTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (event.source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN
        ) {
            PadModality.touched()
        }
    }
}
