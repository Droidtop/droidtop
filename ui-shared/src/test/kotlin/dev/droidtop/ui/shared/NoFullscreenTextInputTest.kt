package dev.droidtop.ui.shared

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** droidtop's fields keep the input method out of full screen (Droidtop/tracker#485 comment 6224857113). */
class NoFullscreenTextInputTest {
    @Test
    fun `the no-fullscreen and no-extract flags are added and the rest kept`() {
        val search = EditorInfo.IME_ACTION_SEARCH
        assertEquals(
            search or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
            noFullscreen(search),
        )
    }
}
