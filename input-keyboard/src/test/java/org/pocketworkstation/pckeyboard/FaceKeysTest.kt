package org.pocketworkstation.pckeyboard

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One mapping from a key to what it means, the face-button swap applied, for every screen (review, 2026-10-16). */
class FaceKeysTest {
    @After
    fun reset() {
        FaceKeys.swapped = false
    }

    @Test
    fun `unswapped, A confirms and B cancels`() {
        assertTrue(FaceKeys.isConfirm(KeyEvent.KEYCODE_BUTTON_A))
        assertTrue(FaceKeys.isCancel(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(FaceKeys.Role.X, FaceKeys.roleOf(KeyEvent.KEYCODE_BUTTON_X))
    }

    @Test
    fun `swapped, B confirms and A cancels, X and Y trade`() {
        FaceKeys.swapped = true
        assertTrue(FaceKeys.isConfirm(KeyEvent.KEYCODE_BUTTON_B))
        assertTrue(FaceKeys.isCancel(KeyEvent.KEYCODE_BUTTON_A))
        assertFalse(FaceKeys.isConfirm(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(FaceKeys.Role.Y, FaceKeys.roleOf(KeyEvent.KEYCODE_BUTTON_X))
    }

    @Test
    fun `a keyboard's Enter and Escape and the back key are outside the swap`() {
        FaceKeys.swapped = true
        assertTrue(FaceKeys.isConfirm(KeyEvent.KEYCODE_ENTER))
        assertTrue(FaceKeys.isCancel(KeyEvent.KEYCODE_ESCAPE))
        assertTrue(FaceKeys.isCancel(KeyEvent.KEYCODE_BACK))
    }
}
