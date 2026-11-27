package dev.droidtop.shell.gamepad

import org.junit.Assert.assertSame
import org.junit.Test

/** Android's theme host must publish the very palette its shared components read, on the same change. */
class SharedComponentPaletteTest {
    @Test
    fun changingTheDefaultAppearancePublishesTheSharedPaletteImmediately() {
        try {
            GamingTheme.setAppearance(true)
            assertSame(GamingTheme.palette.componentColors, ComponentTheme.palette)
            GamingTheme.setAppearance(false)
            assertSame(GamingTheme.palette.componentColors, ComponentTheme.palette)
        } finally {
            GamingTheme.setAppearance(false)
        }
    }
}
