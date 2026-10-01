package dev.droidtop.app

import android.content.pm.ActivityInfo
import dev.droidtop.library.settings.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOrientationPrefsTest {
    @Test fun choicesMatchEachModeAndAndroidOrientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ScreenOrientationPrefs.requestedOrientation(Mode.GAMING, "follow"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ScreenOrientationPrefs.requestedOrientation(Mode.GAMING, "landscape"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ScreenOrientationPrefs.requestedOrientation(Mode.GAMING, "landscape_flipped"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ScreenOrientationPrefs.requestedOrientation(Mode.GAMING, "portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ScreenOrientationPrefs.requestedOrientation(Mode.LAUNCHER, "portrait"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ScreenOrientationPrefs.requestedOrientation(Mode.DESKTOP, "portrait_flipped"))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ScreenOrientationPrefs.requestedOrientation(Mode.DESKTOP, "landscape"))
    }

    @Test fun portraitOptionsOnlyAppearForStandardAndDesktop() {
        assertFalse(ScreenOrientationPrefs.options(Mode.GAMING).any { it.first == "portrait" })
        assertTrue(ScreenOrientationPrefs.options(Mode.LAUNCHER).any { it.first == "portrait_flipped" })
        assertTrue(ScreenOrientationPrefs.options(Mode.DESKTOP).any { it.first == "portrait" })
    }
}
