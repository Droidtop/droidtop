package dev.droidtop.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * droidtop's baseline profile (docs/SPEC.md 10b, Droidtop/tracker#954 item 13): the code a person runs first after an
 * update, so Android compiles it ahead of time instead of droidtop starting interpreted. The walk is the start into
 * Gaming's Home, then the left menu's PC Games and Settings, the screens every session opens.
 *
 * A fresh install opens onboarding, which a person who updates never sees again, so before each start the walk marks
 * onboarding done and Gaming as the mode Home opens (the two keys in droidtop's launcher prefs, OnboardingPrefs and
 * Modes). That takes root, which the plain emulator image the workflow runs gives the shell.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startIntoGamingAndBrowse() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        killProcess()
        skipOnboarding()
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        // Home settles (its shelves and status) before the walk.
        Thread.sleep(SETTLE_MS)
        // The left menu, then PC Games (one row down).
        press(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_A)
        Thread.sleep(SETTLE_MS)
        // Across PC Games' tabs and down its grid.
        press(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)
        Thread.sleep(SETTLE_MS)
        // The left menu again, then Settings (its last row).
        press(KeyEvent.KEYCODE_BUTTON_START)
        repeat(SETTINGS_ROW) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_BUTTON_A)
        Thread.sleep(SETTLE_MS)
        // Down Settings' categories.
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        device.waitForIdle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.press(vararg keys: Int) {
        keys.forEach { key ->
            device.pressKeyCode(key)
            device.waitForIdle()
            Thread.sleep(STEP_MS)
        }
    }

    /** Writes droidtop's launcher prefs with onboarding done and Gaming as Home's mode, while droidtop is stopped. */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.skipOnboarding() {
        val staged = File(InstrumentationRegistry.getInstrumentation().context.cacheDir, PREFS_FILE)
        staged.writeText(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <boolean name=\"droidtop_onboarding_complete\" value=\"true\" />\n" +
                "    <string name=\"droidtop_default_mode\">gaming</string>\n</map>\n",
        )
        staged.setReadable(true, false)
        val dataDir = "/data/data/$PACKAGE"
        val prefsDir = "$dataDir/shared_prefs"
        val uid = device.executeShellCommand("su 0 stat -c %u $dataDir").trim()
        device.executeShellCommand("su 0 mkdir -p $prefsDir")
        device.executeShellCommand("su 0 cp ${staged.absolutePath} $prefsDir/$PREFS_FILE")
        device.executeShellCommand("su 0 chown -R $uid:$uid $prefsDir")
        device.executeShellCommand("su 0 chmod 771 $prefsDir")
        device.executeShellCommand("su 0 chmod 660 $prefsDir/$PREFS_FILE")
        device.executeShellCommand("su 0 restorecon -R $prefsDir")
    }

    private companion object {
        const val PACKAGE = "dev.droidtop.app"

        /** LAUNCHER_PREFS_FILE_NAME (runtime-common), which OnboardingPrefs and Modes both use. */
        const val PREFS_FILE = "com.android.launcher3.prefs.xml"

        /** Settings' row in the left menu: Home, PC Games, Retro Games, Apps, Downloads and Updates, Settings. */
        const val SETTINGS_ROW = 5

        const val SETTLE_MS = 3_000L
        const val STEP_MS = 400L
    }
}
