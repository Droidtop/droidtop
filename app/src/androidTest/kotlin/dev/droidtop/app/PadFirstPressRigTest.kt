package dev.droidtop.app

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.droidtop.library.OwnGameScreens
import dev.droidtop.shell.standard.BackButtonMenu
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first D-pad press is handled everywhere in Gaming (Droidtop/tracker#820). On the console a D-pad press on the
 * Apps grid, in the Quick Menu (R2) and over a game (HOME + L2) was dropped as "unhandled" until A was pressed: the
 * gate delivers the D-pad keys it makes from the hat into the window directly, and a window left in touch mode by a
 * touch (the companion's screen) gave them no focused view. This puts the window in touch mode first, the state the
 * console was in, then presses the D-pad the way the gate does (a gamepad key delivered to the activity) and asserts
 * the press was handled on each main section, in the Quick Menu on every tab (R1 steps them), in the Start menu and in
 * the L2 menu. Run by hand on a set-up console with `am instrument`, never in CI:
 *
 *     adb shell am instrument -w -e class dev.droidtop.app.PadFirstPressRigTest \
 *         dev.droidtop.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PadFirstPressRigTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext

    @Test
    fun theFirstDpadPressIsHandledOnEveryMainSection() {
        for (section in SECTIONS) {
            val shell = open(Intent().putExtra(BackButtonMenu.EXTRA_GAMING_START_SECTION, section))
            assertTrue("the first D-pad press on $section was dropped", firstPress(shell, KeyEvent.KEYCODE_DPAD_DOWN) || firstPress(shell, KeyEvent.KEYCODE_DPAD_RIGHT))
        }
    }

    @Test
    fun theFirstDpadPressIsHandledOnEveryQuickMenuTab() {
        val shell = open(Intent().putExtra(OwnGameScreens.EXTRA_QUICK_MENU, true))
        repeat(QUICK_MENU_TABS) { tab ->
            assertTrue("the first D-pad press on Quick Menu tab ${tab + 1} was dropped", firstPress(shell, KeyEvent.KEYCODE_DPAD_DOWN))
            press(shell, KeyEvent.KEYCODE_BUTTON_R1)
            Thread.sleep(SETTLE_MS)
        }
    }

    @Test
    fun theFirstDpadPressIsHandledInTheStartAndL2Menus() {
        for (menuKey in listOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_L2)) {
            val shell = open(Intent().putExtra(BackButtonMenu.EXTRA_GAMING_START_SECTION, "APPS"))
            press(shell, menuKey)
            Thread.sleep(SETTLE_MS)
            assertTrue("the first D-pad press in the ${KeyEvent.keyCodeToString(menuKey)} menu was dropped", firstPress(shell, KeyEvent.KEYCODE_DPAD_DOWN))
            press(shell, KeyEvent.KEYCODE_BUTTON_B)
        }
    }

    /** Gaming, opened as a person would reach it, then left in touch mode as a touch on the companion leaves it. */
    private fun open(extras: Intent): MainActivity {
        val intent = Intent(app, MainActivity::class.java)
            .putExtras(extras)
            .putExtra(BackButtonMenu.EXTRA_MODE, "gaming")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        Thread.sleep(OPEN_MS)
        instrumentation.setInTouchMode(true)
        instrumentation.waitForIdleSync()
        return activity
    }

    /** One D-pad press the way the gate delivers it; whether its down edge was handled. */
    private fun firstPress(activity: MainActivity, keyCode: Int): Boolean {
        var handled = false
        val now = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            handled = activity.dispatchKeyEvent(padKey(now, KeyEvent.ACTION_DOWN, keyCode))
            activity.dispatchKeyEvent(padKey(now, KeyEvent.ACTION_UP, keyCode))
        }
        instrumentation.waitForIdleSync()
        return handled
    }

    private fun press(activity: MainActivity, keyCode: Int) {
        firstPress(activity, keyCode)
    }

    private fun padKey(downTime: Long, action: Int, keyCode: Int) =
        KeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_GAMEPAD)

    private companion object {
        val SECTIONS = listOf("HOME", "GAMES", "PC_GAMES", "APPS", "SETTINGS")
        const val QUICK_MENU_TABS = 6
        const val OPEN_MS = 2_500L
        const val SETTLE_MS = 600L
    }
}
