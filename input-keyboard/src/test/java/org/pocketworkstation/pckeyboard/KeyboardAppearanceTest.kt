package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Droidtop/tracker#562: "Follow light or dark" draws Material light or dark; a theme the person chose stays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardAppearanceTest {

    @Test
    fun followDrawsMaterialLightOrDark() {
        assertEquals(KeyboardAppearance.MATERIAL_LIGHT, KeyboardAppearance.resolve(KeyboardAppearance.FOLLOW, light = true))
        assertEquals(KeyboardAppearance.MATERIAL_DARK, KeyboardAppearance.resolve(KeyboardAppearance.FOLLOW, light = false))
    }

    @Test
    fun aChosenThemeStaysWhateverTheAppearance() {
        for (theme in 0..7) {
            assertEquals(theme, KeyboardAppearance.resolve(theme, light = true))
            assertEquals(theme, KeyboardAppearance.resolve(theme, light = false))
        }
    }

    @Test
    fun anyoneWhoNeverChoseFollows() {
        assertEquals(KeyboardAppearance.FOLLOW.toString(), KeyboardSwitcher.DEFAULT_LAYOUT_ID)
    }

    @Test
    fun panelsAreLightOnlyBesideTheLightKeyboard() {
        assertTrue(KeyboardAppearance.lightPanels(KeyboardAppearance.MATERIAL_LIGHT))
        for (theme in listOf(0, 1, 2, 3, 4, 6, 7)) assertFalse(KeyboardAppearance.lightPanels(theme))
    }
}
