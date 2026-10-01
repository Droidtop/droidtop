package dev.droidtop.display

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SecondScreenWindowFlagsTest {
    @Test
    fun touchOnlyActivityDoesNotTakeFocusOrRequestImeLayering() {
        assertEquals(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, SecondScreenWindowFlags.touchOnly())
    }

    @Test
    fun touchOnlyWindowCanOptIntoAlternateImeLayering() {
        assertEquals(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            SecondScreenWindowFlags.touchOnly(includeAltFocusableIm = true),
        )
    }
}
