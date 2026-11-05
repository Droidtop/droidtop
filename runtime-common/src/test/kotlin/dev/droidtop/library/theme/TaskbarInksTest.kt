package dev.droidtop.library.theme

import dev.droidtop.notices.ColorMath
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Standard's taskbar takes the colours outside Gaming (Droidtop/tracker#701). */
class TaskbarInksTest {
    private fun behind(inks: TaskbarInks) = listOf(ColorMath.over(inks.plate, ColorMath.BLACK), ColorMath.over(inks.plate, ColorMath.WHITE))

    @Test
    fun `the text and the marks read on the plate over a dark app and a light one, in either theme`() {
        for (light in listOf(false, true)) {
            val inks = TaskbarInks.of(GamingThemeMapping.defaultFor(light))
            behind(inks).forEach { ground ->
                assertTrue(ColorMath.ratio(inks.text, ground) >= 4.5 - 0.01)
                assertTrue(ColorMath.ratio(inks.accent, ground) >= 3.0 - 0.01)
            }
        }
    }

    @Test
    fun `a light theme gives a light bar, not the old fixed dark plate`() {
        val dark = TaskbarInks.of(GamingThemeMapping.defaultFor(false))
        val light = TaskbarInks.of(GamingThemeMapping.defaultFor(true))
        assertNotEquals(dark.plate, light.plate)
        assertTrue(ColorMath.luminance(ColorMath.over(light.plate, ColorMath.BLACK)) > ColorMath.luminance(ColorMath.over(dark.plate, ColorMath.BLACK)))
    }
}
